package org.javadb.types;

import org.javadb.common.exception.QueryException;
import org.javadb.config.DatabaseConfig;
import org.javadb.types.values.BigIntValue;
import org.javadb.types.values.BooleanValue;
import org.javadb.types.values.FieldValue;
import org.javadb.types.values.FloatValue;
import org.javadb.types.values.IntValue;
import org.javadb.types.values.TimestampValue;
import org.javadb.types.values.VarcharValue;

/**
 * ============================================================
 * TypeDescriptor: A Column's Full Type (type + length)
 * ============================================================
 *
 * DataType alone cannot express VARCHAR(255): the 255 is extra
 * information. TypeDescriptor bundles both.
 *
 *   TypeDescriptor.INTEGER       -> INTEGER
 *   TypeDescriptor.varchar(255)  -> VARCHAR(255)
 *
 * For non-VARCHAR types, length is always normalised to 0 so two
 * descriptors for the same type are always equal.
 *
 * coerce() implements the implicit conversions used when a value
 * is assigned to a column (INSERT / UPDATE):
 *   INTEGER -> BIGINT -> FLOAT      (numeric widening)
 *   anything else must match exactly.
 *
 * PostgreSQL parallel: atttypid + atttypmod in pg_attribute;
 * coerce() is a tiny version of coerce_to_target_type().
 *
 * Java concepts: record with compact constructor, switch
 * expressions on enum and on sealed types, pattern matching
 * ============================================================
 */
public record TypeDescriptor(DataType type, int length) {

    public static final TypeDescriptor BOOLEAN   = new TypeDescriptor(DataType.BOOLEAN, 0);
    public static final TypeDescriptor INTEGER   = new TypeDescriptor(DataType.INTEGER, 0);
    public static final TypeDescriptor BIGINT    = new TypeDescriptor(DataType.BIGINT, 0);
    public static final TypeDescriptor FLOAT     = new TypeDescriptor(DataType.FLOAT, 0);
    public static final TypeDescriptor TIMESTAMP = new TypeDescriptor(DataType.TIMESTAMP, 0);

    public TypeDescriptor {
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null.");
        }
        if (type == DataType.NULL) {
            throw new IllegalArgumentException(
                "NULL is not a valid column type.");
        }
        if (type == DataType.VARCHAR) {
            if (length < 1 || length > DatabaseConfig.MAX_VARCHAR_LENGTH) {
                throw new IllegalArgumentException(
                    "VARCHAR length must be 1.." +
                    DatabaseConfig.MAX_VARCHAR_LENGTH + ", got: " + length);
            }
        } else {
            length = 0;   // normalise so equals() works across callers
        }
    }

    /** Creates VARCHAR(length). */
    public static TypeDescriptor varchar(int length) {
        return new TypeDescriptor(DataType.VARCHAR, length);
    }

    /** Creates the descriptor for a fixed-length type. */
    public static TypeDescriptor of(DataType type) {
        return new TypeDescriptor(type, 0);
    }

    public boolean isFixedLength() {
        return type.isFixedLength();
    }

    /**
     * Worst-case bytes one value of this type can occupy when
     * serialized. Used to check a row can ever fit in a page.
     * VARCHAR: 2-byte prefix + up to 4 UTF-8 bytes per character,
     * capped at the maximum string size.
     */
    public int maxSerializedSize() {
        if (type.isFixedLength()) {
            return type.getFixedSize();
        }
        long worst = 2L + (long) length * 4;
        return (int) Math.min(worst, 2L + DatabaseConfig.MAX_VARCHAR_LENGTH);
    }

    /**
     * Converts a value to this type for storage, applying implicit
     * widening and checking VARCHAR length. NULL passes through
     * (the NOT NULL check belongs to the catalog/executor).
     *
     * @throws QueryException TYPE_MISMATCH if the value cannot be stored
     */
    public FieldValue coerce(FieldValue value) {
        if (value.isNull()) {
            return value;
        }

        return switch (type) {
            case BOOLEAN -> (value instanceof BooleanValue) ? value : mismatch(value);

            case INTEGER -> (value instanceof IntValue) ? value : mismatch(value);

            case BIGINT -> switch (value) {
                case BigIntValue b -> b;
                case IntValue i    -> new BigIntValue(i.value());
                default            -> mismatch(value);
            };

            case FLOAT -> switch (value) {
                case FloatValue f  -> f;
                case BigIntValue b -> new FloatValue(b.value());
                case IntValue i    -> new FloatValue(i.value());
                default            -> mismatch(value);
            };

            case VARCHAR -> {
                if (!(value instanceof VarcharValue s)) {
                    yield mismatch(value);
                }
                if (s.value().length() > length) {
                    throw new QueryException(
                        QueryException.ErrorCode.TYPE_MISMATCH,
                        "value too long for type " + this +
                        " (" + s.value().length() + " characters).");
                }
                yield s;
            }

            case TIMESTAMP -> (value instanceof TimestampValue) ? value : mismatch(value);

            case NULL -> throw new IllegalStateException(
                "TypeDescriptor can never have type NULL.");
        };
    }

    /** Always throws; returns FieldValue only so it fits in switch arms. */
    private FieldValue mismatch(FieldValue value) {
        throw QueryException.typeMismatch(toString(), value.dataType().getSqlName());
    }

    @Override
    public String toString() {
        return type == DataType.VARCHAR
            ? "VARCHAR(" + length + ")"
            : type.getSqlName();
    }
}