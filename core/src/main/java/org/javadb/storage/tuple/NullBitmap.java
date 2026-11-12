package org.javadb.storage.tuple;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.StringJoiner;

/**
 * ============================================================
 * NullBitmap: One Bit Per Column Recording NULL or Not
 * ============================================================
 *
 * A row with N columns carries a bitmap of ceil(N / 8) bytes at
 * its start. Column i lives in byte (i / 8), bit (i % 8), counting
 * from the least significant bit:
 *
 *   10 columns -> 2 bytes
 *   byte 0: columns 7 6 5 4 3 2 1 0   (bit 0 = column 0)
 *   byte 1: unused ... 9 8            (bit 0 = column 8)
 *
 * Storage convention (same as PostgreSQL's t_bits):
 *   bit = 1  ->  the column HAS a value
 *   bit = 0  ->  the column is NULL
 * This class hides that behind isNull() / setNull(), so callers
 * never think in inverted bits.
 *
 * Padding bits (past the last column) are always 0, so the same
 * logical bitmap always produces the same bytes.
 *
 * Java concepts: bit manipulation (>>, &, |, ~, <<),
 *   defensive copying of byte[], equals/hashCode on arrays
 * ============================================================
 */
public final class NullBitmap {

    private final int columnCount;
    private final byte[] bits;

    /** Creates a bitmap where every column has a value (nothing NULL). */
    public NullBitmap(int columnCount) {
        if (columnCount < 0) {
            throw new IllegalArgumentException(
                "columnCount cannot be negative: " + columnCount);
        }
        this.columnCount = columnCount;
        this.bits = new byte[sizeFor(columnCount)];
        for (int i = 0; i < columnCount; i++) {
            bits[i >> 3] |= (byte) (1 << (i & 7));
        }
    }

    private NullBitmap(int columnCount, byte[] bits) {
        this.columnCount = columnCount;
        this.bits = bits;
    }

    /** Number of bytes a bitmap for this many columns occupies. */
    public static int sizeFor(int columnCount) {
        return (columnCount + 7) / 8;
    }

    /**
     * Reads a bitmap from the buffer's current position and
     * advances the position past it.
     *
     * @throws java.nio.BufferUnderflowException if too few bytes remain
     */
    public static NullBitmap read(ByteBuffer buffer, int columnCount) {
        byte[] bytes = new byte[sizeFor(columnCount)];
        buffer.get(bytes);
        return new NullBitmap(columnCount, bytes);
    }

    // ---------------------------------------------------------
    // Queries and updates
    // ---------------------------------------------------------

    public boolean isNull(int column) {
        check(column);
        return (bits[column >> 3] & (1 << (column & 7))) == 0;
    }

    /** Marks the column as NULL (clears its bit). */
    public void setNull(int column) {
        check(column);
        bits[column >> 3] &= (byte) ~(1 << (column & 7));
    }

    /** Marks the column as holding a value (sets its bit). */
    public void setNotNull(int column) {
        check(column);
        bits[column >> 3] |= (byte) (1 << (column & 7));
    }

    /** Number of NULL columns. */
    public int nullCount() {
        int nulls = 0;
        for (int i = 0; i < columnCount; i++) {
            if (isNull(i)) nulls++;
        }
        return nulls;
    }

    public int columnCount()  { return columnCount; }
    public int sizeInBytes()  { return bits.length; }

    // ---------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------

    public void writeTo(ByteBuffer buffer) {
        buffer.put(bits);
    }

    /** Returns a copy of the bitmap bytes. */
    public byte[] toBytes() {
        return bits.clone();
    }

    private void check(int column) {
        if (column < 0 || column >= columnCount) {
            throw new IllegalArgumentException(
                "Column " + column + " out of range (0.." + (columnCount - 1) + ").");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NullBitmap other
            && columnCount == other.columnCount
            && Arrays.equals(bits, other.bits);
    }

    @Override
    public int hashCode() {
        return 31 * columnCount + Arrays.hashCode(bits);
    }

    @Override
    public String toString() {
        StringJoiner nulls = new StringJoiner(",", "NullBitmap{nullColumns=[", "]}");
        for (int i = 0; i < columnCount; i++) {
            if (isNull(i)) nulls.add(Integer.toString(i));
        }
        return nulls.toString();
    }
}