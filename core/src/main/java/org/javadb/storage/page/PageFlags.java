package org.javadb.storage.page;

/**
 * ============================================================
 * PageFlags — Bit Flags in the Page Header
 * ============================================================
 *
 * The page header contains a 2-byte (short) flags field.
 * Each bit in this field represents a boolean property of the
 * page. Using bit flags is more space-efficient than storing
 * multiple boolean fields separately.
 *
 * PostgreSQL parallel:
 * pd_flags in PageHeaderData (bufpage.h):
 * PD_HAS_FREE_LINES 0x0001 — has free line pointers
 * PD_PAGE_FULL 0x0002 — page is full
 * PD_ALL_VISIBLE 0x0004 — all tuples visible to everyone
 * PD_VALID_FLAG_BITS 0x0007 — all valid flag bits
 *
 * We define our own flag set that mirrors these concepts.
 *
 * How bit flags work:
 * Each flag is a power of 2 (1, 2, 4, 8, 16...) so they
 * occupy separate bits and can be combined with bitwise OR:
 *
 * short flags = 0;
 * flags |= PageFlags.HAS_FREE_SLOTS; // set bit 0
 * flags |= PageFlags.ALL_VISIBLE; // set bit 2
 * // flags = 0b00000101 = 5
 *
 * Check if a flag is set with bitwise AND:
 * boolean isFull = (flags & PageFlags.PAGE_FULL) != 0;
 *
 * Java concepts:
 * - Constants class (no instantiation)
 * - Bit manipulation (|, &, ~, ^)
 * - short data type (matches 2-byte pg field)
 * - static utility methods for flag operations
 *
 * ============================================================
 */
public final class PageFlags {

    /*
     * Private constructor — prevents instantiation.
     * This is a constants + utility class.
     */
    private PageFlags() {
        throw new UnsupportedOperationException(
                "PageFlags is a constants class and cannot be instantiated.");
    }

    // =========================================================
    // Flag Constants (powers of 2 — each occupies one bit)
    // =========================================================

    /**
     * Page has at least one free slot in the slot array.
     * A "free slot" is a slot entry with offset=0 and length=0,
     * left over from a DELETE operation. New inserts can reuse
     * these slots instead of extending the slot array.
     *
     * PostgreSQL parallel: PD_HAS_FREE_LINES (0x0001)
     */
    public static final short HAS_FREE_SLOTS = 0x0001;

    /**
     * Page is full — no more tuples can be inserted.
     * Set when free space < minimum tuple size.
     * The FreeSpaceMap uses this to skip full pages quickly.
     *
     * PostgreSQL parallel: PD_PAGE_FULL (0x0002)
     */
    public static final short PAGE_FULL = 0x0002;

    /**
     * All tuples on this page are visible to all transactions.
     * Set by VACUUM after it confirms no dead tuples remain.
     * When set, the executor can skip MVCC visibility checks
     * for tuples on this page — a major performance optimization.
     *
     * PostgreSQL parallel: PD_ALL_VISIBLE (0x0004)
     */
    public static final short ALL_VISIBLE = 0x0004;

    /**
     * Page has been modified (dirty) since last checkpoint.
     * Set whenever any tuple on the page is inserted/updated/deleted.
     * Cleared when the page is written to disk during checkpoint.
     *
     * Note: The Buffer Pool also tracks dirty state in the Frame,
     * but storing it in the page header allows detection of
     * in-place modifications that bypass the Buffer Pool.
     *
     * PostgreSQL parallel: Not in pg_flags — tracked in buffer
     * descriptor. We add it here for educational clarity.
     */
    public static final short DIRTY = 0x0008;

    /**
     * Page contains at least one dead tuple (deleted but not yet
     * reclaimed by vacuum). When set, the sequential scan must
     * check tuple visibility — it cannot skip MVCC checks.
     *
     * PostgreSQL parallel: Implied by absence of PD_ALL_VISIBLE
     */
    public static final short HAS_DEAD_TUPLES = 0x0010;

