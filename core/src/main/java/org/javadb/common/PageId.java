package org.javadb.common;

import org.javadb.config.DatabaseConfig;

/**
 * ============================================================
 * PageId — Universal Page Address
 * ============================================================
 *
 * A PageId uniquely identifies any page in the entire database
 * across all files. It is a composite key of:
 *   - fileId    : which physical file this page belongs to
 *   - pageNumber: the page's position within that file
 *
 * PostgreSQL parallel:
 *   In PostgreSQL, a page is identified by:
 *     RelFileNode (spcNode + dbNode + relNode) + BlockNumber
 *   defined in include/storage/block.h and relfilenode.h
 *
 *   We simplify to (fileId, pageNumber) for clarity.
 *   The concept is identical — every page in the system
 *   has a unique, stable address.
 *
 * Why it matters:
 *   - The Buffer Pool uses PageId as the key to its page table.
 *     PageId → Frame (the in-memory page slot)
 *   - The Disk Manager uses PageId to compute byte offsets:
 *     offset = pageNumber * PAGE_SIZE
 *   - WAL records reference PageId to identify which page
 *     a log record applies to during recovery.
 *   - B+Tree nodes store PageId references to their children.
 *
 * Design decisions:
 *   - Implemented as a Java 'record' — records are ideal for
 *     immutable value objects (like coordinates, keys, IDs).
 *     The compiler auto-generates:
 *       constructor, getters, equals(), hashCode(), toString()
 *
 *   - equals() + hashCode() correctness is CRITICAL here
 *     because PageId is used as a HashMap key in the Buffer Pool.
 *     Records guarantee correct equals/hashCode based on all
 *     component fields — no manual implementation needed.
 *
 *   - Implements Comparable<PageId> so PageIds can be sorted.
 *     This is used in B+Tree page ordering and WAL replay ordering.
 *
 * Java concepts:
 *   - record (Java 16+)        : immutable data carrier
 *   - implements Comparable    : natural ordering
 *   - static factory methods   : PageId.of() instead of new PageId()
 *   - compact constructor      : validation inside record
 *
 * ============================================================
 */
