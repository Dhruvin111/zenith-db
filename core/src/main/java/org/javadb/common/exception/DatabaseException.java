package org.javadb.common.exception;

/**
 * ============================================================
 * DatabaseException — Root of the Engine Exception Hierarchy
 * ============================================================
 *
 * Every exception thrown by the JavaDB engine is a subclass
 * of DatabaseException. This gives callers the ability to
 * catch ALL engine errors with a single catch block when needed:
 *
 * try {
 * engine.execute(sql);
 * } catch (DatabaseException e) {
 * // handles any engine-level failure
 * }
 *
 * PostgreSQL parallel:
 * PostgreSQL's error system (elog.h / ereport.h) assigns
 * every error:
 * - A severity level (ERROR, FATAL, PANIC, WARNING, etc.)
 * - A SQLSTATE code (5-character code, e.g. "42P01")
 * - A primary message
 * - An optional detail message
 * - An optional hint message
 *
 * We mirror this structure with:
 * - ErrorCode enum (mirrors SQLSTATE codes)
 * - Severity enum (mirrors PostgreSQL severity levels)
 * - message (primary human-readable message)
 * - cause (root cause exception for chaining)
 *
 * Design decisions:
 *
 * 1. CHECKED vs UNCHECKED:
 * DatabaseException extends RuntimeException (unchecked).
 *
 * Why unchecked?
 * - Database errors are often unrecoverable at the call site
 * (e.g., disk full, page corrupted). Forcing every caller
 * to declare "throws DatabaseException" adds noise without
 * adding safety.
 * - Mirrors modern Java practice (Spring, Hibernate all use
 * unchecked exceptions for data access failures).
 * - PostgreSQL itself uses setjmp/longjmp for error handling
 * — essentially unchecked jumps.
 *
 * Exceptions to this rule:
 * - IOException from disk operations is caught at the
 * DiskManager level and wrapped in StorageException.
 *
 * 2. EXCEPTION CHAINING:
 * All constructors accept an optional Throwable cause.
 * This preserves the original root cause through layer
 * boundaries (e.g., IOException → StorageException →
 * BufferPoolException). Never lose the original stack trace.
 *
 * 3. ERROR CODES:
 * Every exception carries an ErrorCode enum value.
 * This enables programmatic error handling without
 * string parsing:
 * if (e.getErrorCode() == ErrorCode.DEADLOCK_DETECTED) {
 * // retry the transaction
 * }
 *
 * Java concepts:
 * - RuntimeException subclass : unchecked exception
 * - Nested enum (ErrorCode) : typed error codes
 * - Nested enum (Severity) : severity levels
 * - Constructor chaining : this() / super() delegation
 * - Exception chaining : initCause / cause parameter
 *
 * ============================================================
 */
public class DatabaseException extends RuntimeException {

    // =========================================================
    // ErrorCode — Mirrors PostgreSQL SQLSTATE Codes
    // =========================================================

    /**
     * Typed error codes for programmatic error handling.
     *
     * PostgreSQL parallel:
     * SQLSTATE codes defined in errcodes.txt
     * e.g., "42P01" = undefined_table
     * "40P01" = deadlock_detected
     * "53300" = too_many_connections
     *
     * We use a readable enum instead of cryptic 5-char codes,
     * but include the equivalent SQLSTATE for reference.
     */
    public enum ErrorCode {

        // ── Generic ──────────────────────────────────────────
        /** Catch-all for unclassified engine errors */
        INTERNAL_ERROR("XX000", "Internal error"),

        // ── Storage Layer (Section 04) ────────────────────────
        /** Physical file read/write failed */
        IO_ERROR("58030", "I/O error"),
        /** Attempted to read/write beyond file bounds */
        INVALID_PAGE_ACCESS("58031", "Invalid page access"),
        /** File or directory not found */
        FILE_NOT_FOUND("58032", "File not found"),
        /** Disk is full — cannot write more data */
        DISK_FULL("53100", "Disk full"),
        /** Page checksum mismatch — data corruption detected */
        PAGE_CORRUPTION("XX001", "Page corruption detected"),

        // ── Buffer Pool Layer (Section 09) ───────────────────
        /** All buffer pool frames are pinned — cannot evict */
        BUFFER_POOL_FULL("53200", "Buffer pool exhausted"),
        /** Page pin count exceeded maximum allowed */
        PIN_COUNT_EXCEEDED("53201", "Pin count limit exceeded"),
        /** Attempted to unpin a page that is not pinned */
        PAGE_NOT_PINNED("53202", "Page not pinned"),
        /** Requested page not found in buffer pool */
        PAGE_NOT_FOUND("53203", "Page not found in buffer pool"),

