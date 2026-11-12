package org.javadb.types.values;

import org.javadb.types.DataType;

import java.nio.ByteBuffer;
import java.time.Instant;

/** A TIMESTAMP value: milliseconds since the Unix epoch, UTC (8 bytes). */
public record TimestampValue(long epochMillis) implements FieldValue {

    public static TimestampValue of(Instant instant) {
        return new TimestampValue(instant.toEpochMilli());
    }

    public static TimestampValue now() {
        return new TimestampValue(System.currentTimeMillis());
    }

    public Instant toInstant() {
        return Instant.ofEpochMilli(epochMillis);
    }

    @Override public DataType dataType()   { return DataType.TIMESTAMP; }
    @Override public int serializedSize()  { return 8; }
    @Override public void writeTo(ByteBuffer buffer) { buffer.putLong(epochMillis); }

    @Override public String toString() { return toInstant().toString(); }
}