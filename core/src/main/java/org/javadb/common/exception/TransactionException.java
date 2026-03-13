package org.javadb.common.exception;

import org.javadb.common.TransactionId;

/**
 * ============================================================
 * TransactionException — Transaction & Concurrency Failures
 * ============================================================
 *
 * Thrown by the TransactionManager and LockManager (Section 15)
 * when transaction lifecycle or locking operations fail.
 *
 * PostgreSQL parallel:
 *   40P01  deadlock_detected
 *   55P03  lock_not_available (lock_timeout)
 *   25P02  in_failed_sql_transaction
 *   25000  invalid_transaction_state
 *
 * Important behavior:
 *   On DEADLOCK_DETECTED, the application should:
 *     1. Catch TransactionException
 *     2. Check getErrorCode() == DEADLOCK_DETECTED
 *     3. Roll back the current transaction
 *     4. Retry the entire transaction from the beginning
 *
 *   This is exactly what PostgreSQL clients do — the server
 *   detects the deadlock, aborts one victim transaction, and
 *   the client retries.
 *
 * Java concepts:
 *   - Extends DatabaseException     : fits into our hierarchy
 *   - Stores TransactionId context  : identifies which tx failed
 *   - Static factory methods        : readable construction
 *
 * ============================================================
 */
public class TransactionException extends DatabaseException {

    /**
     * The TransactionId of the transaction that failed.
     * May be null for errors not tied to a specific transaction.
     */
    private final TransactionId transactionId;

    // =========================================================
    // Constructors
    // =========================================================

    /**
     * Full constructor.
     *
     * @param errorCode     the transaction error code
     * @param message       description of what failed
     * @param transactionId the affected transaction (may be null)
     * @param cause         the root cause (may be null)
     */
    public TransactionException(
            ErrorCode     errorCode,
            String        message,
            TransactionId transactionId,
            Throwable     cause) {
        super(errorCode, message, cause);
        this.transactionId = transactionId;
    }

    /**
     * Constructor without cause.
     *
     * @param errorCode     the transaction error code
     * @param message       description of what failed
     * @param transactionId the affected transaction
     */
    public TransactionException(
            ErrorCode     errorCode,
            String        message,
            TransactionId transactionId) {
        this(errorCode, message, transactionId, null);
    }

    // =========================================================
    // Static Factory Methods
    // =========================================================

    /**
     * Deadlock detected between two transactions.
     *
     * PostgreSQL behavior: the "victim" transaction is chosen
     * (usually the one that ran shorter) and is aborted.
     * The surviving transaction continues.
     *
     * @param victim   the transaction being aborted (the victim)
     * @param blocker  the transaction that caused the deadlock
     * @return a TransactionException with DEADLOCK_DETECTED code
     */
    public static TransactionException deadlockDetected(
            TransactionId victim,
            TransactionId blocker) {
        return new TransactionException(
            ErrorCode.DEADLOCK_DETECTED,
            String.format(
                "Deadlock detected: transaction %s is waiting for " +
                "transaction %s, creating a circular wait. " +
                "Transaction %s has been chosen as the deadlock victim " +
                "and will be rolled back. Retry the transaction.",
                victim, blocker, victim
            ),
            victim
        );
    }

    /**
     * Transaction waited too long for a lock and timed out.
     *
     * @param txId        the transaction that timed out
     * @param timeoutMs   the lock timeout in milliseconds
     * @return a TransactionException with LOCK_TIMEOUT code
     */
    public static TransactionException lockTimeout(
            TransactionId txId,
            long          timeoutMs) {
        return new TransactionException(
            ErrorCode.LOCK_TIMEOUT,
            String.format(
                "Transaction %s timed out after %d ms waiting for a lock. " +
                "Another transaction is holding the required lock. " +
                "Retry or increase lock_timeout in DatabaseConfig.",
                txId, timeoutMs
            ),
            txId
        );
    }

    /**
     * Operation attempted on an already-aborted transaction.
     *
     * PostgreSQL behavior: once a transaction encounters an error,
     * all subsequent commands fail with "current transaction is
     * aborted" until the client issues ROLLBACK.
     *
     * @param txId the aborted transaction
     * @return a TransactionException with TRANSACTION_ABORTED code
     */
    public static TransactionException transactionAborted(TransactionId txId) {
        return new TransactionException(
            ErrorCode.TRANSACTION_ABORTED,
            String.format(
                "Transaction %s has been aborted. " +
                "All commands are ignored until ROLLBACK is issued. " +
                "Issue ROLLBACK to end this transaction.",
                txId
            ),
            txId
        );
    }

    /**
     * Operation requires an active transaction but none exists.
     *
     * @return a TransactionException with NO_ACTIVE_TRANSACTION code
     */
    public static TransactionException noActiveTransaction() {
        return new TransactionException(
            ErrorCode.NO_ACTIVE_TRANSACTION,
            "No active transaction. " +
            "Start a transaction with BEGIN before executing statements.",
            null
        );
    }

    // =========================================================
    // Getter
    // =========================================================

    /**
     * Returns the TransactionId of the failed transaction.
     * May be null for non-transaction-specific errors.
     *
     * @return the affected TransactionId, or null
     */
    public TransactionId getTransactionId() {
        return transactionId;
    }
}