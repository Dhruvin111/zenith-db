package org.javadb.storage.page;

import org.javadb.common.RID;
import org.javadb.common.PageId;
import org.javadb.common.exception.StorageException;
import org.javadb.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================
 * SlottedPage — Variable-Length Tuple Storage Layout Engine
 * ============================================================
 *
 * SlottedPage implements the "slotted page" layout — the
 * industry-standard technique for storing variable-length
 * records inside a fixed-size page.
 *
 * It does NOT subclass or extend Page. Instead it WRAPS a Page
 * object and provides tuple-level operations on top of the raw
 * bytes. This follows the principle of composition over inheritance.
 *
 * Page → manages bytes, pin/unpin, dirty tracking
 * SlottedPage → manages slot array and tuple placement
 *
 * PostgreSQL parallel:
 * src/backend/storage/page/bufpage.c
 * src/include/storage/bufpage.h
 *
 * PostgreSQL functions mirrored here:
 * PageAddItemExtended() → insertTuple()
 * PageGetItem() → getTuple()
 * PageIndexTupleDelete() → deleteTuple()
 * PageRepairFragmentation()→ compact()
 * PageGetFreeSpace() → getFreeSpace()
 * PageGetMaxOffsetNumber() → getSlotCount()
 *
 * Slot Entry Format (4 bytes = DatabaseConfig.SLOT_SIZE):
 * ┌─────────────────┬─────────────────┐
 * │ offset (2 bytes)│ length (2 bytes)│
 * └─────────────────┴─────────────────┘
 * offset: byte position of tuple from start of page
 * 0 = UNUSED slot (never had a tuple)
 * 1+ = valid slot pointing to tuple data
 * length: byte length of tuple
 * 0 = DEAD slot (tuple was deleted)
 * 1+ = live tuple of this many bytes
 *
 * PostgreSQL's ItemIdData uses bit fields (lp_off:15, lp_flags:2,
 * lp_len:15) packed into 4 bytes. We use two plain shorts for
 * clarity — same 4 bytes, easier to understand.
 *
 * The slot array grows FORWARD (lower increases with each slot).
 * Tuples grow BACKWARD (upper decreases with each tuple).
 * Free space is the gap between lower and upper.
 *
 * Java concepts:
 * - Composition over inheritance (wraps Page)
 * - ByteBuffer absolute positional access (get/put at index)
 * - Short.toUnsignedInt() for unsigned offset/length values
 * - Defensive validation at every public method
 * - Compact algorithm (defragmentation)
 *
 * ============================================================
 */
public class SlottedPage {

    private static final Logger logger = LoggerFactory.getLogger(SlottedPage.class);

    // =========================================================
    // Slot Entry Constants
    // =========================================================

    /*
     * Within each 4-byte slot entry:
     * byte 0-1: offset (short) — tuple position from page start
     * byte 2-3: length (short) — tuple size in bytes
     */

    /** Byte offset of the 'offset' field within a slot entry */
    private static final int SLOT_OFFSET_FIELD = 0;

    /** Byte offset of the 'length' field within a slot entry */
    private static final int SLOT_LENGTH_FIELD = 2;

    /**
     * Sentinel value for an UNUSED slot.
     * A slot with offset=SLOT_UNUSED has never held a tuple.
     * These appear after deleteTuple() is called on the last slot.
     *
     * PostgreSQL parallel: LP_UNUSED (lp_flags = 0)
     */
    private static final short SLOT_UNUSED = 0;

    /**
     * Sentinel value for a DEAD slot (deleted tuple).
     * A slot with length=SLOT_DEAD had a tuple that was deleted.
     * The tuple bytes are still on the page (not yet reclaimed)
     * but must not be returned to callers.
     *
     * Reclaimed by compact() which rebuilds the slot array and
     * repacks all live tuples.
     *
     * PostgreSQL parallel: LP_DEAD (lp_flags = 3)
     */
    private static final short SLOT_DEAD = 0;

    // =========================================================
    // The Wrapped Page
    // =========================================================

    /**
     * The underlying Page this SlottedPage operates on.
     * All reads and writes go through page.getBuffer().
     */
    private final Page page;

    /**
     * Direct reference to the page's ByteBuffer.
     * Cached here to avoid calling page.getBuffer() repeatedly.
     * Always BIG_ENDIAN byte order.
     */
    private final ByteBuffer buffer;

    /**
     * Direct reference to the page's header.
     * Cached for performance — avoids repeated page.getHeader().
     */
    private final PageHeader header;

    // =========================================================
    // Constructor
    // =========================================================

