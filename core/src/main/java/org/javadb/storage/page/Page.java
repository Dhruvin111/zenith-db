package org.javadb.storage.page;

import org.javadb.common.LSN;
import org.javadb.common.PageId;
import org.javadb.common.exception.BufferPoolException;
import org.javadb.config.DatabaseConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ============================================================
 * Page — The 8KB Unit of Storage
 * ============================================================
 *
 * A Page is the fundamental unit of storage in JavaDB.
 * Every piece of data — table rows, index nodes, free space maps
 * — lives inside a Page. The engine never reads or writes
 * individual bytes; it always reads and writes full pages.
 *
 * A Page consists of:
 * 1. A ByteBuffer of exactly PAGE_SIZE (8192) bytes
 * — the actual on-disk content
 * 2. A PageHeader view into the first 20 bytes of that buffer
 * — metadata (LSN, checksum, free space pointers)
 * 3. Runtime state (NOT stored on disk):
 * - pageId : which page this is
 * - pinCount : how many threads are using it
 * - dirty : has it been modified since last disk write
 *
 * PostgreSQL parallel:
 * In PostgreSQL's buffer manager (bufmgr.c), a buffer is
 * represented by two structures:
 *
 * 1. BufferDesc (buffer descriptor):
 * Contains the buffer's metadata — tag (which page),
 * state (pin count, dirty flag, usage count, locking).
 * Stored in shared memory, NOT in the page data itself.
 *
 * 2. The actual page data in the buffer pool array:
 * Raw bytes at BufferBlocks[bufId * BLCKSZ]
 *
 * We combine both into a single Page class for simplicity.
 * The Buffer Pool (Section 09) wraps Page in a Frame,
 * which adds eviction metadata (reference bit, usage count).
 *
 * Page lifecycle:
 * 1. DiskManager reads 8192 bytes from disk into ByteBuffer
 * 2. BufferPoolManager wraps it in a Page object
 * 3. Caller pins the page (pinCount++)
 * 4. Caller reads/writes tuples via SlottedPage
 * 5. If modified, page is marked dirty
 * 6. Caller unpins (pinCount--)
 * 7. When evicted, if dirty, DiskManager writes to disk
 *
 * Thread safety:
 * - pinCount uses AtomicInteger (lock-free increment/decrement)
 * - dirty uses AtomicBoolean (lock-free flag)
 * - The ByteBuffer itself is NOT thread-safe — callers must
 * hold the appropriate buffer pool frame lock before
 * accessing page content (enforced in BufferPoolManager)
 *
 * Java concepts:
 * - ByteBuffer.allocateDirect() — off-heap memory for I/O
 * - AtomicInteger — thread-safe pin count
 * - AtomicBoolean — thread-safe dirty flag
 * - ByteOrder.BIG_ENDIAN — consistent byte layout
 * - PageHeader composition — shares the same ByteBuffer
 *
 * ============================================================
 */
public class Page {

    // =========================================================
    // The Page Buffer — the actual 8192 bytes
    // =========================================================

    /**
     * The 8KB ByteBuffer containing the page's on-disk content.
     *
     * Why allocateDirect()?
     * Direct ByteBuffers are backed by native memory (off-heap).
     * When FileChannel reads into a direct buffer, the OS can
     * write directly from kernel space to native memory without
     * copying through the JVM heap first. This is called
     * "zero-copy I/O" and is significantly faster for large
     * buffers like page caches.
     *
     * The tradeoff: direct buffers are not GC'd normally.
     * They are freed when the ByteBuffer object is GC'd or
     * when the Cleaner runs. For a buffer pool, this is fine
     * because we explicitly manage the pool's lifetime.
     *
     * PostgreSQL parallel:
     * PostgreSQL uses shared memory (ShmemInitStruct) allocated
     * via mmap() — also off-heap, also avoids GC concerns.
     */
    private final ByteBuffer buffer;

    // =========================================================
    // Page Header — view into the first 20 bytes of buffer
    // =========================================================

    /**
     * The PageHeader provides typed access to the header fields
     * in the first 20 bytes of the buffer.
     *
     * This is NOT a copy — the PageHeader reads/writes directly
     * into the same ByteBuffer. Changes to the header are
     * immediately reflected in the buffer and vice versa.
     */
    private final PageHeader header;

    // =========================================================
    // Runtime State (NOT stored on disk)
    // =========================================================

