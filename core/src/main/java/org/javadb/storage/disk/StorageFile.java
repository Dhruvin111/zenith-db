package org.javadb.storage.disk;

import org.javadb.config.DatabaseConfig;

/**
 * ============================================================
 * StorageFile — File Type Identifiers
 * ============================================================
 *
 * Enumerates the different types of files the DiskManager
 * manages. Each type has a distinct file extension, naming
 * convention, and purpose.
 *
 * PostgreSQL parallel:
 * PostgreSQL uses "forks" to distinguish file types for
 * the same relation (table/index):
 * MAIN_FORKNUM (0) → actual data (e.g., 16384)
 * FSM_FORKNUM (1) → free space map (e.g., 16384_fsm)
 * VISMAP_FORKNUM (2) → visibility map (e.g., 16384_vm)
 * INIT_FORKNUM (3) → unlogged init fork (e.g., 16384_init)
 *
 * WAL files are stored separately in pg_wal/ directory.
 *
 * We simplify to four file types with clear extensions
 * for readability during learning.
 *
 * Java concepts:
 * - enum with fields and methods
 * - Constructor in enum
 * - Method on enum instance
 *
 * ============================================================
 */
public enum StorageFile {

    /**
     * Heap data file — stores actual table rows (tuples).
     *
     * Naming: {tableName}.db
     * Example: users.db, orders.db
     *
     * PostgreSQL parallel: MAIN_FORKNUM (the base relation file)
     * Each page in this file is a HeapPage containing tuples.
     */
    DATA(
            DatabaseConfig.DATA_FILE_EXTENSION,
            "Heap data file — stores table tuples"),

    /**
     * Index file — stores B+Tree or Hash index nodes.
     *
     * Naming: {indexName}.idx
     * Example: users_pkey.idx, orders_user_id_idx.idx
     *
     * PostgreSQL parallel: Index relations also use MAIN_FORKNUM
     * but are stored as separate relation files.
     * Each page in this file is a BTreeNode (internal or leaf).
     */
    INDEX(
            DatabaseConfig.INDEX_FILE_EXTENSION,
            "Index file — stores B+Tree/Hash index nodes"),

    /**
     * Write-Ahead Log (WAL) segment file.
     *
     * Naming: {segmentNumber}.wal (e.g., 00000001.wal)
     * Multiple segment files are created as WAL grows.
     *
     * PostgreSQL parallel: WAL segment files in pg_wal/
     * Named as: 000000010000000000000001 (timeline+segment hex)
     * We use simpler decimal segment numbers for clarity.
     */
    WAL(
            DatabaseConfig.WAL_FILE_EXTENSION,
            "WAL segment file — stores log records for recovery"),

    /**
     * Temporary file — used for external sort / hash spill.
     *
     * Created during query execution when sort or hash operations
     * exceed their memory budget (work_mem equivalent).
     * Deleted at end of query execution.
     *
     * Naming: tmp_{queryId}_{operatorId}.tmp
     *
     * PostgreSQL parallel: Temporary files in pgsql_tmp/
     */
    TEMP(
            ".tmp",
            "Temporary file — used for sort/hash spill to disk");

    // =========================================================
    // Enum Fields
    // =========================================================

    /** File extension for this storage file type */
    private final String extension;

    /** Human-readable description */
    private final String description;

    // =========================================================
    // Enum Constructor
    // =========================================================

    StorageFile(String extension, String description) {
        this.extension = extension;
        this.description = description;
    }

    // =========================================================
    // Methods
    // =========================================================

    /**
     * Returns the file extension for this storage type.
     * Includes the leading dot (e.g., ".db", ".idx", ".wal").
     *
     * @return file extension string
     */
    public String getExtension() {
        return extension;
    }

    /**
     * Returns the description of this file type.
     *
     * @return human-readable description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Builds the full file name for a given base name.
     *
     * Usage:
     * StorageFile.DATA.fileName("users") → "users.db"
     * StorageFile.INDEX.fileName("pk_idx") → "pk_idx.idx"
     * StorageFile.WAL.fileName("00000001") → "00000001.wal"
     *
     * @param baseName the base name without extension
     * @return full file name with extension
     */
    public String fileName(String baseName) {
        return baseName + extension;
    }

    /**
     * Builds the full file path given a directory and base name.
     *
     * Usage:
     * StorageFile.DATA.filePath("./data", "users") → "./data/users.db"
     *
     * @param directory the directory path (no trailing slash needed)
     * @param baseName  the base name without extension
     * @return full file path string
     */
    public String filePath(String directory, String baseName) {
        /*
         * Use java.io.File.separator for OS-independent path building.
         * On Linux/Mac: "/"
         * On Windows: "\"
         */
        return directory
                + java.io.File.separator
                + fileName(baseName);
    }
}