    /**
     * Wraps an existing Page with slotted page operations.
     *
     * The Page should already be initialized (either freshly
     * via Page.initialize() or loaded from disk via DiskManager).
     *
     * Does NOT pin the page — the caller is responsible for
     * pinning before constructing and unpinning when done.
     *
     * @param page the page to wrap (must be initialized)
     * @throws IllegalArgumentException if page is null
     */
    public SlottedPage(Page page) {
        if (page == null) {
            throw new IllegalArgumentException(
                    "Page cannot be null in SlottedPage.");
        }
        this.page = page;
        this.buffer = page.getBuffer();
        this.header = page.getHeader();
    }

    // =========================================================
    // INSERT — add a new tuple to the page
    // =========================================================

    /**
     * Inserts a tuple into this page and returns its slot index.
     *
     * Algorithm:
     * 1. Check if page has enough free space
     * 2. Look for a reusable DEAD slot (avoid extending slot array)
     * 3. If no dead slot found, extend the slot array (lower += 4)
     * 4. Place tuple data at upper - tupleSize (grow backward)
     * 5. Update upper = upper - tupleSize
     * 6. Write slot entry: (offset=upper, length=tupleSize)
     * 7. Mark page dirty
     * 8. Return the slot index
     *
     * PostgreSQL parallel:
     * PageAddItemExtended() in bufpage.c — inserts an item at
     * a specific offset number or finds the next available slot.
     *
     * @param tupleData the raw bytes of the tuple to insert
     *                  (serialized by TupleSerializer in Section 11)
     * @return the slot index assigned to this tuple (0-based)
     * @throws StorageException         if the page is full
     * @throws IllegalArgumentException if tupleData is null/empty
     *                                  or larger than MAX_TUPLE_SIZE
     */
    public int insertTuple(byte[] tupleData) {
        /*
         * ── Validation ───────────────────────────────────────
         */
        if (tupleData == null || tupleData.length == 0) {
            throw new IllegalArgumentException(
                    "Tuple data cannot be null or empty.");
        }
        if (tupleData.length > DatabaseConfig.MAX_TUPLE_SIZE) {
            throw new IllegalArgumentException(
                    String.format(
                            "Tuple size %d bytes exceeds maximum allowed " +
                                    "tuple size %d bytes. " +
                                    "Consider splitting large values.",
                            tupleData.length, DatabaseConfig.MAX_TUPLE_SIZE));
        }

        int tupleSize = tupleData.length;

        /*
         * ── Space Check ──────────────────────────────────────
         * Check if page has room for:
         * - The tuple bytes themselves
         * - A slot entry (SLOT_SIZE = 4 bytes) ONLY if we need
         * a new slot (may reuse a dead slot)
         *
         * We do a conservative check first with a new slot.
         * If a dead slot is found below, we reclaim it and
         * the actual space needed is just tupleSize.
         */
        if (!header.canFit(tupleSize)) {
            throw new StorageException(
                    StorageException.ErrorCode.IO_ERROR,
                    String.format(
                            "Page %s is full. Cannot insert tuple of %d bytes. " +
                                    "Free space: %d bytes (need %d including slot entry).",
                            page.getPageId(),
                            tupleSize,
                            header.getFreeSpace(),
                            tupleSize + DatabaseConfig.SLOT_SIZE));
        }

        /*
         * ── Find or Create Slot ───────────────────────────────
         *
         * Step 1: Scan for a reusable DEAD slot.
         * Reusing a dead slot avoids extending the slot array
         * and reclaims space from previously deleted tuples.
         *
         * PostgreSQL parallel:
         * PageAddItemExtended() with PAI_OVERWRITE flag checks
         * for LP_DEAD or LP_UNUSED slots to reuse.
         */
        int slotIndex = findDeadSlot();
        boolean reusingSlot = (slotIndex >= 0);

        if (!reusingSlot) {
            /*
             * No dead slot found — allocate a new slot by
             * extending the slot array:
             * lower += SLOT_SIZE (slot array grows forward)
             *
             * Check again that we have room for both the tuple
             * AND the new slot entry.
             */
            if (header.getFreeSpace() < tupleSize + DatabaseConfig.SLOT_SIZE) {
                throw new StorageException(
                        StorageException.ErrorCode.IO_ERROR,
                        String.format(
                                "Page %s is full. Cannot insert tuple of %d bytes " +
                                        "with new slot. Free space: %d bytes.",
                                page.getPageId(),
                                tupleSize,
                                header.getFreeSpace()));
            }

            /*
             * New slot index = current slot count
             * (slots are 0-indexed: 0, 1, 2, ...)
             */
            slotIndex = header.getSlotCount();

            /*
             * Advance lower by SLOT_SIZE to make room for
             * the new slot entry in the slot array.
             */
            int newLower = Short.toUnsignedInt(header.getLower())
                    + DatabaseConfig.SLOT_SIZE;
            header.setLower((short) newLower);
        }

        /*
         * ── Place Tuple Data ──────────────────────────────────
         *
         * Tuples are placed from the END of the page backward.
         * New tuple goes at: upper - tupleSize
         *
         * Then upper is decremented:
         * new upper = old upper - tupleSize
         *
         * This is the "grow backward" part of the slotted layout.
         */
        int oldUpper = Short.toUnsignedInt(header.getUpper());
        int tupleStart = oldUpper - tupleSize;
        int newUpper = tupleStart;

        /*
         * Safety check: tuple must not overlap with slot array.
         * tupleStart must be >= lower after accounting for slot.
         *
         * If reusing a slot: lower stays the same.
         * If new slot: lower was already incremented above.
         */
        int currentLower = Short.toUnsignedInt(header.getLower());
        if (tupleStart < currentLower) {
            throw new StorageException(
                    StorageException.ErrorCode.IO_ERROR,
                    String.format(
                            "Tuple placement would overlap slot array on page %s. " +
                                    "tupleStart=%d, lower=%d. Page is effectively full.",
                            page.getPageId(), tupleStart, currentLower));
        }

        /*
         * Write the tuple bytes at position tupleStart in the buffer.
         * We use absolute position put() to avoid disturbing the
         * buffer's current position.
         */
        writeTupleData(tupleStart, tupleData);

        /*
         * Update the upper pointer in the header.
         */
        header.setUpper((short) newUpper);

        /*
         * ── Write Slot Entry ──────────────────────────────────
         * Write the (offset, length) pair into the slot array
         * at the calculated slot index.
         *
         * offset = tupleStart (where the tuple begins in the page)
         * length = tupleSize (how many bytes the tuple occupies)
         */
        writeSlot(slotIndex, (short) tupleStart, (short) tupleSize);

        /*
         * ── Update Page Flags ─────────────────────────────────
         * Update the HAS_FREE_SLOTS flag based on whether any
         * dead slots remain after this insertion.
         */
        updateFreeSlotFlag();

        /*
         * ── Mark Dirty ────────────────────────────────────────
         * Page has been modified — must be written to disk
         * before eviction.
         */
        page.markDirty();

        logger.trace(
                "Inserted tuple: page={}, slot={}, offset={}, size={}",
                page.getPageId(), slotIndex, tupleStart, tupleSize);

        return slotIndex;
    }