public record PageId(int fileId, int pageNumber)
        implements Comparable<PageId> {

    /*
     * ── Compact Constructor ──────────────────────────────────
     *
     * In a record, the "compact constructor" runs before the
     * auto-generated canonical constructor. It's used for
     * validation. We don't need to assign fields here —
     * the record handles that automatically after this block.
     *
     * This validates inputs WITHOUT allowing invalid PageIds
     * to exist anywhere in the system. Fail-fast principle.
     */
    public PageId {
        /*
         * Allow INVALID_FILE_ID and INVALID_PAGE_ID as sentinel
         * values (used for null/uninitialized page references),
         * but reject any other negative values which would be bugs.
         */
        if (fileId < DatabaseConfig.INVALID_FILE_ID) {
            throw new IllegalArgumentException(
                "fileId must be >= " + DatabaseConfig.INVALID_FILE_ID +
                ", got: " + fileId
            );
        }
        if (pageNumber < DatabaseConfig.INVALID_PAGE_ID) {
            throw new IllegalArgumentException(
                "pageNumber must be >= " + DatabaseConfig.INVALID_PAGE_ID +
                ", got: " + pageNumber
            );
        }
    }

    // =========================================================
    // Static Factory Methods
    // =========================================================

    /**
     * Creates a valid PageId for a real page.
     *
     * Static factory pattern preferred over 'new PageId(...)' because:
     *   1. Method name 'of' is self-documenting
     *   2. Can be cached/pooled later without changing call sites
     *   3. Consistent with Java standard library (List.of, Map.of)
     *
     * Usage:
     *   PageId pageId = PageId.of(1, 42);
     *   // fileId=1, pageNumber=42
     *
     * @param fileId     the ID of the file containing this page
     * @param pageNumber the zero-based page number within the file
     * @return a new PageId
     */
    public static PageId of(int fileId, int pageNumber) {
        return new PageId(fileId, pageNumber);
    }

    /**
     * Creates the sentinel "invalid" PageId.
     *
     * Used to represent "no page" — similar to a null pointer
     * but type-safe. Examples:
     *   - B+Tree leaf node's nextPageId when it's the last leaf
     *   - Uninitialized page reference in a new index node
     *
     * PostgreSQL parallel:
     *   InvalidBlockNumber = (BlockNumber) 0xFFFFFFFF in block.h
     *
     * @return the invalid/null PageId sentinel
     */
    public static PageId invalid() {
        return new PageId(
            DatabaseConfig.INVALID_FILE_ID,
            DatabaseConfig.INVALID_PAGE_ID
        );
    }

    // =========================================================
    // Query Methods
    // =========================================================

    /**
     * Returns true if this PageId is the invalid sentinel.
     *
     * Always use this method instead of comparing with
     * PageId.invalid() directly, as it avoids object allocation
     * and is more readable:
     *
     *   // Preferred:
     *   if (nextPage.isInvalid()) { ... }
     *
     *   // Avoid:
     *   if (nextPage.equals(PageId.invalid())) { ... }
     */
    public boolean isInvalid() {
        return fileId == DatabaseConfig.INVALID_FILE_ID
            && pageNumber == DatabaseConfig.INVALID_PAGE_ID;
    }

    /**
     * Returns true if this PageId refers to a real, valid page.
     * Convenience inverse of isInvalid().
     */
    public boolean isValid() {
        return !isInvalid();
    }

    /**
     * Computes the byte offset of this page within its file.
     *
     * This is the core formula the DiskManager uses to seek
     * to the correct position in the file before reading/writing.
     *
     * Formula:
     *   offset = pageNumber * PAGE_SIZE
     *
     * Example:
     *   pageNumber=0 → offset=0       (first page, start of file)
     *   pageNumber=1 → offset=8192    (second page)
     *   pageNumber=2 → offset=16384   (third page)
     *
     * @return byte offset of this page within its file
     */
    public long byteOffset() {
        return (long) pageNumber * DatabaseConfig.PAGE_SIZE;
    }

    /**
     * Returns the next sequential PageId (same file, next page).
     *
     * Used when scanning a heap file sequentially:
     *   PageId current = PageId.of(1, 0);
     *   PageId next    = current.next();  // PageId(1, 1)
     *
     * @return PageId of the next page in the same file
     */
    public PageId next() {
        return new PageId(fileId, pageNumber + 1);
    }

    // =========================================================
    // Comparable — natural ordering for sorting/B+Tree
    // =========================================================

    /**
     * Natural ordering: sort by fileId first, then by pageNumber.
     *
     * This ordering is used when:
     *   - Sorting dirty pages before flushing (checkpoint)
     *     Writing pages in order minimizes disk seeks
     *   - WAL replay: pages replayed in page order
     *
     * @param other the other PageId to compare to
     * @return negative if this < other, 0 if equal, positive if this > other
     */
    @Override
    public int compareTo(PageId other) {
        /*
         * Compare fileId first (primary sort key).
         * If fileIds are equal, compare pageNumber (secondary sort key).
         *
         * Integer.compare() is preferred over subtraction (a - b)
         * because subtraction can overflow with large integers.
         */
        int fileCompare = Integer.compare(this.fileId, other.fileId);
        if (fileCompare != 0) {
            return fileCompare;
        }
        return Integer.compare(this.pageNumber, other.pageNumber);
    }

    /*
     * Note: We do NOT override equals() and hashCode() here.
     * Java records auto-generate correct implementations based
     * on ALL component fields (fileId, pageNumber).
     * This is exactly what we want for HashMap key correctness.
     *
     * The auto-generated toString() produces:
     *   PageId[fileId=1, pageNumber=42]
     * which is clear enough for logging/debugging.
     */
}