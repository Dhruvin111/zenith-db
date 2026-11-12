package org.javadb.types.values;

import org.javadb.config.DatabaseConfig;
import org.javadb.types.DataType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A VARCHAR value.
 *
 * On disk: [length: 2 bytes, unsigned][UTF-8 bytes].
 * The length counts BYTES, not characters: "é" is 2 bytes and an
 * emoji is 4. The 2-byte prefix caps a value at 65535 bytes, which
 * is DatabaseConfig.MAX_VARCHAR_LENGTH.
 *
 * Note: the per-column limit (VARCHAR(n)) counts characters and is
 * enforced by TypeDescriptor.coerce(), not here.
 */
public record VarcharValue(String value) implements FieldValue {

    public VarcharValue {
        if (value == null) {
            throw new IllegalArgumentException(
                "VarcharValue cannot hold Java null. Use NullValue.INSTANCE.");
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > DatabaseConfig.MAX_VARCHAR_LENGTH) {
            throw new IllegalArgumentException(
                "String is " + bytes + " bytes in UTF-8; maximum is " +
                DatabaseConfig.MAX_VARCHAR_LENGTH + ".");
        }
    }

    @Override public DataType dataType() { return DataType.VARCHAR; }

    @Override
    public int serializedSize() {
        return 2 + value.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public void writeTo(ByteBuffer buffer) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buffer.putShort((short) bytes.length);   // read back with toUnsignedInt
        buffer.put(bytes);
    }

    @Override public String toString() { return value; }
}