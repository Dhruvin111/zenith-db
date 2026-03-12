package org.javadb.common;

import org.javadb.config.DatabaseConfig;

/**
 * ============================================================
 * TransactionId — Unique Transaction Identifier
 * ============================================================
 *
 * A TransactionId (XID) uniquely identifies a transaction.
 * It is a monotonically increasing 64-bit integer assigned
 * when a transaction begins.
 *
 * PostgreSQL parallel:
 *   TransactionId in transam.h:
 *     typedef uint32 TransactionId;  (32-bit in PostgreSQL!)
 *
 *   PostgreSQL uses 32-bit XIDs and handles "wraparound"
 *   (the famous XID wraparound problem) with VACUUM FREEZE.
 *   We use 64-bit to avoid this complexity during learning.
 *   At 1 million transactions/second, 64-bit gives us
 *   ~585,000 years before wraparound.
 *
 * Reserved transaction IDs (mirrors PostgreSQL):
 *   0 = InvalidTransactionId  (no transaction)
 *   1 = BootstrapTransactionId (catalog bootstrap)
 *   2 = FrozenTransactionId    (frozen tuples — always visible)
 *   3 = FirstNormalTransactionId (first real user transaction)
 *   We use 4+ for user transactions (DatabaseConfig.FIRST_TRANSACTION_ID)
 *
 * Why TransactionId matters:
 *
 *   1. MVCC VISIBILITY:
 *      Each tuple stores xmin (the XID that inserted it) and
 *      xmax (the XID that deleted it). A tuple is visible to
 *      transaction T if:
 *        xmin committed AND xmin.value <= T.value
 *        AND (xmax is invalid OR xmax > T.value OR xmax aborted)
 *
 *   2. LOCKING:
 *      The lock manager tracks which transaction holds each lock.
 *      LockRequest stores the TransactionId of the requester.
 *
 *   3. WAL RECORDS:
 *      Every WAL record is stamped with the TransactionId that
 *      generated it. During recovery, WAL records belonging to
 *      uncommitted transactions are undone.
 *
 * Design decisions:
 *   - Record type wrapping a single long.
 *   - Static factory + singleton pattern for reserved XIDs
 *     (INVALID, BOOTSTRAP, FROZEN) — created once, reused.
 *   - Thread-safe ID generation is in TransactionManager
 *     (Section 15), not here. TransactionId is just a value.
 *
 * Java concepts:
 *   - record                   : immutable value wrapper
 *   - static final instances   : pre-built sentinel values
 *   - Comparable<TransactionId>: ordering for MVCC visibility
 *
 * ============================================================
 */
public record TransactionId(long value) implements Comparable<TransactionId> {

    // =========================================================
    // Pre-built Sentinel Instances
    // =========================================================
    /*
     * These are created once at class load time.
     * Reusing these singletons is more efficient than creating
     * new instances every time INVALID or FROZEN is needed.
     *
     * PostgreSQL parallel:
     *   #define InvalidTransactionId    ((TransactionId) 0)
     *   #define BootstrapTransactionId  ((TransactionId) 1)
     *   #define FrozenTransactionId     ((TransactionId) 2)
     *   #define FirstNormalTransactionId ((TransactionId) 3)
     */

    /** Represents "no transaction" — uninitialized or system context */
    public static final TransactionId INVALID   = new TransactionId(0L);

    /**
     * Used during catalog bootstrap — the very first transactions
     * that create system tables during database initialization.
     */
    public static final TransactionId BOOTSTRAP = new TransactionId(1L);

    /**
     * "Frozen" transaction ID — tuples marked with FROZEN are
     * visible to ALL transactions regardless of snapshot.
     * Used by VACUUM to prevent XID wraparound in PostgreSQL.
     * We use it for system catalog tuples that must always be visible.
     */
    public static final TransactionId FROZEN    = new TransactionId(2L);