        // ── WAL Layer (Section 14) ────────────────────────────
        /** WAL segment write failed */
        WAL_WRITE_ERROR("XX010", "WAL write error"),
        /** WAL segment read failed during recovery */
        WAL_READ_ERROR("XX011", "WAL read error"),
        /** WAL record is malformed or truncated */
        WAL_CORRUPTION("XX012", "WAL record corrupted"),
        /** WAL buffer is full and cannot accept more records */
        WAL_BUFFER_FULL("XX013", "WAL buffer full"),

        // ── Transaction Layer (Section 15) ───────────────────
        /** Deadlock detected between two or more transactions */
        DEADLOCK_DETECTED("40P01", "Deadlock detected"),
        /** Transaction waited too long for a lock */
        LOCK_TIMEOUT("55P03", "Lock timeout"),
        /** Transaction was explicitly rolled back */
        TRANSACTION_ABORTED("25P02", "Transaction aborted"),
        /** Operation attempted outside of a transaction */
        NO_ACTIVE_TRANSACTION("25000", "No active transaction"),
        /** Transaction ID space exhausted (shouldn't happen with 64-bit) */
        TRANSACTION_ID_EXHAUSTED("53400", "Transaction ID exhausted"),

        // ── Catalog Layer (Section 13) ────────────────────────
        /** Table does not exist */
        UNDEFINED_TABLE("42P01", "Undefined table"),
        /** Column does not exist in the specified table */
        UNDEFINED_COLUMN("42703", "Undefined column"),
        /** Index does not exist */
        UNDEFINED_INDEX("42704", "Undefined index"),
        /** Table with this name already exists */
        TABLE_ALREADY_EXISTS("42P07", "Table already exists"),
        /** Column with this name already exists in the table */
        COLUMN_ALREADY_EXISTS("42701", "Column already exists"),
        /** Schema (database) does not exist */
        UNDEFINED_SCHEMA("3F000", "Undefined schema"),

        // ── Query Layer (Sections 17-20) ─────────────────────
        /** SQL syntax error */
        SYNTAX_ERROR("42601", "Syntax error"),
        /** Type mismatch in expression or assignment */
        TYPE_MISMATCH("42804", "Type mismatch"),
        /** Division by zero in expression */
        DIVISION_BY_ZERO("22012", "Division by zero"),
        /** NULL value where non-null is required */
        NOT_NULL_VIOLATION("23502", "Not null violation"),
        /** Ambiguous column reference in query */
        AMBIGUOUS_COLUMN("42702", "Ambiguous column"),
        /** Feature not yet implemented */
        FEATURE_NOT_SUPPORTED("0A000", "Feature not supported");

        // ── Enum Fields ───────────────────────────────────────

        /**
         * SQLSTATE-compatible 5-character code.
         * Useful if we ever implement a real PostgreSQL wire protocol
         * (Section 22) — clients expect SQLSTATE codes in error responses.
         */
        private final String sqlState;

        /** Human-readable description of this error code */
        private final String description;

        ErrorCode(String sqlState, String description) {
            this.sqlState = sqlState;
            this.description = description;
        }

        public String getSqlState() {
            return sqlState;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return name() + "(" + sqlState + ")";
        }
    }

    // =========================================================
    // Severity — Mirrors PostgreSQL Error Severity Levels
    // =========================================================

    /**
     * Severity levels for database errors.
     *
     * PostgreSQL parallel (elog.h):
     * DEBUG1-5 : debug messages (not errors)
     * INFO : informational
     * NOTICE : notable but not harmful
     * WARNING : something is wrong but recoverable
     * ERROR : current operation failed, transaction aborted
     * FATAL : current session must terminate
     * PANIC : entire database system must shut down
     *
     * We use a simplified 4-level hierarchy for our engine.
     */
    public enum Severity {

        /**
         * Recoverable error — current operation fails but the
         * transaction can potentially continue (or be retried).
         * Example: deadlock detected → abort and retry transaction.
         *
         * PostgreSQL equivalent: ERROR
         */
        ERROR,

        /**
         * Session-level failure — the current connection/session
         * must be terminated but other sessions are unaffected.
         * Example: authentication failure, memory exhaustion for
         * one session.
         *
         * PostgreSQL equivalent: FATAL
         */
        FATAL,

        /**
         * System-level failure — the entire database engine must
         * shut down immediately to prevent data corruption.
         * Example: WAL write failure, page corruption on system catalog.
         *
         * PostgreSQL equivalent: PANIC
         */
        PANIC,

        /**
         * Non-fatal warning — operation succeeded but something
         * noteworthy occurred. Not an exception per se, but
         * included for completeness.
         *
         * PostgreSQL equivalent: WARNING
         */
        WARNING
    }

    // =========================================================
    // Instance Fields
    // =========================================================

    /**
     * The typed error code for this exception.
     * Enables programmatic error handling without string parsing.
     */
    private final ErrorCode errorCode;

