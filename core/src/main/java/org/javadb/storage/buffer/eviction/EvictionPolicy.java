package org.javadb.storage.buffer.eviction;

import org.javadb.storage.buffer.Frame;

import java.util.Optional;

/**
 * ============================================================
 * EvictionPolicy: Strategy Interface for Victim Selection
 * ============================================================
 *
 * Decides WHICH frame the buffer pool should reuse when it
 * has no free frame left.
 *
 * Contract with BufferPoolManager (Section 09):
 *   1. On every page access (pin)      -> recordAccess(frame)
 *   2. When no free frame exists       -> selectVictim()
 *   3. After the victim is flushed and
 *      removed from the PageTable      -> remove(frame)
 *
 * IMPORTANT: selectVictim() only SUGGESTS a victim. Another
 * thread may pin that frame right after it is returned, so the
 * BufferPoolManager must re-check frame.isPinned() while holding
 * its own lock before actually evicting.
 *
 * PostgreSQL parallel: StrategyGetBuffer() in freelist.c
 *
 * Java concepts: Strategy pattern, Optional<T> as a null-safe result
 * ============================================================
 */
public interface EvictionPolicy {

    /**
     * Records that a frame was just accessed (pinned).
     * Called on the hot path, so implementations must be cheap.
     *
     * @param frame the frame that was accessed
     */
    void recordAccess(Frame frame);

    /**
     * Picks a frame that is safe to evict: valid and not pinned.
     *
     * @return the suggested victim, or empty if every frame is
     *         pinned (the pool is exhausted)
     */
    Optional<Frame> selectVictim();

    /**
     * Stops tracking a frame. Called after the frame has been
     * evicted or invalidated, so the policy forgets its history.
     *
     * @param frame the frame being removed
     */
    void remove(Frame frame);

    /**
     * Returns how many frames this policy currently tracks.
     * Used for statistics and tests.
     */
    int trackedCount();

    /**
     * Returns the policy name for logging, e.g. "CLOCK" or "LRU".
     */
    String name();
}