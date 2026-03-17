package org.javadb.common.exception;

import org.javadb.common.PageId;

/**
 * ============================================================
 * StorageException — Disk I/O and Storage Layer Failures
 * ============================================================
 *
 * Thrown by the DiskManager (Section 04) when physical file
 * operations fail. This is the lowest-level exception in the
 * engine — it wraps raw Java IOExceptions.
 *
 * PostgreSQL parallel:
 * ERRCODE_IO_ERROR (58030) — generic I/O error
 * ERRCODE_DISK_FULL (53100) — no space left on device
 * ERRCODE_DATA_CORRUPTED (XX001) — page checksum failure
 *
 * Common scenarios:
 * - FileChannel.read() returns fewer bytes than PAGE_SIZE
 * → page read incomplete
 * - FileChannel.write() throws IOException
 * → disk write failed
 * - CRC32 checksum of read page doesn't match stored checksum
 * → page corrupted on disk (hardware failure, incomplete write)
 * - File does not exist at expected path
 * → database files missing or moved
 *
 * Java concepts:
 * - Extends DatabaseException : fits into our hierarchy
 * - Stores PageId context : identifies which page failed
 * - Static factory methods : readable construction
 *
 * ============================================================
 */
public class StorageException extends DatabaseException {

    /**
     * The PageId being accessed when the error occurred.
     * Null if the error is not page-specific
     * (e.g., file open failure).
     */
    private final PageId pageId;

    // =========================================================
    // Constructors
    // =========================================================

    /**
     * Full constructor with page context.
     *
     * @param errorCode the specific storage error code
     * @param message   description of what failed
     * @param pageId    the page being accessed (may be null)
     * @param cause     the underlying IOException (may be null)
     */
    public StorageException(
            ErrorCode errorCode,
            String message,
            PageId pageId,
            Throwable cause) {
        super(errorCode, message, cause);
        this.pageId = pageId;
    }

    /**
     * Constructor without page context.
     * Used for file-level errors (open, create, delete).
     *
     * @param errorCode the specific storage error code
     * @param message   description of what failed
     * @param cause     the underlying IOException
     */
    public StorageException(
            ErrorCode errorCode,
            String message,
            Throwable cause) {
        this(errorCode, message, null, cause);
    }

    /**
     * Constructor without cause.
     * Used when the error is detected by our logic
     * (not by an underlying Java exception).
     *
     * @param errorCode the specific storage error code
     * @param message   description of what failed
     */
    public StorageException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    // =========================================================
    // Static Factory Methods — readable, self-documenting
    // =========================================================

    /**
     * Creates a StorageException for a page read failure.
     *
     * Usage:
     * throw StorageException.readFailed(pageId, ioException);
     *
     * @param pageId the page that failed to read
     * @param cause  the underlying IOException
     * @return a StorageException with IO_ERROR code
     */
    public static StorageException readFailed(PageId pageId, Throwable cause) {
        return new StorageException(
                ErrorCode.IO_ERROR,
                String.format(
                        "Failed to read page %s from disk. " +
                                "Page may be corrupted or disk may be failing.",
                        pageId),
                pageId,
                cause);
    }

    /**
     * Creates a StorageException for a page write failure.
     *
     * @param pageId the page that failed to write
     * @param cause  the underlying IOException
     * @return a StorageException with IO_ERROR code
     */
    public static StorageException writeFailed(PageId pageId, Throwable cause) {
        return new StorageException(
                ErrorCode.IO_ERROR,
                String.format(
                        "Failed to write page %s to disk. " +
                                "Check disk space and permissions.",
                        pageId),
                pageId,
                cause);
    }

    /**
     * Creates a StorageException for page checksum failure.
     *
     * This is serious — it means the page on disk is corrupted.
     * Severity should be PANIC in production (stops the database).
     * We use ERROR here for testing flexibility.
     *
     * @param pageId   the corrupted page
     * @param expected the expected CRC32 checksum
     * @param actual   the actual CRC32 checksum found
     * @return a StorageException with PAGE_CORRUPTION code
     */
    public static StorageException checksumMismatch(
            PageId pageId, int expected, int actual) {
        return new StorageException(
                ErrorCode.PAGE_CORRUPTION,
                String.format(
                        "Page checksum mismatch for page %s. " +
                                "Expected: 0x%08X, Actual: 0x%08X. " +
                                "Page may be corrupted — restore from backup.",
                        pageId, expected, actual),
                pageId,
                null);
    }

    /**
     * Creates a StorageException for a missing file.
     *
     * @param filePath the path of the missing file
     * @return a StorageException with FILE_NOT_FOUND code
     */
    public static StorageException fileNotFound(String filePath) {
        return new StorageException(
                ErrorCode.FILE_NOT_FOUND,
                String.format(
                        "Database file not found: '%s'. " +
                                "Ensure the data directory exists and the " +
                                "database was properly initialized.",
                        filePath));
    }

    /**
     * Creates a StorageException for disk full condition.
     *
     * @param filePath the file being written when disk became full
     * @param cause    the underlying IOException
     * @return a StorageException with DISK_FULL code
     */
    public static StorageException diskFull(String filePath, Throwable cause) {
        return new StorageException(
                ErrorCode.DISK_FULL,
                String.format(
                        "Disk full while writing to '%s'. " +
                                "Free up disk space and restart the operation.",
                        filePath),
                null,
                cause);
    }

    // =========================================================
    // Getter
    // =========================================================

    /**
     * Returns the PageId that was being accessed when the error
     * occurred. May be null for file-level errors.
     *
     * @return the affected PageId, or null
     */
    public PageId getPageId() {
        return pageId;
    }
}