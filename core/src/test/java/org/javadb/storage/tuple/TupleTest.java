package org.javadb.storage.tuple;

import org.javadb.common.PageId;
import org.javadb.common.RID;
import org.javadb.common.exception.DatabaseException;
import org.javadb.common.exception.QueryException;
import org.javadb.common.exception.StorageException;
import org.javadb.storage.page.Page;
import org.javadb.storage.page.PageType;
import org.javadb.storage.page.SlottedPage;
import org.javadb.storage.tuple.TupleDescriptor.Column;
import org.javadb.types.TypeDescriptor;
import org.javadb.types.values.*;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TupleTest {

    private static final TupleDescriptor PEOPLE = TupleDescriptor.of(
        Column.notNull("id", TypeDescriptor.INTEGER),
        Column.of("name", TypeDescriptor.varchar(50)),
        Column.of("active", TypeDescriptor.BOOLEAN),
        Column.of("score", TypeDescriptor.FLOAT));

    private static Tuple alice() {
        return Tuple.of(PEOPLE, new IntValue(1), new VarcharValue("Alice"),
            BooleanValue.TRUE, new FloatValue(2.5));
    }

    private static Tuple nobody() {
        return Tuple.of(PEOPLE, new IntValue(2), NullValue.INSTANCE,
            BooleanValue.FALSE, new FloatValue(-1.0));
    }

    // ---------------- NullBitmap ----------------

    @Test
    void nullBitmapPacksBitsLsbFirstWithZeroPadding() {
        NullBitmap bitmap = new NullBitmap(10);
        assertEquals(2, bitmap.sizeInBytes());
        assertEquals(0, bitmap.nullCount());

        bitmap.setNull(9);
        assertTrue(bitmap.isNull(9));
        assertFalse(bitmap.isNull(8));
        assertFalse(bitmap.isNull(0));
        assertEquals(1, bitmap.nullCount());

        // byte 0: columns 0-7 present = 0xFF; byte 1: only column 8 present = 0x01
        assertArrayEquals(new byte[] {(byte) 0xFF, 0x01}, bitmap.toBytes());

        bitmap.setNotNull(9);
        assertEquals(0, bitmap.nullCount());
        assertThrows(IllegalArgumentException.class, () -> bitmap.isNull(10));
    }

    // ---------------- Descriptor ----------------

    @Test
    void descriptorValidatesAndFindsColumnsCaseInsensitively() {
        assertEquals(1, PEOPLE.findColumn("NAME").orElseThrow());
        assertTrue(PEOPLE.findColumn("missing").isEmpty());

        assertThrows(IllegalArgumentException.class, () -> new TupleDescriptor(List.of()));
        assertThrows(IllegalArgumentException.class, () -> TupleDescriptor.of(
            Column.of("Id", TypeDescriptor.INTEGER),
            Column.of("id", TypeDescriptor.BIGINT)));
        assertThrows(IllegalArgumentException.class,
            () -> Column.of(" ", TypeDescriptor.INTEGER));
        assertThrows(IllegalArgumentException.class,
            () -> Column.of("x".repeat(64), TypeDescriptor.INTEGER));
    }

    // ---------------- Tuple construction ----------------

    @Test
    void tupleOfCoercesValuesToColumnTypes() {
        TupleDescriptor d = TupleDescriptor.of(
            Column.notNull("big", TypeDescriptor.BIGINT),
            Column.of("ratio", TypeDescriptor.FLOAT));

        Tuple t = Tuple.of(d, new IntValue(7), new IntValue(2));
        assertEquals(new BigIntValue(7), t.get(0));
        assertEquals(new FloatValue(2.0), t.get("ratio"));
    }

    @Test
    void tupleOfEnforcesNotNullTypesAndArity() {
        QueryException notNull = assertThrows(QueryException.class, () ->
            Tuple.of(PEOPLE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE));
        assertEquals(DatabaseException.ErrorCode.NOT_NULL_VIOLATION, notNull.getErrorCode());

        assertThrows(QueryException.class, () ->
            Tuple.of(PEOPLE, new VarcharValue("x"), NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE));

        assertThrows(IllegalArgumentException.class, () -> Tuple.of(PEOPLE, new IntValue(1)));
    }

    // ---------------- Serialization ----------------

    @Test
    void everyTypeRoundTrips() {
        TupleDescriptor d = TupleDescriptor.of(
            Column.of("a", TypeDescriptor.INTEGER),
            Column.of("b", TypeDescriptor.BIGINT),
            Column.of("c", TypeDescriptor.FLOAT),
            Column.of("d", TypeDescriptor.BOOLEAN),
            Column.of("e", TypeDescriptor.varchar(100)),
            Column.of("f", TypeDescriptor.TIMESTAMP));

        Tuple t = Tuple.of(d, new IntValue(-5), new BigIntValue(Long.MAX_VALUE),
            new FloatValue(Math.PI), BooleanValue.TRUE, new VarcharValue("héllo"),
            new TimestampValue(1_700_000_000_000L));

        Tuple back = TupleSerializer.deserialize(d, TupleSerializer.serialize(t));
        assertEquals(t, back);
    }

    @Test
    void nullsCostOnlyABitAndNoDataBytes() {
        byte[] full = TupleSerializer.serialize(alice());
        assertEquals(1 + 4 + (2 + 5) + 1 + 8, full.length);      // 21 bytes
        assertEquals(0x0F, full[0]);                              // all 4 columns present
        assertEquals(full.length, TupleSerializer.serializedSize(alice()));

        byte[] withNull = TupleSerializer.serialize(nobody());
        assertEquals(1 + 4 + 1 + 8, withNull.length);             // name bytes gone: 14
        assertEquals(0x0D, withNull[0]);                          // column 1 cleared

        assertEquals(nobody(), TupleSerializer.deserialize(PEOPLE, withNull));
    }

    @Test
    void bitmapSpansMultipleBytes() {
        Column[] cols = new Column[9];
        FieldValue[] vals = new FieldValue[9];
        for (int i = 0; i < 9; i++) {
            cols[i] = Column.of("c" + i, TypeDescriptor.INTEGER);
            vals[i] = (i == 8) ? NullValue.INSTANCE : new IntValue(i);
        }
        TupleDescriptor d = TupleDescriptor.of(cols);
        Tuple t = Tuple.of(d, vals);

        byte[] bytes = TupleSerializer.serialize(t);
        assertEquals(2 + 8 * 4, bytes.length);                    // 2-byte bitmap, 8 ints
        assertEquals(t, TupleSerializer.deserialize(d, bytes));
        assertTrue(TupleSerializer.deserialize(d, bytes).isNull(8));
    }

    @Test
    void readColumnSkipsEarlierColumnsWithoutDecoding() {
        byte[] data = TupleSerializer.serialize(alice());
        assertEquals(new FloatValue(2.5), TupleSerializer.readColumn(PEOPLE, data, 3));
        assertEquals(new VarcharValue("Alice"), TupleSerializer.readColumn(PEOPLE, data, 1));

        byte[] withNull = TupleSerializer.serialize(nobody());
        assertTrue(TupleSerializer.readColumn(PEOPLE, withNull, 1).isNull());
        assertEquals(new FloatValue(-1.0), TupleSerializer.readColumn(PEOPLE, withNull, 3));
    }

    @Test
    void corruptDataIsDetected() {
        byte[] data = TupleSerializer.serialize(alice());

        byte[] truncated = Arrays.copyOf(data, data.length - 3);
        StorageException e1 = assertThrows(StorageException.class,
            () -> TupleSerializer.deserialize(PEOPLE, truncated));
        assertEquals(DatabaseException.ErrorCode.PAGE_CORRUPTION, e1.getErrorCode());

        byte[] extra = Arrays.copyOf(data, data.length + 1);
        assertThrows(StorageException.class,
            () -> TupleSerializer.deserialize(PEOPLE, extra));

        assertThrows(StorageException.class,
            () -> TupleSerializer.readColumn(PEOPLE, truncated, 3));
    }

    @Test
    void rowsLargerThanAPageAreRejected() {
        TupleDescriptor d = TupleDescriptor.of(
            Column.of("blob", TypeDescriptor.varchar(65535)));

        Tuple fits = Tuple.of(d, new VarcharValue("a".repeat(8000)));
        assertEquals(1 + 2 + 8000, TupleSerializer.serialize(fits).length);

        Tuple tooBig = Tuple.of(d, new VarcharValue("a".repeat(9000)));
        assertThrows(QueryException.class, () -> TupleSerializer.serialize(tooBig));
    }

    // ---------------- Integration with SlottedPage ----------------

    private static SlottedPage newHeapPage() {
        Page page = new Page();
        page.setPageId(PageId.of(1, 0));
        page.initialize(PageType.HEAP);
        return new SlottedPage(page);
    }

    @Test
    void rowsSurviveAPageRoundTripAndCarryTheirRid() {
        SlottedPage sp = newHeapPage();

        int slot0 = sp.insertTuple(TupleSerializer.serialize(alice()));
        int slot1 = sp.insertTuple(TupleSerializer.serialize(nobody()));

        Tuple back = TupleSerializer.deserialize(PEOPLE, sp.getTuple(slot1), sp.getRID(slot1));
        assertEquals(nobody(), back);
        assertEquals(RID.of(PageId.of(1, 0), slot1), back.getRid());
        assertTrue(back.hasRid());
        assertFalse(alice().hasRid());
        assertEquals(slot0 + 1, slot1);
    }

    @Test
    void scanDecodesEveryLiveRowAndSkipsDeletedOnes() {
        SlottedPage sp = newHeapPage();
        int s0 = sp.insertTuple(TupleSerializer.serialize(alice()));
        sp.insertTuple(TupleSerializer.serialize(nobody()));
        sp.deleteTuple(s0);

        List<SlottedPage.TupleLocation> live = sp.scanTuples();
        assertEquals(1, live.size());

        SlottedPage.TupleLocation loc = live.get(0);
        Tuple t = TupleSerializer.deserialize(
            PEOPLE, loc.tupleData(), loc.toRID(sp.getPage().getPageId()));
        assertEquals(nobody(), t);
        assertEquals(1, t.getRid().slotIndex());
    }
}