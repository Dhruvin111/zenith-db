package org.javadb.storage.disk;

import org.javadb.common.PageId;
import org.javadb.common.exception.StorageException;
import org.javadb.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * ============================================================
 * DiskManager — The Engine's Only Filesystem Interface
 * ============================================================
 *
 * The DiskManager is the SOLE component that directly performs
 * filesystem operations. Every other component (buffer pool,
 * heap file manager, B+Tree, WAL) reads and writes pages
 * exclusively by calling DiskManager methods.
 *
 * This strict layering (only DiskManager touches disk) mirrors
 * PostgreSQL's architecture and gives us several benefits:
 * - Single place to add page checksums
 * - Single place to add I/O statistics and monitoring
 * - Single place to mock disk I/O in unit tests
 * - Clean separation between "how to store" and "what to store"
 *
 * PostgreSQL parallel:
 * smgr.c — Storage Manager, the abstraction layer
 * md.c — Magnetic Disk implementation of smgr
 * fd.c — File descriptor management (LRU cache of open fds)
 *
 * Key function mapping:
 * DiskManager.readPage() ↔ mdread() in md.c
 * DiskManager.writePage() ↔ mdwrite() in md.c
 * DiskManager.allocatePage()↔ mdextend() in md.c
 * DiskManager.createFile() ↔ mdcreate() in md.c
 * DiskManager.deleteFile() ↔ mdunlink() in md.c
 * DiskManager.syncFile() ↔ mdsync() in md.c
 *
 * Responsibilities:
 * 1. FILE MANAGEMENT — create, open, delete files
 * 2. PAGE I/O — read and write individual pages
 * 3. PAGE ALLOCATION — extend files to create new pages
 * 4. CHECKSUM — CRC32 verification on page read/write
 * 5. FSYNC — force writes to physical disk
 *
 * Thread safety:
 * - openFiles map is ConcurrentHashMap (safe for concurrent access)
 * - Per-file locking is handled inside FileHandle
 * - fileIdCounter uses AtomicInteger for safe ID generation
 *
 * Java concepts:
 * - ConcurrentHashMap : thread-safe file registry
 * - AtomicInteger : thread-safe ID generation
 * - ByteBuffer : page-sized memory buffers
 * - CRC32 : checksum calculation
 * - Path / Files (NIO.2) : modern file operations
 * - AutoCloseable : try-with-resources support
 *
 * ============================================================
 */
