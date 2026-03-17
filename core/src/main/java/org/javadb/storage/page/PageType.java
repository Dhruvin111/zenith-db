package org.javadb.storage.page;

/**
 * ============================================================
 * PageType — Identifies the Purpose of a Page
 * ============================================================
 *
 * Every page in the database has a type stored in its header.
 * The type tells the engine how to interpret the page's content
 * — a heap page is laid out differently from a B+Tree node.
 *
 * PostgreSQL parallel:
 * PostgreSQL does NOT store page type in the page header.
 * Instead, type is implied by which relation (table/index)
 * the page belongs to — every page in a heap file is a heap
 * page, every page in a btree file is a btree page.
 *
 * We store type explicitly in the header for two reasons:
 * 1. Easier debugging — inspect any page and know its type
 * 2. Defensive programming — detect type mismatches early
 * (e.g., accidentally reading a WAL page as a heap page)
 *
 * This is similar to how PostgreSQL's pg_filedump tool
 * identifies page types for forensic analysis.
 *
 * Java concepts:
 * - enum with int code field
 * - static lookup method (fromCode)
 * - Defensive programming with unknown code handling
 *
 * ============================================================
 */
public enum PageType {

    /**
     * Uninitialized page — freshly allocated, zero-filled.
     * A page stays UNINITIALIZED until its first write.
     * Code: 0 (default value in a zero-filled page)
     */
    UNINITIALIZED(0, "Uninitialized — freshly allocated page"),

    /**
     * Heap page — stores table tuples (rows).
     * Uses slotted page layout (slot array + tuples).
     *
     * PostgreSQL parallel: heap page in heapam.c
     */
    HEAP(1, "Heap page — stores table tuples"),

    /**
     * B+Tree internal node — stores separator keys and
     * child page pointers. Does NOT store tuple data.
     *
     * PostgreSQL parallel: BT_BRANCH in nbtree/nbtree.h
     */
    BTREE_INTERNAL(2, "B+Tree internal node — keys + child pointers"),

    /**
     * B+Tree leaf node — stores index entries (key + RID pairs).
     * Leaf nodes are linked in a doubly linked list for range scans.
     *
     * PostgreSQL parallel: BT_LEAF in nbtree/nbtree.h
     */
    BTREE_LEAF(3, "B+Tree leaf node — index entries + RIDs"),

    /**
     * Free Space Map page — tracks available free space per
     * heap page. Used by HeapFile to find pages with enough
     * space for new tuple insertions.
     *
     * PostgreSQL parallel: FSM page in freespace/freespace.c
     */
    FREE_SPACE_MAP(4, "Free Space Map — tracks space per heap page"),

    /**
     * Overflow page — stores oversized tuple data that does not
     * fit in a single heap page (TOAST equivalent).
     * Not yet implemented — reserved for future use.
     *
     * PostgreSQL parallel: TOAST (The Oversized Attribute Storage)
     */
    OVERFLOW(5, "Overflow page — stores oversized tuple data"),

    /**
     * Meta page — first page of an index file.
     * Stores the root page pointer and tree height.
     * Every B+Tree file starts with exactly one meta page at page 0.
     *
     * PostgreSQL parallel: BTMetaPageData in nbtree/nbtree.h
     */
    BTREE_META(6, "B+Tree meta page — root pointer + tree height");

    // =========================================================
    // Enum Fields
    // =========================================================

    /**
     * Numeric code stored in the page header.
     * Fits in 1 byte (0-255). We use only 0-6 currently.
     */
    private final int code;

    /** Human-readable description for logging and debugging */
    private final String description;

    // =========================================================
    // Enum Constructor
    // =========================================================

    PageType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    // =========================================================
    // Methods
    // =========================================================

    /**
     * Returns the numeric code stored in the page header byte.
     *
     * @return integer code (0-255)
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the human-readable description.
     *
     * @return description string
     */
    public String getDescription() {
        return description;
    }

    /**
     * Looks up a PageType by its numeric code.
     *
     * Used when reading a page from disk — we read the code
     * from the page header and convert it back to an enum.
     *
     * @param code the numeric code read from the page header
     * @return the corresponding PageType
     * @throws IllegalArgumentException if the code is unknown
     *                                  (indicates page corruption or a version
     *                                  mismatch)
     */
    public static PageType fromCode(int code) {
        /*
         * Linear scan over enum values.
         * Only 7 values — O(7) is perfectly acceptable here.
         * A switch statement would also work but requires
         * updating in two places when adding new types.
         */
        for (PageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                String.format(
                        "Unknown PageType code: %d. " +
                                "Valid codes are 0-%d. " +
                                "Page may be corrupted or from an incompatible version.",
                        code, values().length - 1));
    }

    @Override
    public String toString() {
        return name() + "(" + code + ")";
    }
}