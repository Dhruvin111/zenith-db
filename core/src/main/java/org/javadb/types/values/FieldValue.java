package org.javadb.types.values;

import org.javadb.common.exception.QueryException;
import org.javadb.types.DataType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * ============================================================
 * FieldValue: A Single Typed Column Value (Sealed Hierarchy)
 * ============================================================
 *
 * Every value the engine handles is exactly one of the seven
 * permitted record types below. "sealed" tells the compiler the
 * list is closed, which gives us exhaustive switches:
 *
 *   switch (value) {
 *       case IntValue i     -> ...
 *       case VarcharValue s -> ...
 *       ...                    // forget one and it will not compile
 *   }
 *
 * Responsibilities:
 *   - report its DataType
 *   - serialize itself to / from a ByteBuffer (data bytes only;
 *     NULL tracking is the null bitmap's job in Section 11)
 *   - compare with other values using SQL rules
 *
 * compareTo semantics:
 *   - NULL sorts LAST (like PostgreSQL ASC default)
 *   - INTEGER, BIGINT and FLOAT compare numerically with each other
 *   - any other mix of types throws TYPE_MISMATCH
 *   - NOTE: compareTo is NOT consistent with equals across types:
 *     IntValue(1).compareTo(BigIntValue(1)) == 0, but the records
 *     are not equal(). Do not use FieldValue as a TreeMap key
 *     across mixed numeric types.
 *
 * Java concepts: sealed interface, permits, records, pattern
 * matching for switch, record deconstruction patterns,
 * default and private static interface methods
 * ============================================================
 */
public sealed interface FieldValue extends Comparable<FieldValue>
        permits IntValue, BigIntValue, FloatValue, BooleanValue,
                VarcharValue, TimestampValue, NullValue {

    // =========================================================
    // What every value must provide
    // =========================================================

    /** The type of this value (NullValue reports DataType.NULL). */
    DataType dataType();

    /** Number of bytes writeTo() will produce. */
    int serializedSize();

    /** Writes the data bytes at the buffer's current position. */
    void writeTo(ByteBuffer buffer);

    // =========================================================
    // Shared behaviour
    // =========================================================

    default boolean isNull() {
        return this instanceof NullValue;
    }

    /** Serializes into a fresh byte array of exactly serializedSize(). */
    default byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(serializedSize());
        writeTo(buf);
        return buf.array();
    }

    @Override
    default int compareTo(FieldValue other) {
        return compare(this, other);
    }

    // =========================================================
    // Deserialization
    // =========================================================

    /**
     * Reads one value of the given type from the buffer's current
     * position and advances the position past it.
     *
     * @param type must be a column type (NULL yields NullValue
     *             without consuming bytes)
     */
    static FieldValue readFrom(ByteBuffer buffer, DataType type) {
        return switch (type) {
            case BOOLEAN   -> BooleanValue.of(buffer.get() != 0);
            case INTEGER   -> new IntValue(buffer.getInt());
            case BIGINT    -> new BigIntValue(buffer.getLong());
            case FLOAT     -> new FloatValue(buffer.getDouble());
            case TIMESTAMP -> new TimestampValue(buffer.getLong());
            case VARCHAR   -> {
                int length = Short.toUnsignedInt(buffer.getShort());
                byte[] bytes = new byte[length];
                buffer.get(bytes);
                yield new VarcharValue(new String(bytes, StandardCharsets.UTF_8));
            }
            case NULL      -> NullValue.INSTANCE;
        };
    }

    // =========================================================
    // Comparison
    // =========================================================

    static int compare(FieldValue a, FieldValue b) {
        // NULL handling: both null -> equal, otherwise NULL is last.
        if (a.isNull() || b.isNull()) {
            if (a.isNull() && b.isNull()) return 0;
            return a.isNull() ? 1 : -1;
        }

        if (a instanceof VarcharValue x && b instanceof VarcharValue y) {
            return x.value().compareTo(y.value());
        }
        if (a instanceof BooleanValue x && b instanceof BooleanValue y) {
            return Boolean.compare(x.value(), y.value());
        }
        if (a instanceof TimestampValue x && b instanceof TimestampValue y) {
            return Long.compare(x.epochMillis(), y.epochMillis());
        }

        if (a.dataType().isNumeric() && b.dataType().isNumeric()) {
            // If either side is FLOAT, compare as doubles; otherwise
            // as longs so BIGINT values keep full precision.
            if (a instanceof FloatValue || b instanceof FloatValue) {
                return Double.compare(asDouble(a), asDouble(b));
            }
            return Long.compare(asLong(a), asLong(b));
        }

        throw QueryException.typeMismatch(
            a.dataType().getSqlName(), b.dataType().getSqlName());
    }

    private static long asLong(FieldValue v) {
        return switch (v) {
            case IntValue i    -> i.value();
            case BigIntValue b -> b.value();
            default -> throw new IllegalArgumentException("Not an integer: " + v);
        };
    }

    private static double asDouble(FieldValue v) {
        return switch (v) {
            case IntValue i    -> i.value();
            case BigIntValue b -> b.value();
            case FloatValue f  -> f.value();
            default -> throw new IllegalArgumentException("Not numeric: " + v);
        };
    }

    // =========================================================
    // Display: pattern matching showcase
    // =========================================================

    /**
     * Renders a value as a SQL literal. This switch has NO default:
     * the compiler proves all seven cases are covered. The
     * "case IntValue(int n)" form is a record deconstruction
     * pattern (Java 21): it matches AND extracts the field.
     */
    static String toSqlLiteral(FieldValue value) {
        return switch (value) {
            case NullValue n                     -> "NULL";
            case IntValue(int n)                 -> Integer.toString(n);
            case BigIntValue(long n)             -> Long.toString(n);
            case FloatValue(double d)            -> Double.toString(d);
            case BooleanValue(boolean flag)      -> flag ? "TRUE" : "FALSE";
            case VarcharValue(String s)          -> "'" + s.replace("'", "''") + "'";
            case TimestampValue(long ms)         -> "TIMESTAMP '" + Instant.ofEpochMilli(ms) + "'";
        };
    }
}