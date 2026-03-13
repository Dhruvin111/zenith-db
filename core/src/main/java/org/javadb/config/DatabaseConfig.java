package org.javadb.config;

/**
 * ============================================================
 * DatabaseConfig — Engine-Wide Constants & Configuration
 * ============================================================
 *
 * This class is the SINGLE SOURCE OF TRUTH for all low-level
 * database engine constants. Every subsystem (disk manager,
 * buffer pool, WAL, transactions) reads from here.
 *
 * PostgreSQL parallel:
 * In PostgreSQL, these constants live in:
 * - pg_config_manual.h (compile-time constants)
 * - postgresql.conf (runtime tunable parameters)
 * We combine both into one class for clarity.
 *
 * Design decisions:
 * - All fields are public static final — constants, never
 * mutated at runtime. This is intentional: a production
 * system would load these from a config file at startup,
 * but for learning purposes, hardcoded constants make
 * the dependencies between layers crystal clear.
 *
 * - No instantiation allowed (private constructor) —
 * this is a utility/constants class, not a stateful object.
 *
 * Java concepts used:
 * - static final constants (compile-time inlining by JIT)
 * - private constructor (prevents instantiation)
 * - Nested grouping via comments (logical sections)
 *
 * ============================================================
 */
public final class DatabaseConfig {

    /*
     * Private constructor — prevents anyone from doing:
     * new DatabaseConfig()
     * This class is purely a namespace for constants.
     */
    private DatabaseConfig() {
        throw new UnsupportedOperationException(
                "DatabaseConfig is a constants class and cannot be instantiated.");
    }

    // =========================================================
    // SECTION 1: PAGE CONFIGURATION
    // =========================================================
    // A "page" is the fundamental unit of storage in our engine.
    // Everything — table rows, index nodes, WAL records — lives
    // inside pages. This mirrors PostgreSQL exactly.
    //
    // PostgreSQL parallel:
    // BLCKSZ in pg_config_manual.h = 8192 (default)
    // PostgreSQL compiles with a fixed page size. We define
    // ours as a runtime constant for flexibility.
    // =========================================================

    /**
     * Page size in bytes — 8KB, identical to PostgreSQL's default.
     *
     * Why 8KB?
     * - Matches most OS virtual memory page sizes (multiples of 4KB)
     * - Large enough to hold many tuples, small enough for fine-grained
     * locking (lock at page level, not file level)
     * - PostgreSQL benchmarks show 8KB is optimal for OLTP workloads
     *
     * Impact:
     * Every ByteBuffer we allocate for a page is exactly this size.
     * Every read/write to disk transfers exactly this many bytes.
     */
    public static final int PAGE_SIZE = 8192; // 8KB — same as PostgreSQL

    /**
     * Size of the page header in bytes.
     *
     * Our page header layout (mirrors PostgreSQL's PageHeaderData):
     * Offset 0 : LSN (8 bytes) — last WAL record for this page
     * Offset 8 : checksum (2 bytes) — page integrity check
     * Offset 10 : flags (2 bytes) — page state flags
     * Offset 12 : lower (2 bytes) — start of free space
     * Offset 14 : upper (2 bytes) — end of free space
     * Offset 16 : special (2 bytes) — special space offset
     * Offset 18 : pageVersion (2 bytes) — layout version
     * Total: 20 bytes
     *
     * PostgreSQL parallel:
     * sizeof(PageHeaderData) in bufpage.h = 24 bytes
     * We use 20 bytes for simplicity.
     */
    public static final int PAGE_HEADER_SIZE = 20;

    /**
     * Usable data space per page after subtracting the header.
     *
     * This is the maximum amount of tuple data + slot array that
     * can fit on a single page.
     */
    public static final int PAGE_DATA_SIZE = PAGE_SIZE - PAGE_HEADER_SIZE;

    /**
     * Size of each slot entry in the slotted page array (bytes).
     *
     * Each slot = 2 bytes offset + 2 bytes length = 4 bytes total.
     * The slot array grows from the start of the data area downward.
     * Tuples grow from the end of the page upward.
     * Free space is the gap between them.
     *
     * PostgreSQL parallel:
     * ItemIdData in itemid.h = 4 bytes (lp_off:15, lp_flags:2, lp_len:15)
     * We simplify to plain (short offset, short length) for clarity.
     */
    public static final int SLOT_SIZE = 4; // 2 bytes offset + 2 bytes length

    /**
     * Invalid/null page number sentinel value.
     *
     * Used to represent "no page" — e.g., a B+Tree leaf with no
     * right sibling has nextPageId = INVALID_PAGE_ID.
     *
     * PostgreSQL parallel:
     * InvalidBlockNumber = (BlockNumber) 0xFFFFFFFF in block.h
     */
    public static final int INVALID_PAGE_ID = -1;

