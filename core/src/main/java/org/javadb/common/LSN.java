package org.javadb.common;

import org.javadb.config.DatabaseConfig;

/**
 * ============================================================
 * LSN — Log Sequence Number
 * ============================================================
 *
 * An LSN is a monotonically increasing 64-bit integer that
 * represents a position in the Write-Ahead Log (WAL).
 *
 * Every WAL record written gets a unique LSN.
 * Every page header stores the LSN of the most recent WAL
 * record that modified it (pageLSN).
 *
 * PostgreSQL parallel:
 * XLogRecPtr in xlogdefs.h:
 * typedef uint64 XLogRecPtr;
 * PostgreSQL displays LSNs as two hex segments:
 * 0/16B374 (segment/offset within segment)
 *
 * Why LSN matters — three critical uses:
 *
 * 1. RECOVERY (ARIES algorithm):
 * During crash recovery, we compare the LSN in the WAL
 * record to the pageLSN stored in the page header.
 * If WAL_LSN > pageLSN → page needs redo (not yet persisted)
 * If WAL_LSN <= pageLSN → skip (already persisted)
 *
 * 2. BUFFER POOL (dirty page tracking):
 * When flushing a dirty page, we record the page's LSN.
 * The WAL manager must have flushed all WAL records up to
 * that LSN before the page can be written to disk.
 * (WAL-before-data guarantee = durability)
 *
 * 3. REPLICATION (future):
 * Streaming replication uses LSNs to track how far behind
 * a replica is from the primary.
 *
 * Design decisions:
 * - Implemented as a record wrapping a single long value.
 * - Long (64-bit) = can address ~18 exabytes of WAL.
 * PostgreSQL also uses 64-bit LSNs since version 9.3.
 * - Implements Comparable for LSN ordering comparisons.
 *
 * Java concepts:
 * - record : immutable value wrapper
 * - Comparable<LSN> : natural ordering (chronological)
 * - static factory methods : LSN.of(), LSN.invalid()
 * - method delegation : compareTo delegates to Long.compare
 *
 * ============================================================
 */
public record LSN(long value) implements Comparable<LSN> {

    /*
     * ── Compact Constructor (Validation) ─────────────────────
     *
     * LSN values must be non-negative.
     * LSN 0 is reserved as INVALID_LSN (DatabaseConfig.INVALID_LSN).
     * Valid LSNs start from DatabaseConfig.FIRST_LSN = 1.
     */
    public LSN {
        if (value < DatabaseConfig.INVALID_LSN) {
            throw new IllegalArgumentException(
                    "LSN value must be >= 0, got: " + value);
        }
    }

    // =========================================================
    // Static Factory Methods
    // =========================================================

    /**
     * Creates an LSN from a raw long value.
     *
     * Usage:
     * LSN lsn = LSN.of(1024L);
     *
     * @param value the raw LSN value (must be >= 0)
     * @return a new LSN
     */
    public static LSN of(long value) {
        return new LSN(value);
    }

    /**
     * Creates the invalid/null LSN sentinel (value = 0).
     *
     * A page with pageLSN = invalid() has never been modified
     * by any WAL record (freshly initialized page).
     *
     * PostgreSQL parallel:
     * InvalidXLogRecPtr = 0 in xlogdefs.h
     *
     * @return the invalid LSN sentinel
     */
    public static LSN invalid() {
        return new LSN(DatabaseConfig.INVALID_LSN);
    }

    /**
     * Creates the first valid LSN (value = 1).
     * WAL writing begins from this LSN.
     *
     * @return the first valid LSN
     */
    public static LSN first() {
        return new LSN(DatabaseConfig.FIRST_LSN);
    }

    // =========================================================
    // Query & Arithmetic Methods
    // =========================================================

    /**
     * Returns true if this LSN is the invalid sentinel (value = 0).
     *
     * @return true if invalid
     */
    public boolean isInvalid() {
        return value == DatabaseConfig.INVALID_LSN;
    }

    /**
     * Returns true if this is a valid (non-zero) LSN.
     */
    public boolean isValid() {
        return !isInvalid();
    }

    /**
     * Returns true if this LSN is strictly before the other LSN.
     *
     * Used in recovery:
     * if (walRecordLSN.isAfter(page.getPageLSN())) {
     * // this WAL record is newer than what's on the page
     * // → apply redo
     * }
     *
     * @param other the LSN to compare against
     * @return true if this LSN < other LSN
     */
    public boolean isBefore(LSN other) {
        return this.value < other.value;
    }

    /**
     * Returns true if this LSN is strictly after the other LSN.
     *
     * @param other the LSN to compare against
     * @return true if this LSN > other LSN
     */
    public boolean isAfter(LSN other) {
        return this.value > other.value;
    }

    /**
     * Returns true if this LSN is at or after the other LSN.
     *
     * Used in buffer pool flush decisions:
     * if (walFlushedLSN.isAtOrAfter(page.getPageLSN())) {
     * // WAL is durable up to at least pageLSN
     * // → safe to write this page to disk
     * }
     *
     * @param other the LSN to compare against
     * @return true if this LSN >= other LSN
     */
    public boolean isAtOrAfter(LSN other) {
        return this.value >= other.value;
    }

    /**
     * Advances this LSN by the given number of bytes.
     *
     * WAL is a sequential byte stream. When we write a WAL record
     * of N bytes, the next LSN = current LSN + N bytes.
     *
     * PostgreSQL parallel:
     * LSN arithmetic: newLSN = oldLSN + record_length
     *
     * @param bytes number of bytes to advance by
     * @return a new LSN advanced by the given number of bytes
     */
    public LSN advance(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException(
                    "Cannot advance LSN by negative bytes: " + bytes);
        }
        return new LSN(this.value + bytes);
    }

    /**
     * Returns the byte distance between this LSN and another.
     *
     * Used to calculate how much WAL needs to be replayed:
     * long bytesToReplay = currentLSN.distanceTo(targetLSN)
     *
     * @param other the target LSN
     * @return byte distance (other.value - this.value)
     */
    public long distanceTo(LSN other) {
        return other.value - this.value;
    }

    // =========================================================
    // Comparable — chronological ordering
    // =========================================================

    /**
     * Natural ordering: chronological (lower value = earlier in WAL).
     *
     * @param other the other LSN to compare to
     * @return negative if this earlier, 0 if same, positive if later
     */
    @Override
    public int compareTo(LSN other) {
        /*
         * Long.compare() handles all edge cases correctly,
         * including MIN_VALUE and MAX_VALUE.
         * Never use (this.value - other.value) — can overflow!
         */
        return Long.compare(this.value, other.value);
    }

    // =========================================================
    // toString — PostgreSQL-style display
    // =========================================================

    /**
     * Returns a PostgreSQL-style LSN string representation.
     *
     * PostgreSQL displays LSNs as: segment/offset
     * Example: 0/16B374
     *
     * We display as: LSN(decimal) for simplicity during learning.
     * Example: LSN(1024)
     */
    @Override
    public String toString() {
        if (isInvalid()) {
            return "LSN(INVALID)";
        }
        /*
         * Display in PostgreSQL-like hex format: high32/low32
         * Example: LSN(0/00000400) for value=1024
         */
        long high = value >> 32;
        long low = value & 0xFFFFFFFFL;
        return String.format("LSN(%X/%08X)", high, low);
    }
}