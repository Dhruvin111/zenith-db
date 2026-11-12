package org.javadb.types;

import org.javadb.common.exception.QueryException;
import org.javadb.types.values.*;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypeSystemTest {

    /** Writes a value, reads it back as the given type, expects equality. */
    private static void assertRoundTrip(FieldValue value) {
        ByteBuffer buf = ByteBuffer.allocate(value.serializedSize());
        value.writeTo(buf);
        assertEquals(0, buf.remaining(), "serializedSize() must match bytes written");

        buf.flip();
        FieldValue back = FieldValue.readFrom(buf, value.dataType());
        assertEquals(value, back);
        assertEquals(0, buf.remaining(), "readFrom must consume exactly the written bytes");
    }

    @Test
    void everyTypeRoundTrips() {
        assertRoundTrip(new IntValue(-123456));
        assertRoundTrip(new BigIntValue(Long.MIN_VALUE));
        assertRoundTrip(new FloatValue(3.14159));
        assertRoundTrip(BooleanValue.TRUE);
        assertRoundTrip(BooleanValue.FALSE);
        assertRoundTrip(new VarcharValue("hello"));
        assertRoundTrip(new VarcharValue(""));
        assertRoundTrip(new TimestampValue(1_700_000_000_000L));
        assertRoundTrip(NullValue.INSTANCE);
    }

    @Test
    void varcharLengthIsBytesNotCharacters() {
        VarcharValue v = new VarcharValue("é😀");   // 2 + 4 UTF-8 bytes
        assertEquals(2 + 6, v.serializedSize());
        assertRoundTrip(v);
    }

    @Test
    void largestVarcharUsesFullUnsignedLength() {
        VarcharValue v = new VarcharValue("a".repeat(65535));
        assertRoundTrip(v);   // length 65535 does not fit a signed short
    }

    @Test
    void numericTypesCompareAcrossEachOther() {
        assertEquals(0, new IntValue(5).compareTo(new BigIntValue(5)));
        assertTrue(new IntValue(5).compareTo(new FloatValue(5.5)) < 0);
        assertTrue(new BigIntValue(Long.MAX_VALUE)
            .compareTo(new BigIntValue(Long.MAX_VALUE - 1)) > 0);
    }

    @Test
    void nullsSortLast() {
        List<FieldValue> list = new ArrayList<>(List.of(
            NullValue.INSTANCE, new IntValue(3), new IntValue(1)));
        Collections.sort(list);
        assertEquals(new IntValue(1), list.get(0));
        assertEquals(new IntValue(3), list.get(1));
        assertTrue(list.get(2).isNull());
    }

    @Test
    void incompatibleTypesCannotBeCompared() {
        assertThrows(QueryException.class,
            () -> new IntValue(1).compareTo(new VarcharValue("1")));
    }

    @Test
    void coerceWidensNumbers() {
        assertEquals(new BigIntValue(7), TypeDescriptor.BIGINT.coerce(new IntValue(7)));
        assertEquals(new FloatValue(7.0), TypeDescriptor.FLOAT.coerce(new IntValue(7)));
        assertThrows(QueryException.class,
            () -> TypeDescriptor.INTEGER.coerce(new BigIntValue(7)));   // no narrowing
    }

    @Test
    void coerceEnforcesVarcharLength() {
        TypeDescriptor v3 = TypeDescriptor.varchar(3);
        assertEquals(new VarcharValue("abc"), v3.coerce(new VarcharValue("abc")));
        assertThrows(QueryException.class, () -> v3.coerce(new VarcharValue("abcd")));
        assertThrows(QueryException.class, () -> v3.coerce(new IntValue(1)));
    }

    @Test
    void coerceLetsNullThrough() {
        assertTrue(TypeDescriptor.INTEGER.coerce(NullValue.INSTANCE).isNull());
    }

    @Test
    void descriptorValidation() {
        assertThrows(IllegalArgumentException.class, () -> TypeDescriptor.varchar(0));
        assertThrows(IllegalArgumentException.class, () -> TypeDescriptor.varchar(70000));
        assertThrows(IllegalArgumentException.class, () -> TypeDescriptor.of(DataType.NULL));
        assertEquals("VARCHAR(255)", TypeDescriptor.varchar(255).toString());
        assertEquals(TypeDescriptor.INTEGER, new TypeDescriptor(DataType.INTEGER, 99));
    }

    @Test
    void sqlLiteralRendering() {
        assertEquals("'it''s'", FieldValue.toSqlLiteral(new VarcharValue("it's")));
        assertEquals("NULL", FieldValue.toSqlLiteral(NullValue.INSTANCE));
        assertEquals("TRUE", FieldValue.toSqlLiteral(BooleanValue.TRUE));
        assertEquals("42", FieldValue.toSqlLiteral(new IntValue(42)));
    }

    @Test
    void sqlNameLookup() {
        assertEquals(DataType.INTEGER, DataType.fromSqlName("int").orElseThrow());
        assertEquals(DataType.VARCHAR, DataType.fromSqlName("TEXT").orElseThrow());
        assertTrue(DataType.fromSqlName("blob").isEmpty());
    }
}