package org.javadb.storage.buffer;

import org.javadb.common.PageId;
import org.javadb.common.exception.BufferPoolException;
import org.javadb.config.DatabaseConfig;
import org.javadb.storage.page.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

/**
 * ============================================================
 * Frame — A Single Buffer Pool Slot
 * ============================================================
 *
 * A Frame is one slot in the Buffer Pool's fixed-size array.
 * It wraps a Page (the actual 8KB data) and adds the runtime
 * metadata the Buffer Pool Manager needs to manage it:
 *
 *   1. frameId       — position in the buffer pool array (0 to N-1)
 *   2. referenceBit  — used by Clock eviction algorithm
 *   3. usageCount    — used by enhanced Clock (LRU-K approximation)
 *   4. RW lock       — concurrent read / exclusive write access
 *   5. valid flag    — is this frame holding a real page?
 *
 * PostgreSQL parallel:
 *   BufferDesc in src/include/storage/buf_internals.h
 *
 *   typedef struct BufferDesc {
 *     BufferTag  tag;           // page identity (RelFileNode + BlockNumber)
 *     int        buf_id;        // buffer's index in BufferDescriptors[]
 *     pg_atomic_uint32 state;   // encodes: refcount, usagecount, flags
 *     int        wait_backend_pgprocno; // deadlock detection
 *     LWLock     content_lock;  // read/write lock for page content
 *   } BufferDesc;
 *
 *   Key differences from PostgreSQL's approach:
 *   - PostgreSQL packs refcount + usagecount + flags into one
 *     atomic uint32 using bit fields (for atomic CAS updates).
 *     We use separate AtomicInteger/AtomicBoolean for clarity.
 *   - PostgreSQL uses LWLock (lightweight lock) for content_lock.
 *     We use StampedLock (Java 8+) which supports optimistic reads —
 *     faster than ReentrantReadWriteLock for read-heavy workloads
 *     like buffer pool page access.
 *
 * Frame States:
 *   INVALID  → frame is free, not holding any page
 *   VALID    → frame holds a valid page (may be clean or dirty)
 *   PINNED   → frame is valid AND has active pins (cannot evict)
 *
 * Eviction eligibility:
 *   A frame is eligible for eviction ONLY if:
 *     isValid() == true
 *     isPinned() == false (pinCount == 0)
 *     (referenceBit and usageCount guide the choice of victim)
 *
 * Java concepts:
 *   - StampedLock       : optimistic + pessimistic read/write locking
 *   - AtomicInteger     : lock-free pin count and usage count
 *   - AtomicBoolean     : lock-free reference bit and valid flag
 *   - volatile PageId   : visibility guarantee across threads
 *   - Composition       : wraps Page rather than extending it
 *
 * ============================================================
 */
public class Frame {

    private static final Logger logger =
        LoggerFactory.getLogger(Frame.class);

    // =========================================================
    // Frame Identity
    // =========================================================

    /**
     * The position of this frame in the buffer pool's frame array.
     * Range: 0 to BUFFER_POOL_SIZE - 1.
     *
     * Immutable after construction — a frame always occupies
     * the same slot in the pool. Only its contents change.
     *
     * PostgreSQL parallel: buf_id in BufferDesc
     */
    private final int frameId;

    // =========================================================
    // The Page — actual 8KB content
    // =========================================================

    /**
     * The Page object holding the 8KB ByteBuffer and its header.
     *
     * The page is pre-allocated once when the buffer pool
     * initializes and reused across its entire lifetime.
     * Its content changes as different disk pages are loaded
     * into this frame (via reset + DiskManager.readPage).
     *
     * PostgreSQL parallel:
     *   BufferBlocks[buf_id * BLCKSZ] — the raw page bytes.
     *   In PostgreSQL, the BufferDesc and raw bytes are separate.
     *   We combine them here for simplicity.
     */
    private final Page page;

    // =========================================================
    // Valid Flag — is this frame holding a real page?
    // =========================================================

