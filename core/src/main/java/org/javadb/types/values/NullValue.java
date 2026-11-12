package org.javadb.types.values;

import org.javadb.types.DataType;

import java.nio.ByteBuffer;

/**
 * The SQL NULL value.
 *
 * Writes ZERO bytes: whether a column is NULL is recorded in the
 * tuple's null bitmap (Section 11), so no data bytes are needed.
 * Use the shared INSTANCE; all NullValues are equal anyway.
 */
public record NullValue() implements FieldValue {

    public static final NullValue INSTANCE = new NullValue();

    @Override public DataType dataType()   { return DataType.NULL; }
    @Override public int serializedSize()  { return 0; }
    @Override public void writeTo(ByteBuffer buffer) { /* nothing to write */ }

    @Override public String toString() { return "NULL"; }
}