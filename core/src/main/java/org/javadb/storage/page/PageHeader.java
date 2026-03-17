package org.javadb.storage.page;

import org.javadb.common.LSN;
import org.javadb.config.DatabaseConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ============================================================
 * PageHeader — First 20 Bytes of Every Page
 * ============================================================
 *
 * The PageHeader is the metadata prefix of every page in the
 * database. It occupies the first PAGE_HEADER_SIZE (20) bytes
 * of the 8192-byte page. All subsystems read/write the header
 * to understand the state of a page before accessing its data.
 *
 * PostgreSQL parallel:
 * PageHeaderData in src/include/storage/bufpage.h:
 *
 * typedef struct PageHeaderData {
 * PageXLogRecPtr pd_lsn; // 8 bytes: LSN of last WAL record
 * uint16 pd_checksum; // 2 bytes: page checksum
 * uint16 pd_flags; // 2 bytes: page state flags
 * LocationIndex pd_lower; // 2 bytes: free space start offset
 * LocationIndex pd_upper; // 2 bytes: free space end offset
 * LocationIndex pd_special; // 2 bytes: special space offset
 * uint16 pd_pagesize_version; // 2 bytes: size+version
 * } PageHeaderData; // Total: 20 bytes
 *
 * Our layout (exactly matching PostgreSQL's byte layout):
 * Offset 0: lsn (8 bytes) — last modifying WAL LSN
 * Offset 8: checksum (4 bytes) — CRC32 of page content
 * Offset 12: flags (2 bytes) — PageFlags bitmask
 * Offset 14: lower (2 bytes) — end of slot array
 * Offset 16: upper (2 bytes) — start of tuple data
 * Offset 18: pageVersion (2 bytes) — layout version number
 * Total: 20 bytes = DatabaseConfig.PAGE_HEADER_SIZE
 *
 * Key pointers — lower and upper:
 * lower points to the END of the slot array
 * upper points to the START of the tuple area
 * Free space = upper - lower (the gap between them)
 *
 * Initial state of a fresh page:
 * lower = PAGE_HEADER_SIZE (20) — right after the header
 * upper = PAGE_SIZE (8192) — end of page
 * Free space = 8192 - 20 = 8172 bytes
 *
 * Design decisions:
 * - PageHeader is NOT a record — it is mutable.
 * We need to update lsn, flags, lower, upper frequently.
 * - Reads/writes directly from/to a ByteBuffer at fixed offsets.
 * This mirrors how PostgreSQL directly accesses struct fields
 * via pointer arithmetic.
 * - BIG_ENDIAN byte order — matches network byte order and
 * PostgreSQL's on-disk format. Ensures pages are portable
 * across machines with different native endianness.
 *
 * Java concepts:
 * - ByteBuffer positional reads (get/put at absolute offset)
 * - ByteOrder.BIG_ENDIAN — explicit byte ordering
 * - Static offset constants — self-documenting byte layout
 * - Defensive validation in setters
 *
 * ============================================================
 */
public class PageHeader {

    // =========================================================
    // Byte Offsets — exactly where each field lives in the page
    // =========================================================

    /*
     * These constants define the byte offset of each header field
     * within the page ByteBuffer. They are the authoritative
     * definition of our on-disk page format.
     *
     * CRITICAL: If you change any offset, ALL existing database
     * files become unreadable. Increment PAGE_LAYOUT_VERSION
     * in DatabaseConfig when modifying the layout.
     */

    /** Byte offset of the LSN field (8 bytes, long) */
    public static final int OFFSET_LSN = 0;

    /** Byte offset of the checksum field (4 bytes, int) */
    public static final int OFFSET_CHECKSUM = 8;

    /** Byte offset of the flags field (2 bytes, short) */
    public static final int OFFSET_FLAGS = 12;

    /** Byte offset of the lower pointer (2 bytes, short) */
    public static final int OFFSET_LOWER = 14;

    /** Byte offset of the upper pointer (2 bytes, short) */
    public static final int OFFSET_UPPER = 16;

    /** Byte offset of the page layout version (2 bytes, short) */
    public static final int OFFSET_PAGE_VERSION = 18;

    // =========================================================
    // Total header size (must equal DatabaseConfig.PAGE_HEADER_SIZE)
    // =========================================================

    /**
     * Total size of the page header in bytes.
     * Compile-time check: this must equal DatabaseConfig.PAGE_HEADER_SIZE.
     */
    public static final int SIZE = DatabaseConfig.PAGE_HEADER_SIZE; // 20 bytes

    // =========================================================
    // The underlying ByteBuffer
    // =========================================================

    /**
     * The ByteBuffer that backs this page header.
     *
     * IMPORTANT: This is the SAME ByteBuffer as the containing Page.
     * We do NOT copy the header bytes — we read/write directly
     * at fixed offsets in the page's buffer. This means:
     * 1. No memory wasted on a separate header copy
     * 2. Changes to PageHeader are immediately visible in the Page
     * 3. The Page and its PageHeader are always in sync
     *
     * PostgreSQL parallel:
     * PostgreSQL accesses page header fields via direct pointer
     * arithmetic on the page buffer:
     * ((PageHeader) page)->pd_lsn = ...
     * We achieve the same by reading/writing at known byte offsets
     * in the shared ByteBuffer.
     */
    private final ByteBuffer buffer;

    // =========================================================
    // Constructor
    // =========================================================

    /**
     * Creates a PageHeader view over an existing page ByteBuffer.
     *
     * This does NOT copy the buffer — it stores a reference to
     * the same ByteBuffer that the Page uses. All reads and writes
     * go directly to/from the page's memory.
     *
     * The buffer's byte order is set to BIG_ENDIAN to ensure
     * consistent multi-byte field reading across platforms.
     *
     * @param pageBuffer the page's ByteBuffer (must be PAGE_SIZE bytes)
     * @throws IllegalArgumentException if buffer is too small
     */
    public PageHeader(ByteBuffer pageBuffer) {
        if (pageBuffer.capacity() < SIZE) {
            throw new IllegalArgumentException(
                    String.format(
                            "Page buffer too small for header. " +
                                    "Required: %d bytes, got: %d bytes.",
                            SIZE, pageBuffer.capacity()));
        }

        /*
         * Set BIG_ENDIAN byte order on the buffer.
         *
         * Why BIG_ENDIAN?
         * Multi-byte values (long, int, short) are stored with the
         * most significant byte first. This is called "network byte
         * order" and is used by PostgreSQL and most database formats.
         *
         * Without explicit byte order, Java defaults to BIG_ENDIAN
         * for ByteBuffer — but we set it explicitly to document
         * the intention and prevent accidental changes.
         *
         * Example: int 0x01020304 in BIG_ENDIAN:
         * byte[0]=0x01, byte[1]=0x02, byte[2]=0x03, byte[3]=0x04
         * In LITTLE_ENDIAN (x86 native):
         * byte[0]=0x04, byte[1]=0x03, byte[2]=0x02, byte[3]=0x01
         */
        pageBuffer.order(ByteOrder.BIG_ENDIAN);
        this.buffer = pageBuffer;
    }

    // =========================================================
    // Initialize a Fresh Page Header
    // =========================================================

    /**
     * Writes the initial header values for a brand-new page.
     *
     * Called by Page.initialize() when a page is first allocated.
     * Sets all fields to their "empty page" starting values:
     * - LSN = INVALID (never modified by WAL)
     * - checksum = 0 (will be computed on first write)
     * - flags = 0 (no flags set)
     * - lower = PAGE_HEADER_SIZE (slot array starts right after header)
     * - upper = PAGE_SIZE (tuple area starts at end of page)
     * - pageVersion = current layout version
     *
     * PostgreSQL parallel:
     * PageInit() in bufpage.c:
     * PageSetLSN(page, InvalidXLogRecPtr);
     * PageSetChecksumBypassMode(page);
     * ((PageHeader)page)->pd_lower = SizeOfPageHeaderData;
     * ((PageHeader)page)->pd_upper = pd_special = page_size;
     *
     * @param pageType the type of page being initialized
     */
    public void initialize(PageType pageType) {
        /*
         * Zero out the entire header region first.
         * This ensures no leftover bytes from a previously
         * used buffer affect the new page's header.
         */
        for (int i = 0; i < SIZE; i++) {
            buffer.put(i, (byte) 0);
        }

        /*
         * Set each field to its initial value.
         * We use absolute positional puts (put(index, value))
         * which do NOT change the buffer's position/limit —
         * safe to call from any context.
         */

        // LSN = INVALID (0) — page has no WAL history yet
        setLsn(LSN.invalid());

        // Checksum = 0 — computed by DiskManager on first writePage()
        setChecksum(0);

        // Flags = 0 — no flags set on a fresh empty page
        setFlags((short) 0);

        // Lower = end of header — slot array begins right after header
        setLower((short) SIZE);

        // Upper = end of page — tuples grow backward from here
        setUpper((short) DatabaseConfig.PAGE_SIZE);

        // Version = current layout version from DatabaseConfig
        setPageVersion((short) DatabaseConfig.PAGE_LAYOUT_VERSION);
    }

    // =========================================================
    // LSN — Log Sequence Number
    // =========================================================

    /**
     * Returns the LSN of the most recent WAL record that modified
     * this page (the page's pageLSN).
     *
     * The pageLSN is used by the ARIES recovery algorithm:
     * If WAL record LSN > pageLSN:
     * Page needs redo (WAL change not yet on disk)
     * If WAL record LSN <= pageLSN:
     * Skip redo (change already persisted)
     *
     * PostgreSQL parallel:
     * PageGetLSN() macro in bufpage.h:
     * return ((PageHeader)(page))->pd_lsn
     *
     * @return the page's current LSN
     */
    public LSN getLsn() {
        /*
         * Read 8 bytes (long) from offset OFFSET_LSN.
         * getLong(index) reads at absolute position without
         * moving the buffer's current position.
         */
        long lsnValue = buffer.getLong(OFFSET_LSN);
        return LSN.of(lsnValue);
    }

    /**
     * Sets the page's LSN to the given value.
     *
     * Called by the WAL manager every time it modifies this page:
     * page.getHeader().setLsn(currentWALlsn);
     *
     * IMPORTANT: The LSN must only be increased, never decreased.
     * We validate this to catch bugs where WAL replay goes backward.
     *
     * PostgreSQL parallel:
     * PageSetLSN() macro in bufpage.h:
     * ((PageHeader)(page))->pd_lsn = lsn
     *
     * @param lsn the new LSN (must be >= current LSN)
     * @throws IllegalArgumentException if lsn is less than current
     */
    public void setLsn(LSN lsn) {
        /*
         * Allow setting to INVALID (0) during initialization.
         * For all other cases, LSN must only increase.
         */
        if (lsn.isValid()) {
            LSN current = getLsn();
            if (current.isValid() && lsn.isBefore(current)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Cannot decrease page LSN. " +
                                        "Current: %s, Attempted: %s. " +
                                        "LSN must always increase.",
                                current, lsn));
            }
        }
        buffer.putLong(OFFSET_LSN, lsn.value());
    }

    // =========================================================
    // Checksum
    // =========================================================

    /**
     * Returns the stored CRC32 checksum of this page.
     * Used by DiskManager.verifyChecksum() to detect corruption.
     *
     * @return the stored checksum value
     */
    public int getChecksum() {
        return buffer.getInt(OFFSET_CHECKSUM);
    }

    /**
     * Sets the checksum field in the page header.
     * Called by DiskManager.writeChecksum() before writing to disk.
     *
     * @param checksum the CRC32 checksum to store
     */
    public void setChecksum(int checksum) {
        buffer.putInt(OFFSET_CHECKSUM, checksum);
    }

    // =========================================================
    // Flags
    // =========================================================

    /**
     * Returns the raw flags bitmask.
     * Use PageFlags.hasFlag() to test individual flags.
     *
     * @return the raw 2-byte flags value
     */
    public short getFlags() {
        return buffer.getShort(OFFSET_FLAGS);
    }

    /**
     * Sets the raw flags bitmask.
     * Use PageFlags.setFlag() / clearFlag() to modify individual flags.
     *
     * @param flags the new flags value
     */
    public void setFlags(short flags) {
        buffer.putShort(OFFSET_FLAGS, flags);
    }

    /**
     * Sets a specific flag bit without affecting other flags.
     *
     * Convenience method — equivalent to:
     * setFlags(PageFlags.setFlag(getFlags(), flag))
     *
     * @param flag the flag to set (a PageFlags constant)
     */
    public void setFlag(short flag) {
        setFlags(PageFlags.setFlag(getFlags(), flag));
    }

    /**
     * Clears a specific flag bit without affecting other flags.
     *
     * @param flag the flag to clear (a PageFlags constant)
     */
    public void clearFlag(short flag) {
        setFlags(PageFlags.clearFlag(getFlags(), flag));
    }

    /**
     * Returns true if a specific flag is set.
     *
     * @param flag the flag to check (a PageFlags constant)
     * @return true if the flag is set
     */
    public boolean hasFlag(short flag) {
        return PageFlags.hasFlag(getFlags(), flag);
    }

    // =========================================================
    // Lower Pointer — end of slot array
    // =========================================================

    /**
     * Returns the lower boundary of free space.
     * This is the byte offset just PAST the last slot in the
     * slot array — i.e., where the next slot entry would go.
     *
     * Initial value: PAGE_HEADER_SIZE (20)
     * After each insert: lower += SLOT_SIZE (4)
     *
     * PostgreSQL parallel: pd_lower in PageHeaderData
     *
     * @return the lower free space boundary offset
     */
    public short getLower() {
        return buffer.getShort(OFFSET_LOWER);
    }

    /**
     * Sets the lower boundary of free space.
     * Called by SlottedPage.insertTuple() after adding a slot entry.
     *
     * @param lower the new lower boundary (must be >= PAGE_HEADER_SIZE)
     * @throws IllegalArgumentException if lower is invalid
     */
    public void setLower(short lower) {
        /*
         * Validate: lower must be at least PAGE_HEADER_SIZE
         * (cannot move backward into the header) and must not
         * exceed upper (lower > upper means no free space).
         */
        if (lower < SIZE) {
            throw new IllegalArgumentException(
                    String.format(
                            "lower pointer (%d) cannot be less than " +
                                    "PAGE_HEADER_SIZE (%d).",
                            lower, SIZE));
        }
        if (lower > DatabaseConfig.PAGE_SIZE) {
            throw new IllegalArgumentException(
                    String.format(
                            "lower pointer (%d) cannot exceed PAGE_SIZE (%d).",
                            lower, DatabaseConfig.PAGE_SIZE));
        }
        buffer.putShort(OFFSET_LOWER, lower);
    }

    // =========================================================
    // Upper Pointer — start of tuple area
    // =========================================================

    /**
     * Returns the upper boundary of free space.
     * This is the byte offset of the first (topmost) tuple on
     * the page — i.e., where the next tuple would END.
     *
     * Initial value: PAGE_SIZE (8192)
     * After each insert: upper -= tuple_size
     *
     * PostgreSQL parallel: pd_upper in PageHeaderData
     *
     * @return the upper free space boundary offset
     */
    public short getUpper() {
        return buffer.getShort(OFFSET_UPPER);
    }

    /**
     * Sets the upper boundary of free space.
     * Called by SlottedPage.insertTuple() after placing a tuple.
     *
     * @param upper the new upper boundary
     * @throws IllegalArgumentException if upper is invalid
     */
    public void setUpper(short upper) {
        if (upper < SIZE) {
            throw new IllegalArgumentException(
                    String.format(
                            "upper pointer (%d) cannot be less than " +
                                    "PAGE_HEADER_SIZE (%d).",
                            upper, SIZE));
        }
        if (upper > DatabaseConfig.PAGE_SIZE) {
            throw new IllegalArgumentException(
                    String.format(
                            "upper pointer (%d) cannot exceed PAGE_SIZE (%d).",
                            upper, DatabaseConfig.PAGE_SIZE));
        }
        buffer.putShort(OFFSET_UPPER, upper);
    }

    // =========================================================
    // Page Version
    // =========================================================

    /**
     * Returns the page layout version number.
     *
     * Used to detect pages written by a different (incompatible)
     * version of the engine. If the version doesn't match
     * DatabaseConfig.PAGE_LAYOUT_VERSION, the page cannot be read.
     *
     * PostgreSQL parallel: pd_pagesize_version encodes both
     * page size and version in 2 bytes.
     *
     * @return the page layout version
     */
    public short getPageVersion() {
        return buffer.getShort(OFFSET_PAGE_VERSION);
    }

    /**
     * Sets the page layout version.
     * Called during page initialization with the current version.
     *
     * @param version the layout version to store
     */
    public void setPageVersion(short version) {
        buffer.putShort(OFFSET_PAGE_VERSION, version);
    }

    // =========================================================
    // Derived Properties — computed from header fields
    // =========================================================

    /**
     * Returns the amount of contiguous free space on the page.
     *
     * Free space = upper - lower
     *
     * This is the gap between the end of the slot array (lower)
     * and the start of the tuple area (upper).
     *
     * A new tuple of size N can be inserted only if:
     * getFreeSpace() >= N + SLOT_SIZE
     * (need space for both the tuple data AND a new slot entry)
     *
     * PostgreSQL parallel:
     * PageGetFreeSpace() in bufpage.c:
     * return pd_upper - pd_lower
     *
     * @return available free space in bytes
     */
    public int getFreeSpace() {
        int lower = Short.toUnsignedInt(getLower());
        int upper = Short.toUnsignedInt(getUpper());
        int free = upper - lower;
        /*
         * Free space should never be negative in a well-formed page.
         * Return 0 if somehow lower > upper (corrupted page).
         */
        return Math.max(0, free);
    }

    /**
     * Returns the number of slot entries currently in the slot array.
     *
     * Slot count = (lower - PAGE_HEADER_SIZE) / SLOT_SIZE
     *
     * Each slot entry is SLOT_SIZE (4) bytes.
     * The slot array starts right after the page header.
     *
     * PostgreSQL parallel:
     * PageGetMaxOffsetNumber() in bufpage.h:
     * return (pd_lower - SizeOfPageHeaderData) / sizeof(ItemIdData)
     *
     * @return number of slots (including any dead/reusable slots)
     */
    public int getSlotCount() {
        int lower = Short.toUnsignedInt(getLower());
        return (lower - SIZE) / DatabaseConfig.SLOT_SIZE;
    }

    /**
     * Returns true if the page has enough free space to insert
     * a tuple of the given size.
     *
     * Checks that: getFreeSpace() >= tupleSize + SLOT_SIZE
     * (must have room for both the tuple data and its slot entry)
     *
     * @param tupleSize the size of the tuple to insert (bytes)
     * @return true if insertion is possible
     */
    public boolean canFit(int tupleSize) {
        return getFreeSpace() >= tupleSize + DatabaseConfig.SLOT_SIZE;
    }

    /**
     * Returns true if this page is empty (no tuples at all).
     * An empty page has lower == PAGE_HEADER_SIZE (no slots allocated).
     *
     * @return true if the page has zero tuples
     */
    public boolean isEmpty() {
        return Short.toUnsignedInt(getLower()) == SIZE;
    }

    /**
     * Returns true if this page is full (cannot fit minimum tuple).
     * Uses DatabaseConfig.SLOT_SIZE as the minimum tuple size.
     *
     * @return true if no more tuples can be inserted
     */
    public boolean isFull() {
        return getFreeSpace() < DatabaseConfig.SLOT_SIZE * 2;
    }

    // =========================================================
    // toString — human-readable summary
    // =========================================================

    /**
     * Returns a detailed summary of the page header state.
     * Used in logging, debugging, and the pg_filedump equivalent.
     *
     * Example output:
     * PageHeader{lsn=LSN(0/00000001), checksum=0xABCD1234,
     * flags=[DIRTY], lower=28, upper=8100,
     * freeSpace=8072, slots=2, version=1}
     */
    @Override
    public String toString() {
        return String.format(
                "PageHeader{lsn=%s, checksum=0x%08X, flags=%s, " +
                        "lower=%d, upper=%d, freeSpace=%d, slots=%d, version=%d}",
                getLsn(),
                getChecksum(),
                PageFlags.describe(getFlags()),
                Short.toUnsignedInt(getLower()),
                Short.toUnsignedInt(getUpper()),
                getFreeSpace(),
                getSlotCount(),
                getPageVersion());
    }
}