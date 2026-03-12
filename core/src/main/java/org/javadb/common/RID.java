package org.javadb.common;

/**
 * ============================================================
 * RID — Row Identifier (Record ID)
 * ============================================================
 *
 * A RID uniquely identifies a single tuple (row) in the entire
 * database. It is a composite of:
 *   - pageId    : which page contains this tuple
 *   - slotIndex : which slot in that page's slot array
 *
 * PostgreSQL parallel:
 *   ItemPointer / TID (Tuple ID) / ctid in itemptr.h
 *   Defined as:
 *     typedef struct ItemPointerData {
 *         BlockIdData  ip_blkid;   -- page number
 *         OffsetNumber ip_posid;   -- slot number (1-based in pg)
 *     } ItemPointerData;
 *
 *   In PostgreSQL, you can SELECT the ctid of any row:
 *     SELECT ctid, * FROM users;
 *     -- ctid looks like: (0,1) meaning page 0, slot 1
 *
 *   Our RID is the direct equivalent.
 *
 * Why it matters:
 *   - Index entries (B+Tree leaf values) store RIDs.
 *     When the index finds a match, it returns the RID,
 *     and the executor uses it to fetch the actual tuple.
 *   - UPDATE and DELETE use RIDs to locate the exact tuple
 *     to modify without a full table scan.
 *   - MVCC version chains link tuples via RIDs.
 *
 * Design decisions:
 *   - Record type: same reasoning as PageId — immutable,
 *     correct equals/hashCode, minimal boilerplate.
 *   - slotIndex is 0-based (unlike PostgreSQL's 1-based
 *     OffsetNumber) for simpler array indexing in Java.
 *
 * Java concepts:
 *   - record                   : immutable value object
 *   - compact constructor      : validation
 *   - static factory methods   : RID.of()
 *
 * ============================================================
 */
public record RID(PageId pageId, int slotIndex)
        implements Comparable<RID> {

    /*
     * ── Compact Constructor (Validation) ─────────────────────
     *
     * Validates that slotIndex is non-negative.
     * pageId validity is checked by PageId's own constructor.
     */
    public RID {
        if (pageId == null) {
            throw new IllegalArgumentException(
                "PageId cannot be null in RID."
            );
        }
        if (slotIndex < 0) {
            throw new IllegalArgumentException(
                "slotIndex must be >= 0, got: " + slotIndex +
                ". RID slot indexes are 0-based."
            );
        }
    }

    // =========================================================
    // Static Factory Methods
    // =========================================================

    /**
     * Creates a RID from a PageId and slot index.
     *
     * Usage:
     *   RID rid = RID.of(PageId.of(1, 0), 3);
     *   // Page 0 of file 1, slot index 3
     *
     * @param pageId    the page containing this tuple
     * @param slotIndex the 0-based slot index within the page
     * @return a new RID
     */
    public static RID of(PageId pageId, int slotIndex) {
        return new RID(pageId, slotIndex);
    }

    /**
     * Creates a RID directly from raw components.
     * Convenience overload that constructs the PageId internally.
     *
     * Usage:
     *   RID rid = RID.of(1, 0, 3);
     *   // fileId=1, pageNumber=0, slotIndex=3
     *
     * @param fileId     file ID of the page
     * @param pageNumber page number within the file
     * @param slotIndex  slot index within the page
     * @return a new RID
     */
    public static RID of(int fileId, int pageNumber, int slotIndex) {
        return new RID(PageId.of(fileId, pageNumber), slotIndex);
    }

    /**
     * Creates an invalid/null RID sentinel.
     *
     * Used to represent "no tuple" — for example, when a
     * DELETE marks a tuple as dead, the slot's RID becomes
     * invalid. Also used in MVCC version chains as a terminator.
     *
     * @return the invalid RID sentinel
     */
    public static RID invalid() {
        return new RID(PageId.invalid(), 0);
    }

    // =========================================================
    // Query Methods
    // =========================================================

    /**
     * Returns true if this RID is the invalid sentinel.
     *
     * @return true if invalid, false if pointing to a real tuple
     */
    public boolean isInvalid() {
        return pageId.isInvalid();
    }

    /**
     * Returns true if this RID points to a real tuple.
     */
    public boolean isValid() {
        return !isInvalid();
    }

    /**
     * Extracts the file ID from the embedded PageId.
     * Convenience accessor to avoid rid.pageId().fileId().
     *
     * @return the file ID of the page containing this tuple
     */
    public int fileId() {
        return pageId.fileId();
    }

    /**
     * Extracts the page number from the embedded PageId.
     * Convenience accessor to avoid rid.pageId().pageNumber().
     *
     * @return the page number containing this tuple
     */
    public int pageNumber() {
        return pageId.pageNumber();
    }

    // =========================================================
    // Comparable — natural ordering
    // =========================================================

    /**
     * Natural ordering: sort by pageId first, then by slotIndex.
     *
     * This ordering is used when:
     *   - Sorting index entries during bulk load
     *   - Ordering RIDs in a bitmap scan result
     *     (scan in physical order = sequential I/O = faster)
     *
     * @param other the other RID to compare to
     * @return negative if this < other, 0 if equal, positive if this > other
     */
    @Override
    public int compareTo(RID other) {
        /*
         * Delegate page-level comparison to PageId.compareTo(),
         * then compare slot index if pages are equal.
         */
        int pageCompare = this.pageId.compareTo(other.pageId);
        if (pageCompare != 0) {
            return pageCompare;
        }
        return Integer.compare(this.slotIndex, other.slotIndex);
    }

    /*
     * toString() auto-generated by record produces:
     *   RID[pageId=PageId[fileId=1, pageNumber=0], slotIndex=3]
     *
     * For more PostgreSQL-like output (e.g., "(0,3)"), we
     * override toString() here.
     */
    @Override
    public String toString() {
        /*
         * Format mirrors PostgreSQL's ctid display: (pageNumber, slot)
         * Example: (0,3) means pageNumber=0, slotIndex=3
         * We add fileId for multi-file clarity: file1:(0,3)
         */
        return String.format("(%d,%d,%d)",
            pageId.fileId(), pageId.pageNumber(), slotIndex);
    }
}