package com.genesis.p2p.util.collections;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LRU (Least Recently Used) Cache implementation.
 *
 * Thread-safe cache with automatic eviction of least recently used entries.
 * Based on LinkedHashMap with access-order.
 *
 * Features:
 * - Fixed maximum size
 * - LRU eviction policy
 * - Thread-safe operations
 * - O(1) get/put performance
 * - Optional eviction listener
 * - Hit/miss statistics
 *
 * Use Cases:
 * - Caching peer information
 * - Message deduplication
 * - Session management
 * - Resource caching
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class LRUCache<K, V> extends LinkedHashMap<K, V> {

    private static final long serialVersionUID = 1L;
    private static final float LOAD_FACTOR = 0.75f;

    private final int maxSize;
    private final ReadWriteLock lock;
    private EvictionListener<K, V> evictionListener;

    // Statistics
    private long hits;
    private long misses;
    private long evictions;

    /**
     * Creates an LRU cache with specified maximum size.
     *
     * @param maxSize maximum number of entries
     */
    public LRUCache(int maxSize) {
        super((int) Math.ceil(maxSize / LOAD_FACTOR) + 1, LOAD_FACTOR, true);
        if (maxSize <= 0) {
            throw new IllegalArgumentException("Max size must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Creates an LRU cache with eviction listener.
     *
     * @param maxSize maximum number of entries
     * @param evictionListener listener for evicted entries
     */
    public LRUCache(int maxSize, EvictionListener<K, V> evictionListener) {
        this(maxSize);
        this.evictionListener = evictionListener;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        boolean shouldRemove = size() > maxSize;
        if (shouldRemove) {
            evictions++;
            if (evictionListener != null) {
                try {
                    evictionListener.onEviction(eldest.getKey(), eldest.getValue());
                } catch (Exception e) {
                    // Don't let listener exceptions break the cache
                    System.err.println("Eviction listener error: " + e.getMessage());
                }
            }
        }
        return shouldRemove;
    }

    @Override
    public V get(Object key) {
        lock.readLock().lock();
        try {
            V value = super.get(key);
            if (value != null) {
                hits++;
            } else {
                misses++;
            }
            return value;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public V put(K key, V value) {
        lock.writeLock().lock();
        try {
            return super.put(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public V remove(Object key) {
        lock.writeLock().lock();
        try {
            return super.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            super.clear();
            resetStats();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return super.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean containsKey(Object key) {
        lock.readLock().lock();
        try {
            return super.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean containsValue(Object value) {
        lock.readLock().lock();
        try {
            return super.containsValue(value);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets value or returns default if not found.
     */
    @Override
    public V getOrDefault(Object key, V defaultValue) {
        lock.readLock().lock();
        try {
            V value = super.get(key);
            if (value != null) {
                hits++;
                return value;
            } else {
                misses++;
                return defaultValue;
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Puts value if key is absent, returns existing value if present.
     */
    public V putIfAbsent(K key, V value) {
        lock.writeLock().lock();
        try {
            V existing = super.get(key);
            if (existing == null) {
                super.put(key, value);
                return null;
            }
            return existing;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets maximum size of cache.
     */
    public int maxSize() {
        return maxSize;
    }

    /**
     * Gets remaining capacity.
     */
    public int remainingCapacity() {
        return maxSize - size();
    }

    /**
     * Checks if cache is at capacity.
     */
    public boolean isFull() {
        return size() >= maxSize;
    }

    /**
     * Sets eviction listener.
     */
    public void setEvictionListener(EvictionListener<K, V> listener) {
        lock.writeLock().lock();
        try {
            this.evictionListener = listener;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Statistics ====================

    /**
     * Gets cache hit count.
     */
    public long getHits() {
        lock.readLock().lock();
        try {
            return hits;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets cache miss count.
     */
    public long getMisses() {
        lock.readLock().lock();
        try {
            return misses;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets eviction count.
     */
    public long getEvictions() {
        lock.readLock().lock();
        try {
            return evictions;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets total requests (hits + misses).
     */
    public long getTotalRequests() {
        lock.readLock().lock();
        try {
            return hits + misses;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets cache hit rate (0.0 to 1.0).
     */
    public double getHitRate() {
        lock.readLock().lock();
        try {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets cache miss rate (0.0 to 1.0).
     */
    public double getMissRate() {
        return 1.0 - getHitRate();
    }

    /**
     * Resets statistics.
     */
    public void resetStats() {
        lock.writeLock().lock();
        try {
            hits = 0;
            misses = 0;
            evictions = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets cache statistics.
     */
    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            return new CacheStats(
                    size(),
                    maxSize,
                    hits,
                    misses,
                    evictions,
                    getHitRate()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets oldest key (least recently used).
     */
    public K oldestKey() {
        lock.readLock().lock();
        try {
            Iterator<K> it = keySet().iterator();
            return it.hasNext() ? it.next() : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets newest key (most recently used).
     */
    public K newestKey() {
        lock.readLock().lock();
        try {
            K last = null;
            for (K key : keySet()) {
                last = key;
            }
            return last;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Creates a snapshot of current entries.
     */
    public Map<K, V> snapshot() {
        lock.readLock().lock();
        try {
            return new LinkedHashMap<>(this);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        return String.format("LRUCache[size=%d/%d, hits=%d, misses=%d, hitRate=%.2f%%]",
                size(), maxSize, hits, misses, getHitRate() * 100);
    }

    // ==================== Factory Methods ====================

    /**
     * Creates an LRU cache with specified size.
     */
    public static <K, V> LRUCache<K, V> create(int maxSize) {
        return new LRUCache<>(maxSize);
    }

    /**
     * Creates an LRU cache with eviction listener.
     */
    public static <K, V> LRUCache<K, V> create(int maxSize,
                                               EvictionListener<K, V> listener) {
        return new LRUCache<>(maxSize, listener);
    }

    // ==================== Interfaces ====================

    /**
     * Listener for eviction events.
     */
    @FunctionalInterface
    public interface EvictionListener<K, V> {
        /**
         * Called when an entry is evicted from cache.
         *
         * @param key the evicted key
         * @param value the evicted value
         */
        void onEviction(K key, V value);
    }

    /**
     * Cache statistics.
     */
    public static class CacheStats {
        private final int size;
        private final int maxSize;
        private final long hits;
        private final long misses;
        private final long evictions;
        private final double hitRate;

        public CacheStats(int size, int maxSize, long hits, long misses,
                         long evictions, double hitRate) {
            this.size = size;
            this.maxSize = maxSize;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.hitRate = hitRate;
        }

        public int getSize() { return size; }
        public int getMaxSize() { return maxSize; }
        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public long getEvictions() { return evictions; }
        public double getHitRate() { return hitRate; }
        public long getTotalRequests() { return hits + misses; }

        @Override
        public String toString() {
            return String.format("CacheStats[size=%d/%d, hits=%d, misses=%d, " +
                            "evictions=%d, hitRate=%.2f%%]",
                    size, maxSize, hits, misses, evictions, hitRate * 100);
        }
    }
}