    /**
     * True if this frame is currently holding a valid page
     * loaded from disk. False if the frame is free/empty.
     *
     * A frame becomes valid when a page is loaded into it.
     * A frame becomes invalid when it is evicted and not yet
     * reloaded (very brief window — the BufferPoolManager
     * immediately reloads after eviction).
     *
     * PostgreSQL parallel: BM_VALID bit in BufferDesc.state
     */
    private final AtomicBoolean valid;

    // =========================================================
    // Eviction Metadata — used by eviction algorithms
    // =========================================================

    /**
     * Reference bit — used by the Clock eviction algorithm.
     *
     * Set to true every time the page is accessed (pin()).
     * The Clock algorithm's "second chance" mechanism:
     *   - On eviction scan, if referenceBit is true:
     *       clear it (give the page a second chance)
     *   - On next eviction scan, if referenceBit is false:
     *       evict the page (it wasn't used in the last round)
     *
     * This approximates LRU without maintaining a full LRU list.
     *
     * PostgreSQL parallel:
     *   usage_count in BufferDesc.state (bits 22-24).
     *   PostgreSQL's clock uses usage_count (0-5) not a single bit.
     *   We use a simpler single-bit version first (Section 08 adds
     *   the full usage count for enhanced Clock).
     */
    private final AtomicBoolean referenceBit;

    /**
     * Usage count — counts how many times this page has been
     * pinned since it was loaded. Used by enhanced Clock and
     * LRU-K eviction algorithms.
     *
     * Higher usage count = more popular page = evict last.
     * Reset to 0 when the frame is evicted and reused.
     *
     * PostgreSQL parallel:
     *   usage_count field (3 bits, max value 5) in buf_state.
     *   PostgreSQL caps at BM_MAX_USAGE_COUNT = 5.
     *   We use a full int for a richer count.
     */
    private final AtomicInteger usageCount;

    // =========================================================
    // Content Lock — for thread-safe page access
    // =========================================================

    /**
     * StampedLock for controlling concurrent access to page content.
     *
     * Why StampedLock over ReentrantReadWriteLock?
     *
     *   ReentrantReadWriteLock:
     *     - Multiple readers can hold read lock simultaneously
     *     - One exclusive writer blocks all readers
     *     - Lock acquisition always blocks/unblocks threads
     *
     *   StampedLock (Java 8+):
     *     - Same read/write semantics as RWLock
     *     - PLUS: optimistic reads (no lock acquisition at all!)
     *       tryOptimisticRead() returns a stamp without acquiring
     *       any lock. After reading, validate(stamp) checks if
     *       any write happened during the read. If not — done!
     *       If yes — fall back to real read lock.
     *
     *   In the buffer pool, pages are READ far more than written.
     *   Optimistic reads avoid lock contention on hot pages,
     *   giving significant throughput improvements.
     *
     * Usage pattern:
     *   // Optimistic read (no lock taken):
     *   long stamp = frame.tryOptimisticRead();
     *   byte[] data = page.copyData();
     *   if (!frame.validate(stamp)) {
     *       // Concurrent write happened — fall back to real lock
     *       stamp = frame.readLock();
     *       try { data = page.copyData(); }
     *       finally { frame.unlockRead(stamp); }
     *   }
     *
     *   // Exclusive write:
     *   long stamp = frame.writeLock();
     *   try { page.modifyData(); }
     *   finally { frame.unlockWrite(stamp); }
     *
     * PostgreSQL parallel:
     *   content_lock LWLock in BufferDesc.
     *   LWLockAcquire(buf->content_lock, LW_SHARED)   — read lock
     *   LWLockAcquire(buf->content_lock, LW_EXCLUSIVE) — write lock
     */
    private final StampedLock contentLock;

    // =========================================================
    // Constructor
    // =========================================================

    /**
     * Creates a new Frame at the given position in the pool.
     *
     * Allocates a fresh Page (pre-allocates the 8KB ByteBuffer).
     * The frame is initially INVALID — it holds no page data.
     *
     * @param frameId the position of this frame in the pool array
     *                (0 to BUFFER_POOL_SIZE - 1)
     */
    public Frame(int frameId) {
        this.frameId      = frameId;
        this.page         = new Page();      // allocates 8KB ByteBuffer
        this.valid        = new AtomicBoolean(false);
        this.referenceBit = new AtomicBoolean(false);
        this.usageCount   = new AtomicInteger(0);
        this.contentLock  = new StampedLock();

        logger.trace("Frame {} created (INVALID, awaiting page load)", frameId);
    }

