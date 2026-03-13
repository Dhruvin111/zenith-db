package org.javadb.storage.disk;

import org.javadb.common.PageId;
import org.javadb.common.exception.StorageException;
import org.javadb.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ============================================================
 * FileHandle — Per-File Descriptor Wrapper
 * ============================================================
 *
 * A FileHandle wraps a single open file on disk. It holds:
 * - The FileChannel for reading/writing pages
 * - The current page count (how many pages the file has)
 * - A ReadWriteLock for concurrent access control
 * - Metadata: file path, file ID, storage type
 *
 * PostgreSQL parallel:
 * In PostgreSQL's md.c (Magnetic Disk manager):
 * MdfdVec struct holds the file descriptor (vfd) and
 * segment number for each open file segment.
 * In fd.c:
 * VfdCache manages open file descriptors with an LRU cache
 * to avoid exceeding OS file descriptor limits.
 *
 * We simplify by keeping one FileChannel per file (no LRU
 * descriptor cache) since we have fewer files in our engine.
 *
 * Thread safety:
 * Multiple threads (connections) can read pages concurrently.
 * Writing a page or extending the file requires exclusive access.
 *
 * We use ReentrantReadWriteLock:
 * - Multiple threads can hold the read lock simultaneously
 * (concurrent page reads are safe)
 * - Only one thread can hold the write lock
 * (page writes and file extension are exclusive)
 *
 * Java concepts:
 * - FileChannel : NIO-based random-access file I/O
 * - StandardOpenOption : type-safe file open modes
 * - ReentrantReadWriteLock: concurrent read, exclusive write
 * - AtomicLong : thread-safe page count
 * - AutoCloseable : try-with-resources support
 *
 * ============================================================
 * @author : Dhruvin Suryavanshi
 */
