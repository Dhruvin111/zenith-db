package org.javadb.storage.buffer;

import org.javadb.common.LSN;
import org.javadb.common.PageId;
import org.javadb.common.exception.BufferPoolException;
import org.javadb.common.exception.StorageException;
import org.javadb.config.DatabaseConfig;
import org.javadb.storage.buffer.eviction.ClockEvictor;
import org.javadb.storage.buffer.eviction.EvictionPolicy;
import org.javadb.storage.buffer.eviction.LRUEvictor;
import org.javadb.storage.disk.DiskManager;
import org.javadb.storage.disk.StorageFile;
import org.javadb.storage.page.Page;
import org.javadb.storage.page.PageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * ============================================================
 * BufferPoolManager: The In-Memory Page Cache
 * ============================================================
 *
 * Owns a fixed array of Frames and decides which disk pages live
 * in memory. Every upper layer asks for pages here; only this
 * class calls DiskManager.readPage()/writePage().
 *
 * Components it coordinates:
 * DiskManager -> reads/writes 8KB pages (Section 04)
 * Frame -> one slot: page + eviction state (Section 07)
 * PageTable -> PageId -> Frame lookup (Section 07)
 * EvictionPolicy -> picks the victim frame (Section 08)
 * free list -> frames not holding any page yet
 *
 * Pin protocol (callers MUST follow it):
 * Page p = pool.fetchPage(id); // pinned, cannot be evicted
 * try { ...use p... }
 * finally { pool.unpinPage(id, modified); }
 *
 * Locking:
 * poolLock protects the free list, the PageTable mutations and
 * the eviction decision. Page CONTENT is protected separately by
 * each Frame's StampedLock. Never call flushPage() on a page
 * while your own thread holds that frame's WRITE lock
 * (StampedLock is not reentrant).
 *
 * Known simplification: a cache miss performs disk I/O while
 * holding poolLock. Correct and simple, but it serializes misses.
 * Production systems use partitioned locks and I/O-in-progress flags.
 *
 * PostgreSQL parallel: bufmgr.c (BufferAlloc, FlushBuffer, BufferSync)
 *
 * Java concepts: ReentrantLock, Deque free list, Strategy pattern,
 * Consumer<T> callback hook, AtomicLong counters, AutoCloseable
 * ============================================================
 */
