package org.javadb.common.exception;

import org.javadb.common.LSN;

/**
 * ============================================================
 * WALException — Write-Ahead Log Failures
 * ============================================================
 *
 * Thrown by the WALManager (Section 14) when log write, read,
 * or flush operations fail.
 *
 * WAL failures are among the most serious errors in the engine.
 * If WAL cannot be written, we cannot guarantee durability —
 * the database must stop accepting writes immediately.
 *
 * PostgreSQL parallel:
 * WAL-related errors in xlog.c:
 * ereport(PANIC, ...) for WAL write failures
 * ereport(FATAL, ...) for WAL read failures during recovery
 *
 * Severity notes:
 * - WAL write failure → PANIC (database must shut down)
 * - WAL read failure during recovery → FATAL (cannot recover)
 * - WAL buffer full → ERROR (operation fails, can retry)
 *
 * Java concepts:
 * - Extends DatabaseException : fits into our hierarchy
 * - Stores LSN context : identifies which log position failed
 * - Static factory methods : readable construction
 *
 * ============================================================
 */
public class WALException extends DatabaseException {

    /**
     * The LSN at which the WAL error occurred.
     * Null if the error is not LSN-specific.
     */
    private final LSN lsn;

    // =========================================================
    // Constructors
    // =========================================================

    /**
     * Full constructor.
     *
     * @param errorCode the WAL error code
     * @param severity  the severity (PANIC for write failures)
     * @param message   description of what failed
     * @param lsn       the LSN at which failure occurred (may be null)
     * @param cause     the root cause (may be null)
     */
    public WALException(
            ErrorCode errorCode,
            Severity severity,
            String message,
            LSN lsn,
            Throwable cause) {
        super(errorCode, severity, message, null, null, cause);
        this.lsn = lsn;
    }

    /**
     * Constructor with LSN context, defaults to FATAL severity.
     *
     * @param errorCode the WAL error code
     * @param message   description of what failed
     * @param lsn       the LSN at which failure occurred
     * @param cause     the root cause
     */
    public WALException(
            ErrorCode errorCode,
            String message,
            LSN lsn,
            Throwable cause) {
        this(errorCode, Severity.FATAL, message, lsn, cause);
    }

    // =========================================================
    // Static Factory Methods
    // =========================================================

    /**
     * WAL write failure — the most critical WAL error.
     * Database MUST shut down to prevent data loss.
     *
     * @param lsn   the LSN being written when failure occurred
     * @param cause the underlying IOException
     * @return a WALException with PANIC severity
     */
    public static WALException writeFailed(LSN lsn, Throwable cause) {
        return new WALException(
                ErrorCode.WAL_WRITE_ERROR,
                Severity.PANIC,
                String.format(
                        "CRITICAL: WAL write failed at LSN %s. " +
                                "Database durability cannot be guaranteed. " +
                                "Shutting down to prevent data corruption.",
                        lsn),
                lsn,
                cause);
    }

    /**
     * WAL read failure during crash recovery.
     * Cannot complete recovery without reading the full WAL.
     *
     * @param lsn   the LSN being read during recovery
     * @param cause the underlying IOException
     * @return a WALException with FATAL severity
     */
    public static WALException readFailed(LSN lsn, Throwable cause) {
        return new WALException(
                ErrorCode.WAL_READ_ERROR,
                Severity.FATAL,
                String.format(
                        "WAL read failed at LSN %s during recovery. " +
                                "Database recovery cannot complete. " +
                                "Restore from backup.",
                        lsn),
                lsn,
                cause);
    }

    /**
     * WAL record is corrupted — checksum mismatch or truncation.
     *
     * @param lsn the LSN of the corrupted record
     * @return a WALException with WAL_CORRUPTION code
     */
    public static WALException corrupted(LSN lsn) {
        return new WALException(
                ErrorCode.WAL_CORRUPTION,
                Severity.FATAL,
                String.format(
                        "WAL record at LSN %s is corrupted. " +
                                "Checksum mismatch or incomplete write detected. " +
                                "Recovery will stop at last valid LSN.",
                        lsn),
                lsn,
                null);
    }

    /**
     * WAL buffer is full — cannot accept more log records.
     * This is a recoverable error — wait for flush and retry.
     *
     * @return a WALException with ERROR severity
     */
    public static WALException bufferFull() {
        return new WALException(
                ErrorCode.WAL_BUFFER_FULL,
                Severity.ERROR,
                "WAL buffer is full. " +
                        "Waiting for background WAL writer to flush. " +
                        "Consider increasing WAL_BUFFER_SIZE in DatabaseConfig.",
                null,
                null);
    }

    // =========================================================
    // Getter
    // =========================================================

    /**
     * Returns the LSN at which the error occurred.
     * May be null for non-LSN-specific errors.
     *
     * @return the affected LSN, or null
     */
    public LSN getLsn() {
        return lsn;
    }
}