    // =========================================================
    // Page Access
    // =========================================================

    /**
     * Returns the Page held by this frame.
     *
     * The caller MUST hold at least a read lock (or optimistic
     * read stamp) before accessing the page content.
     * The BufferPoolManager enforces this contract.
     *
     * @return the Page object (never null — pre-allocated)
     */
    public Page getPage() {
        return page;
    }

    /**
     * Returns the PageId of the page currently in this frame.
     *
     * Returns PageId.invalid() if the frame is not valid.
     *
     * @return the current PageId
     */
    public PageId getPageId() {
        return page.getPageId();
    }

    // =========================================================
    // Frame Validity
    // =========================================================

    /**
     * Returns true if this frame holds a valid (loaded) page.
     *
     * Invalid frames are free slots in the buffer pool —
     * they are the first candidates for loading a new page
     * (no eviction needed for invalid frames).
     *
     * @return true if this frame holds a valid page
     */
    public boolean isValid() {
        return valid.get();
    }

    /**
     * Marks this frame as valid — it now holds a real page.
     * Called by BufferPoolManager after loading a page from disk.
     */
    public void markValid() {
        valid.set(true);
    }

    /**
     * Marks this frame as invalid — it no longer holds a page.
     * Called by BufferPoolManager when evicting a frame.
     *
     * Note: The page's buffer is NOT cleared here — it will
     * be overwritten by the next DiskManager.readPage() call.
     */
    public void markInvalid() {
        valid.set(false);
    }

    // =========================================================
    // Pin / Unpin — delegates to Page
    // =========================================================

    /**
     * Pins this frame's page, preventing eviction.
     *
     * Increments the page's pin count AND sets the reference
     * bit (the page was just accessed — give it a "second chance"
     * in Clock eviction).
     *
     * Also increments usageCount for LRU-K tracking.
     *
     * @throws BufferPoolException if pin count exceeds maximum
     */
    public void pin() {
        /*
         * Pin the underlying page (increments AtomicInteger).
         * Page.pin() throws if MAX_PIN_COUNT is exceeded.
         */
        page.pin();

        /*
         * Set reference bit — this page was just accessed.
         * The Clock algorithm will see this and give the page
         * a second chance before evicting it.
         */
        referenceBit.set(true);

        /*
         * Increment usage count — tracks cumulative popularity.
         * LRU-K uses this to identify frequently accessed pages.
         */
        usageCount.incrementAndGet();

        logger.trace(
            "Frame {} pinned: pageId={}, pinCount={}, usageCount={}",
            frameId,
            page.getPageId(),
            page.getPinCount(),
            usageCount.get()
        );
    }

    /**
     * Unpins this frame's page, making it eligible for eviction
     * once the pin count reaches zero.
     *
     * @throws BufferPoolException if the page is not pinned
     */
    public void unpin() {
        page.unpin();

        logger.trace(
            "Frame {} unpinned: pageId={}, pinCount={}",
            frameId,
            page.getPageId(),
            page.getPinCount()
        );
    }

    /**
     * Returns true if this frame's page is currently pinned.
     * Pinned frames CANNOT be evicted.
     *
     * @return true if pin count > 0
     */
    public boolean isPinned() {
        return page.isPinned();
    }

    /**
     * Returns the current pin count.
     *
     * @return pin count (0 = eviction eligible)
     */
    public int getPinCount() {
        return page.getPinCount();
    }

    // =========================================================
    // Dirty Flag — delegates to Page
    // =========================================================

    /**
     * Returns true if the page in this frame has been modified
     * and needs to be written to disk before eviction.
     *
     * @return true if the page is dirty
     */
    public boolean isDirty() {
        return page.isDirty();
    }

    /**
     * Marks the page in this frame as dirty.
     * Called when the page content is modified.
     */
    public void markDirty() {
        page.markDirty();
    }