    /**
     * The PageId of this page — which file and page number.
     *
     * Set when the page is loaded from disk by BufferPoolManager.
     * Used by the Buffer Pool to route writes back to the correct
     * file and offset.
     */
    private volatile PageId pageId;

    /**
     * The type of this page — determines how its content is
     * interpreted (heap tuples, B+Tree nodes, etc.).
     */
    private volatile PageType pageType;

    /**
     * Pin count — number of threads currently using this page.
     *
     * A pinned page CANNOT be evicted from the buffer pool.
     * The buffer pool eviction algorithm skips all pinned frames.
     *
     * pin() → pinCount++ (called before accessing the page)
     * unpin() → pinCount-- (called when done with the page)
     *
     * A page with pinCount > 0 is "in use."
     * A page with pinCount == 0 is "eviction candidate."
     *
     * AtomicInteger: multiple threads can pin/unpin concurrently
     * without a lock — CAS (compare-and-swap) guarantees atomicity.
     *
     * PostgreSQL parallel:
     * buf_state field in BufferDesc encodes pin count in
     * bits BUF_PIN_COUNT_ONE..BUF_USAGECOUNT_ONE-1
     */
    private final AtomicInteger pinCount;

    /**
     * Dirty flag — true if this page has been modified since
     * it was last written to disk (or since it was loaded).
     *
     * The Buffer Pool flushes dirty pages during:
     * - Checkpoint (periodic)
     * - Eviction (when frame is needed for a new page)
     * - Transaction commit (for WAL-less tables, if any)
     *
     * AtomicBoolean: flag can be set from any thread without lock.
     *
     * PostgreSQL parallel:
     * BM_DIRTY bit in buf_state field of BufferDesc
     */
    private final AtomicBoolean dirty;

    // =========================================================
    // Constructors
    // =========================================================

