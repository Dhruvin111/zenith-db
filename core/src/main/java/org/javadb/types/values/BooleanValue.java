package org.javadb.types.values;

import org.javadb.types.DataType;

import java.nio.ByteBuffer;

/** A BOOLEAN value (1 byte: 0 = false, 1 = true). */
public record BooleanValue(boolean value) implements FieldValue {

    public static final BooleanValue TRUE  = new BooleanValue(true);
    public static final BooleanValue FALSE = new BooleanValue(false);

    /** Returns the shared TRUE/FALSE instance. */
    public static BooleanValue of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override public DataType dataType()   { return DataType.BOOLEAN; }
    @Override public int serializedSize()  { return 1; }
    @Override public void writeTo(ByteBuffer buffer) {
        buffer.put((byte) (value ? 1 : 0));
    }

    @Override public String toString() { return value ? "true" : "false"; }
}