    /**
     * Invalid file ID sentinel value.
     * Used in PageId to represent an uninitialized or null page reference.
     */
    public static final int INVALID_FILE_ID = -1;

    // =========================================================
    // SECTION 2: BUFFER POOL CONFIGURATION
    // =========================================================
    // The buffer pool is an in-memory cache of pages.
    // It sits between all upper layers and the disk manager.
    // Nothing reads from or writes to disk directly — everything
    // goes through the buffer pool.
    //
    // PostgreSQL parallel:
    // shared_buffers in postgresql.conf
    // Default: 128MB. We use a smaller default for dev/learning.
    // =========================================================

    /**
     * Number of pages the buffer pool can hold simultaneously.
     *
     * Memory consumption = BUFFER_POOL_SIZE * PAGE_SIZE
     * = 1024 * 8192 = 8MB
     *
     * PostgreSQL default (shared_buffers):
     * 128MB = 16,384 pages of 8KB each
     * Production systems typically set this to 25% of RAM.
     *
     * We use 1024 pages (8MB) — sufficient for learning/testing.
     * Increase this for larger datasets.
     */
    public static final int BUFFER_POOL_SIZE = 1024; // pages

    /**
     * Number of pages in the buffer pool reserved for internal
     * operations (catalog access, index splits, WAL flushing).
     *
     * These pages are never evicted while a system operation is
     * in progress. Mirrors PostgreSQL's NBuffers reservation.
     */
    public static final int BUFFER_POOL_RESERVED_PAGES = 64;

    /**
     * Maximum number of times a page can be "pinned" simultaneously.
     *
     * Pinning prevents a page from being evicted. If too many
     * threads pin too many pages, the buffer pool deadlocks.
     * This limit acts as a safety valve.
     */
    public static final int MAX_PIN_COUNT = 1000;

    // =========================================================
    // SECTION 3: WAL (WRITE-AHEAD LOG) CONFIGURATION
    // =========================================================
    // WAL is the durability mechanism. EVERY modification to a
    // page must be logged to WAL BEFORE the page is written to
    // disk. On crash recovery, WAL is replayed to restore state.
    //
    // PostgreSQL parallel:
    // WAL settings in postgresql.conf:
    // wal_segment_size = 16MB
    // wal_buffers = 64KB
    // checkpoint_timeout = 5min
    // =========================================================

    /**
     * Size of a single WAL segment file (bytes).
     *
     * WAL is written to a series of fixed-size segment files:
     * 000000010000000000000001
     * 000000010000000000000002
     * ...
     *
     * PostgreSQL default: 16MB per segment.
     * We use 4MB for easier debugging/inspection.
     */
    public static final int WAL_SEGMENT_SIZE = 4 * 1024 * 1024; // 4MB

    /**
     * Size of the in-memory WAL write buffer (bytes).
     *
     * WAL records are first written here, then flushed to disk
     * in batches (on commit, or when buffer is full).
     * Larger buffer = fewer disk writes = better throughput.
     * Smaller buffer = more frequent flushes = better durability.
     *
     * PostgreSQL default: max(64KB, 1/32 of shared_buffers)
     */
    public static final int WAL_BUFFER_SIZE = 64 * 1024; // 64KB

    /**
     * Interval between automatic checkpoints (milliseconds).
     *
     * At each checkpoint, all dirty buffer pool pages are flushed
     * to disk and a checkpoint WAL record is written. This limits
     * how much WAL needs to be replayed during crash recovery.
     *
     * PostgreSQL default: checkpoint_timeout = 5 minutes
     * We use 30 seconds for faster recovery during testing.
     */
    public static final long CHECKPOINT_INTERVAL_MS = 30_000; // 30 seconds

    /**
     * Invalid Log Sequence Number sentinel value.
     *
     * LSN = 0 means "this page has never been modified" or
     * "this WAL record has no predecessor."
     *
     * PostgreSQL parallel:
     * InvalidXLogRecPtr = 0 in xlogdefs.h
     */
    public static final long INVALID_LSN = 0L;

    /**
     * The first valid LSN. WAL starts writing from this position.
     * LSN 0 is reserved as INVALID, so real WAL starts at 1.
     */
    public static final long FIRST_LSN = 1L;

    // =========================================================
    // SECTION 4: TRANSACTION CONFIGURATION
    // =========================================================
    // Transactions are the unit of atomicity and isolation.
    // Every read and write happens within a transaction context.
    //
    // PostgreSQL parallel:
    // max_connections = 100 (default)
    // deadlock_timeout = 1s
    // lock_timeout = 0 (disabled by default)
    // =========================================================