    // =========================================================
    // GET — read a tuple from the page
    // =========================================================

    /**
     * Returns the raw bytes of the tuple at the given slot index.
     *
     * Returns null if the slot is DEAD (tuple was deleted).
     * Throws if the slot index is out of bounds.
     *
     * The returned byte array is a COPY of the tuple data.
     * Modifying it does NOT affect the page content.
     * (The actual modification path is updateTuple().)
     *
     * PostgreSQL parallel:
     * PageGetItem() in bufpage.h:
     * return (Item)(((char*)(page)) + ItemIdGetOffset(itemId))
     * PostgreSQL returns a direct pointer (no copy) — we return
     * a copy for safety (prevents accidental in-place mutation).
     *
     * @param slotIndex the 0-based slot index
     * @return the tuple's raw bytes, or null if slot is DEAD
     * @throws IllegalArgumentException if slotIndex is out of bounds
     */
    public byte[] getTuple(int slotIndex) {
        validateSlotIndex(slotIndex);

        /*
         * Read the slot entry at this index.
         */
        int offset = getSlotOffset(slotIndex);
        int length = getSlotLength(slotIndex);

        /*
         * A length of 0 means the slot is DEAD (deleted tuple).
         * Return null to indicate "no tuple here."
         * Callers (e.g., SeqScanOperator) check for null and skip.
         */
        if (length == SLOT_DEAD) {
            logger.trace(
                    "getTuple: slot {} on page {} is DEAD (deleted)",
                    slotIndex, page.getPageId());
            return null;
        }

        /*
         * Validate the stored offset and length against page bounds.
         * Corrupt values here indicate a page corruption bug.
         */
        validateTupleLocation(slotIndex, offset, length);

        /*
         * Read the tuple bytes from the buffer into a new array.
         * Using a copy protects the page content from accidental
         * mutation by the caller.
         */
        return readTupleData(offset, length);
    }

    /**
     * Returns the raw bytes of the tuple identified by its RID.
     *
     * RID = (pageId, slotIndex). This method validates that the
     * RID's pageId matches this page before reading.
     *
     * Used by the index scan path: B+Tree returns a RID,
     * executor calls getTuple(rid) to fetch the actual row.
     *
     * @param rid the Row Identifier
     * @return the tuple bytes, or null if deleted
     * @throws IllegalArgumentException if RID doesn't match this page
     */
    public byte[] getTuple(RID rid) {
        if (!rid.pageId().equals(page.getPageId())) {
            throw new IllegalArgumentException(
                    String.format(
                            "RID %s refers to page %s but this SlottedPage " +
                                    "wraps page %s.",
                            rid, rid.pageId(), page.getPageId()));
        }
        return getTuple(rid.slotIndex());
    }