    /**
     * Page is a temporary page — created for the duration of
     * a query (e.g., sort spill, hash build). Will be deleted
     * when the query or transaction ends.
     *
     * Temporary pages do NOT need WAL logging — they are
     * not durable across crashes by design.
     */
    public static final short TEMPORARY = 0x0020;

    // =========================================================
    // Static Utility Methods — flag manipulation helpers
    // =========================================================

    /**
     * Sets a specific flag in a flags value.
     *
     * Usage:
     * short flags = PageFlags.setFlag(flags, PageFlags.PAGE_FULL);
     *
     * @param flags the current flags value
     * @param flag  the flag to set (one of the constants above)
     * @return the new flags value with the flag set
     */
    public static short setFlag(short flags, short flag) {
        /*
         * Bitwise OR sets the bit without affecting other bits.
         * Cast back to short because Java promotes to int in bitwise ops.
         */
        return (short) (flags | flag);
    }

    /**
     * Clears (unsets) a specific flag in a flags value.
     *
     * Usage:
     * short flags = PageFlags.clearFlag(flags, PageFlags.DIRTY);
     *
     * @param flags the current flags value
     * @param flag  the flag to clear
     * @return the new flags value with the flag cleared
     */
    public static short clearFlag(short flags, short flag) {
        /*
         * Bitwise AND with NOT of the flag clears the bit.
         * ~flag inverts all bits: e.g., ~0x0002 = 0xFFFD
         * flags & 0xFFFD = clear bit 1, keep all others.
         */
        return (short) (flags & ~flag);
    }

    /**
     * Checks if a specific flag is set.
     *
     * Usage:
     * if (PageFlags.hasFlag(flags, PageFlags.ALL_VISIBLE)) {
     * // skip MVCC check — all tuples are visible
     * }
     *
     * @param flags the current flags value
     * @param flag  the flag to check
     * @return true if the flag is set, false otherwise
     */
    public static boolean hasFlag(short flags, short flag) {
        /*
         * Bitwise AND returns non-zero only if the bit is set.
         * The (flags & flag) result is int (Java promotion),
         * so we compare to 0 not to flag.
         */
        return (flags & flag) != 0;
    }

    /**
     * Toggles a specific flag (set if clear, clear if set).
     *
     * @param flags the current flags value
     * @param flag  the flag to toggle
     * @return the new flags value with the flag toggled
     */
    public static short toggleFlag(short flags, short flag) {
        /*
         * Bitwise XOR toggles the bit:
         * 0 XOR 1 = 1 (set)
         * 1 XOR 1 = 0 (clear)
         */
        return (short) (flags ^ flag);
    }

    /**
     * Returns a human-readable description of which flags are set.
     * Useful for debugging and logging.
     *
     * Example output: "[HAS_FREE_SLOTS, ALL_VISIBLE]"
     *
     * @param flags the flags value to describe
     * @return formatted string listing all set flags
     */
    public static String describe(short flags) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;

        /*
         * Check each flag and append its name if set.
         * Manual check is clearer than reflection for 6 flags.
         */
        if (hasFlag(flags, HAS_FREE_SLOTS)) {
            sb.append("HAS_FREE_SLOTS");
            first = false;
        }
        if (hasFlag(flags, PAGE_FULL)) {
            if (!first)
                sb.append(", ");
            sb.append("PAGE_FULL");
            first = false;
        }
        if (hasFlag(flags, ALL_VISIBLE)) {
            if (!first)
                sb.append(", ");
            sb.append("ALL_VISIBLE");
            first = false;
        }
        if (hasFlag(flags, DIRTY)) {
            if (!first)
                sb.append(", ");
            sb.append("DIRTY");
            first = false;
        }
        if (hasFlag(flags, HAS_DEAD_TUPLES)) {
            if (!first)
                sb.append(", ");
            sb.append("HAS_DEAD_TUPLES");
            first = false;
        }
        if (hasFlag(flags, TEMPORARY)) {
            if (!first)
                sb.append(", ");
            sb.append("TEMPORARY");
        }

        sb.append("]");
        return sb.toString();
    }
}