public class DiskManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DiskManager.class);

    // =========================================================
    // Fields
    // =========================================================

    /**
     * The root data directory where all database files are stored.
     *
     * PostgreSQL parallel: PGDATA directory
     */
    private final Path dataDirectory;

    /**
     * The WAL directory (may be same as dataDirectory or separate).
     *
     * PostgreSQL parallel: pg_wal/ inside PGDATA
     * Can be a symlink to a separate, faster disk in production.
     */
    private final Path walDirectory;

    /**
     * Registry of all currently open files.
     *
     * Key: file name (e.g., "users.db", "orders_idx.idx")
     * Value: FileHandle wrapping the open FileChannel
     *
     * ConcurrentHashMap: multiple threads can look up FileHandles
     * concurrently without blocking each other.
     *
     * PostgreSQL parallel:
     * VfdCache in fd.c — array of open file descriptors.
     * We use a map (name → handle) instead of an integer-indexed
     * array for clarity.
     */
    private final ConcurrentHashMap<String, FileHandle> openFiles;

    /**
     * Counter for generating unique file IDs.
     *
     * Each FileHandle gets a unique integer ID. This ID is the
     * fileId component of PageId — so every page in the system
     * has a globally unique (fileId, pageNumber) address.
     *
     * AtomicInteger: thread-safe increment without synchronization.
     */
    private final AtomicInteger fileIdCounter;

    /**
     * Whether page checksum verification is enabled.
     *
     * When true:
     * - writePage() computes CRC32 and stores it in page header
     * - readPage() recomputes CRC32 and compares to stored value
     * - Mismatch → StorageException.checksumMismatch()
     *
     * PostgreSQL parallel:
     * data_checksums option (enabled at initdb time).
     * PostgreSQL uses a specific checksum algorithm (not CRC32)
     * defined in pg_checksum_page() in checksums.c.
     *
     * We use CRC32 for simplicity and availability in Java stdlib.
     */
    private final boolean checksumsEnabled;

    // =========================================================
    // Checksum Layout in Page Header
    // =========================================================

    /*
     * Per DatabaseConfig.PageHeader layout:
     * Offset 0 : LSN (8 bytes)
     * Offset 8 : checksum (4 bytes) ← CRC32 stored here
     * Offset 12 : flags (2 bytes)
     * ... (rest of header)
     *
     * When computing checksum, we zero out the checksum field
     * itself before computing — same technique PostgreSQL uses
     * to avoid the chicken-and-egg problem of "checksum of a
     * page that includes the checksum."
     */
    private static final int CHECKSUM_OFFSET = 8; // bytes from page start

    // =========================================================
    // Constructor
    // =========================================================

    /**
     * Creates a DiskManager rooted at the given data directory.
     *
     * Creates the data and WAL directories if they don't exist.
     *
     * @param dataDirectory    path to the main data directory
     * @param walDirectory     path to the WAL directory
     * @param checksumsEnabled whether to enable CRC32 page checksums
     * @throws StorageException if directories cannot be created
     */
    public DiskManager(
            String dataDirectory,
            String walDirectory,
            boolean checksumsEnabled) {

        this.dataDirectory = Paths.get(dataDirectory);
        this.walDirectory = Paths.get(walDirectory);
        this.checksumsEnabled = checksumsEnabled;
        this.openFiles = new ConcurrentHashMap<>();
        this.fileIdCounter = new AtomicInteger(1); // start from 1 (0 = invalid)

        /*
         * Ensure data and WAL directories exist.
         * createDirectories() creates all intermediate directories too,
         * like "mkdir -p" in Unix.
         */
        initializeDirectories();

        logger.info(
                "DiskManager initialized: dataDir='{}', walDir='{}', checksums={}",
                dataDirectory, walDirectory, checksumsEnabled);
    }

    /**
     * Convenience constructor using DatabaseConfig defaults.
     *
     * @param checksumsEnabled whether to enable CRC32 page checksums
     */
    public DiskManager(boolean checksumsEnabled) {
        this(
                DatabaseConfig.DEFAULT_DATA_DIRECTORY,
                DatabaseConfig.DEFAULT_WAL_DIRECTORY,
                checksumsEnabled);
    }

    // =========================================================
    // Directory Initialization
    // =========================================================

    /**
     * Creates the data and WAL directories if they don't exist.
     *
     * PostgreSQL parallel:
     * InitFileSystem() in storage/file/fd.c checks and creates
     * PGDATA and its subdirectories during initdb.
     *
     * @throws StorageException if directory creation fails
     */
    private void initializeDirectories() {
        try {
            /*
             * Files.createDirectories() is idempotent —
             * no error if the directory already exists.
             * This is safe to call on every startup.
             */
            Files.createDirectories(dataDirectory);
            Files.createDirectories(walDirectory);

            logger.debug(
                    "Storage directories ready: data='{}', wal='{}'",
                    dataDirectory, walDirectory);

        } catch (IOException e) {
            throw new StorageException(
                    StorageException.ErrorCode.IO_ERROR,
                    String.format(
                            "Failed to create storage directories. " +
                                    "data='%s', wal='%s'. Check permissions.",
                            dataDirectory, walDirectory),
                    e);
        }
    }

    // =========================================================
    // File Management
    // =========================================================

    /**
     * Creates a new database file and registers it.
     *
     * If the file already exists, it is opened (not truncated).
     * Returns the fileId assigned to this file.
     *
     * PostgreSQL parallel:
     * mdcreate() in md.c:
     * PathNameOpenFile(path, O_RDWR | O_CREAT | O_EXCL)
     *
     * @param fileName the file name (e.g., "users.db")
     * @param fileType the type of storage file
     * @return the fileId assigned to this file
     * @throws StorageException if the file cannot be created
     */
    public int createFile(String fileName, StorageFile fileType) {
        /*
         * Check if already open — return existing fileId.
         * This handles restart scenarios where files exist from
         * a previous run.
         */
        if (openFiles.containsKey(fileName)) {
            logger.debug("File '{}' already open, returning existing handle", fileName);
            return openFiles.get(fileName).getFileId();
        }

        /*
         * Assign a new unique fileId.
         * AtomicInteger.getAndIncrement() is atomic —
         * no two files get the same ID even with concurrent calls.
         */
        int fileId = fileIdCounter.getAndIncrement();

        /*
         * Resolve full path: dataDirectory / fileName
         * WAL files go in walDirectory, all others in dataDirectory.
         */
        Path filePath = resolveFilePath(fileName, fileType);

        /*
         * Create the FileHandle — opens (or creates) the file.
         */
        FileHandle handle = new FileHandle(fileId, filePath, fileType, true);

        /*
         * Register in the open files map.
         * ConcurrentHashMap.putIfAbsent() is atomic — prevents
         * duplicate registration if two threads try simultaneously.
         */
        FileHandle existing = openFiles.putIfAbsent(fileName, handle);
        if (existing != null) {
            /*
             * Another thread created the file concurrently.
             * Close our handle and return the existing fileId.
             */
            handle.close();
            return existing.getFileId();
        }

        logger.info(
                "Created file '{}' with fileId={} (type={})",
                fileName, fileId, fileType);

        return fileId;
    }

    /**
     * Opens an existing file and registers it.
     *
     * Unlike createFile(), this fails if the file doesn't exist.
     * Used during database startup to open existing data files.
     *
     * PostgreSQL parallel:
     * mdopen() in md.c — opens an existing relation file.
     *
     * @param fileName the file name to open
     * @param fileType the type of storage file
     * @return the fileId assigned to this file
     * @throws StorageException if the file does not exist
     */
    public int openFile(String fileName, StorageFile fileType) {
        if (openFiles.containsKey(fileName)) {
            return openFiles.get(fileName).getFileId();
        }

        Path filePath = resolveFilePath(fileName, fileType);

        /*
         * Verify the file exists before attempting to open.
         * Files.exists() is a quick check before the heavier open.
         */
        if (!Files.exists(filePath)) {
            throw StorageException.fileNotFound(filePath.toString());
        }

        int fileId = fileIdCounter.getAndIncrement();
        FileHandle handle = new FileHandle(fileId, filePath, fileType, false);

        openFiles.putIfAbsent(fileName, handle);

        logger.info(
                "Opened file '{}' with fileId={} (pages={})",
                fileName, fileId, handle.getPageCount());

        return fileId;
    }

    /**
     * Closes and removes a file from the open files registry.
     *
     * The file remains on disk — this just releases the OS
     * file descriptor. Use deleteFile() to permanently remove.
     *
     * @param fileName the file to close
     */
    public void closeFile(String fileName) {
        FileHandle handle = openFiles.remove(fileName);
        if (handle != null) {
            handle.close();
            logger.debug("Closed file '{}'", fileName);
        }
    }

    /**
     * Deletes a file from disk and removes it from the registry.
     *
     * PostgreSQL parallel:
     * mdunlink() in md.c — unlinks the file from the filesystem.
     * Called during DROP TABLE, DROP INDEX.
     *
     * @param fileName the file to delete
     * @throws StorageException if deletion fails
     */
    public void deleteFile(String fileName) {
        /*
         * Close the file handle first to release the file descriptor.
         * On Windows, open files cannot be deleted — this is a no-op
         * on Linux but important for portability.
         */
        FileHandle handle = openFiles.remove(fileName);
        if (handle != null) {
            handle.close();
        }

        /*
         * Determine file path and attempt deletion.
         */
        try {
            // Try data directory first, then WAL directory
            Path dataPath = dataDirectory.resolve(fileName);
            Path walPath = walDirectory.resolve(fileName);

            if (Files.deleteIfExists(dataPath)) {
                logger.info("Deleted file '{}'", dataPath);
            } else if (Files.deleteIfExists(walPath)) {
                logger.info("Deleted file '{}'", walPath);
            } else {
                logger.warn(
                        "File '{}' not found on disk during deletion.", fileName);
            }

        } catch (IOException e) {
            throw new StorageException(
                    StorageException.ErrorCode.IO_ERROR,
                    String.format("Failed to delete file '%s'.", fileName),
                    e);
        }
    }

    // =========================================================
    // Page I/O — Core Operations
    // =========================================================

    /**
     * Reads a page from disk into a newly allocated ByteBuffer.
     *
     * Allocates a PAGE_SIZE ByteBuffer, reads the page into it,
     * optionally verifies checksum, and returns the buffer.
     *
     * The returned buffer is in read mode (position=0, limit=PAGE_SIZE).
     *
     * PostgreSQL parallel:
     * smgrread() → mdread() → FileRead() → pread()
     *
     * @param pageId   the page to read
     * @param fileName the file containing this page
     * @return ByteBuffer containing the full page data
     * @throws StorageException on I/O error or checksum mismatch
     */
    public ByteBuffer readPage(PageId pageId, String fileName) {
        FileHandle handle = getOpenHandle(fileName);

        /*
         * Allocate a direct ByteBuffer for the page.
         *
         * Direct vs Heap ByteBuffer:
         * Heap buffer : ByteBuffer.allocate(n)
         * → backed by JVM heap byte array
         * → JVM must copy data from kernel to heap on I/O
         *
         * Direct buffer : ByteBuffer.allocateDirect(n)
         * → backed by native memory (outside JVM heap)
         * → FileChannel can read directly into native memory
         * without a JVM heap copy (zero-copy I/O)
         * → not garbage collected normally (use carefully)
         *
         * For page I/O, direct buffers are more efficient.
         * PostgreSQL uses mmap() or direct pread() for similar reasons.
         *
         * We use allocateDirect() here for production-grade behavior.
         * In tests, allocate() (heap) is fine.
         */
        ByteBuffer buffer = ByteBuffer.allocateDirect(
                DatabaseConfig.PAGE_SIZE);

        // Delegate actual I/O to FileHandle
        handle.readPage(pageId, buffer);

        /*
         * Verify checksum if enabled.
         * This catches silent disk corruption (bit rot, bad sectors).
         *
         * We do this AFTER reading, not before — we need the data
         * to compute and compare the checksum.
         */
        if (checksumsEnabled) {
            verifyChecksum(pageId, buffer);
        }

        return buffer;
    }

    /**
     * Writes a page to disk from the provided ByteBuffer.
     *
     * Before writing, optionally computes and stores the CRC32
     * checksum in the page header.
     *
     * CRITICAL: Buffer's position must be 0 and limit must be
     * PAGE_SIZE before calling this method.
     *
     * PostgreSQL parallel:
     * smgrwrite() → mdwrite() → FileWrite() → pwrite()
     *
     * @param pageId   the page to write
     * @param fileName the file to write to
     * @param buffer   the page data (PAGE_SIZE bytes, position=0)
     * @throws StorageException on I/O error
     */
    public void writePage(PageId pageId, String fileName, ByteBuffer buffer) {
        FileHandle handle = getOpenHandle(fileName);

        /*
         * Compute and store checksum before writing.
         * This way, when we read the page back, the stored
         * checksum matches what we compute from the data.
         */
        if (checksumsEnabled) {
            writeChecksum(buffer);
        }

        handle.writePage(pageId, buffer);
    }

    /**
     * Allocates a new page in the given file.
     *
     * Extends the file by exactly one page (PAGE_SIZE bytes).
     * Returns the PageId of the new page.
     *
     * The new page is zero-filled. The caller must write a proper
     * page header before using this page.
     *
     * PostgreSQL parallel:
     * smgrextend() → mdextend()
     *
     * @param fileName the file to extend
     * @return the PageId of the newly allocated page
     * @throws StorageException if extension fails (disk full)
     */
    public PageId allocatePage(String fileName) {
        FileHandle handle = getOpenHandle(fileName);
        return handle.allocatePage();
    }

    /**
     * Forces all pending writes for a file to physical disk (fsync).
     *
     * Called at:
     * - Transaction commit (after WAL flush)
     * - Checkpoint (flush all dirty pages)
     * - Database shutdown
     *
     * PostgreSQL parallel:
     * smgrsync() → mdsync() → FileSync() → fsync()
     *
     * @param fileName the file to sync
     * @throws StorageException if fsync fails
     */
    public void syncFile(String fileName) {
        FileHandle handle = getOpenHandle(fileName);
        /*
         * metaData=false: only sync file data, not metadata.
         * This is equivalent to fdatasync() — faster than fsync()
         * when file size hasn't changed (most page writes don't
         * change file size — only allocatePage() does).
         */
        handle.force(false);
    }

    /**
     * Forces all open files to disk.
     *
     * Called at checkpoint — ensures all dirty pages are durable.
     *
     * PostgreSQL parallel:
     * CheckPointBuffers() → smgrsync() for all open relations
     */
    public void syncAll() {
        logger.debug("Syncing all {} open files to disk...", openFiles.size());

        openFiles.values().forEach(handle -> {
            try {
                handle.force(false);
            } catch (StorageException e) {
                /*
                 * Log but don't stop — try to sync remaining files.
                 * Re-throw after all files attempted if any failed.
                 */
                logger.error(
                        "Failed to sync file '{}': {}",
                        handle.getFilePath(), e.getMessage());
            }
        });

        logger.debug("Sync complete for all files.");
    }

    // =========================================================
    // Query Methods
    // =========================================================

    /**
     * Returns the number of pages in the given file.
     *
     * @param fileName the file to query
     * @return number of pages
     */
    public long getPageCount(String fileName) {
        FileHandle handle = openFiles.get(fileName);
        if (handle == null) {
            return 0;
        }
        return handle.getPageCount();
    }

    /**
     * Returns true if the given file is currently open.
     *
     * @param fileName the file to check
     * @return true if open
     */
    public boolean isFileOpen(String fileName) {
        return openFiles.containsKey(fileName);
    }

    /**
     * Returns true if the given file exists on disk.
     *
     * @param fileName the file to check
     * @param fileType used to determine which directory to look in
     * @return true if file exists
     */
    public boolean fileExists(String fileName, StorageFile fileType) {
        Path filePath = resolveFilePath(fileName, fileType);
        return Files.exists(filePath);
    }

    // =========================================================
    // Checksum Implementation (CRC32)
    // =========================================================

    /**
     * Computes CRC32 checksum of the page and stores it in the
     * page header at CHECKSUM_OFFSET.
     *
     * Algorithm:
     * 1. Zero out the checksum field in the buffer
     * (so the checksum is computed over data only, not itself)
     * 2. Compute CRC32 over the entire PAGE_SIZE bytes
     * 3. Store the 4-byte CRC32 at CHECKSUM_OFFSET
     *
     * PostgreSQL parallel:
     * pg_checksum_page() in checksums.c
     * PostgreSQL uses a modified FNV-1a algorithm, not CRC32.
     * We use CRC32 because it's in the Java standard library.
     *
     * @param buffer the page buffer to checksum (PAGE_SIZE bytes)
     */
    private void writeChecksum(ByteBuffer buffer) {
        /*
         * Save current position and limit — we need to restore
         * them after computing the checksum so the caller's
         * view of the buffer is unchanged.
         */
        int savedPosition = buffer.position();
        int savedLimit = buffer.limit();

        try {
            /*
             * Step 1: Zero out the checksum field before computing.
             * This ensures the checksum is computed over everything
             * EXCEPT the checksum field itself.
             */
            buffer.position(CHECKSUM_OFFSET);
            buffer.putInt(0); // zero the 4-byte checksum field

            /*
             * Step 2: Compute CRC32 over the entire page.
             * CRC32 is a standard error-detection code.
             */
            CRC32 crc32 = new CRC32();

            /*
             * Convert ByteBuffer to byte[] for CRC32 update.
             * For direct ByteBuffers we can't access the array directly.
             */
            buffer.position(0);
            buffer.limit(DatabaseConfig.PAGE_SIZE);

            byte[] pageBytes;
            if (buffer.hasArray()) {
                // Heap buffer — can access backing array directly
                pageBytes = buffer.array();
            } else {
                // Direct buffer — must copy to heap array
                pageBytes = new byte[DatabaseConfig.PAGE_SIZE];
                buffer.get(pageBytes);
            }

            crc32.update(pageBytes, 0, DatabaseConfig.PAGE_SIZE);
            int checksum = (int) crc32.getValue();

            /*
             * Step 3: Store the computed checksum in the page header.
             */
            buffer.position(CHECKSUM_OFFSET);
            buffer.putInt(checksum);

        } finally {
            /*
             * Restore buffer position and limit to what the caller
             * expects. The writePage() in FileHandle will prepare
             * the buffer for writing (position=0).
             */
            buffer.position(0);
            buffer.limit(savedLimit);
        }
    }

    /**
     * Reads and verifies the CRC32 checksum of a page.
     *
     * Algorithm:
     * 1. Read stored checksum from page header
     * 2. Zero out checksum field in buffer
     * 3. Recompute CRC32 over the zeroed buffer
     * 4. Compare — if mismatch, throw StorageException
     *
     * @param pageId the page being verified (for error reporting)
     * @param buffer the page buffer to verify (PAGE_SIZE bytes)
     * @throws StorageException if checksum doesn't match
     */
    private void verifyChecksum(PageId pageId, ByteBuffer buffer) {
        int savedPosition = buffer.position();
        int savedLimit = buffer.limit();

        try {
            /*
             * Step 1: Read the stored checksum from the header.
             */
            buffer.position(CHECKSUM_OFFSET);
            int storedChecksum = buffer.getInt();

            /*
             * Step 2: Zero out the checksum field for re-computation.
             * (Same as writeChecksum — checksum must be computed
             * over the same "zeroed checksum field" state.)
             */
            buffer.position(CHECKSUM_OFFSET);
            buffer.putInt(0);

            /*
             * Step 3: Recompute CRC32.
             */
            CRC32 crc32 = new CRC32();

            buffer.position(0);
            buffer.limit(DatabaseConfig.PAGE_SIZE);

            byte[] pageBytes;
            if (buffer.hasArray()) {
                pageBytes = buffer.array();
            } else {
                pageBytes = new byte[DatabaseConfig.PAGE_SIZE];
                buffer.get(pageBytes);
            }

            crc32.update(pageBytes, 0, DatabaseConfig.PAGE_SIZE);
            int computedChecksum = (int) crc32.getValue();

            /*
             * Step 4: Compare stored vs computed.
             * If they differ, the page is corrupted.
             */
            if (storedChecksum != computedChecksum) {
                throw StorageException.checksumMismatch(
                        pageId, storedChecksum, computedChecksum);
            }

            /*
             * Restore the stored checksum back into the buffer
             * (we zeroed it for computation — put it back so
             * the caller sees the original page state).
             */
            buffer.position(CHECKSUM_OFFSET);
            buffer.putInt(storedChecksum);

        } finally {
            // Restore buffer to read-from-start state
            buffer.position(0);
            buffer.limit(savedLimit);
        }
    }

    // =========================================================
    // Internal Helpers
    // =========================================================

    /**
     * Resolves the full filesystem path for a file name.
     *
     * WAL files go in walDirectory, all others in dataDirectory.
     *
     * @param fileName the file name
     * @param fileType determines which directory to use
     * @return the full Path
     */
    private Path resolveFilePath(String fileName, StorageFile fileType) {
        if (fileType == StorageFile.WAL) {
            return walDirectory.resolve(fileName);
        }
        return dataDirectory.resolve(fileName);
    }

    /**
     * Retrieves the open FileHandle for the given file name.
     *
     * @param fileName the file to look up
     * @return the open FileHandle
     * @throws StorageException if the file is not open
     */
    private FileHandle getOpenHandle(String fileName) {
        FileHandle handle = openFiles.get(fileName);
        if (handle == null) {
            throw StorageException.fileNotFound(
                    String.format(
                            "File '%s' is not open. Call createFile() or " +
                                    "openFile() before performing I/O.",
                            fileName));
        }
        return handle;
    }

    // =========================================================
    // Statistics (Observability)
    // =========================================================

    /**
     * Returns the total number of currently open files.
     *
     * @return open file count
     */
    public int getOpenFileCount() {
        return openFiles.size();
    }

    /**
     * Returns a summary of all open files for debugging.
     *
     * @return formatted string listing all open files
     */
    public String getOpenFileSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("DiskManager open files (")
                .append(openFiles.size())
                .append("):\n");

        openFiles.forEach((name, handle) -> sb.append("  ").append(handle).append("\n"));

        return sb.toString();
    }

    // =========================================================
    // AutoCloseable — Shutdown
    // =========================================================

    /**
     * Closes all open files and releases all OS file descriptors.
     *
     * Called during database shutdown. Ensures all FileChannels
     * are properly closed. Note: does NOT fsync — caller should
     * call syncAll() before close() for durability.
     *
     * PostgreSQL parallel:
     * smgrcloseall() in smgr.c — closes all open smgr handles.
     *
     * @throws StorageException if any file fails to close
     */
    @Override
    public void close() {
        logger.info(
                "DiskManager shutting down. Closing {} open files...",
                openFiles.size());

        /*
         * Close all open FileHandles.
         * We iterate over a copy of values() to avoid
         * ConcurrentModificationException while modifying
         * the map inside closeFile().
         */
        openFiles.keySet()
                .forEach(this::closeFile);

        logger.info("DiskManager shutdown complete.");
    }

    // =========================================================
    // toString
    // =========================================================

    @Override
    public String toString() {
        return String.format(
                "DiskManager{dataDir='%s', walDir='%s', " +
                        "openFiles=%d, checksums=%b}",
                dataDirectory, walDirectory,
                openFiles.size(), checksumsEnabled);
    }
}