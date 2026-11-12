package org.javadb.storage.buffer.eviction;

import org.javadb.storage.buffer.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * ============================================================
 * ClockEvictor: Second-Chance Clock Algorithm
 * ============================================================
 *
 * Frames are arranged logically in a circle. A "clock hand"
 * sweeps around looking for a victim:
 *
 *            frame 0
 *        frame 7   frame 1
 *      frame 6  [hand]  frame 2      hand -> next frame to inspect
 *        frame 5   frame 3
 *            frame 4
 *
 * For the frame under the hand:
 *   - not evictable (pinned or invalid) -> skip
 *   - referenceBit == true  -> clear it (second chance), move on
 *   - referenceBit == false -> THIS is the victim
 *
 * Why at most 2 * N steps?
 *   The first lap clears every reference bit. The second lap
 *   must then find any unpinned frame. If nothing is found after
 *   2 laps, every frame is pinned, so we return empty.
 *
 * Thread safety:
 *   - recordAccess() is lock-free (a single atomic bit set).
 *     Pins never wait for the sweep. This is the main reason
 *     PostgreSQL prefers Clock over exact LRU.
 *   - selectVictim() is serialized so only one sweep moves the
 *     hand at a time.
 *
 * PostgreSQL parallel: nextVictimBuffer in StrategyControl
 * (freelist.c). PostgreSQL decrements a usage_count (0 to 5)
 * per pass; we use the simpler single reference bit.
 *
 * Java concepts: circular array indexing with modulo,
 *   synchronized critical section, Optional return
 * ============================================================
 */
public class ClockEvictor implements EvictionPolicy {

    private static final Logger logger =
        LoggerFactory.getLogger(ClockEvictor.class);

    /** The buffer pool's frame array, indexed by frameId. */
    private final Frame[] frames;

    /** Index of the next frame the hand will inspect. */
    private int hand = 0;

    /** Guards the hand position during a sweep. */
    private final Object sweepLock = new Object();

    /**
     * @param frames the buffer pool's frame array (not copied)
     * @throws IllegalArgumentException if null or empty
     */
    public ClockEvictor(Frame[] frames) {
        if (frames == null || frames.length == 0) {
            throw new IllegalArgumentException(
                "ClockEvictor requires a non-empty frame array."
            );
        }
        this.frames = frames;
    }

    @Override
    public void recordAccess(Frame frame) {
        // Lock-free: just mark the frame as recently used.
        frame.setReferenceBit();
    }

    @Override
    public Optional<Frame> selectVictim() {
        synchronized (sweepLock) {
            int n = frames.length;

            // Two full laps: lap 1 clears bits, lap 2 finds a victim.
            for (int step = 0; step < 2 * n; step++) {
                Frame candidate = frames[hand];
                hand = (hand + 1) % n; // advance, wrapping at the end

                // Pinned or empty frames can never be victims.
                if (!candidate.isEvictionCandidate()) {
                    continue;
                }

                // Recently used: spend its second chance and move on.
                if (candidate.getReferenceBit()) {
                    candidate.clearReferenceBit();
                    continue;
                }

                logger.trace("Clock victim: frame {} after {} steps",
                    candidate.getFrameId(), step + 1);
                return Optional.of(candidate);
            }

            logger.debug("Clock sweep found no victim: all frames pinned.");
            return Optional.empty();
        }
    }

    @Override
    public void remove(Frame frame) {
        // Clock keeps no per-frame structure; just reset the bit.
        frame.clearReferenceBit();
    }

    @Override
    public int trackedCount() {
        int count = 0;
        for (Frame f : frames) {
            if (f.isValid()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String name() {
        return "CLOCK";
    }
}