public class BufferPoolManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BufferPoolManager.class);

    /** Which eviction algorithm the pool should use. */
    public enum PolicyType {
        CLOCK, LRU
    }

    // =========================================================
    // Fields
    // =========================================================

    private final DiskManager diskManager;
    private final int poolSize;

    /** The frame array. frames[i].getFrameId() == i. */
    private final Frame[] frames;

    private final PageTable pageTable;
    private final EvictionPolicy evictionPolicy;

    /** Frames that currently hold no page. Guarded by poolLock. */
    private final Deque<Frame> freeList = new ArrayDeque<>();

    /** Guards free list, page table mutation and victim selection. */
    private final ReentrantLock poolLock = new ReentrantLock();

    /** fileId -> file name, because DiskManager is keyed by name. */
    private final ConcurrentHashMap<Integer, String> fileNames = new ConcurrentHashMap<>();

    /**
     * Called with a page's LSN before that page is written to disk,
     * so the WAL can be flushed up to it first (WAL-before-data).
     * No-op until WALManager exists (Section 14).
     */
    private volatile Consumer<LSN> walFlusher = lsn -> {
    };

    // Statistics (the PageTable's own counters also count unpin
    // lookups, so the pool keeps its own accurate ones).
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong diskReads = new AtomicLong();
    private final AtomicLong diskWrites = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    // =========================================================
    // Construction
    // =========================================================

    /**
     * @param diskManager the storage layer (not owned: caller closes it)
     * @param poolSize    number of frames (pages cached in memory)
     * @param policyType  eviction algorithm
     */
    public BufferPoolManager(
            DiskManager diskManager, int poolSize, PolicyType policyType) {
        if (diskManager == null) {
            throw new IllegalArgumentException("DiskManager cannot be null.");
        }
        if (poolSize <= 0) {
            throw new IllegalArgumentException(
                    "poolSize must be > 0, got: " + poolSize);
        }

        this.diskManager = diskManager;
        this.poolSize = poolSize;
        this.frames = new Frame[poolSize];
        this.pageTable = new PageTable();

        // Pre-allocate every frame (and its direct 8KB buffer) once.
        for (int i = 0; i < poolSize; i++) {
            frames[i] = new Frame(i);
            freeList.addLast(frames[i]);
        }

        this.evictionPolicy = switch (policyType) {
            case CLOCK -> new ClockEvictor(frames);
            case LRU -> new LRUEvictor(poolSize);
        };

        logger.info("BufferPoolManager ready: {} frames ({} KB), policy={}",
                poolSize, (long) poolSize * DatabaseConfig.PAGE_SIZE / 1024,
                evictionPolicy.name());
    }

    /** Uses DatabaseConfig.BUFFER_POOL_SIZE and the Clock policy. */
    public BufferPoolManager(DiskManager diskManager) {
        this(diskManager, DatabaseConfig.BUFFER_POOL_SIZE, PolicyType.CLOCK);
    }

    // =========================================================
    // File Registration (fileId <-> file name)
    // =========================================================

    /**
     * Creates (or reopens) a file and registers it with the pool.
     *
     * @return the fileId to use in PageIds for this file
     */
    public int registerFile(String fileName, StorageFile type) {
        int fileId = diskManager.createFile(fileName, type);
        fileNames.put(fileId, fileName);
        return fileId;
    }

    /**
     * Opens an EXISTING file (e.g. at restart) and registers it.
     *
     * @throws StorageException if the file does not exist
     */
    public int registerExistingFile(String fileName, StorageFile type) {
        int fileId = diskManager.openFile(fileName, type);
        fileNames.put(fileId, fileName);
        return fileId;
    }

    /** Returns the number of pages currently allocated in a file. */
    public long getPageCount(int fileId) {
        return diskManager.getPageCount(fileNameOf(fileId));
    }

    // =========================================================
    // fetchPage / fetchFrame
    // =========================================================

    /**
     * Returns the page, pinned. Loads it from disk on a miss.
     * Caller MUST call unpinPage() when done.
     *
     * @throws BufferPoolException if every frame is pinned
     * @throws StorageException    on I/O error or checksum mismatch
     */
    public Page fetchPage(PageId pageId) {
        return fetchFrame(pageId).getPage();
    }

    /**
     * Same as fetchPage() but returns the Frame, so the caller can
     * take the frame's content lock (readLock/writeLock/optimistic).
     */
    public Frame fetchFrame(PageId pageId) {
        requireValid(pageId);

        poolLock.lock();
        try {
            // ---- HIT ----
            Optional<Frame> hit = pageTable.lookup(pageId);
            if (hit.isPresent()) {
                Frame frame = hit.get();
                frame.pin();
                evictionPolicy.recordAccess(frame);
                hits.incrementAndGet();
                return frame;
            }

            // ---- MISS ----
            misses.incrementAndGet();
            Frame frame = acquireFrame();

            try {
                frame.reset(pageId); // clears state, sets new identity
                loadFromDisk(frame, pageId); // fills the 8KB buffer
            } catch (RuntimeException e) {
                releaseFrame(frame); // give the frame back, stay consistent
                throw e;
            }

            frame.markValid();
            pageTable.insert(pageId, frame);
            frame.pin();
            evictionPolicy.recordAccess(frame);
            return frame;

        } finally {
            poolLock.unlock();
        }
    }

    // =========================================================
    // newPage / newFrame
    // =========================================================

    /**
     * Allocates a brand-new page at the end of the file, initializes
     * its header as the given type, and returns it pinned and dirty.
     */
    public Page newPage(int fileId, PageType type) {
        return newFrame(fileId, type).getPage();
    }

    /** Same as newPage() but returns the Frame. */
    public Frame newFrame(int fileId, PageType type) {
        String fileName = fileNameOf(fileId);

        poolLock.lock();
        try {
            // Get the frame FIRST: if the pool is exhausted we fail
            // before extending the file, so no disk page is leaked.
            Frame frame = acquireFrame();
            PageId newId;

            try {
                newId = diskManager.allocatePage(fileName);
                frame.reset(newId);
                frame.getPage().initialize(type); // zero + header + dirty
            } catch (RuntimeException e) {
                releaseFrame(frame);
                throw e;
            }

            frame.markValid();
            pageTable.insert(newId, frame);
            frame.pin();
            evictionPolicy.recordAccess(frame);

            logger.debug("New page {} ({}) in frame {}",
                    newId, type, frame.getFrameId());
            return frame;

        } finally {
            poolLock.unlock();
        }
    }

    // =========================================================
    // unpinPage
    // =========================================================

    /**
     * Releases one pin. If the caller modified the page, pass
     * modified=true so the pool knows it must be written back.
     *
     * @throws BufferPoolException if the page is not resident or not pinned
     */
    public void unpinPage(PageId pageId, boolean modified) {
        poolLock.lock();
        try {
            Frame frame = pageTable.lookup(pageId)
                    .orElseThrow(() -> BufferPoolException.pageNotFound(pageId));

            if (modified) {
                frame.markDirty();
            }
            frame.unpin(); // throws pageNotPinned() on over-unpin
        } finally {
            poolLock.unlock();
        }
    }

    // =========================================================
    // Flushing
    // =========================================================

    /**
     * Writes one page to disk if it is dirty.
     *
     * @return false if the page is not in the pool, true otherwise
     */
    public boolean flushPage(PageId pageId) {
        Frame frame;

        poolLock.lock();
        try {
            frame = pageTable.lookup(pageId).orElse(null);
            if (frame == null) {
                return false;
            }
            // Pin so it cannot be evicted while we write outside the lock.
            frame.pin();
        } finally {
            poolLock.unlock();
        }

        try {
            flushFrame(frame);
        } finally {
            frame.unpin();
        }
        return true;
    }

    /**
     * Writes every dirty page to disk (in PageId order, which keeps
     * disk access sequential), then fsyncs all files. This is the
     * checkpoint write-out.
     *
     * @return number of dirty pages processed
     */
    public int flushAll() {
        List<Frame> dirty = new ArrayList<>();

        // Phase 1 (under lock): find dirty frames and pin them.
        poolLock.lock();
        try {
            for (Frame f : pageTable.allFrames()) {
                if (f.isDirty()) {
                    try {
                        f.pin();
                    } catch (RuntimeException e) {
                        dirty.forEach(Frame::unpin); // don't leak pins
                        throw e;
                    }
                    dirty.add(f);
                }
            }
        } finally {
            poolLock.unlock();
        }

        dirty.sort(Comparator.comparing(Frame::getPageId));

        // Phase 2 (no pool lock): write them out.
        RuntimeException firstError = null;
        for (Frame f : dirty) {
            try {
                flushFrame(f);
            } catch (RuntimeException e) {
                if (firstError == null)
                    firstError = e;
                else
                    firstError.addSuppressed(e);
            } finally {
                f.unpin();
            }
        }
        if (firstError != null) {
            throw firstError;
        }

        diskManager.syncAll(); // make the writes durable
        logger.debug("flushAll: {} dirty pages written", dirty.size());
        return dirty.size();
    }

    // =========================================================
    // Hooks and Statistics
    // =========================================================

    /**
     * Installs the WAL flush callback (set by WALManager in Section 14).
     * It receives a page's LSN just before that page is written.
     */
    public void setWalFlusher(Consumer<LSN> walFlusher) {
        this.walFlusher = (walFlusher != null) ? walFlusher : lsn -> {
        };
    }

    public int getPoolSize() {
        return poolSize;
    }

    public long getHitCount() {
        return hits.get();
    }

    public long getMissCount() {
        return misses.get();
    }

    public long getDiskReadCount() {
        return diskReads.get();
    }

    public long getDiskWriteCount() {
        return diskWrites.get();
    }

    public long getEvictionCount() {
        return evictions.get();
    }

    public String getPolicyName() {
        return evictionPolicy.name();
    }

    /** Hit ratio as a percentage (0.0 to 100.0). */
    public double getHitRatio() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total * 100.0;
    }

    /** Frames holding no page. */
    public int getFreeFrameCount() {
        poolLock.lock();
        try {
            return freeList.size();
        } finally {
            poolLock.unlock();
        }
    }

    /** Frames with at least one active pin. */
    public int getPinnedFrameCount() {
        int n = 0;
        for (Frame f : frames) {
            if (f.isValid() && f.isPinned())
                n++;
        }
        return n;
    }

    /** Frames holding modified pages not yet on disk. */
    public int getDirtyFrameCount() {
        int n = 0;
        for (Frame f : frames) {
            if (f.isValid() && f.isDirty())
                n++;
        }
        return n;
    }

    public String getStats() {
        return String.format(
                "BufferPool{policy=%s, size=%d, resident=%d, pinned=%d, dirty=%d, " +
                        "hits=%d, misses=%d, hitRatio=%.2f%%, reads=%d, writes=%d, evictions=%d}",
                evictionPolicy.name(), poolSize, pageTable.size(),
                getPinnedFrameCount(), getDirtyFrameCount(),
                hits.get(), misses.get(), getHitRatio(),
                diskReads.get(), diskWrites.get(), evictions.get());
    }

    // =========================================================
    // Internals: frame acquisition and eviction
    // =========================================================

    /**
     * Returns a frame that is free to receive a new page: either one
     * from the free list, or a victim chosen by the eviction policy
     * (flushed first if dirty). Caller must hold poolLock.
     *
     * @throws BufferPoolException if every frame is pinned
     */
    private Frame acquireFrame() {
        // 1. Cheapest: a frame that was never used.
        Frame free = freeList.pollFirst();
        if (free != null) {
            return free;
        }

        // 2. Ask the policy for a victim.
        Frame victim = evictionPolicy.selectVictim()
                .orElseThrow(BufferPoolException::bufferPoolFull);

        // Pins only happen under poolLock, so this cannot change
        // between selection and now. Verify the invariant anyway.
        if (victim.isPinned()) {
            throw new IllegalStateException(
                    "Eviction policy returned pinned frame " + victim);
        }

        PageId oldId = victim.getPageId();

        // 3. Write the old page back if it was modified.
        if (victim.isDirty()) {
            flushFrame(victim);
        }

        // 4. Forget the old page.
        pageTable.remove(oldId);
        evictionPolicy.remove(victim);
        evictions.incrementAndGet();

        logger.trace("Evicted {} from frame {}", oldId, victim.getFrameId());
        return victim;
    }

    /**
     * Returns an acquired-but-unused frame to the free list
     * (used when loading or allocating a page failed).
     */
    private void releaseFrame(Frame frame) {
        frame.markInvalid();
        evictionPolicy.remove(frame);
        freeList.addFirst(frame);
    }

    // =========================================================
    // Internals: disk I/O
    // =========================================================

    /** Reads the page from disk and copies it into the frame's buffer. */
    private void loadFromDisk(Frame frame, PageId pageId) {
        ByteBuffer src = diskManager.readPage(pageId, fileNameOf(pageId.fileId()));
        ByteBuffer dst = frame.getPage().getBuffer();

        dst.clear();
        src.rewind();
        dst.put(src); // bulk copy of PAGE_SIZE bytes
        dst.clear(); // leave the buffer in its normal state

        diskReads.incrementAndGet();
    }

    /**
     * Writes one frame to disk if dirty.
     *
     * Order of operations:
     * 1. take the frame's READ lock (no writer changes it mid-write)
     * 2. flush the WAL up to the page's LSN (WAL before data)
     * 3. clear the dirty flag BEFORE writing (a concurrent
     * modification after this point re-marks it dirty, so it
     * is never lost)
     * 4. write; on failure restore the dirty flag
     */
    private void flushFrame(Frame frame) {
        if (!frame.isDirty()) {
            return;
        }

        PageId pageId = frame.getPageId();
        String fileName = fileNameOf(pageId.fileId());

        long stamp = frame.readLock();
        try {
            if (!frame.isDirty()) {
                return; // another thread flushed it meanwhile
            }

            LSN pageLsn = frame.getPage().getPageLSN();
            if (pageLsn.isValid()) {
                walFlusher.accept(pageLsn);
            }

            frame.clearDirty();
            try {
                // duplicate() = independent position/limit, shared bytes,
                // so DiskManager's checksum lands in the frame itself.
                ByteBuffer out = frame.getPage().getBuffer().duplicate();
                out.clear();
                diskManager.writePage(pageId, fileName, out);
            } catch (RuntimeException e) {
                frame.markDirty(); // still not on disk
                throw e;
            }

            diskWrites.incrementAndGet();
            logger.trace("Flushed {} from frame {}", pageId, frame.getFrameId());
        } finally {
            frame.unlockRead(stamp);
        }
    }

    // =========================================================
    // Internals: validation
    // =========================================================

    private String fileNameOf(int fileId) {
        String name = fileNames.get(fileId);
        if (name == null) {
            throw new StorageException(
                    StorageException.ErrorCode.INVALID_PAGE_ACCESS,
                    "fileId " + fileId + " is not registered with the " +
                            "buffer pool. Call registerFile() first.");
        }
        return name;
    }

    private static void requireValid(PageId pageId) {
        if (pageId == null || pageId.isInvalid()) {
            throw new IllegalArgumentException(
                    "Cannot fetch an invalid PageId: " + pageId);
        }
    }

    // =========================================================
    // Shutdown
    // =========================================================

    /**
     * Flushes every dirty page and fsyncs. Does NOT close the
     * DiskManager: whoever created it closes it.
     */
    @Override
    public void close() {
        logger.info("BufferPoolManager shutting down. {}", getStats());
        flushAll();
        poolLock.lock();
        try {
            pageTable.clear();
        } finally {
            poolLock.unlock();
        }
    }

    @Override
    public String toString() {
        return getStats();
    }
}