    /*
     * ── Compact Constructor (Validation) ─────────────────────
     */
    public TransactionId {
        if (value < 0) {
            throw new IllegalArgumentException(
                "TransactionId value must be >= 0, got: " + value
            );
        }
    }

    // =========================================================
    // Static Factory Methods
    // =========================================================

    /**
     * Creates a TransactionId from a raw long value.
     *
     * @param value the raw transaction ID value
     * @return a new TransactionId
     */
    public static TransactionId of(long value) {
        /*
         * Return pre-built sentinels for known special values.
         * This avoids allocating new objects for the most common
         * cases (INVALID is checked very frequently in MVCC).
         */
        if (value == 0L) return INVALID;
        if (value == 1L) return BOOTSTRAP;
        if (value == 2L) return FROZEN;
        return new TransactionId(value);
    }

    // =========================================================
    // Query Methods
    // =========================================================

    /**
     * Returns true if this is the invalid/null transaction ID.
     *
     * @return true if this is INVALID (value == 0)
     */
    public boolean isInvalid() {
        return value == DatabaseConfig.INVALID_TRANSACTION_ID;
    }

    /**
     * Returns true if this is a valid (assigned) transaction ID.
     */
    public boolean isValid() {
        return !isInvalid();
    }

    /**
     * Returns true if this is the FROZEN transaction ID.
     *
     * Frozen tuples are always visible — no snapshot check needed.
     * This short-circuit is a performance optimization in MVCC:
     *
     *   if (tuple.getXmin().isFrozen()) {
     *       return true; // always visible, skip full snapshot check
     *   }
     *
     * @return true if this is FROZEN (value == 2)
     */
    public boolean isFrozen() {
        return value == FROZEN.value;
    }

    /**
     * Returns true if this is a normal user transaction.
     *
     * User transactions have IDs >= FIRST_TRANSACTION_ID (4).
     * System transactions (INVALID, BOOTSTRAP, FROZEN) have
     * IDs 0, 1, 2 respectively.
     *
     * @return true if this is a user-level transaction
     */
    public boolean isUserTransaction() {
        return value >= DatabaseConfig.FIRST_TRANSACTION_ID;
    }

    /**
     * Returns true if this transaction started before the given one.
     *
     * Used in MVCC visibility checks:
     *   "Is this tuple's xmin older than my snapshot?"
     *
     * @param other the transaction to compare against
     * @return true if this transaction started before other
     */
    public boolean isOlderThan(TransactionId other) {
        return this.value < other.value;
    }

    /**
     * Returns true if this transaction started after the given one.
     *
     * @param other the transaction to compare against
     * @return true if this transaction started after other
     */
    public boolean isNewerThan(TransactionId other) {
        return this.value > other.value;
    }

    // =========================================================
    // Comparable — chronological ordering
    // =========================================================

    /**
     * Natural ordering: by transaction start order.
     * Lower value = started earlier.
     *
     * Used in:
     *   - MVCC snapshot comparison
     *   - Deadlock detection (younger transaction aborts first)
     *   - WAL record ordering during recovery
     *
     * @param other the other TransactionId to compare to
     * @return negative if older, 0 if same, positive if newer
     */
    @Override
    public int compareTo(TransactionId other) {
        return Long.compare(this.value, other.value);
    }

    // =========================================================
    // toString
    // =========================================================

    /**
     * Human-readable display.
     *
     * Examples:
     *   TransactionId(INVALID)    for value=0
     *   TransactionId(BOOTSTRAP)  for value=1
     *   TransactionId(FROZEN)     for value=2
     *   TransactionId(1042)       for a normal user transaction
     */
    @Override
    public String toString() {
        return switch ((int) Math.min(value, 3L)) {
            case 0  -> "XID(INVALID)";
            case 1  -> "XID(BOOTSTRAP)";
            case 2  -> "XID(FROZEN)";
            default -> "XID(" + value + ")";
        };
    }
}