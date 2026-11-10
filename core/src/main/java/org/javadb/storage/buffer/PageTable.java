package org.javadb.storage.buffer;

import org.javadb.common.PageId;
import org.javadb.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================
 * PageTable — Thread-Safe PageId to Frame Lookup Table
 * ============================================================
 *
 * The PageTable is the directory of the Buffer Pool. It maps
 * every page currently in memory to the Frame that holds it:
 *
 *   PageId  ──────────►  Frame
 *   (1, 42)             Frame{id=7, page=...}
 *   (1, 43)             Frame{id=2, page=...}
 *   (2, 0)              Frame{id=15, page=...}
 *
 * When a caller requests page (1, 42):
 *   - PageTable.lookup(PageId.of(1, 42)) → Frame 7  (buffer hit)
 *   - PageTable.lookup(PageId.of(1, 99)) → empty    (buffer miss)
 *
 * PostgreSQL parallel:
 *   buf_table.c — The shared buffer hash table.
 *
 *   PostgreSQL uses a custom hash table (dynahash.c) stored in
 *   shared memory, partitioned into BUF_TABLE_HASH_PARTITIONS (128)
 *   partitions. Each partition has its own LWLock for reduced
 *   contention on the hash table itself.
 *
 *   BufTableHashCode()  → hash the (RelFileNode, BlockNumber) key
 *   BufTableLookup()    → find buffer ID for a key
 *   BufTableInsert()    → add a new key → buffer ID mapping
 *   BufTableDelete()    → remove a mapping when page is evicted
 *
 *   We use Java's ConcurrentHashMap which provides:
 *   - Lock-free reads (using CAS operations internally)
 *   - Fine-grained locking (16 or more stripes by default)
 *   - O(1) average lookup, insert, delete
 *   - No separate hash partitions needed (ConcurrentHashMap
 *     handles this internally)
 *
 * Thread Safety:
 *   ConcurrentHashMap guarantees:
 *   - get() is fully lock-free
 *   - put() and remove() use fine-grained segment locks
 *   - putIfAbsent() is atomic (compare-and-set semantics)
 *   Multiple threads can look up different pages simultaneously
 *   without blocking each other.
 *
 * Java concepts:
 *   - ConcurrentHashMap         : lock-free concurrent hash table
 *   - Optional<Frame>           : null-safe lookup result
 *   - putIfAbsent()             : atomic conditional insert
 *   - computeIfAbsent()         : atomic get-or-create
 *   - Collection views          : keySet(), values()
 *
 * ============================================================
 */
public class PageTable {

    private static final Logger logger =
        LoggerFactory.getLogger(PageTable.class);

    // =========================================================
    // The Hash Table
    // =========================================================

    /**
     * The core data structure — maps PageId to Frame.
     *
     * ConcurrentHashMap internals:
     *   Java's ConcurrentHashMap uses a technique called
     *   "lock striping" — it divides the hash table into
     *   segments (typically 16-32), each with its own lock.
     *   Multiple threads can write to DIFFERENT segments
     *   simultaneously. Only threads writing to the SAME
     *   segment contend with each other.
     *
     *   In Java 8+, ConcurrentHashMap further improved to
     *   use lock-free CAS (Compare-And-Swap) for most
     *   operations, making it even faster.
     *
     * Initial capacity set to BUFFER_POOL_SIZE:
     *   The map will hold at most BUFFER_POOL_SIZE entries
     *   (one per frame). Pre-sizing avoids rehashing.
     *
     * Load factor 0.75 (default):
     *   Rehashing triggers when 75% full. Since we pre-size
     *   to BUFFER_POOL_SIZE, rehashing should never occur.
     *
     * PostgreSQL parallel:
     *   The shared buffer hash table in buf_table.c is sized
     *   to NBuffers + NUM_BUFFER_PARTITIONS entries.
     */
    private final ConcurrentHashMap<PageId, Frame> table;

    // =========================================================
    // Statistics — for Buffer Pool hit ratio tracking
    // =========================================================

    /**
     * Total number of lookups ever performed.
     * Used to compute cache hit ratio: hits / totalLookups.
     *
     * We use long[] instead of AtomicLong for slightly better
     * performance — stats are best-effort, not exact.
     *
     * Note: These are not thread-safe (race conditions possible)
     * but that's acceptable for approximate statistics.
     */
    private long totalLookups = 0L;
    private long cacheHits    = 0L;
    private long cacheMisses  = 0L;

    // =========================================================
    // Constructor
    // =========================================================