    // =========================================================
    // DELETE — mark a tuple as dead
    // =========================================================

    /**
     * Marks the tuple at the given slot as DEAD (logically deleted).
     *
     * IMPORTANT: This does NOT immediately reclaim the space
     * occupied by the tuple bytes. The tuple data remains on
     * the page until compact() is called.
     *
     * Why not immediate reclaim?
     * Immediate reclaim would require shifting all tuples
     * above the deleted one — extremely expensive.
     * Lazy reclaim (mark dead, compact later) is how
     * PostgreSQL VACUUM works:
     * DELETE marks tuple as dead (xmax is set)
     * VACUUM later reclaims the space via page compaction
     *
     * After deletion:
     * - slot[slotIndex].length = 0 (SLOT_DEAD sentinel)
     * - slot[slotIndex].offset remains (for forensic analysis)
     * - page HAS_FREE_SLOTS flag is set
     * - page HAS_DEAD_TUPLES flag is set
     *
     * PostgreSQL parallel:
     * HeapTupleHeaderSetXmax() marks the tuple with the
     * deleting transaction's XID. Our simplified version
     * just zeroes the slot length.
     *
     * @param slotIndex the 0-based slot index of the tuple to delete
     * @throws IllegalArgumentException if slotIndex is out of bounds
     * @throws StorageException         if the slot is already dead
     */
    public void deleteTuple(int slotIndex) {
        validateSlotIndex(slotIndex);

        int length = getSlotLength(slotIndex);

        /*
         * Check if already deleted — double-delete is a bug.
         */
        if (length == SLOT_DEAD) {
            throw new StorageException(
                    StorageException.ErrorCode.INVALID_PAGE_ACCESS,
                    String.format(
                            "Slot %d on page %s is already dead (deleted). " +
                                    "Cannot delete twice.",
                            slotIndex, page.getPageId()));
        }

        int offset = getSlotOffset(slotIndex);

        /*
         * Mark slot as DEAD by setting length = 0.
         * The offset is preserved for forensic/recovery purposes.
         *
         * PostgreSQL parallel:
         * ItemIdSetDead() macro in itemid.h:
         * itemId->lp_flags = LP_DEAD; itemId->lp_len = 0;
         */
        writeSlot(slotIndex, (short) offset, SLOT_DEAD);

        /*
         * Update page flags:
         * HAS_FREE_SLOTS → there is now a reusable dead slot
         * HAS_DEAD_TUPLES → page has dead tuple data to clean up
         */
        header.setFlag(PageFlags.HAS_FREE_SLOTS);
        header.setFlag(PageFlags.HAS_DEAD_TUPLES);

        /*
         * Page is now dirty — the slot entry was modified.
         */
        page.markDirty();

        logger.trace(
                "Deleted tuple: page={}, slot={} (marked DEAD)",
                page.getPageId(), slotIndex);
    }

    /**
     * Marks the tuple identified by RID as deleted.
     *
     * @param rid the Row Identifier of the tuple to delete
     */
    public void deleteTuple(RID rid) {
        if (!rid.pageId().equals(page.getPageId())) {
            throw new IllegalArgumentException(
                    String.format(
                            "RID %s refers to page %s but this SlottedPage " +
                                    "wraps page %s.",
                            rid, rid.pageId(), page.getPageId()));
        }
        deleteTuple(rid.slotIndex());
    }

    // =========================================================
    // UPDATE — replace tuple data in place
    // =========================================================

