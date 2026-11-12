package org.javadb.storage.tuple;

import org.javadb.common.RID;
import org.javadb.common.exception.QueryException;
import org.javadb.storage.tuple.TupleDescriptor.Column;
import org.javadb.types.values.FieldValue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ============================================================
 * Tuple: One Row of Typed Values
 * ============================================================
 *
 * A Tuple pairs a TupleDescriptor with one FieldValue per column.
 * It is immutable. "Updating" a row means building a new Tuple.
 *
 * Two ways to obtain one:
 * Tuple.of(...) user-supplied values: every value is coerced
 * to its column type (INTEGER widens to BIGINT,
 * VARCHAR length is checked) and NOT NULL is
 * enforced. This is the INSERT / UPDATE path.
 * fromStored(...) values decoded from disk: already valid, so
 * checks are skipped. Package-private on purpose;
 * only TupleSerializer may use it.
 *
 * RID: a Tuple read from a page knows where it lives. A Tuple that
 * has not been stored yet carries RID.invalid(). Equality ignores
 * the RID: two rows with equal values are the same logical row.
 *
 * PostgreSQL parallel: HeapTuple, with t_self as the RID
 *
 * Java concepts: immutability with defensive copies, varargs
 * factory, package-private access, equals/hashCode on arrays
 * ============================================================
 */
public final class Tuple {

    private final TupleDescriptor descriptor;
    private final FieldValue[] values; // never exposed, never mutated
    private final RID rid;

    private Tuple(TupleDescriptor descriptor, FieldValue[] values, RID rid) {
        this.descriptor = descriptor;
        this.values = values;
        this.rid = rid;
    }

    /**
     * Builds a validated tuple from user-supplied values.
     *
     * @throws IllegalArgumentException wrong number of values, or a Java null
     * @throws QueryException           TYPE_MISMATCH if a value cannot be stored in
     *                                  its
     *                                  column; NOT_NULL_VIOLATION if NULL goes into
     *                                  a NOT NULL column
     */
    public static Tuple of(TupleDescriptor descriptor, FieldValue... input) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor cannot be null.");
        }
        int n = descriptor.getColumnCount();
        if (input == null || input.length != n) {
            throw new IllegalArgumentException(
                    "Descriptor " + descriptor + " expects " + n + " values, got " +
                            (input == null ? 0 : input.length) + ".");
        }

        FieldValue[] stored = new FieldValue[n];
        for (int i = 0; i < n; i++) {
            if (input[i] == null) {
                throw new IllegalArgumentException(
                        "Value " + i + " is Java null. Use NullValue.INSTANCE for SQL NULL.");
            }
            Column column = descriptor.getColumn(i);
            FieldValue value = column.type().coerce(input[i]);

            if (value.isNull() && !column.nullable()) {
                throw new QueryException(
                        QueryException.ErrorCode.NOT_NULL_VIOLATION,
                        "null value in column \"" + column.name() +
                                "\" violates not-null constraint.");
            }
            stored[i] = value;
        }
        return new Tuple(descriptor, stored, RID.invalid());
    }

    /**
     * Wraps already-valid decoded values. The array must not be reused by the
     * caller.
     */
    static Tuple fromStored(TupleDescriptor descriptor, FieldValue[] values, RID rid) {
        return new Tuple(descriptor, values, rid);
    }

    // ---------------------------------------------------------
    // Access
    // ---------------------------------------------------------

    public TupleDescriptor getDescriptor() {
        return descriptor;
    }

    public int size() {
        return values.length;
    }

    public FieldValue get(int column) {
        if (column < 0 || column >= values.length) {
            throw new IllegalArgumentException(
                    "Column index " + column + " out of range (0.." + (values.length - 1) + ").");
        }
        return values[column];
    }

    /** Looks a value up by column name (case-insensitive). */
    public FieldValue get(String columnName) {
        int index = descriptor.findColumn(columnName)
                .orElseThrow(() -> new IllegalArgumentException("No column named '" + columnName + "'."));
        return values[index];
    }

    public boolean isNull(int column) {
        return get(column).isNull();
    }

    /** All values as an unmodifiable list (a copy; the tuple stays immutable). */
    public List<FieldValue> values() {
        return Collections.unmodifiableList(Arrays.asList(values.clone()));
    }

    // ---------------------------------------------------------
    // RID
    // ---------------------------------------------------------

    /** Where this row is stored, or RID.invalid() if it has not been stored. */
    public RID getRid() {
        return rid;
    }

    public boolean hasRid() {
        return rid.isValid();
    }

    /** Returns a copy of this tuple stamped with a storage location. */
    public Tuple withRid(RID newRid) {
        if (newRid == null) {
            throw new IllegalArgumentException("rid cannot be null.");
        }
        return new Tuple(descriptor, values, newRid); // values are never mutated, safe to share
    }

    // ---------------------------------------------------------
    // Object methods
    // ---------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        return o instanceof Tuple other
                && descriptor.equals(other.descriptor)
                && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * descriptor.hashCode() + Arrays.hashCode(values);
    }

    /** Renders like a SQL row: (1, 'Alice', TRUE, 2.5) */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.length; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(FieldValue.toSqlLiteral(values[i]));
        }
        sb.append(')');
        if (rid.isValid()) {
            sb.append(" @ ").append(rid);
        }
        return sb.toString();
    }
}