    /**
     * Creates a new PageTable pre-sized for the buffer pool.
     *
     * Pre-sizing avoids resize/rehash operations during
     * normal operation — the table starts at the right capacity.
     */
    public PageTable() {
        /*
         * Initial capacity = BUFFER_POOL_SIZE + buffer for overhead.
         * The +16 gives a small buffer above the exact pool size.
         *
         * PostgreSQL parallel:
         *   ShmemInitHash() initializes the buffer hash table
         *   with NBuffers + NUM_BUFFER_PARTITIONS as the size.
         */
        this.table = new ConcurrentHashMap<>(
            DatabaseConfig.BUFFER_POOL_SIZE + 16,
            0.75f // load factor — industry standard
        );

        logger.debug(
            "PageTable initialized (capacity={})",
            DatabaseConfig.BUFFER_POOL_SIZE + 16
        );
    }

    // =========================================================
    // Core Operations
    // =========================================================

    /**
     * Looks up the Frame containing the given PageId.
     *
     * This is the HOT PATH in the buffer pool — called on
     * every page access. Must be as fast as possible.
     *
     * Returns Optional.empty() if the page is not in the pool
     * (buffer miss — need to load from disk).
     *
     * Returns Optional.of(frame) if the page is in the pool
     * (buffer hit — use this frame directly).
     *
     * PostgreSQL parallel:
     *   BufTableLookup() in buf_table.c:
     *     hashcode = BufTableHashCode(&newTag);
     *     result = hash_search_with_hash_value(SharedBufHash,
     *              &newTag, hashcode, HASH_FIND, NULL);
     *
     * @param pageId the page to look up
     * @return Optional<Frame> — present if in buffer pool
     */
    public Optional<Frame> lookup(PageId pageId) {
        totalLookups++;

        Frame frame = table.get(pageId);

        if (frame != null) {
            cacheHits++;
            logger.trace("Buffer HIT: pageId={}, frameId={}",
                pageId, frame.getFrameId());
            return Optional.of(frame);
        }

        cacheMisses++;
        logger.trace("Buffer MISS: pageId={}", pageId);
        return Optional.empty();
    }

    /**
     * Inserts a new PageId → Frame mapping.
     *
     * Called by BufferPoolManager after loading a page from
     * disk into a frame.
     *
     * If the PageId is already in the table (race condition
     * where two threads tried to load the same page),
     * the existing mapping is preserved and the new one rejected.
     * Returns the winning Frame (existing or new).
     *
     * PostgreSQL parallel:
     *   BufTableInsert() in buf_table.c:
     *     result = hash_search_with_hash_value(SharedBufHash,
     *              &newTag, hashcode, HASH_ENTER, &found);
     *     if (found) → race condition, return existing
     *     else       → inserted successfully
     *
     * @param pageId the page identifier
     * @param frame  the frame holding this page
     * @return the frame that "won" the insert
     *         (same as frame if inserted, existing frame if already present)
     */
    public Frame insert(PageId pageId, Frame frame) {
        /*
         * putIfAbsent() is atomic:
         *   If key not present → insert and return null
         *   If key present     → do NOT insert, return existing value
         *
         * This prevents two threads from both inserting the same
         * PageId (which would mean two frames think they own the
         * same page — a serious bug).
         */
        Frame existing = table.putIfAbsent(pageId, frame);

        if (existing != null) {
            /*
             * Race condition: another thread already loaded this page.
             * Return the existing frame — it won the race.
             * The caller should use the existing frame instead.
             */
            logger.debug(
                "Race condition on insert: pageId={} already in table " +
                "(frameId={}). Discarding duplicate load.",
                pageId, existing.getFrameId()
            );
            return existing;
        }

        logger.trace(
            "PageTable insert: pageId={} → frameId={}",
            pageId, frame.getFrameId()
        );

        return frame;
    }

    /**
     * Removes the mapping for the given PageId.
     *
     * Called by BufferPoolManager when evicting a page from
     * a frame. The frame is about to be reused for a different
     * page — so the old PageId must be removed from the table.
     *
     * PostgreSQL parallel:
     *   BufTableDelete() in buf_table.c:
     *     hash_search_with_hash_value(SharedBufHash, &tag,
     *                                 hashcode, HASH_REMOVE, NULL);
     *
     * @param pageId the page being evicted
     * @return the Frame that was removed, or null if not found
     */
    public Frame remove(PageId pageId) {
        Frame removed = table.remove(pageId);

        if (removed != null) {
            logger.trace(
                "PageTable remove: pageId={} (frameId={})",
                pageId, removed.getFrameId()
            );
        } else {
            logger.warn(
                "PageTable remove: pageId={} was NOT in table. " +
                "This may indicate a bug in eviction logic.",
                pageId
            );
        }

        return removed;
    }

