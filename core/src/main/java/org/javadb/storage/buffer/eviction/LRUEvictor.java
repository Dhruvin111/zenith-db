package org.javadb.storage.buffer.eviction;

import org.javadb.storage.buffer.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ============================================================
 * LRUEvictor: Exact Least-Recently-Used Policy
 * ============================================================
 *
 * Keeps frames in a doubly linked list ordered by recency:
 *
 *   head <-> [MRU] <-> [ ... ] <-> [LRU] <-> tail
 *   (sentinel)  most recent             least recent  (sentinel)
 *
 *   recordAccess(f): move f to the MRU end        -> O(1)
 *   selectVictim():  walk from the LRU end and
 *                    take the first evictable node -> O(k), k = pinned skipped
 *   remove(f):       unlink the node               -> O(1)
 *
 * O(1) lookup of a frame's node uses an array indexed by frameId
 * (frameIds are 0..N-1), so no hashing is needed.
 *
 * Sentinel nodes (head/tail) mean insert and unlink never need
 * null checks for the list ends, which removes most edge cases.
 *
 * Trade-off vs Clock:
 *   Exact recency, but EVERY access takes a global lock to move
 *   a node. Under heavy concurrency that lock becomes a
 *   bottleneck, which is why PostgreSQL chose Clock.
 *
 * Note: selectVictim() does NOT unlink the victim. The
 * BufferPoolManager calls remove(frame) after eviction succeeds.
 *
 * Java concepts: manual doubly linked list, sentinel nodes,
 *   private static nested class, ReentrantLock with try/finally
 * ============================================================
 */
public class LRUEvictor implements EvictionPolicy {

    private static final Logger logger =
        LoggerFactory.getLogger(LRUEvictor.class);

    /** One list node per tracked frame. */
    private static final class Node {
        final Frame frame;
        Node prev;
        Node next;

        Node(Frame frame) {
            this.frame = frame;
        }
    }

    /** Sentinel: head.next is the most recently used node. */
    private final Node head = new Node(null);

    /** Sentinel: tail.prev is the least recently used node. */
    private final Node tail = new Node(null);

    /** nodes[frameId] -> that frame's list node, or null if untracked. */
    private final Node[] nodes;

    /** Number of frames currently in the list. */
    private int size = 0;

    /** Guards every list mutation. */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param capacity number of frames in the buffer pool
     * @throws IllegalArgumentException if capacity is not positive
     */
    public LRUEvictor(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                "LRUEvictor capacity must be > 0, got: " + capacity
            );
        }
        this.nodes = new Node[capacity];
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public void recordAccess(Frame frame) {
        lock.lock();
        try {
            Node node = nodes[checkId(frame)];

            if (node == null) {
                // First access since load: start tracking.
                node = new Node(frame);
                nodes[frame.getFrameId()] = node;
                size++;
            } else {
                unlink(node); // already tracked: pull out of old position
            }

            insertAtFront(node); // newest access is always the MRU
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Frame> selectVictim() {
        lock.lock();
        try {
            // Start at the LRU end, walk toward MRU.
            Node cur = tail.prev;
            while (cur != head) {
                if (cur.frame.isEvictionCandidate()) {
                    logger.trace("LRU victim: frame {}",
                        cur.frame.getFrameId());
                    return Optional.of(cur.frame);
                }
                cur = cur.prev; // pinned: try the next least recent
            }
            logger.debug("LRU scan found no victim: all frames pinned.");
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void remove(Frame frame) {
        lock.lock();
        try {
            int id = checkId(frame);
            Node node = nodes[id];
            if (node != null) {
                unlink(node);
                nodes[id] = null;
                size--;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int trackedCount() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String name() {
        return "LRU";
    }

    // ---------------------------------------------------------
    // Linked list helpers (caller must hold the lock)
    // ---------------------------------------------------------

    /** Detaches a node from its neighbours. */
    private void unlink(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    /** Inserts a node right after the head sentinel (MRU position). */
    private void insertAtFront(Node node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    /** Validates the frameId fits the lookup array. */
    private int checkId(Frame frame) {
        int id = frame.getFrameId();
        if (id < 0 || id >= nodes.length) {
            throw new IllegalArgumentException(
                "frameId " + id + " outside capacity " + nodes.length
            );
        }
        return id;
    }
}