    /**
     * Invalid transaction ID sentinel value.
     *
     * TransactionId = 0 means "no transaction" or "system operation."
     * Real transactions start from FIRST_TRANSACTION_ID = 1.
     *
     * PostgreSQL parallel:
     * InvalidTransactionId = 0 in transam.h
     */
    public static final long INVALID_TRANSACTION_ID = 0L;

    /**
     * First valid user transaction ID.
     * IDs 1-3 are reserved for system bootstrap transactions
     * (mirrors PostgreSQL's BootstrapTransactionId = 1,
     * FrozenTransactionId = 2, FirstNormalTransactionId = 3).
     */
    public static final long FIRST_TRANSACTION_ID = 4L;

    /**
     * Deadlock detection interval (milliseconds).
     *
     * When a transaction waits for a lock, the deadlock detector
     * runs after this timeout to check for circular wait cycles.
     *
     * PostgreSQL default: deadlock_timeout = 1000ms
     */
    public static final long DEADLOCK_TIMEOUT_MS = 1000; // 1 second

    /**
     * Maximum time a transaction will wait for a lock (milliseconds).
     * 0 means wait indefinitely (PostgreSQL's default behavior).
     *
     * PostgreSQL parallel:
     * lock_timeout = 0 in postgresql.conf
     */
    public static final long LOCK_TIMEOUT_MS = 0; // 0 = wait forever

    /**
     * Maximum number of concurrent transactions.
     *
     * PostgreSQL default: max_connections = 100
     * We set this to match our server config.
     */
    public static final int MAX_TRANSACTIONS = 100;

    // =========================================================
    // SECTION 5: STORAGE FILE CONFIGURATION
    // =========================================================
    // On disk, each table/index is stored as one or more files.
    // Files are divided into fixed-size segments to avoid
    // hitting OS file size limits.
    //
    // PostgreSQL parallel:
    // Each relation (table/index) gets a file in pg_data/base/
    // Files are split at 1GB by default (segment_size)
    // =========================================================

    /**
     * Maximum size of a single data file segment (bytes).
     *
     * When a table's data file exceeds this size, a new segment
     * file is created (e.g., table.0, table.1, table.2...).
     * This avoids 32-bit file size limitations on some OS.
     *
     * PostgreSQL default: 1GB
     * We use 64MB for easier testing/inspection.
     */
    public static final long MAX_FILE_SEGMENT_SIZE = 64 * 1024 * 1024L; // 64MB

    /**
     * Pages per file segment.
     * Derived from MAX_FILE_SEGMENT_SIZE / PAGE_SIZE.
     * = 64MB / 8KB = 8192 pages per segment file.
     */
    public static final int PAGES_PER_SEGMENT = (int) (MAX_FILE_SEGMENT_SIZE / PAGE_SIZE); // 8192 pages

    /**
     * Data directory where all database files are stored.
     *
     * PostgreSQL parallel:
     * PGDATA environment variable, typically /var/lib/postgresql/data/
     *
     * Our default: ./data/ relative to the working directory.
     * This can be overridden via ServerConfig.
     */
    public static final String DEFAULT_DATA_DIRECTORY = "." + java.io.File.separator + "data";

    /**
     * WAL log directory — separate from data files for performance.
     *
     * PostgreSQL parallel:
     * pg_wal/ directory inside PGDATA
     * Can be symlinked to a separate, faster disk.
     */
    public static final String DEFAULT_WAL_DIRECTORY = "." + java.io.File.separator + "data"
            + java.io.File.separator + "wal";

    /**
     * File extension for heap (table) data files.
     * PostgreSQL uses no extension for main forks, but we
     * add extensions for clarity during learning.
     */
    public static final String DATA_FILE_EXTENSION = ".db";

    /**
     * File extension for index files.
     */
    public static final String INDEX_FILE_EXTENSION = ".idx";

    /**
     * File extension for WAL segment files.
     */
    public static final String WAL_FILE_EXTENSION = ".wal";

    // =========================================================
    // SECTION 6: CATALOG CONFIGURATION
    // =========================================================
    // The catalog stores metadata about all tables, columns,
    // indexes. It is itself stored as heap files (tables).
    //
    // PostgreSQL parallel:
    // System catalog tables in pg_catalog schema:
    // pg_class, pg_attribute, pg_index, pg_type, etc.
    // =========================================================

    /**
     * Reserved table OID range for system catalog tables.
     * User-created tables get OIDs starting from USER_TABLE_OID_START.
     *
     * PostgreSQL parallel:
     * System catalog OIDs are hardcoded < 16384 in pg_class.h
     */
    public static final int SYSTEM_TABLE_OID_START = 1;
    public static final int USER_TABLE_OID_START = 16384;

