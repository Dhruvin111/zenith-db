package org.javadb.common.exception;

import org.javadb.common.PageId;

/**
 * ============================================================
 * BufferPoolException — Buffer Pool Management Failures
 * ============================================================
 *
 * Thrown by the BufferPoolManager (Section 09) when in-memory
 * page management operations fail.
 *
 * BufferPoolException extends StorageException because buffer
 * pool failures are a specific category of storage failures —
 * they occur at the memory layer above disk but below the
 * heap/index layer.
 *
 * PostgreSQL parallel:
 * PostgreSQL's buffer manager errors (bufmgr.c):
 * elog(ERROR, "no unpinned buffers available")
 * elog(ERROR, "buffer is not owned by current process")
 *
 * Common scenarios:
 * - All buffer pool frames are pinned by active operations
 * → cannot evict any page → buffer pool exhausted
 * - Trying to unpin a page that this thread never pinned
 * → programming error in operator implementation
 * - Pin count for a single page exceeds MAX_PIN_COUNT
 * → likely a pin leak (forgot to unpin)
 *
 * Java concepts:
 * - Extends StorageException : inherits pageId context
 * - Static factory methods : self-documenting error creation
 *
 * ============================================================
 */
public class BufferPoolException extends StorageException {

    // =========================================================
    // Constructors
    // =========================================================

    /**
     * Full constructor.
     *
     * @param errorCode the buffer pool error code
     * @param message   description of what failed
     * @param pageId    the affected page (may be null)
     * @param cause     the root cause (may be null)
     */
    public BufferPoolException(
            ErrorCode errorCode,
            String message,
            PageId pageId,
            Throwable cause) {
        super(errorCode, message, pageId, cause);
    }

    /**
     * Constructor without cause.
     *
     * @param errorCode the buffer pool error code
     * @param message   description of what failed
     * @param pageId    the affected page (may be null)
     */
    public BufferPoolException(
            ErrorCode errorCode,
            String message,
            PageId pageId) {
        this(errorCode, message, pageId, null);
    }

    // =========================================================
    // Static Factory Methods
    // =========================================================

    /**
     * All buffer pool frames are pinned — cannot load a new page.
     *
     * This is a critical error. It means all BUFFER_POOL_SIZE
     * frames are currently in use. Possible causes:
     * - Too many concurrent operations holding pages pinned
     * - Pin leak: a thread pinned a page and never unpinned it
     * - Buffer pool is too small for the workload
     *
     * @return a BufferPoolException with BUFFER_POOL_FULL code
     */
    public static BufferPoolException bufferPoolFull() {
        return new BufferPoolException(
                ErrorCode.BUFFER_POOL_FULL,
                "Buffer pool exhausted: all frames are pinned. " +
                        "Possible causes: pin leak or buffer pool too small. " +
                        "Increase BUFFER_POOL_SIZE in DatabaseConfig.",
                null);
    }

    /**
     * Attempted to unpin a page that is not currently pinned
     * (or was never pinned by this caller).
     *
     * This is always a programming error — indicates that an
     * operator called unpin() more times than pin() for a page.
     *
     * @param pageId the page that was improperly unpinned
     * @return a BufferPoolException with PAGE_NOT_PINNED code
     */
    public static BufferPoolException pageNotPinned(PageId pageId) {
        return new BufferPoolException(
                ErrorCode.PAGE_NOT_PINNED,
                String.format(
                        "Attempted to unpin page %s which is not pinned. " +
                                "Ensure every pin() call has a matching unpin() call. " +
                                "Use try-finally to guarantee unpin on exception.",
                        pageId),
                pageId);
    }

    /**
     * Pin count for a page exceeded the maximum allowed.
     *
     * @param pageId   the over-pinned page
     * @param pinCount the current (excessive) pin count
     * @return a BufferPoolException with PIN_COUNT_EXCEEDED code
     */
    public static BufferPoolException pinCountExceeded(
            PageId pageId, int pinCount) {
        return new BufferPoolException(
                ErrorCode.PIN_COUNT_EXCEEDED,
                String.format(
                        "Pin count for page %s exceeded maximum (%d). " +
                                "Current pin count: %d. " +
                                "Likely a pin leak — check for missing unpin() calls.",
                        pageId,
                        org.javadb.config.DatabaseConfig.MAX_PIN_COUNT,
                        pinCount),
                pageId);
    }

    /**
     * Requested page was not found in the buffer pool.
     * Should only happen if a page was never loaded or was
     * evicted while still logically in use.
     *
     * @param pageId the missing page
     * @return a BufferPoolException with PAGE_NOT_FOUND code
     */
    public static BufferPoolException pageNotFound(PageId pageId) {
        return new BufferPoolException(
                ErrorCode.PAGE_NOT_FOUND,
                String.format(
                        "Page %s not found in buffer pool. " +
                                "The page may have been evicted unexpectedly. " +
                                "Ensure the page is pinned before accessing it.",
                        pageId),
                pageId);
    }
}