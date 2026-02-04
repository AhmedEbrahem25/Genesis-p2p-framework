package com.genesis.p2p.util.collections;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Evicting Queue - Fixed-size queue that automatically removes oldest elements.
 *
 * Thread-safe bounded queue with FIFO eviction policy.
 * Useful for:
 * - Keeping recent N messages
 * - Sliding window of events
 * - Fixed-size buffers
 * - Rate limiting windows
 *
 * Features:
 * - Fixed maximum size
 * - Automatic eviction of oldest elements
 * - Thread-safe operations
 * - O(1) add and remove
 * - Iterator support
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class EvictingQueue<E> extends AbstractQueue<E> {

    private final int maxSize;
    private final Queue<E> delegate;
    private final ReadWriteLock lock;

    /**
     * Creates an evicting queue with specified maximum size.
     *
     * @param maxSize maximum number of elements
     */
    public EvictingQueue(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("Max size must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
        this.delegate = new LinkedList<>();
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Creates an evicting queue with initial elements.
     *
     * @param maxSize maximum number of elements
     * @param initialElements initial elements to add
     */
    public EvictingQueue(int maxSize, Collection<E> initialElements) {
        this(maxSize);
        addAll(initialElements);
    }

    @Override
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException("Null elements not allowed");
        }

        lock.writeLock().lock();
        try {
            // Evict oldest if at capacity
            if (delegate.size() >= maxSize) {
                delegate.poll();
            }
            return delegate.offer(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public E poll() {
        lock.writeLock().lock();
        try {
            return delegate.poll();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public E peek() {
        lock.readLock().lock();
        try {
            return delegate.peek();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Iterator<E> iterator() {
        lock.readLock().lock();
        try {
            // Return copy to avoid concurrent modification
            return new ArrayList<>(delegate).iterator();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return delegate.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets maximum size of this queue.
     */
    public int maxSize() {
        return maxSize;
    }

    /**
     * Gets remaining capacity.
     */
    public int remainingCapacity() {
        lock.readLock().lock();
        try {
            return maxSize - delegate.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if queue is at capacity.
     */
    public boolean isFull() {
        return remainingCapacity() == 0;
    }

    @Override
    public boolean contains(Object o) {
        lock.readLock().lock();
        try {
            return delegate.contains(o);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean remove(Object o) {
        lock.writeLock().lock();
        try {
            return delegate.remove(o);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            delegate.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Object[] toArray() {
        lock.readLock().lock();
        try {
            return delegate.toArray();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public <T> T[] toArray(T[] a) {
        lock.readLock().lock();
        try {
            return delegate.toArray(a);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns list of elements in order (oldest to newest).
     */
    public List<E> asList() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(delegate);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the oldest element (same as peek).
     */
    public E oldest() {
        return peek();
    }

    /**
     * Gets the newest element.
     */
    public E newest() {
        lock.readLock().lock();
        try {
            if (delegate.isEmpty()) {
                return null;
            }
            // For LinkedList, last element is newest
            if (delegate instanceof LinkedList) {
                return ((LinkedList<E>) delegate).getLast();
            }
            // Fallback: iterate to last
            E last = null;
            for (E e : delegate) {
                last = e;
            }
            return last;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Creates a snapshot of current elements.
     */
    public EvictingQueue<E> snapshot() {
        lock.readLock().lock();
        try {
            return new EvictingQueue<>(maxSize, delegate);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return String.format("EvictingQueue[size=%d/%d, %s]",
                    delegate.size(), maxSize, delegate);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Creates an evicting queue with specified size.
     */
    public static <E> EvictingQueue<E> create(int maxSize) {
        return new EvictingQueue<>(maxSize);
    }

    /**
     * Creates an evicting queue from existing collection.
     */
    public static <E> EvictingQueue<E> create(int maxSize, Collection<E> elements) {
        return new EvictingQueue<>(maxSize, elements);
    }
}

