package org.javadb.types.values;

import org.javadb.types.DataType;

import java.nio.ByteBuffer;

/** A 64-bit BIGINT value (8 bytes). */
public record BigIntValue(long value) implements FieldValue {

    @Override public DataType dataType()   { return DataType.BIGINT; }
    @Override public int serializedSize()  { return 8; }
    @Override public void writeTo(ByteBuffer buffer) { buffer.putLong(value); }

    @Override public String toString() { return Long.toString(value); }
}