    /**
     * Clears the dirty flag after the page has been persisted.
     * Called by BufferPoolManager after successfully writing
     * the page to disk (via DiskManager.writePage).
     */
    public void clearDirty() {
        page.clearDirty();
    }

    // =========================================================
    // Eviction Metadata — Reference Bit
    // =========================================================

    /**
     * Returns the current value of the reference bit.
     *
     * The Clock eviction algorithm checks this bit:
     *   true  → page was recently accessed → clear bit, skip
     *   false → page has not been accessed → evict this frame
     *
     * @return true if the page was recently accessed
     */
    public boolean getReferenceBit() {
        return referenceBit.get();
    }

    /**
     * Sets the reference bit to true.
     * Called when the page is accessed (pinned).
     */
    public void setReferenceBit() {
        referenceBit.set(true);
    }

    /**
     * Clears the reference bit (sets to false).
     *
     * Called by the Clock eviction algorithm when it gives
     * the page a "second chance" — the bit is cleared now,
     * and if it's still false on the next clock pass, the
     * page will be evicted.
     *
     * PostgreSQL parallel:
     *   StrategyFreeBuffer() in freelist.c decrements usage_count.
     *   When usage_count reaches 0, the buffer is evictable.
     */
    public void clearReferenceBit() {
        referenceBit.set(false);
    }

    // =========================================================
    // Eviction Metadata — Usage Count
    // =========================================================

    /**
     * Returns the cumulative usage count.
     * Higher = more frequently accessed = evict last.
     *
     * @return usage count since last reset
     */
    public int getUsageCount() {
        return usageCount.get();
    }

    /**
     * Resets the usage count to zero.
     * Called when the frame is evicted and reused for a new page.
     * The new page starts with zero usage history.
     */
    public void resetUsageCount() {
        usageCount.set(0);
    }

    /**
     * Returns true if this frame is eligible for eviction.
     *
     * A frame is eligible when:
     *   1. It is valid (holds a page — invalid frames are
     *      grabbed directly without eviction)
     *   2. It is NOT pinned (no active users)
     *
     * Note: dirty frames CAN be evicted — they must be written
     * to disk first (flush before evict).
     *
     * @return true if this frame can be evicted
     */
    public boolean isEvictionCandidate() {
        return isValid() && !isPinned();
    }

    // =========================================================
    // Frame Reset — prepare for new page
    // =========================================================

    /**
     * Resets this frame to hold a new page.
     *
     * Called by BufferPoolManager when evicting the current page
     * and loading a new one into this frame.
     *
     * Steps:
     *   1. Mark frame invalid (briefly — new page loading)
     *   2. Reset the Page object for the new PageId
     *   3. Reset eviction metadata (usage count, reference bit)
     *   4. Frame is ready for DiskManager.readPage()
     *
     * The caller (BufferPoolManager) is responsible for:
     *   - Flushing dirty data BEFORE calling reset()
     *   - Removing old PageId from PageTable BEFORE reset()
     *   - Adding new PageId to PageTable AFTER reset()
     *
     * @param newPageId the PageId of the page being loaded
     */
    public void reset(PageId newPageId) {
        /*
         * Safety check: must not reset a pinned frame.
         * If this throws, there is a bug in BufferPoolManager's
         * eviction logic (evicting a pinned frame).
         */
        if (isPinned()) {
            throw new BufferPoolException(
                BufferPoolException.ErrorCode.BUFFER_POOL_FULL,
                String.format(
                    "Cannot reset frame %d: it is still pinned " +
                    "(pinCount=%d). Frame must be unpinned before reset.",
                    frameId, getPinCount()
                ),
                page.getPageId()
            );
        }

        /*
         * Mark invalid during the brief window between eviction
         * and loading the new page. If another thread queries
         * this frame during this window, isValid() returns false
         * and the query will not use this frame.
         */
        markInvalid();

        /*
         * Reset the Page object — clears its content buffer and
         * updates its identity to the new PageId.
         */
        page.reset(newPageId);

        /*
         * Reset eviction metadata for the new page.
         * It starts with zero usage history — fair competition
         * with other pages on future eviction passes.
         */
        resetUsageCount();
        clearReferenceBit();

        logger.trace(
            "Frame {} reset for new page: {}",
            frameId, newPageId
        );
    }