    /**
     * The severity of this error.
     * Determines whether the session or entire engine must stop.
     */
    private final Severity severity;

    /**
     * Optional detail message providing additional context.
     *
     * PostgreSQL parallel:
     * errdetail() in ereport() — the secondary message that
     * provides more specific information about the error.
     *
     * Example:
     * message: "Table not found"
     * detail: "Table 'users' does not exist in schema 'public'"
     */
    private final String detail;

    /**
     * Optional hint message suggesting how to fix the error.
     *
     * PostgreSQL parallel:
     * errhint() in ereport() — actionable suggestion for the user.
     *
     * Example:
     * hint: "Use CREATE TABLE to create the table first."
     */
    private final String hint;

    // =========================================================
    // Constructors
    // =========================================================

    /**
     * Full constructor — all fields specified.
     *
     * @param errorCode the typed error code
     * @param severity  the severity level
     * @param message   the primary error message
     * @param detail    optional detail (may be null)
     * @param hint      optional hint (may be null)
     * @param cause     the root cause exception (may be null)
     */
    public DatabaseException(
            ErrorCode errorCode,
            Severity severity,
            String message,
            String detail,
            String hint,
            Throwable cause) {
        /*
         * super(message, cause) sets both the message returned by
         * getMessage() and the cause returned by getCause().
         * This preserves the full stack trace chain.
         */
        super(message, cause);
        this.errorCode = errorCode;
        this.severity = severity;
        this.detail = detail;
        this.hint = hint;
    }

    /**
     * Common constructor — message + error code + cause.
     * Defaults severity to ERROR (most common case).
     *
     * @param errorCode the typed error code
     * @param message   the primary error message
     * @param cause     the root cause exception
     */
    public DatabaseException(
            ErrorCode errorCode,
            String message,
            Throwable cause) {
        this(errorCode, Severity.ERROR, message, null, null, cause);
    }

    /**
     * Simple constructor — message + error code, no cause.
     * Used when there is no underlying exception to chain.
     *
     * @param errorCode the typed error code
     * @param message   the primary error message
     */
    public DatabaseException(ErrorCode errorCode, String message) {
        this(errorCode, Severity.ERROR, message, null, null, null);
    }

    /**
     * Minimal constructor — uses INTERNAL_ERROR code.
     * Only for truly unexpected situations.
     *
     * @param message the primary error message
     */
    public DatabaseException(String message) {
        this(ErrorCode.INTERNAL_ERROR, Severity.ERROR, message,
                null, null, null);
    }

    /**
     * Wrapping constructor — wraps an existing exception.
     * Used to re-throw with additional context.
     *
     * @param message the primary error message
     * @param cause   the root cause exception
     */
    public DatabaseException(String message, Throwable cause) {
        this(ErrorCode.INTERNAL_ERROR, Severity.ERROR, message,
                null, null, cause);
    }

    // =========================================================
    // Getters
    // =========================================================

    /** Returns the typed error code for this exception */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** Returns the severity level */
    public Severity getSeverity() {
        return severity;
    }

    /** Returns the detail message (may be null) */
    public String getDetail() {
        return detail;
    }

    /** Returns the hint message (may be null) */
    public String getHint() {
        return hint;
    }

    /**
     * Returns the SQLSTATE code for this exception.
     * Convenience method for wire protocol error responses.
     *
     * @return 5-character SQLSTATE code string
     */
    public String getSqlState() {
        return errorCode.getSqlState();
    }

    /**
     * Returns true if this is a PANIC-level error.
     * PANIC means the entire database must shut down.
     *
     * @return true if severity is PANIC
     */
    public boolean isPanic() {
        return severity == Severity.PANIC;
    }

    /**
     * Returns true if this is a FATAL-level error.
     * FATAL means the current session must terminate.
     *
     * @return true if severity is FATAL
     */
    public boolean isFatal() {
        return severity == Severity.FATAL;
    }

    // =========================================================
    // toString — Detailed Error Display
    // =========================================================

    /**
     * Produces a structured, multi-line error message.
     *
     * Format mirrors PostgreSQL's psql error output:
     * ERROR: table "users" does not exist
     * DETAIL: No relation named "users" was found.
     * HINT: Use \dt to list available tables.
     * SQLSTATE: 42P01
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity.name())
                .append(":  ")
                .append(getMessage());

        if (detail != null && !detail.isBlank()) {
            sb.append("\nDETAIL: ").append(detail);
        }
        if (hint != null && !hint.isBlank()) {
            sb.append("\nHINT: ").append(hint);
        }

        sb.append("\nSQLSTATE: ").append(getSqlState());
        sb.append("\nERROR_CODE: ").append(errorCode.name());

        if (getCause() != null) {
            sb.append("\nCAUSE: ").append(getCause().getMessage());
        }

        return sb.toString();
    }
}