    /**
     * Updates the tuple at the given slot with new data.
     *
     * Two cases:
     *
     * CASE 1: New data fits in the same space (newData.length
     * <= old tuple length):
     * → Write new data in place (same slot, same offset)
     * → Wasted bytes between new end and old end become
     * internal fragmentation (reclaimed by compact())
     *
     * CASE 2: New data is larger than old:
     * → Delete old tuple (mark DEAD)
     * → Insert new tuple (finds new location)
     * → Returns new slot index (may differ from original)
     *
     * PostgreSQL parallel:
     * PostgreSQL never updates in-place for heap tuples —
     * it always creates a new tuple version (MVCC).
     * The old tuple is marked dead (xmax set), new tuple
     * is inserted, and they are linked via t_ctid.
     *
     * We implement a simplified in-place update here
     * (without MVCC versioning — that comes in Section 15).
     *
     * @param slotIndex the slot of the tuple to update
     * @param newData   the new tuple bytes
     * @return the slot index of the updated tuple
     *         (same as slotIndex if in-place, new index if relocated)
     * @throws IllegalArgumentException if slot is dead or out of bounds
     */
    public int updateTuple(int slotIndex, byte[] newData) {
        validateSlotIndex(slotIndex);

        int oldLength = getSlotLength(slotIndex);

        if (oldLength == SLOT_DEAD) {
            throw new IllegalArgumentException(
                    String.format(
                            "Cannot update DEAD slot %d on page %s. " +
                                    "Slot must contain a live tuple.",
                            slotIndex, page.getPageId()));
        }

        if (newData == null || newData.length == 0) {
            throw new IllegalArgumentException(
                    "New tuple data cannot be null or empty for update.");
        }

        int oldOffset = getSlotOffset(slotIndex);
        int newLength = newData.length;

        if (newLength <= oldLength) {
            /*
             * CASE 1: In-place update.
             * New tuple fits within the old tuple's space.
             * Write new data at the same offset.
             *
             * Note: If newLength < oldLength, the remaining
             * bytes are wasted (internal fragmentation).
             * compact() will reclaim them.
             */
            writeTupleData(oldOffset, newData);

            /*
             * Update slot entry with new length.
             * Offset stays the same.
             */
            writeSlot(slotIndex, (short) oldOffset, (short) newLength);

            page.markDirty();

            logger.trace(
                    "Updated tuple in-place: page={}, slot={}, " +
                            "oldLen={}, newLen={}",
                    page.getPageId(), slotIndex, oldLength, newLength);

            return slotIndex;

        } else {
            /*
             * CASE 2: Relocation.
             * New tuple is larger — cannot fit in old space.
             *
             * Delete old, insert new.
             * New slot index may differ from old.
             */
            deleteTuple(slotIndex);
            int newSlotIndex = insertTuple(newData);

            logger.trace(
                    "Updated tuple (relocated): page={}, " +
                            "oldSlot={}, newSlot={}, oldLen={}, newLen={}",
                    page.getPageId(), slotIndex, newSlotIndex,
                    oldLength, newLength);

            return newSlotIndex;
        }
    }

    // =========================================================
    // COMPACT — reclaim space from dead tuples
    // =========================================================

    /**
     * Compacts the page by removing all DEAD tuples and rebuilding
     * the slot array with only live tuples.
     *
     * After compaction:
     * - All dead slots are removed from the slot array
     * - All live tuples are repacked toward the end of the page
     * - Free space is maximized (no fragmentation)
     * - All slot indexes are RENUMBERED (0, 1, 2, ... for live tuples)
     *
     * CRITICAL: Callers that hold RIDs pointing into this page
     * MUST update those RIDs after compact() since slot indexes
     * change. In practice, compact() is called by VACUUM which
     * rebuilds all indexes afterward.
     *
     * Algorithm:
     * 1. Collect all live tuples (slot index + tuple bytes)
     * 2. Zero out the page data area (bytes from header end to page end)
     * 3. Re-initialize the header (reset lower, upper)
     * 4. Re-insert all live tuples in order
     * 5. Slot indexes are reassigned 0, 1, 2, ...
     *
     * PostgreSQL parallel:
     * PageRepairFragmentation() in bufpage.c
     * Also called implicitly by HOT (Heap Only Tuple) updates
     * and explicitly by VACUUM.
     *
     * @return number of tuples remaining after compaction
     */
    public int compact() {
        logger.debug(
                "Compacting page {}: slots={}, freeSpace={} bytes",
                page.getPageId(),
                header.getSlotCount(),
                header.getFreeSpace());

        /*
         * Step 1: Collect all live tuples.
         * We read them before clearing the page so we can
         * re-insert them after the page is reset.
         */
        List<byte[]> liveTuples = new ArrayList<>();

        int slotCount = header.getSlotCount();
        for (int i = 0; i < slotCount; i++) {
            byte[] tupleData = getTuple(i);

            /*
             * getTuple() returns null for DEAD slots.
             * Only collect non-null (live) tuples.
             */
            if (tupleData != null) {
                liveTuples.add(tupleData);
            }
        }

        /*
         * Step 2: Zero out the data area of the page.
         * We preserve the page header (first PAGE_HEADER_SIZE bytes).
         * Everything after the header is cleared.
         *
         * This removes all dead tuple bytes and the old slot array.
         */
        int headerSize = DatabaseConfig.PAGE_HEADER_SIZE;
        int pageSize = DatabaseConfig.PAGE_SIZE;

        for (int i = headerSize; i < pageSize; i++) {
            buffer.put(i, (byte) 0);
        }

        /*
         * Step 3: Reset header pointers.
         * lower = PAGE_HEADER_SIZE (slot array starts fresh)
         * upper = PAGE_SIZE (tuple area starts at end)
         *
         * Also clear the dead-tuple-related flags.
         */
        header.setLower((short) headerSize);
        header.setUpper((short) pageSize);
        header.clearFlag(PageFlags.HAS_FREE_SLOTS);
        header.clearFlag(PageFlags.HAS_DEAD_TUPLES);
        header.clearFlag(PageFlags.PAGE_FULL);

        /*
         * Step 4: Re-insert all live tuples.
         * insertTuple() handles updating lower, upper, and slot array.
         * Tuples are reinserted in their original order (preserving
         * relative ordering — important for index consistency).
         */
        for (byte[] tupleData : liveTuples) {
            insertTuple(tupleData);
        }

        page.markDirty();

        int remaining = liveTuples.size();

        logger.debug(
                "Compacted page {}: {} tuples remain, {} bytes free",
                page.getPageId(), remaining, header.getFreeSpace());

        return remaining;
    }