public class FileHandle implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FileHandle.class);

    // =========================================================
    // Fields
    // =========================================================

    /** Unique integer ID for this file — matches fileId in PageId */
    private final int fileId;

    /** Absolute path to the file on disk */
    private final Path filePath;

    /** What kind of file this is (DATA, INDEX, WAL, TEMP) */
    private final StorageFile fileType;

    /**
     * The NIO FileChannel — the core I/O abstraction.
     *
     * Why FileChannel over FileInputStream/FileOutputStream?
     * - FileChannel supports random access (seek to any position)
     * which is essential for page-based I/O
     * - FileChannel.read(buffer, position) reads from a specific
     * byte offset without changing the channel's position —
     * thread-safe for concurrent reads at different offsets
     * - FileChannel supports force() (fsync) for durability
     * - FileChannel can use direct ByteBuffers (off-heap memory)
     * to avoid one memory copy (kernel → JVM heap)
     *
     * PostgreSQL parallel:
     * PostgreSQL uses raw POSIX file descriptors (int fd) and
     * pread()/pwrite() system calls — the equivalent of
     * FileChannel.read(buffer, position).
     */
    private final FileChannel fileChannel;

    /**
     * Number of pages currently in this file.
     *
     * AtomicLong ensures thread-safe reads without requiring
     * the full write lock just to check page count.
     *
     * Invariant: fileChannel.size() == pageCount * PAGE_SIZE
     */
    private final AtomicLong pageCount;

    /**
     * ReadWriteLock for file operations.
     *
     * Read lock: acquired for page reads (concurrent allowed)
     * Write lock: acquired for page writes and file extension
     *
     * PostgreSQL parallel:
     * PostgreSQL uses LWLock (lightweight lock) on buffer
     * descriptors for similar concurrent read / exclusive write
     * semantics.
     */
    private final ReentrantReadWriteLock lock;

    // =========================================================
    // Constructor
    // =========================================================

    /**
     * Opens (or creates) a file at the given path.
     *
     * @param fileId   unique ID for this file
     * @param filePath path to the file on disk
     * @param fileType type of storage file
     * @param create   if true, create the file if it doesn't exist
     * @throws StorageException if the file cannot be opened
     */
    public FileHandle(
            int fileId,
            Path filePath,
            StorageFile fileType,
            boolean create) {
        this.fileId = fileId;
        this.filePath = filePath;
        this.fileType = fileType;
        this.lock = new ReentrantReadWriteLock(true); // fair mode

        try {
            /*
             * Open the FileChannel with appropriate options.
             *
             * StandardOpenOption.READ — allow reading
             * StandardOpenOption.WRITE — allow writing
             * StandardOpenOption.CREATE — create if not exists (if create=true)
             * StandardOpenOption.SYNC — writes go to disk immediately
             * (we manage fsync manually instead)
             *
             * We use READ + WRITE always.
             * CREATE is added only when create=true.
             *
             * Note: We do NOT use SYNC here — instead we call
             * force() (fsync) explicitly at transaction commit.
             * This gives us WAL group commit optimization later
             * (batch multiple transactions' fsync calls).
             *
             * PostgreSQL parallel:
             * open(path, O_RDWR | O_CREAT, 0600) in fd.c
             */
            if (create) {
                this.fileChannel = FileChannel.open(
                        filePath,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE);
            } else {
                this.fileChannel = FileChannel.open(
                        filePath,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
            }

            /*
             * Calculate initial page count from file size.
             * If file is new (size=0), pageCount=0.
             * If file already exists (restart scenario), count
             * existing pages so we know where to append new ones.
             */
            long fileSizeBytes = fileChannel.size();
            long initialPageCount = fileSizeBytes / DatabaseConfig.PAGE_SIZE;
            this.pageCount = new AtomicLong(initialPageCount);

            logger.debug(
                    "Opened file: {} (fileId={}, type={}, pages={})",
                    filePath, fileId, fileType, initialPageCount);

        } catch (IOException e) {
            throw StorageException.fileNotFound(filePath.toString());
        }
    }

    // =========================================================
    // Core I/O Methods
    // =========================================================

    /**
     * Reads a page from disk into the provided ByteBuffer.
     *
     * The buffer must have exactly PAGE_SIZE bytes of remaining
     * capacity. After this call, the buffer is ready to be
     * read from (position=0, limit=PAGE_SIZE).
     *
     * Thread safety: Multiple threads can call read() concurrently
     * for different pageNumbers — FileChannel.read(buf, pos) is
     * thread-safe for concurrent reads at different positions.
     *
     * PostgreSQL parallel:
     * mdread() in md.c calls FileRead() which calls pread():
     * pread(fd, buffer, BLCKSZ, (off_t)blocknum * BLCKSZ)
     *
     * @param pageId the page to read (must exist — pageNumber < pageCount)
     * @param buffer the ByteBuffer to read into (must have PAGE_SIZE capacity)
     * @throws StorageException if the read fails or the page doesn't exist
     */
    public void readPage(PageId pageId, java.nio.ByteBuffer buffer) {
        /*
         * Validate the page exists before attempting I/O.
         * Reading beyond the file end is a programming error.
         */
        validatePageExists(pageId);

        /*
         * Acquire read lock — allows concurrent reads from
         * multiple threads simultaneously.
         */
        lock.readLock().lock();
        try {
            /*
             * Prepare the buffer: position=0, limit=PAGE_SIZE.
             * clear() sets position to 0 and limit to capacity,
             * making the full buffer available for writing.
             */
            buffer.clear();

            /*
             * Compute byte offset of this page within the file.
             * Formula: offset = pageNumber * PAGE_SIZE
             *
             * FileChannel.read(buffer, position) reads starting
             * at the given byte position. It does NOT change the
             * channel's internal position — this is the key
             * difference from InputStream.read() and makes it
             * safe for concurrent access.
             */
            long offset = pageId.byteOffset();
            int bytesRead = 0;

            /*
             * Loop until we've read exactly PAGE_SIZE bytes.
             *
             * Why loop? FileChannel.read() may return fewer bytes
             * than requested (partial read) if:
             * - The OS read buffer is smaller than PAGE_SIZE
             * - An interrupt occurred
             * - We're at a non-aligned boundary
             *
             * We must keep reading until the buffer is full.
             * This is called a "read loop" — essential for
             * reliable I/O in any production system.
             */
            while (buffer.hasRemaining()) {
                int n = fileChannel.read(buffer, offset + bytesRead);

                if (n == -1) {
                    /*
                     * End of file reached before reading PAGE_SIZE bytes.
                     * This means the file is corrupted (truncated).
                     */
                    throw StorageException.readFailed(
                            pageId,
                            new IOException(String.format(
                                    "Unexpected end of file reading page %s. " +
                                            "Expected %d bytes, got %d. " +
                                            "File may be truncated.",
                                    pageId, DatabaseConfig.PAGE_SIZE, bytesRead)));
                }
                bytesRead += n;
            }

            /*
             * Flip the buffer: switches from write mode to read mode.
             * Sets limit=position, position=0.
             * After flip(), the buffer is ready to be read by the caller.
             */
            buffer.flip();

            logger.trace("Read page {} ({} bytes)", pageId, bytesRead);

        } catch (IOException e) {
            throw StorageException.readFailed(pageId, e);
        } finally {
            /*
             * ALWAYS release the lock in a finally block.
             * If we forget to unlock, the entire database deadlocks.
             * try-finally is the correct pattern for locks in Java.
             */
            lock.readLock().unlock();
        }
    }

    /**
     * Writes a page from the provided ByteBuffer to disk.
     *
     * The buffer must be in read mode (position=0, limit=PAGE_SIZE)
     * — i.e., as returned by buffer.flip() after filling it.
     *
     * IMPORTANT: This writes to the OS page cache, NOT directly
     * to physical disk. The write is not durable until force()
     * (fsync) is called. This is intentional — WAL ensures
     * durability, not immediate page writes.
     *
     * PostgreSQL parallel:
     * mdwrite() in md.c calls FileWrite() which calls pwrite():
     * pwrite(fd, buffer, BLCKSZ, (off_t)blocknum * BLCKSZ)
     *
     * @param pageId the page to write (must already exist OR be the next new page)
     * @param buffer the ByteBuffer containing the page data (PAGE_SIZE bytes)
     * @throws StorageException if the write fails
     */
    public void writePage(PageId pageId, java.nio.ByteBuffer buffer) {
        /*
         * Acquire write lock — exclusive, blocks all other reads/writes
         * until this write completes.
         */
        lock.writeLock().lock();
        try {
            /*
             * Validate buffer has exactly one full page of data.
             */
            if (buffer.remaining() != DatabaseConfig.PAGE_SIZE) {
                throw new StorageException(
                        StorageException.ErrorCode.IO_ERROR,
                        String.format(
                                "Buffer has %d bytes remaining, expected %d (PAGE_SIZE).",
                                buffer.remaining(), DatabaseConfig.PAGE_SIZE));
            }

            long offset = pageId.byteOffset();
            int bytesWritten = 0;

            /*
             * Write loop — mirror of the read loop above.
             * FileChannel.write() may also do partial writes.
             * Keep writing until the entire PAGE_SIZE is written.
             */
            while (buffer.hasRemaining()) {
                int n = fileChannel.write(buffer, offset + bytesWritten);
                bytesWritten += n;
            }

            logger.trace("Wrote page {} ({} bytes)", pageId, bytesWritten);

        } catch (IOException e) {
            throw StorageException.writeFailed(pageId, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Allocates a new page at the end of the file and returns
     * its page number.
     *
     * This is called when a heap file needs more space for new
     * tuples, or when a B+Tree needs a new node page.
     *
     * The new page is zero-filled on disk. The caller is responsible
     * for initializing the page header before using it.
     *
     * PostgreSQL parallel:
     * mdextend() in md.c:
     * Writes a page-sized block of zeros at the end of the file.
     * Updates the file size.
     *
     * @return the PageId of the newly allocated page
     * @throws StorageException if the file cannot be extended
     */
    public PageId allocatePage() {
        lock.writeLock().lock();
        try {
            /*
             * The new page's number is the current page count.
             * (Pages are 0-indexed: first page=0, second=1, etc.)
             */
            long newPageNumber = pageCount.get();

            /*
             * Compute byte offset where the new page starts.
             */
            long offset = newPageNumber * DatabaseConfig.PAGE_SIZE;

            /*
             * Write PAGE_SIZE zeros at the end of the file.
             * This "extends" the file by exactly one page.
             *
             * We write a zero-filled ByteBuffer — the page is
             * empty but properly sized. The caller (HeapFile or
             * BTreeIndex) will write a proper page header on top.
             *
             * Why zeros? Uninitialized pages with random bytes
             * are dangerous — a crash could leave partial data.
             * Zero-filling ensures we can detect truly empty pages.
             *
             * PostgreSQL parallel:
             * PostgreSQL writes a single zero byte at the last
             * position to extend the file, then fills with smgrzeroextend().
             */
            java.nio.ByteBuffer zeros = java.nio.ByteBuffer.allocate(DatabaseConfig.PAGE_SIZE);
            // Buffer is already zero-filled by allocate()

            while (zeros.hasRemaining()) {
                fileChannel.write(zeros, offset + (DatabaseConfig.PAGE_SIZE - zeros.remaining()));
            }

            /*
             * Increment page count atomically AFTER the write succeeds.
             * If the write fails (IOException), pageCount is NOT
             * incremented — the file state is consistent.
             */
            pageCount.incrementAndGet();

            PageId newPageId = PageId.of(fileId, (int) newPageNumber);

            logger.debug(
                    "Allocated new page {} in file {} (total pages: {})",
                    newPageId, filePath.getFileName(), pageCount.get());

            return newPageId;

        } catch (IOException e) {
            throw new StorageException(
                    StorageException.ErrorCode.IO_ERROR,
                    String.format(
                            "Failed to allocate new page in file '%s'. " +
                                    "Disk may be full.",
                            filePath),
                    e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Forces (fsyncs) all pending writes to physical disk.
     *
     * After this call returns, all previous writePage() calls
     * are guaranteed to be durable — they will survive a power
     * failure or OS crash.
     *
     * This is called:
     * 1. At transaction commit (after WAL flush)
     * 2. At checkpoint (flush all dirty buffer pool pages)
     * 3. At database shutdown
     *
     * Performance note:
     * fsync is expensive (~1-10ms per call on HDDs, ~0.1ms on SSDs).
     * We batch multiple transactions' writes before calling force()
     * — this is called "group commit" in PostgreSQL.
     *
     * PostgreSQL parallel:
     * mdsync() in md.c calls FileSync() which calls fsync():
     * fsync(fd)
     * PostgreSQL also uses fdatasync() when available (faster —
     * skips metadata sync when file size hasn't changed).
     *
     * @param metaData if true, also sync file metadata (size, timestamps)
     * @throws StorageException if the fsync fails
     */
    public void force(boolean metaData) {
        lock.writeLock().lock();
        try {
            /*
             * FileChannel.force(metaData):
             * metaData=true → flush data AND file metadata (like fsync)
             * metaData=false → flush data only (like fdatasync)
             *
             * For WAL files: always use true (metadata matters — file size)
             * For data files: false is sufficient (we know the size)
             */
            fileChannel.force(metaData);

            logger.trace(
                    "fsync completed for file {} (metaData={})",
                    filePath.getFileName(), metaData);

        } catch (IOException e) {
            throw new StorageException(
                    StorageException.ErrorCode.IO_ERROR,
                    String.format(
                            "fsync failed for file '%s'. " +
                                    "Data durability cannot be guaranteed.",
                            filePath),
                    e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // =========================================================
    // Validation Helpers
    // =========================================================

    /**
     * Validates that the given pageId refers to an existing page
     * in this file (i.e., the page has been allocated).
     *
     * @param pageId the page to validate
     * @throws StorageException if the page does not exist
     */
    private void validatePageExists(PageId pageId) {
        if (pageId.fileId() != this.fileId) {
            throw new StorageException(
                    StorageException.ErrorCode.INVALID_PAGE_ACCESS,
                    String.format(
                            "PageId %s references fileId=%d but this " +
                                    "FileHandle manages fileId=%d.",
                            pageId, pageId.fileId(), this.fileId));
        }
        if (pageId.pageNumber() < 0
                || pageId.pageNumber() >= pageCount.get()) {
            throw new StorageException(
                    StorageException.ErrorCode.INVALID_PAGE_ACCESS,
                    String.format(
                            "Page %s does not exist. " +
                                    "File '%s' has %d pages (valid range: 0 to %d).",
                            pageId, filePath.getFileName(),
                            pageCount.get(), pageCount.get() - 1));
        }
    }

    // =========================================================
    // Getters
    // =========================================================

    /** Returns the unique file ID */
    public int getFileId() {
        return fileId;
    }

    /** Returns the file path */
    public Path getFilePath() {
        return filePath;
    }

    /** Returns the file type */
    public StorageFile getFileType() {
        return fileType;
    }

    /** Returns the current number of pages in the file */
    public long getPageCount() {
        return pageCount.get();
    }

    /** Returns true if this file has no pages yet */
    public boolean isEmpty() {
        return pageCount.get() == 0;
    }

    // =========================================================
    // AutoCloseable — try-with-resources support
    // =========================================================

    /**
     * Closes the FileChannel and releases OS file descriptor.
     *
     * Always call close() when done with a FileHandle — or use
     * try-with-resources:
     * try (FileHandle fh = new FileHandle(...)) {
     * fh.readPage(...);
     * } // auto-closed here
     *
     * PostgreSQL parallel:
     * FileClose() in fd.c — decrements reference count,
     * closes fd when count reaches zero.
     *
     * @throws StorageException if the channel cannot be closed
     */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (fileChannel.isOpen()) {
                fileChannel.close();
                logger.debug(
                        "Closed file: {} (fileId={})",
                        filePath.getFileName(), fileId);
            }
        } catch (IOException e) {
            throw new StorageException(
                    StorageException.ErrorCode.IO_ERROR,
                    String.format(
                            "Failed to close file '%s'.", filePath),
                    e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // =========================================================
    // toString
    // =========================================================

    @Override
    public String toString() {
        return String.format(
                "FileHandle{fileId=%d, path='%s', type=%s, pages=%d, open=%b}",
                fileId, filePath, fileType, pageCount.get(),
                fileChannel.isOpen());
    }
}