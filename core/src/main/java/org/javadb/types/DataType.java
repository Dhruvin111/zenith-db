package org.javadb.types;

import java.util.Optional;

/**
 * ============================================================
 * DataType: The SQL Types Supported by the Engine
 * ============================================================
 *
 * Every column has a DataType. The enum carries three facts about
 * each type:
 *   - code      : 1 byte stored on disk (in the catalog, Section 13)
 *   - sqlName   : how the type is printed (e.g. in error messages)
 *   - fixedSize : bytes a value occupies, or -1 if variable length
 *
 * PostgreSQL parallel:
 *   pg_type catalog. Fixed-width types have typlen > 0, variable
 *   types (text, varchar) have typlen = -1, exactly like fixedSize.
 *
 * Java concepts: enum with fields, static lookup, Optional
 * ============================================================
 */
public enum DataType {

    /** Type of an untyped NULL literal. Never valid as a column type. */
    NULL(0, "NULL", 0),

    BOOLEAN(1, "BOOLEAN", 1),

    /** 32-bit signed integer (PostgreSQL int4). */
    INTEGER(2, "INTEGER", 4),

    /** 64-bit signed integer (PostgreSQL int8). */
    BIGINT(3, "BIGINT", 8),

    /** 64-bit IEEE 754 double (PostgreSQL float8 / DOUBLE PRECISION). */
    FLOAT(4, "DOUBLE PRECISION", 8),

    /** Variable-length UTF-8 string with a maximum length. */
    VARCHAR(5, "VARCHAR", -1),

    /** Milliseconds since 1970-01-01T00:00:00Z. */
    TIMESTAMP(6, "TIMESTAMP", 8);

    private final int code;
    private final String sqlName;

    /** Bytes per value, or -1 for variable-length types. */
    private final int fixedSize;

    DataType(int code, String sqlName, int fixedSize) {
        this.code = code;
        this.sqlName = sqlName;
        this.fixedSize = fixedSize;
    }

    public int getCode()       { return code; }
    public String getSqlName() { return sqlName; }

    /** True if every value of this type has the same byte size. */
    public boolean isFixedLength() { return fixedSize >= 0; }

    /**
     * @return bytes per value
     * @throws IllegalStateException for variable-length types
     */
    public int getFixedSize() {
        if (!isFixedLength()) {
            throw new IllegalStateException(sqlName + " has no fixed size.");
        }
        return fixedSize;
    }

    /** True for INTEGER, BIGINT, FLOAT (types that compare numerically). */
    public boolean isNumeric() {
        return this == INTEGER || this == BIGINT || this == FLOAT;
    }

    /**
     * Looks up a type by its on-disk code.
     *
     * @throws IllegalArgumentException for an unknown code (corruption)
     */
    public static DataType fromCode(int code) {
        for (DataType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException(
            "Unknown DataType code: " + code + ". Catalog may be corrupted.");
    }

    /**
     * Maps a SQL type name to a DataType. Used by the parser
     * (Section 18) for CREATE TABLE. Accepts common aliases.
     *
     * @return the type, or empty if the name is not recognised
     */
    public static Optional<DataType> fromSqlName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return switch (name.trim().toUpperCase()) {
            case "BOOLEAN", "BOOL"                         -> Optional.of(BOOLEAN);
            case "INTEGER", "INT", "INT4"                  -> Optional.of(INTEGER);
            case "BIGINT", "INT8"                          -> Optional.of(BIGINT);
            case "FLOAT", "DOUBLE", "DOUBLE PRECISION",
                 "FLOAT8"                                  -> Optional.of(FLOAT);
            case "VARCHAR", "TEXT"                         -> Optional.of(VARCHAR);
            case "TIMESTAMP"                               -> Optional.of(TIMESTAMP);
            default                                        -> Optional.empty();
        };
    }
}