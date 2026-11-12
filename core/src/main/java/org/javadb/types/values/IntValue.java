package org.javadb.types.values;

import org.javadb.types.DataType;

import java.nio.ByteBuffer;

/** A 32-bit INTEGER value (4 bytes). */
public record IntValue(int value) implements FieldValue {

    @Override public DataType dataType()   { return DataType.INTEGER; }
    @Override public int serializedSize()  { return 4; }
    @Override public void writeTo(ByteBuffer buffer) { buffer.putInt(value); }

    @Override public String toString() { return Integer.toString(value); }
}