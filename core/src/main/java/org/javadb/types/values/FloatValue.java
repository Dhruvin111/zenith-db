package org.javadb.types.values;

import org.javadb.types.DataType;

import java.nio.ByteBuffer;

/** A 64-bit DOUBLE PRECISION value (8 bytes). */
public record FloatValue(double value) implements FieldValue {

    @Override public DataType dataType()   { return DataType.FLOAT; }
    @Override public int serializedSize()  { return 8; }
    @Override public void writeTo(ByteBuffer buffer) { buffer.putDouble(value); }

    @Override public String toString() { return Double.toString(value); }
}