    // =========================================================
    // SCAN — iterate over all live tuples
    // =========================================================

    /**
     * Returns a list of all live (non-deleted) tuples on this page.
     *
     * Used by the sequential scan operator (Section 20) to read
     * all tuples from a heap page.
     *
     * Each entry in the list is a TupleLocation containing:
     * - slotIndex: the slot index (for constructing RIDs)
     * - tupleData: the raw tuple bytes
     *
     * Dead slots are skipped — caller never sees deleted tuples.
     *
     * PostgreSQL parallel:
     * heapgettup() in heapam.c scans heap pages slot by slot,
     * checking visibility (MVCC) for each tuple. We skip the
     * MVCC check here — that's added in Section 15.
     *
     * @return list of live tuples with their slot indexes
     */
    public List<TupleLocation> scanTuples() {
        int slotCount = header.getSlotCount();
        List<TupleLocation> results = new ArrayList<>(slotCount);

        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            int length = getSlotLength(slotIndex);

            /*
             * Skip DEAD slots (deleted tuples).
             * length == 0 means the slot is dead.
             */
            if (length == SLOT_DEAD) {
                continue;
            }

            int offset = getSlotOffset(slotIndex);
            byte[] tupleData = readTupleData(offset, length);

            results.add(new TupleLocation(slotIndex, tupleData));
        }