    /**
     * Creates a new Page backed by a fresh direct ByteBuffer.
     *
     * Used by BufferPoolManager when allocating a new frame.
     * The page is not yet associated with any PageId or disk
     * location — call initialize() or loadFrom() before use.
     */
    public Page() {
        /*
         * Allocate PAGE_SIZE bytes of direct (off-heap) memory.
         * Set BIG_ENDIAN byte order for consistent multi-byte reads.
         */
        this.buffer = ByteBuffer.allocateDirect(DatabaseConfig.PAGE_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        this.header = new PageHeader(buffer);
        this.pageId = PageId.invalid();
        this.pageType = PageType.UNINITIALIZED;
        this.pinCount = new AtomicInteger(0);
        this.dirty = new AtomicBoolean(false);
    }

    /**
     * Creates a Page backed by an existing ByteBuffer.
     *
     * Used when DiskManager reads a page from disk and returns
     * a ByteBuffer that we want to wrap in a Page object.
     *
     * @param pageId the identity of this page
     * @param buffer the ByteBuffer containing the page data
     *               (must be PAGE_SIZE bytes, BIG_ENDIAN order)
     */
    public Page(PageId pageId, ByteBuffer buffer) {
        if (buffer.capacity() != DatabaseConfig.PAGE_SIZE) {
            throw new IllegalArgumentException(
                    String.format(
                            "Buffer must be exactly PAGE_SIZE (%d) bytes. Got: %d",
                            DatabaseConfig.PAGE_SIZE, buffer.capacity()));
        }
        buffer.order(ByteOrder.BIG_ENDIAN);
        this.buffer = buffer;
        this.header = new PageHeader(buffer);
        this.pageId = pageId;
        this.pageType = PageType.HEAP; // default — overridden by readType()
        this.pinCount = new AtomicInteger(0);
        this.dirty = new AtomicBoolean(false);
    }

    // =========================================================
    // Page Initialization
    // =========================================================

    /**
     * Initializes this page as a fresh, empty page of the
     * given type. Writes the page header with default values.
     *
     * Call this after allocating a new page via DiskManager:
     * PageId newId = diskManager.allocatePage(fileName);
     * Page page = bufferPool.fetchPage(newId);
     * page.initialize(PageType.HEAP);
     * // now ready to insert tuples
     *
     * PostgreSQL parallel:
     * PageInit() in bufpage.c
     *
     * @param pageType the type of page to initialize
     */
    public void initialize(PageType pageType) {
        /*
         * Zero out the entire buffer first.
         * This clears any leftover data from a previously
         * evicted page that used this buffer frame.
         *
         * clear() resets position=0, limit=capacity.
         * We then fill all bytes with 0.
         */
        buffer.clear();
        while (buffer.hasRemaining()) {
            buffer.put((byte) 0);
        }

        /*
         * Write the page header with initial values.
         * See PageHeader.initialize() for field values.
         */
        this.pageType = pageType;
        header.initialize(pageType);

        /*
         * Mark dirty — this page must be written to disk
         * before it can be evicted. (It exists in memory
         * but not yet on disk — it was just allocated.)
         */
        markDirty();
    }

    // =========================================================
    // Pin / Unpin — lifecycle management
    // =========================================================

    /**
     * Pins this page, preventing it from being evicted.
     *
     * MUST be called before accessing any page content.
     * MUST be paired with a matching unpin() call.
     *
     * Recommended usage (try-finally ensures unpin):
     * page.pin();
     * try {
     * // read/write page content
     * } finally {
     * page.unpin();
     * }
     *
     * @throws BufferPoolException if pin count would exceed maximum
     */
    public void pin() {
        int newCount = pinCount.incrementAndGet();

        if (newCount > DatabaseConfig.MAX_PIN_COUNT) {
            /*
             * Pin count overflow — almost certainly a pin leak.
             * Decrement to undo the increment, then throw.
             */
            pinCount.decrementAndGet();
            throw BufferPoolException.pinCountExceeded(pageId, newCount);
        }
    }

    /**
     * Unpins this page, making it eligible for eviction
     * once the pin count reaches zero.
     *
     * @throws BufferPoolException if the page is not currently pinned
     */
    public void unpin() {
        /*
         * getAndDecrement() returns the value BEFORE decrement.
         * If it was already 0, we have a bug — unpinning more
         * than we pinned.
         */
        int prevCount = pinCount.getAndDecrement();

        if (prevCount <= 0) {
            /*
             * Undo the decrement (don't go negative) and throw.
             * A negative pin count is worse than an exception.
             */
            pinCount.incrementAndGet();
            throw BufferPoolException.pageNotPinned(pageId);
        }
    }

    /**
     * Returns true if this page is currently pinned.
     * A pinned page cannot be evicted.
     *
     * @return true if pinCount > 0
     */
    public boolean isPinned() {
        return pinCount.get() > 0;
    }

    /**
     * Returns the current pin count.
     *
     * @return current number of active pins
     */
    public int getPinCount() {
        return pinCount.get();
    }

    // =========================================================
    // Dirty Flag
    // =========================================================

    /**
     * Marks this page as dirty — it has been modified and
     * needs to be written to disk before eviction.
     *
     * Called automatically by SlottedPage after any mutation.
     * The Buffer Pool checks this flag when evicting a page.
     */
    public void markDirty() {
        dirty.set(true);
        /*
         * Also set the DIRTY flag in the page header.
         * This makes the dirty state visible in the on-disk
         * representation for crash analysis.
         */
        header.setFlag(PageFlags.DIRTY);
    }

    /**
     * Clears the dirty flag after the page has been written to disk.
     * Called by BufferPoolManager after a successful writePage().
     */
    public void clearDirty() {
        dirty.set(false);
        header.clearFlag(PageFlags.DIRTY);
    }

    /**
     * Returns true if this page has been modified since last disk write.
     *
     * @return true if the page is dirty
     */
    public boolean isDirty() {
        return dirty.get();
    }

    // =========================================================
    // Buffer Access
    // =========================================================

    /**
     * Returns the underlying ByteBuffer for direct byte access.
     *
     * CAUTION: This gives raw access to the page's bytes.
     * The buffer's position/limit/mark may be in any state.
     * Always use buffer.position(offset) or buffer.get(index, ...)
     * for safe positional access.
     *
     * The buffer is SHARED — do not store it outside the
     * scope where the page is pinned, and do not call
     * buffer.clear() or buffer.flip() as these affect all
     * users of this buffer.
     *
     * @return the page's ByteBuffer (PAGE_SIZE bytes, BIG_ENDIAN)
     */
    public ByteBuffer getBuffer() {
        return buffer;
    }

    /**
     * Returns a read-only view of the page buffer.
     *
     * Use this when passing the buffer to code that should
     * only READ from the page (e.g., checksum verification,
     * tuple deserialization).
     *
     * The read-only view shares the same data but throws
     * ReadOnlyBufferException on any put() attempt.
     *
     * @return a read-only ByteBuffer view
     */
    public ByteBuffer getBufferReadOnly() {
        return buffer.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
    }

    /**
     * Copies the page data into a new ByteBuffer.
     *
     * Used when we need a snapshot of the page (e.g., for
     * creating a before-image in a WAL UPDATE record).
     *
     * The returned buffer is independent — modifying it does
     * NOT affect this page.
     *
     * @return a new ByteBuffer containing a copy of the page data
     */
    public ByteBuffer copyBuffer() {
        /*
         * Duplicate creates a new buffer view with its own
         * position/limit/mark but sharing the same backing data.
         * We then transfer to a new independent buffer via put().
         */
        ByteBuffer copy = ByteBuffer.allocate(DatabaseConfig.PAGE_SIZE)
                .order(ByteOrder.BIG_ENDIAN);

        /*
         * Read from position 0, copy all PAGE_SIZE bytes.
         * We use an explicit array copy for clarity.
         */
        ByteBuffer source = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        source.position(0);
        source.limit(DatabaseConfig.PAGE_SIZE);
        copy.put(source);
        copy.flip();

        return copy;
    }

    // =========================================================
    // Header Access
    // =========================================================

    /**
     * Returns the PageHeader for reading/writing header fields.
     *
     * @return the page header (shares the same ByteBuffer)
     */
    public PageHeader getHeader() {
        return header;
    }

    /**
     * Convenience method — returns the page's current LSN.
     * Equivalent to page.getHeader().getLsn()
     *
     * @return the page's LSN
     */
    public LSN getPageLSN() {
        return header.getLsn();
    }

    /**
     * Convenience method — updates the page's LSN.
     * Called by the WAL manager after writing a WAL record
     * for this page.
     *
     * @param lsn the LSN of the WAL record that modified this page
     */
    public void setPageLSN(LSN lsn) {
        header.setLsn(lsn);
        markDirty();
    }

    // =========================================================
    // Identity
    // =========================================================

    /**
     * Returns the PageId of this page.
     *
     * @return the page's (fileId, pageNumber) identity
     */
    public PageId getPageId() {
        return pageId;
    }

    /**
     * Sets the PageId — called by BufferPoolManager when loading
     * a page or when a page is reassigned to a new disk location.
     *
     * @param pageId the new PageId for this page
     */
    public void setPageId(PageId pageId) {
        this.pageId = pageId;
    }

    /**
     * Returns the type of this page.
     *
     * @return the PageType
     */
    public PageType getPageType() {
        return pageType;
    }

    /**
     * Sets the page type.
     *
     * @param pageType the type to assign
     */
    public void setPageType(PageType pageType) {
        this.pageType = pageType;
    }

    // =========================================================
    // Derived Convenience Methods
    // =========================================================

    /**
     * Returns the amount of free space available on this page.
     * Delegates to PageHeader.getFreeSpace().
     *
     * @return free bytes available for new tuple data
     */
    public int getFreeSpace() {
        return header.getFreeSpace();
    }

    /**
     * Returns true if this page can fit a tuple of the given size.
     *
     * @param tupleSize the size of the tuple to insert (bytes)
     * @return true if the tuple fits
     */
    public boolean canFit(int tupleSize) {
        return header.canFit(tupleSize);
    }

    /**
     * Returns true if this page has no tuples.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return header.isEmpty();
    }

    /**
     * Resets this Page for reuse with a new PageId.
     *
     * Called by the Buffer Pool when evicting a page and
     * reusing the frame for a different page. Clears the
     * buffer content and resets all runtime state.
     *
     * @param newPageId the PageId of the new page to be loaded
     */
    public void reset(PageId newPageId) {
        /*
         * Clear the buffer — ready for DiskManager to write new data.
         * clear() sets position=0, limit=capacity (doesn't zero bytes).
         */
        buffer.clear();

        this.pageId = newPageId;
        this.pageType = PageType.UNINITIALIZED;
        this.dirty.set(false);
        // Note: pinCount should already be 0 before reset() is called.
        // The BufferPool verifies pinCount==0 before eviction.
    }

    // =========================================================
    // toString
    // =========================================================

    /**
     * Returns a concise summary of the page's state.
     *
     * Example:
     * Page{pageId=(1,42,0), type=HEAP, pinCount=2,
     * dirty=true, freeSpace=7200, lsn=LSN(0/00000100)}
     */
    @Override
    public String toString() {
        return String.format(
                "Page{pageId=%s, type=%s, pinCount=%d, " +
                        "dirty=%b, freeSpace=%d, lsn=%s}",
                pageId,
                pageType,
                pinCount.get(),
                dirty.get(),
                header.getFreeSpace(),
                header.getLsn());
    }
}