    /**
     * Atomically removes a specific PageId → Frame mapping.
     *
     * Unlike remove(pageId), this version only removes if the
     * CURRENT mapping matches the expected frame.
     * This prevents accidentally removing a mapping that was
     * concurrently replaced by another thread.
     *
     * PostgreSQL parallel:
     *   This is similar to using conditional CAS on the hash entry.
     *
     * @param pageId       the page identifier
     * @param expectedFrame the frame that should currently hold this page
     * @return true if the mapping was removed, false if it didn't match
     */
    public boolean removeIfPresent(PageId pageId, Frame expectedFrame) {
        /*
         * ConcurrentHashMap.remove(key, value) is atomic:
         *   Only removes the entry if key maps to the expected value.
         *   Returns true if removed, false otherwise.
         */
        boolean removed = table.remove(pageId, expectedFrame);

        if (removed) {
            logger.trace(
                "PageTable conditional remove: pageId={} removed " +
                "(frameId={})",
                pageId, expectedFrame.getFrameId()
            );
        }

        return removed;
    }

    // =========================================================
    // Bulk Query Methods
    // =========================================================

    /**
     * Returns all Frames currently in the buffer pool.
     *
     * Used by:
     *   - Checkpoint: iterate all frames, flush dirty ones
     *   - Buffer pool statistics: count dirty / pinned frames
     *   - Shutdown: flush all dirty pages before closing
     *
     * The returned collection is a live view of the table —
     * it may change as pages are loaded/evicted concurrently.
     *
     * @return collection of all current frames
     */
    public Collection<Frame> allFrames() {
        return table.values();
    }

    /**
     * Returns all PageIds currently in the buffer pool.
     *
     * Useful for debugging — shows which pages are resident.
     *
     * @return set of all resident PageIds
     */
    public Set<PageId> allPageIds() {
        return table.keySet();
    }

    /**
     * Returns true if the given PageId is currently in the pool.
     *
     * Slightly faster than lookup() when you only need a boolean
     * answer (no Optional allocation).
     *
     * @param pageId the page to check
     * @return true if in buffer pool
     */
    public boolean contains(PageId pageId) {
        return table.containsKey(pageId);
    }

    /**
     * Returns the current number of pages resident in the pool.
     *
     * @return count of pages in buffer pool
     */
    public int size() {
        return table.size();
    }

    /**
     * Returns true if the buffer pool is empty (no pages loaded).
     * Only true at startup before any pages are fetched.
     *
     * @return true if no pages are in the pool
     */
    public boolean isEmpty() {
        return table.isEmpty();
    }

    /**
     * Removes all entries from the page table.
     *
     * Called during BufferPoolManager shutdown after all dirty
     * pages have been flushed to disk.
     *
     * WARNING: Only call this during orderly shutdown. Calling
     * during normal operation will cause all subsequent page
     * accesses to be cache misses.
     */
    public void clear() {
        logger.info(
            "PageTable cleared ({} entries removed).",
            table.size()
        );
        table.clear();
    }

    // =========================================================
    // Statistics
    // =========================================================

    /**
     * Returns the buffer pool hit ratio as a percentage.
     *
     * Hit ratio = cacheHits / totalLookups × 100
     *
     * A healthy OLTP workload should have 95-99% hit ratio.
     * Lower ratios indicate the buffer pool is too small.
     *
     * PostgreSQL parallel:
     *   pg_stat_bgwriter.buffers_clean (cache misses) and
     *   pg_stat_database.blks_hit / blks_read for per-DB stats.
     *
     * @return hit ratio as percentage (0.0 to 100.0)
     */
    public double getHitRatio() {
        if (totalLookups == 0) {
            return 0.0;
        }
        return (double) cacheHits / totalLookups * 100.0;
    }

    /**
     * Returns a snapshot of buffer pool statistics.
     *
     * @return formatted stats string
     */
    public String getStats() {
        return String.format(
            "PageTable{size=%d, lookups=%d, hits=%d, misses=%d, hitRatio=%.2f%%}",
            table.size(),
            totalLookups,
            cacheHits,
            cacheMisses,
            getHitRatio()
        );
    }

    /**
     * Resets all statistics counters to zero.
     * Useful for benchmarking a specific operation.
     */
    public void resetStats() {
        totalLookups = 0L;
        cacheHits    = 0L;
        cacheMisses  = 0L;
    }

    /**
     * Returns total number of lookups performed.
     *
     * @return total lookup count
     */
    public long getTotalLookups() { return totalLookups; }

    /**
     * Returns total number of cache hits.
     *
     * @return cache hit count
     */
    public long getCacheHits() { return cacheHits; }

    /**
     * Returns total number of cache misses.
     *
     * @return cache miss count
     */
    public long getCacheMisses() { return cacheMisses; }

    // =========================================================
    // toString
    // =========================================================

    /**
     * Returns a summary of the PageTable state.
     *
     * Example:
     *   PageTable{entries=512/1024, hitRatio=98.5%,
     *             lookups=100000, hits=98500, misses=1500}
     */
    @Override
    public String toString() {
        return String.format(
            "PageTable{entries=%d/%d, hitRatio=%.2f%%, " +
            "lookups=%d, hits=%d, misses=%d}",
            table.size(),
            DatabaseConfig.BUFFER_POOL_SIZE,
            getHitRatio(),
            totalLookups,
            cacheHits,
            cacheMisses
        );
    }
}