    /**
     * Maximum length of identifiers (table names, column names, etc.)
     *
     * PostgreSQL parallel:
     * NAMEDATALEN = 64 in pg_config_manual.h
     * Maximum identifier length = NAMEDATALEN - 1 = 63 chars
     */
    public static final int MAX_IDENTIFIER_LENGTH = 63;

    /**
     * Maximum number of columns per table.
     *
     * PostgreSQL parallel:
     * MaxHeapAttributeNumber = 1600 in htup_details.h
     * We use a smaller limit for simplicity.
     */
    public static final int MAX_COLUMNS_PER_TABLE = 1600;

    // =========================================================
    // SECTION 7: TUPLE / ROW CONFIGURATION
    // =========================================================

    /**
     * Maximum size of a single tuple (row) in bytes.
     *
     * A tuple must fit within a single page (minus header overhead).
     * For larger values (e.g., long VARCHAR), PostgreSQL uses TOAST
     * (The Oversized-Attribute Storage Technique). We don't implement
     * TOAST, so we enforce this hard limit.
     *
     * PostgreSQL parallel:
     * MaxHeapTupleSize ≈ 8160 bytes (page size - page header - item id)
     */
    public static final int MAX_TUPLE_SIZE = PAGE_SIZE - PAGE_HEADER_SIZE - SLOT_SIZE;

    /**
     * Maximum length of a VARCHAR field (bytes).
     *
     * PostgreSQL allows up to 1GB for text fields (with TOAST).
     * We cap at 65535 bytes (64KB) for simplicity.
     */
    public static final int MAX_VARCHAR_LENGTH = 65535;

    /**
     * Tuple alignment boundary (bytes).
     *
     * Tuples are aligned to 8-byte boundaries to ensure efficient
     * CPU word-aligned access on 64-bit systems. Matches PostgreSQL's
     * MAXALIGN = 8 on 64-bit platforms.
     */
    public static final int TUPLE_ALIGNMENT = 8;

    // =========================================================
    // SECTION 8: B+ TREE INDEX CONFIGURATION
    // =========================================================

    /**
     * Minimum fill factor for B+ Tree pages (percentage).
     *
     * When a B+ Tree page is split, each resulting page will be
     * filled to at least this percentage. Lower fill factor =
     * more space for future insertions = fewer splits = better
     * insert performance. Higher fill factor = denser tree = better
     * read performance.
     *
     * PostgreSQL default: fillfactor = 90 for btree indexes
     */
    public static final int BTREE_MIN_FILL_FACTOR = 50; // 50%

    /**
     * Maximum order of the B+ Tree.
     * Order = max number of children per internal node.
     * Derived from page size and key size.
     *
     * For our purposes we define a default max order.
     * Actual order is calculated per-index based on key type size.
     */
    public static final int BTREE_DEFAULT_ORDER = 128;

    // =========================================================
    // SECTION 9: QUERY EXECUTION CONFIGURATION
    // =========================================================

    /**
     * Default batch size for hash join and hash aggregate operators.
     * Tuples are processed in batches of this size to improve
     * cache locality during execution.
     */
    public static final int HASH_JOIN_BATCH_SIZE = 256;

    /**
     * Maximum memory a single sort operator can use (bytes).
     * If sort data exceeds this, we spill to disk (external sort).
     *
     * PostgreSQL parallel:
     * work_mem = 4MB (default) per sort/hash operation
     */
    public static final long SORT_MAX_MEMORY_BYTES = 4 * 1024 * 1024L; // 4MB

    /**
     * Default fetch size for cursor-based result iteration.
     * After every CURSOR_FETCH_SIZE rows, the client is expected
     * to call fetch again. Matches PostgreSQL's default_fetch_count.
     */
    public static final int CURSOR_FETCH_SIZE = 100;

    // =========================================================
    // SECTION 10: VERSION & METADATA
    // =========================================================

    /**
     * Database engine version string.
     * Follows semantic versioning: MAJOR.MINOR.PATCH
     */
    public static final String ENGINE_VERSION = "0.1.0";

    /**
     * Page layout version.
     * Increment this if the page format changes in a way that
     * breaks backward compatibility (like PostgreSQL's pg_upgrade).
     */
    public static final int PAGE_LAYOUT_VERSION = 1;

    /**
     * Catalog version. Increment when system catalog schema changes.
     *
     * PostgreSQL parallel:
     * CATALOG_VERSION_NO in catversion.h
     */
    public static final int CATALOG_VERSION = 1;
}