    // =========================================================
    // Content Lock — StampedLock operations
    // =========================================================

    /**
     * Attempts an optimistic read without acquiring any lock.
     *
     * Returns a stamp that must be validated before using the
     * data read. If validation fails, fall back to readLock().
     *
     * Usage:
     *   long stamp = frame.tryOptimisticRead();
     *   // read page data here
     *   if (!frame.validate(stamp)) {
     *       // concurrent write detected — retry with real lock
     *       stamp = frame.readLock();
     *       try { // read again } finally { frame.unlockRead(stamp); }
     *   }
     *
     * @return an optimistic read stamp (non-zero if optimistic
     *         read is available, 0 if a write is in progress)
     */
    public long tryOptimisticRead() {
        return contentLock.tryOptimisticRead();
    }

    /**
     * Validates an optimistic read stamp.
     *
     * Returns true if no write occurred since the stamp was
     * obtained — the data read optimistically is still valid.
     *
     * Returns false if a concurrent write happened — the data
     * may be inconsistent and must be re-read with a real lock.
     *
     * @param stamp the stamp obtained from tryOptimisticRead()
     * @return true if no write occurred (data is valid)
     */
    public boolean validate(long stamp) {
        return contentLock.validate(stamp);
    }

    /**
     * Acquires a shared (read) lock on this frame's content.
     *
     * Multiple threads can hold the read lock simultaneously.
     * Blocks if a write lock is held by another thread.
     *
     * MUST be paired with unlockRead(stamp) in a finally block.
     *
     * @return a stamp to pass to unlockRead()
     */
    public long readLock() {
        return contentLock.readLock();
    }

    /**
     * Releases a shared (read) lock.
     *
     * @param stamp the stamp returned by readLock()
     */
    public void unlockRead(long stamp) {
        contentLock.unlockRead(stamp);
    }

    /**
     * Acquires an exclusive (write) lock on this frame's content.
     *
     * Only one thread can hold the write lock at a time.
     * All readers and other writers are blocked until released.
     *
     * MUST be paired with unlockWrite(stamp) in a finally block.
     *
     * @return a stamp to pass to unlockWrite()
     */
    public long writeLock() {
        return contentLock.writeLock();
    }

    /**
     * Releases an exclusive (write) lock.
     *
     * @param stamp the stamp returned by writeLock()
     */
    public void unlockWrite(long stamp) {
        contentLock.unlockWrite(stamp);
    }

    /**
     * Tries to acquire a write lock without blocking.
     *
     * Returns 0 if the lock cannot be acquired immediately.
     * Non-zero stamp means the lock was acquired.
     *
     * Used by the checkpoint daemon which prefers not to block:
     *   long stamp = frame.tryWriteLock();
     *   if (stamp != 0) {
     *       try { flush dirty page }
     *       finally { frame.unlockWrite(stamp); }
     *   }
     *   // else: skip this frame, try again later
     *
     * @return non-zero stamp if acquired, 0 if busy
     */
    public long tryWriteLock() {
        return contentLock.tryWriteLock();
    }

    /**
     * Returns the frame ID (position in the pool array).
     *
     * @return frame ID (0 to BUFFER_POOL_SIZE - 1)
     */
    public int getFrameId() {
        return frameId;
    }

    // =========================================================
    // toString
    // =========================================================

    /**
     * Returns a detailed summary of this frame's state.
     *
     * Example:
     *   Frame{id=42, pageId=(1,100,0), valid=true,
     *         pinCount=2, dirty=true, refBit=true, usage=5}
     */
    @Override
    public String toString() {
        return String.format(
            "Frame{id=%d, pageId=%s, valid=%b, " +
            "pinCount=%d, dirty=%b, refBit=%b, usage=%d}",
            frameId,
            page.getPageId(),
            valid.get(),
            page.getPinCount(),
            page.isDirty(),
            referenceBit.get(),
            usageCount.get()
        );
    }
}