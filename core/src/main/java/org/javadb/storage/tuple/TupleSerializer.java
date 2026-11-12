package org.javadb.storage.tuple;

import org.javadb.common.RID;
import org.javadb.common.exception.QueryException;
import org.javadb.common.exception.StorageException;
import org.javadb.config.DatabaseConfig;
import org.javadb.types.DataType;
import org.javadb.types.values.FieldValue;
import org.javadb.types.values.NullValue;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * ============================================================
 * TupleSerializer: Tuple <-> byte[]
 * ============================================================
 *
 * On-disk row format (data portion):
 *
 * [ null bitmap: ceil(N/8) bytes ][ value of each NON-NULL column,
 * in column order, no padding ]
 *
 * Fixed-length columns write exactly their fixed size.
 * VARCHAR writes [2-byte length][UTF-8 bytes].
 * NULL columns write nothing: the bitmap already says so.
 *
 * serialize() produces the byte[] that SlottedPage.insertTuple()
 * stores; deserialize() consumes what SlottedPage.getTuple()
 * returns. Because SlottedPage hands back exactly the stored
 * length, decoding can verify it consumed EVERY byte: leftover or
 * missing bytes mean the page is corrupt.
 *
 * readColumn() extracts a single column by walking the bytes and
 * skipping earlier ones, without decoding them. A scan that needs
 * one column of a wide row never pays for the others.
 *
 * Section 15 will prepend an MVCC header (xmin, xmax) in front of
 * this payload; nothing here needs to change for that.
 *
 * PostgreSQL parallel: heap_form_tuple(), heap_deform_tuple(),
 * heap_getattr()
 *
 * Java concepts: stateless utility class, ByteBuffer.wrap(),
 * absolute vs relative positioning, exception translation
 * ============================================================
 */
public final class TupleSerializer {

    private TupleSerializer() {
        throw new UnsupportedOperationException(
                "TupleSerializer is a utility class.");
    }

    // ---------------------------------------------------------
    // Serialize
    // ---------------------------------------------------------

    /** Exact number of bytes serialize() will produce. */
    public static int serializedSize(Tuple tuple) {
        int size = NullBitmap.sizeFor(tuple.size());
        for (int i = 0; i < tuple.size(); i++) {
            size += tuple.get(i).serializedSize(); // NullValue contributes 0
        }
        return size;
    }

    /**
     * Encodes a tuple into bytes ready for SlottedPage.insertTuple().
     *
     * @throws QueryException if the row is larger than one page can hold
     */
    public static byte[] serialize(Tuple tuple) {
        int size = serializedSize(tuple);
        if (size > DatabaseConfig.MAX_TUPLE_SIZE) {
            throw new QueryException(
                    QueryException.ErrorCode.FEATURE_NOT_SUPPORTED,
                    "row is too big: " + size + " bytes, maximum is " +
                            DatabaseConfig.MAX_TUPLE_SIZE +
                            ". Oversized-value storage is not supported.");
        }

        NullBitmap bitmap = new NullBitmap(tuple.size());
        for (int i = 0; i < tuple.size(); i++) {
            if (tuple.isNull(i)) {
                bitmap.setNull(i);
            }
        }

        ByteBuffer buffer = ByteBuffer.allocate(size); // big-endian by default
        bitmap.writeTo(buffer);
        for (int i = 0; i < tuple.size(); i++) {
            if (!tuple.isNull(i)) {
                tuple.get(i).writeTo(buffer);
            }
        }

        if (buffer.hasRemaining()) {
            throw new IllegalStateException(
                    "serializedSize() promised " + size + " bytes but " +
                            buffer.position() + " were written.");
        }
        return buffer.array();
    }

    // ---------------------------------------------------------
    // Deserialize
    // ---------------------------------------------------------

    /** Decodes a whole row. The result has no RID. */
    public static Tuple deserialize(TupleDescriptor descriptor, byte[] data) {
        return deserialize(descriptor, data, RID.invalid());
    }

    /**
     * Decodes a whole row and stamps it with its storage location.
     *
     * @throws StorageException PAGE_CORRUPTION if the bytes do not
     *                          match the descriptor (too short or leftover bytes)
     */
    public static Tuple deserialize(TupleDescriptor descriptor, byte[] data, RID rid) {
        int n = descriptor.getColumnCount();
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            NullBitmap bitmap = NullBitmap.read(buffer, n);

            FieldValue[] values = new FieldValue[n];
            for (int i = 0; i < n; i++) {
                values[i] = bitmap.isNull(i)
                        ? NullValue.INSTANCE
                        : FieldValue.readFrom(buffer, typeOf(descriptor, i));
            }

            if (buffer.hasRemaining()) {
                throw corrupt(buffer.remaining() + " unexpected trailing byte(s) after " +
                        n + " columns");
            }
            return Tuple.fromStored(descriptor, values, rid);

        } catch (BufferUnderflowException e) {
            throw corrupt("data ends before all " + n + " columns were read (" +
                    data.length + " bytes)");
        }
    }

    /**
     * Reads ONE column without decoding the others.
     *
     * Walks the row: for every earlier non-NULL column it jumps over
     * its bytes (fixed size, or the VARCHAR length prefix + payload).
     *
     * @param columnIndex 0-based column to extract
     * @return the value (NullValue.INSTANCE if the column is NULL)
     */
    public static FieldValue readColumn(
            TupleDescriptor descriptor, byte[] data, int columnIndex) {

        descriptor.getColumn(columnIndex); // validates the index
        int n = descriptor.getColumnCount();

        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            NullBitmap bitmap = NullBitmap.read(buffer, n);

            if (bitmap.isNull(columnIndex)) {
                return NullValue.INSTANCE;
            }
            for (int i = 0; i < columnIndex; i++) {
                if (!bitmap.isNull(i)) {
                    skipValue(buffer, typeOf(descriptor, i));
                }
            }
            return FieldValue.readFrom(buffer, typeOf(descriptor, columnIndex));

        } catch (BufferUnderflowException e) {
            throw corrupt("data ends before column " + columnIndex + " (" +
                    data.length + " bytes)");
        }
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private static DataType typeOf(TupleDescriptor descriptor, int column) {
        return descriptor.getColumn(column).type().type();
    }

    /** Advances past one stored value without decoding it. */
    private static void skipValue(ByteBuffer buffer, DataType type) {
        int bytes = type.isFixedLength()
                ? type.getFixedSize()
                : Short.toUnsignedInt(buffer.getShort()); // VARCHAR: length prefix

        if (bytes > buffer.remaining()) {
            throw new BufferUnderflowException(); // position() would throw a less helpful error
        }
        buffer.position(buffer.position() + bytes);
    }

    private static StorageException corrupt(String detail) {
        return new StorageException(
                StorageException.ErrorCode.PAGE_CORRUPTION,
                "Corrupted tuple: " + detail + ".");
    }
}