        return results;
    }

    /**
     * Returns the RID for the tuple at the given slot index.
     *
     * @param slotIndex the slot index
     * @return the RID: (pageId, slotIndex)
     */
    public RID getRID(int slotIndex) {
        validateSlotIndex(slotIndex);
        return RID.of(page.getPageId(), slotIndex);
    }

    // =========================================================
    // QUERY METHODS — page state
    // =========================================================

    /**
     * Returns the number of slots in the slot array.
     * Includes both live AND dead slots.
     *
     * To get only live tuple count, use getLiveTupleCount().
     *
     * @return total slot count (live + dead)
     */
    public int getSlotCount() {
        return header.getSlotCount();
    }

    /**
     * Returns the number of live (non-deleted) tuples on the page.
     *
     * More expensive than getSlotCount() because it scans the
     * slot array. Use getSlotCount() for the total count.
     *
     * @return count of live tuples
     */
    public int getLiveTupleCount() {
        int count = 0;
        int slotCount = header.getSlotCount();

        for (int i = 0; i < slotCount; i++) {
            if (getSlotLength(i) != SLOT_DEAD) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of dead (deleted) slots.
     *
     * @return count of dead slots
     */
    public int getDeadSlotCount() {
        return header.getSlotCount() - getLiveTupleCount();
    }

    /**
     * Returns true if the slot at the given index is live
     * (contains a non-deleted tuple).
     *
     * @param slotIndex the slot to check
     * @return true if the slot is live
     */
    public boolean isLive(int slotIndex) {
        validateSlotIndex(slotIndex);
        return getSlotLength(slotIndex) != SLOT_DEAD;
    }

    /**
     * Returns true if the slot at the given index is dead.
     *
     * @param slotIndex the slot to check
     * @return true if the slot is dead (deleted)
     */
    public boolean isDead(int slotIndex) {
        return !isLive(slotIndex);
    }

    /**
     * Returns the amount of free space on this page.
     * Delegates to PageHeader.getFreeSpace().
     *
     * @return free space in bytes
     */
    public int getFreeSpace() {
        return header.getFreeSpace();
    }

    /**
     * Returns true if this page can fit a tuple of the given size.
     *
     * @param tupleSize size of the candidate tuple
     * @return true if it fits
     */
    public boolean canFit(int tupleSize) {
        return header.canFit(tupleSize);
    }

    /**
     * Returns the underlying Page object.
     *
     * @return the wrapped Page
     */
    public Page getPage() {
        return page;
    }

    // =========================================================
    // PRIVATE — Slot Array Access
    // =========================================================

    /**
     * Computes the byte offset of a slot entry in the buffer.
     *
     * Slot array starts immediately after the page header:
     * slot 0 is at: PAGE_HEADER_SIZE + 0 * SLOT_SIZE = 20
     * slot 1 is at: PAGE_HEADER_SIZE + 1 * SLOT_SIZE = 24
     * slot n is at: PAGE_HEADER_SIZE + n * SLOT_SIZE
     *
     * @param slotIndex the 0-based slot index
     * @return byte offset of this slot's entry in the buffer
     */
    private int slotByteOffset(int slotIndex) {
        return DatabaseConfig.PAGE_HEADER_SIZE
                + (slotIndex * DatabaseConfig.SLOT_SIZE);
    }

    /**
     * Reads the tuple offset stored in a slot entry.
     * The offset is the position of the tuple from page start.
     *
     * @param slotIndex the slot to read
     * @return tuple offset (0 = unused slot)
     */
    private int getSlotOffset(int slotIndex) {
        int slotBase = slotByteOffset(slotIndex);
        /*
         * Short.toUnsignedInt() converts signed short to unsigned.
         * Java shorts are signed (-32768 to 32767) but our offsets
         * are always 0-8191 — we need unsigned interpretation.
         * Without this, offsets > 32767 would appear negative.
         */
        return Short.toUnsignedInt(
                buffer.getShort(slotBase + SLOT_OFFSET_FIELD));
    }

    /**
     * Reads the tuple length stored in a slot entry.
     * 0 means the slot is DEAD (deleted tuple).
     *
     * @param slotIndex the slot to read
     * @return tuple length (0 = dead)
     */
    private int getSlotLength(int slotIndex) {
        int slotBase = slotByteOffset(slotIndex);
        return Short.toUnsignedInt(
                buffer.getShort(slotBase + SLOT_LENGTH_FIELD));
    }

    /**
     * Writes a slot entry (offset, length) at the given slot index.
     *
     * @param slotIndex the slot to write
     * @param offset    the tuple's byte offset from page start
     * @param length    the tuple's byte length (0 = dead slot)
     */
    private void writeSlot(int slotIndex, short offset, short length) {
        int slotBase = slotByteOffset(slotIndex);
        buffer.putShort(slotBase + SLOT_OFFSET_FIELD, offset);
        buffer.putShort(slotBase + SLOT_LENGTH_FIELD, length);
    }

    /**
     * Scans the slot array for the first DEAD slot.
     * Returns its index if found, -1 if no dead slot exists.
     *
     * Dead slots are reused by insertTuple() to avoid
     * unnecessarily extending the slot array.
     *
     * @return index of first dead slot, or -1 if none
     */
    private int findDeadSlot() {
        int slotCount = header.getSlotCount();

        for (int i = 0; i < slotCount; i++) {
            /*
             * A slot is dead (reusable) if its length is 0.
             * We also check offset != 0 to distinguish a dead
             * slot (previously had data) from an unused slot
             * (offset = 0, never used).
             *
             * Actually both can be reused for insertion —
             * the distinction only matters for diagnostics.
             */
            if (getSlotLength(i) == SLOT_DEAD) {
                return i;
            }
        }
        return -1; // no dead slot found
    }

    /**
     * Updates the HAS_FREE_SLOTS flag based on current slot state.
     * Called after insert to reflect whether dead slots remain.
     */
    private void updateFreeSlotFlag() {
        boolean hasDead = false;
        int slotCount = header.getSlotCount();

        for (int i = 0; i < slotCount; i++) {
            if (getSlotLength(i) == SLOT_DEAD) {
                hasDead = true;
                break;
            }
        }

        if (hasDead) {
            header.setFlag(PageFlags.HAS_FREE_SLOTS);
        } else {
            header.clearFlag(PageFlags.HAS_FREE_SLOTS);
        }
    }

    // =========================================================
    // PRIVATE — Tuple Data Access
    // =========================================================

    /**
     * Reads tuple bytes from the buffer at the given offset.
     *
     * @param offset byte offset of the tuple in the page
     * @param length number of bytes to read
     * @return copy of the tuple bytes
     */
    private byte[] readTupleData(int offset, int length) {
        byte[] data = new byte[length];
        /*
         * ByteBuffer.get(index, dst, offset, length):
         * Reads 'length' bytes starting at absolute position 'index'
         * into dst[] starting at dst[offset].
         * Does NOT change the buffer's position.
         * Available since Java 13.
         */
        buffer.get(offset, data, 0, length);
        return data;
    }

    /**
     * Writes tuple bytes into the buffer at the given offset.
     *
     * @param offset byte offset where the tuple should be written
     * @param data   the tuple bytes to write
     */
    private void writeTupleData(int offset, byte[] data) {
        /*
         * ByteBuffer.put(index, src, srcOffset, length):
         * Writes 'length' bytes from src[] starting at src[srcOffset]
         * into the buffer at absolute position 'index'.
         * Does NOT change the buffer's position.
         * Available since Java 13.
         */
        buffer.put(offset, data, 0, data.length);
    }

    // =========================================================
    // PRIVATE — Validation
    // =========================================================

    /**
     * Validates that the given slot index is within bounds.
     *
     * @param slotIndex the slot index to validate
     * @throws IllegalArgumentException if out of bounds
     */
    private void validateSlotIndex(int slotIndex) {
        int slotCount = header.getSlotCount();

        if (slotIndex < 0 || slotIndex >= slotCount) {
            throw new IllegalArgumentException(
                    String.format(
                            "Slot index %d is out of bounds. " +
                                    "Page %s has %d slots (valid range: 0 to %d).",
                            slotIndex,
                            page.getPageId(),
                            slotCount,
                            slotCount - 1));
        }
    }

    /**
     * Validates that the stored offset and length in a slot entry
     * make sense — i.e., the tuple they describe lies within
     * the page's data area and doesn't overlap the slot array.
     *
     * @param slotIndex for error reporting
     * @param offset    the stored tuple offset
     * @param length    the stored tuple length
     * @throws StorageException if the values are invalid
     */
    private void validateTupleLocation(
            int slotIndex, int offset, int length) {

        int headerSize = DatabaseConfig.PAGE_HEADER_SIZE;
        int pageSize = DatabaseConfig.PAGE_SIZE;

        /*
         * Tuple must start at or after the header + slot array.
         * (Tuples cannot overlap the header or slot array.)
         */
        if (offset < headerSize) {
            throw new StorageException(
                    StorageException.ErrorCode.PAGE_CORRUPTION,
                    String.format(
                            "Page %s corruption: slot %d has invalid offset %d " +
                                    "(must be >= PAGE_HEADER_SIZE=%d).",
                            page.getPageId(), slotIndex, offset, headerSize));
        }

        /*
         * Tuple end (offset + length) must be within page bounds.
         */
        if (offset + length > pageSize) {
            throw new StorageException(
                    StorageException.ErrorCode.PAGE_CORRUPTION,
                    String.format(
                            "Page %s corruption: slot %d tuple (offset=%d, " +
                                    "length=%d) extends beyond page end (PAGE_SIZE=%d).",
                            page.getPageId(), slotIndex,
                            offset, length, pageSize));
        }

        /*
         * Tuple must not overlap with the slot array area.
         * The slot array ends at lower.
         */
        int lower = Short.toUnsignedInt(header.getLower());
        if (offset < lower) {
            throw new StorageException(
                    StorageException.ErrorCode.PAGE_CORRUPTION,
                    String.format(
                            "Page %s corruption: slot %d tuple offset %d " +
                                    "overlaps with slot array (lower=%d).",
                            page.getPageId(), slotIndex, offset, lower));
        }
    }

    // =========================================================
    // TupleLocation — result type for scanTuples()
    // =========================================================

    /**
     * Holds the result of a single tuple scan entry.
     *
     * Contains the slot index (for RID construction) and the
     * raw tuple bytes (for deserialization).
     *
     * Implemented as a record — immutable, auto-generated
     * equals/hashCode/toString.
     *
     * Java concept:
     * record — Java 16+ immutable data carrier.
     * Nested inside SlottedPage because it is only used
     * as a return type for scanTuples().
     */
    public record TupleLocation(int slotIndex, byte[] tupleData) {

        /**
         * Constructs a RID for this tuple given the pageId.
         *
         * @param pageId the pageId of the containing page
         * @return the RID: (pageId, slotIndex)
         */
        public RID toRID(PageId pageId) {
            return RID.of(pageId, slotIndex);
        }

        /**
         * Returns the size of the tuple data in bytes.
         *
         * @return tuple byte length
         */
        public int size() {
            return tupleData.length;
        }
    }

    // =========================================================
    // toString
    // =========================================================

    /**
     * Returns a summary of this SlottedPage's state.
     *
     * Example:
     * SlottedPage{page=(1,0,0), slots=5 (3 live, 2 dead),
     * freeSpace=7200 bytes}
     */
    @Override
    public String toString() {
        int total = getSlotCount();
        int live = getLiveTupleCount();
        int dead = total - live;

        return String.format(
                "SlottedPage{page=%s, slots=%d (%d live, %d dead), " +
                        "freeSpace=%d bytes, header=%s}",
                page.getPageId(),
                total, live, dead,
                header.getFreeSpace(),
                header);
    }
}