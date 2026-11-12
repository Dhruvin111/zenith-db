package org.javadb.storage.buffer;

import org.javadb.common.PageId;
import org.javadb.common.exception.BufferPoolException;
import org.javadb.storage.disk.DiskManager;
import org.javadb.storage.disk.StorageFile;
import org.javadb.storage.page.Page;
import org.javadb.storage.page.PageType;
import org.javadb.storage.page.SlottedPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BufferPoolManagerTest {

    @TempDir
    Path tmp;

    private DiskManager disk;

    @BeforeEach
    void setUp() {
        disk = new DiskManager(
            tmp.resolve("data").toString(),
            tmp.resolve("wal").toString(),
            true);   // checksums on
    }

    @AfterEach
    void tearDown() {
        disk.close();
    }

    @ParameterizedTest
    @EnumSource(BufferPoolManager.PolicyType.class)
    void dataSurvivesEviction(BufferPoolManager.PolicyType policy) {
        BufferPoolManager pool = new BufferPoolManager(disk, 3, policy);
        int fileId = pool.registerFile("t.db", StorageFile.DATA);

        // 5 pages through a 3-frame pool forces evictions.
        List<PageId> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Page p = pool.newPage(fileId, PageType.HEAP);
            new SlottedPage(p).insertTuple(
                ("row-" + i).getBytes(StandardCharsets.UTF_8));
            ids.add(p.getPageId());
            pool.unpinPage(p.getPageId(), true);
        }

        assertTrue(pool.getEvictionCount() >= 2, "expected evictions");

        // Every page must come back from disk with its data intact.
        for (int i = 0; i < 5; i++) {
            Page p = pool.fetchPage(ids.get(i));
            byte[] tuple = new SlottedPage(p).getTuple(0);
            assertEquals("row-" + i,
                new String(tuple, StandardCharsets.UTF_8));
            pool.unpinPage(ids.get(i), false);
        }

        assertEquals(0, pool.getPinnedFrameCount(), "pin leak");
        pool.close();
    }

    @ParameterizedTest
    @EnumSource(BufferPoolManager.PolicyType.class)
    void failsWhenEveryFrameIsPinned(BufferPoolManager.PolicyType policy) {
        BufferPoolManager pool = new BufferPoolManager(disk, 2, policy);
        int fileId = pool.registerFile("t.db", StorageFile.DATA);

        Page a = pool.newPage(fileId, PageType.HEAP);   // pinned
        Page b = pool.newPage(fileId, PageType.HEAP);   // pinned

        assertThrows(BufferPoolException.class,
            () -> pool.newPage(fileId, PageType.HEAP));

        pool.unpinPage(a.getPageId(), true);
        pool.unpinPage(b.getPageId(), true);

        // With a frame unpinned again, allocation works.
        Page c = pool.newPage(fileId, PageType.HEAP);
        pool.unpinPage(c.getPageId(), true);
        pool.close();
    }

    @ParameterizedTest
    @EnumSource(BufferPoolManager.PolicyType.class)
    void repeatedFetchIsACacheHit(BufferPoolManager.PolicyType policy) {
        BufferPoolManager pool = new BufferPoolManager(disk, 4, policy);
        int fileId = pool.registerFile("t.db", StorageFile.DATA);

        Page p = pool.newPage(fileId, PageType.HEAP);
        PageId id = p.getPageId();
        pool.unpinPage(id, true);

        pool.fetchPage(id);
        pool.unpinPage(id, false);
        pool.fetchPage(id);
        pool.unpinPage(id, false);

        assertEquals(2, pool.getHitCount());
        assertEquals(0, pool.getDiskReadCount(), "page never left memory");
        pool.close();
    }

    @ParameterizedTest
    @EnumSource(BufferPoolManager.PolicyType.class)
    void doubleUnpinIsRejected(BufferPoolManager.PolicyType policy) {
        BufferPoolManager pool = new BufferPoolManager(disk, 2, policy);
        int fileId = pool.registerFile("t.db", StorageFile.DATA);

        Page p = pool.newPage(fileId, PageType.HEAP);
        PageId id = p.getPageId();
        pool.unpinPage(id, true);

        assertThrows(BufferPoolException.class,
            () -> pool.unpinPage(id, false));
        pool.close();
    }
}