package org.javadb.storage.tuple;

import org.javadb.config.DatabaseConfig;
import org.javadb.types.TypeDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * ============================================================
 * TupleDescriptor: The Column Layout of a Row
 * ============================================================
 *
 * Bytes on a page carry no type information; the descriptor is
 * the "decoder ring" that says column 0 is an INTEGER, column 1
 * a VARCHAR(50), and so on. The same descriptor is needed to
 * serialize a row and to read it back.
 *
 * It is immutable. The catalog (Section 13) builds one per table
 * from its column definitions; the executor builds others for
 * query results (a projection has fewer columns than its table).
 *
 * Column names are matched case-insensitively and must be unique,
 * as in PostgreSQL where unquoted identifiers fold to lower case.
 * The original spelling is kept for display.
 *
 * PostgreSQL parallel: TupleDesc / FormData_pg_attribute
 *
 * Java concepts: immutable class with defensive copy, nested
 * record with compact-constructor validation, varargs factory,
 * OptionalInt, Locale.ROOT for locale-independent case folding
 * ============================================================
 */
public final class TupleDescriptor {

    /**
     * One column: a name, a type, and whether NULL is allowed.
     */
    public record Column(String name, TypeDescriptor type, boolean nullable) {

        public Column {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        "Column name cannot be null or blank.");
            }
            if (name.length() > DatabaseConfig.MAX_IDENTIFIER_LENGTH) {
                throw new IllegalArgumentException(
                        "Column name '" + name + "' is longer than " +
                                DatabaseConfig.MAX_IDENTIFIER_LENGTH + " characters.");
            }
            if (type == null) {
                throw new IllegalArgumentException(
                        "Column '" + name + "' needs a type.");
            }
        }

        /** A column that accepts NULL. */
        public static Column of(String name, TypeDescriptor type) {
            return new Column(name, type, true);
        }

        /** A NOT NULL column. */
        public static Column notNull(String name, TypeDescriptor type) {
            return new Column(name, type, false);
        }
    }

    private final List<Column> columns;
    private final Map<String, Integer> indexByName;

    public TupleDescriptor(List<Column> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "A TupleDescriptor needs at least one column.");
        }
        if (columns.size() > DatabaseConfig.MAX_COLUMNS_PER_TABLE) {
            throw new IllegalArgumentException(
                    "Too many columns: " + columns.size() + " (max " +
                            DatabaseConfig.MAX_COLUMNS_PER_TABLE + ").");
        }

        this.columns = List.copyOf(columns); // immutable defensive copy

        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < this.columns.size(); i++) {
            String key = fold(this.columns.get(i).name());
            if (index.put(key, i) != null) {
                throw new IllegalArgumentException(
                        "Duplicate column name: '" + this.columns.get(i).name() + "'.");
            }
        }
        this.indexByName = Map.copyOf(index);
    }

    public static TupleDescriptor of(Column... columns) {
        return new TupleDescriptor(new ArrayList<>(List.of(columns)));
    }

    // ---------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------

    public int getColumnCount() {
        return columns.size();
    }

    public Column getColumn(int index) {
        if (index < 0 || index >= columns.size()) {
            throw new IllegalArgumentException(
                    "Column index " + index + " out of range (0.." +
                            (columns.size() - 1) + ").");
        }
        return columns.get(index);
    }

    public List<Column> getColumns() {
        return columns; // already immutable
    }

    /** Finds a column by name (case-insensitive). */
    public OptionalInt findColumn(String name) {
        if (name == null) {
            return OptionalInt.empty();
        }
        Integer i = indexByName.get(fold(name));
        return i == null ? OptionalInt.empty() : OptionalInt.of(i);
    }

    /** Bytes the null bitmap of a row with this layout occupies. */
    public int nullBitmapSize() {
        return NullBitmap.sizeFor(columns.size());
    }

    /**
     * Worst-case serialized row size (bitmap + every column at its
     * maximum). A table may legally exceed a page in theory; the
     * real check happens per row in TupleSerializer.serialize().
     */
    public int maxRowSize() {
        long total = nullBitmapSize();
        for (Column c : columns) {
            total += c.type().maxSerializedSize();
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------
    // Object methods
    // ---------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        return o instanceof TupleDescriptor other && columns.equals(other.columns);
    }

    @Override
    public int hashCode() {
        return columns.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            if (i > 0)
                sb.append(", ");
            sb.append(c.name()).append(' ').append(c.type());
            if (!c.nullable())
                sb.append(" NOT NULL");
        }
        return sb.append(')').toString();
    }
}