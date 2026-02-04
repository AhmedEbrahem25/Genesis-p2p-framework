package com.genesis.p2p.util.io;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance object pool for ByteBuffer reuse in P2P operations.
 * Reduces GC pressure and improves throughput for network I/O.
 *
 * Thread-safe implementation using lock-free data structures.
 * Supports both direct and heap ByteBuffers with separate pools.
 *
 * Pattern: Object Pool / Flyweight
 *
 * Usage:
 * <pre>
 * ByteBufferPool pool = new ByteBufferPool(8192, 100);
 * ByteBuffer buffer = pool.acquireDirect();
 * try {
 *     // Use buffer
 *     channel.read(buffer);
 * } finally {
 *     pool.release(buffer);
 * }
 * </pre>
 */
public class ByteBufferPool {

    private final Queue<ByteBuffer> directPool;
    private final Queue<ByteBuffer> heapPool;
    private final int bufferSize;
    private final int maxPoolSize;

    // Pool metrics
    private final AtomicInteger directPooled;
    private final AtomicInteger heapPooled;
    private final AtomicLong directAcquired;
    private final AtomicLong heapAcquired;
    private final AtomicLong directReleased;
    private final AtomicLong heapReleased;
    private final AtomicLong directCreated;
    private final AtomicLong heapCreated;

    // Constants
    private static final int DEFAULT_BUFFER_SIZE = 8192;  // 8KB
    private static final int DEFAULT_MAX_POOL_SIZE = 100;
    private static final int MIN_BUFFER_SIZE = 256;
    private static final int MAX_BUFFER_SIZE = 1024 * 1024; // 1MB

    /**
     * Creates pool with default settings (8KB buffers, max 100 pooled).
     */
    public ByteBufferPool() {
        this(DEFAULT_BUFFER_SIZE, DEFAULT_MAX_POOL_SIZE);
    }

    /**
     * Creates pool with specified buffer size and max pool size.
     *
     * @param bufferSize size of each buffer in bytes
     * @param maxPoolSize maximum number of buffers to pool per type
     * @throws IllegalArgumentException if parameters are invalid
     */
    public ByteBufferPool(int bufferSize, int maxPoolSize) {
        validateParameters(bufferSize, maxPoolSize);

        this.bufferSize = bufferSize;
        this.maxPoolSize = maxPoolSize;
        this.directPool = new ConcurrentLinkedQueue<>();
        this.heapPool = new ConcurrentLinkedQueue<>();

        // Initialize metrics
        this.directPooled = new AtomicInteger(0);
        this.heapPooled = new AtomicInteger(0);
        this.directAcquired = new AtomicLong(0);
        this.heapAcquired = new AtomicLong(0);
        this.directReleased = new AtomicLong(0);
        this.heapReleased = new AtomicLong(0);
        this.directCreated = new AtomicLong(0);
        this.heapCreated = new AtomicLong(0);
    }

    private void validateParameters(int bufferSize, int maxPoolSize) {
        if (bufferSize < MIN_BUFFER_SIZE || bufferSize > MAX_BUFFER_SIZE) {
            throw new IllegalArgumentException(
                    "Buffer size must be between " + MIN_BUFFER_SIZE + " and " + MAX_BUFFER_SIZE);
        }
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("Max pool size must be positive");
        }
    }

    /**
     * Acquires direct ByteBuffer from pool or creates new one.
     * Direct buffers are allocated outside the JVM heap and are optimal
     * for I/O operations with channels.
     *
     * @return cleared ByteBuffer ready for use
     */
    public ByteBuffer acquireDirect() {
        directAcquired.incrementAndGet();

        ByteBuffer buffer = directPool.poll();
        if (buffer != null) {
            directPooled.decrementAndGet();
            buffer.clear();
            return buffer;
        }

        // Create new buffer if pool is empty
        directCreated.incrementAndGet();
        return ByteBuffer.allocateDirect(bufferSize);
    }

    /**
     * Acquires heap ByteBuffer from pool or creates new one.
     * Heap buffers are allocated in JVM heap and are faster to allocate
     * but may require copying for I/O operations.
     *
     * @return cleared ByteBuffer ready for use
     */
    public ByteBuffer acquireHeap() {
        heapAcquired.incrementAndGet();

        ByteBuffer buffer = heapPool.poll();
        if (buffer != null) {
            heapPooled.decrementAndGet();
            buffer.clear();
            return buffer;
        }

        // Create new buffer if pool is empty
        heapCreated.incrementAndGet();
        return ByteBuffer.allocate(bufferSize);
    }

    /**
     * Returns direct ByteBuffer to pool for reuse.
     * Buffer must be direct and match the pool's buffer size.
     *
     * @param buffer the buffer to return (can be null)
     */
    public void releaseDirect(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }

        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be direct");
        }

        if (buffer.capacity() != bufferSize) {
            // Don't pool buffers of different sizes
            return;
        }

        directReleased.incrementAndGet();

        // Only pool if under limit
        if (directPooled.get() < maxPoolSize) {
            buffer.clear();
            if (directPool.offer(buffer)) {
                directPooled.incrementAndGet();
            }
        }
    }

    /**
     * Returns heap ByteBuffer to pool for reuse.
     * Buffer must be heap-based and match the pool's buffer size.
     *
     * @param buffer the buffer to return (can be null)
     */
    public void releaseHeap(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }

        if (buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be heap-based");
        }

        if (buffer.capacity() != bufferSize) {
            // Don't pool buffers of different sizes
            return;
        }

        heapReleased.incrementAndGet();

        // Only pool if under limit
        if (heapPooled.get() < maxPoolSize) {
            buffer.clear();
            if (heapPool.offer(buffer)) {
                heapPooled.incrementAndGet();
            }
        }
    }

    /**
     * Returns any ByteBuffer to appropriate pool.
     * Automatically detects buffer type and routes to correct pool.
     *
     * @param buffer the buffer to return (can be null)
     */
    public void release(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }

        try {
            if (buffer.isDirect()) {
                releaseDirect(buffer);
            } else {
                releaseHeap(buffer);
            }
        } catch (IllegalArgumentException e) {
            // Silently ignore invalid buffers in generic release
        }
    }

    /**
     * Pre-allocates buffers to fill the pool to target size.
     * Useful for warming up the pool before heavy I/O operations.
     *
     * @param directCount number of direct buffers to pre-allocate
     * @param heapCount number of heap buffers to pre-allocate
     */
    public void preallocate(int directCount, int heapCount) {
        directCount = Math.min(directCount, maxPoolSize);
        heapCount = Math.min(heapCount, maxPoolSize);

        for (int i = 0; i < directCount; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            directPool.offer(buffer);
            directPooled.incrementAndGet();
            directCreated.incrementAndGet();
        }

        for (int i = 0; i < heapCount; i++) {
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            heapPool.offer(buffer);
            heapPooled.incrementAndGet();
            heapCreated.incrementAndGet();
        }
    }

    /**
     * Clears all pooled buffers and resets metrics.
     * Useful for testing or forcing pool reset.
     */
    public void clear() {
        directPool.clear();
        heapPool.clear();
        directPooled.set(0);
        heapPooled.set(0);
    }

    /**
     * Gets current number of pooled direct buffers.
     */
    public int getDirectPoolSize() {
        return directPooled.get();
    }

    /**
     * Gets current number of pooled heap buffers.
     */
    public int getHeapPoolSize() {
        return heapPooled.get();
    }

    /**
     * Gets total number of pooled buffers.
     */
    public int getTotalPooled() {
        return directPooled.get() + heapPooled.get();
    }

    /**
     * Gets configured buffer size.
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Gets maximum pool size per type.
     */
    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    /**
     * Gets comprehensive pool statistics.
     */
    public PoolStats getStats() {
        return new PoolStats(
                directPooled.get(),
                heapPooled.get(),
                directAcquired.get(),
                heapAcquired.get(),
                directReleased.get(),
                heapReleased.get(),
                directCreated.get(),
                heapCreated.get(),
                bufferSize,
                maxPoolSize
        );
    }

    /**
     * Calculates pool efficiency (release rate / acquire rate).
     * Value close to 1.0 indicates proper buffer reuse.
     */
    public double getEfficiency() {
        long totalAcquired = directAcquired.get() + heapAcquired.get();
        long totalReleased = directReleased.get() + heapReleased.get();

        if (totalAcquired == 0) {
            return 1.0;
        }

        return (double) totalReleased / totalAcquired;
    }

    /**
     * Calculates pool hit rate (reused buffers / total acquired).
     */
    public double getHitRate() {
        long totalAcquired = directAcquired.get() + heapAcquired.get();
        long totalCreated = directCreated.get() + heapCreated.get();

        if (totalAcquired == 0) {
            return 0.0;
        }

        return 1.0 - ((double) totalCreated / totalAcquired);
    }

    /**
     * Pool statistics holder with comprehensive metrics.
     */
    public static class PoolStats {
        private final int directPooled;
        private final int heapPooled;
        private final long directAcquired;
        private final long heapAcquired;
        private final long directReleased;
        private final long heapReleased;
        private final long directCreated;
        private final long heapCreated;
        private final int bufferSize;
        private final int maxPoolSize;

        public PoolStats(int directPooled, int heapPooled,
                         long directAcquired, long heapAcquired,
                         long directReleased, long heapReleased,
                         long directCreated, long heapCreated,
                         int bufferSize, int maxPoolSize) {
            this.directPooled = directPooled;
            this.heapPooled = heapPooled;
            this.directAcquired = directAcquired;
            this.heapAcquired = heapAcquired;
            this.directReleased = directReleased;
            this.heapReleased = heapReleased;
            this.directCreated = directCreated;
            this.heapCreated = heapCreated;
            this.bufferSize = bufferSize;
            this.maxPoolSize = maxPoolSize;
        }

        public int getDirectPooled() { return directPooled; }
        public int getHeapPooled() { return heapPooled; }
        public int getTotalPooled() { return directPooled + heapPooled; }

        public long getDirectAcquired() { return directAcquired; }
        public long getHeapAcquired() { return heapAcquired; }
        public long getTotalAcquired() { return directAcquired + heapAcquired; }

        public long getDirectReleased() { return directReleased; }
        public long getHeapReleased() { return heapReleased; }
        public long getTotalReleased() { return directReleased + heapReleased; }

        public long getDirectCreated() { return directCreated; }
        public long getHeapCreated() { return heapCreated; }
        public long getTotalCreated() { return directCreated + heapCreated; }

        public int getBufferSize() { return bufferSize; }
        public int getMaxPoolSize() { return maxPoolSize; }

        /**
         * Pool utilization as percentage (0-100).
         */
        public double getUtilization() {
            return (double) getTotalPooled() / (maxPoolSize * 2) * 100;
        }

        /**
         * Hit rate - percentage of buffer reuse (0-100).
         */
        public double getHitRate() {
            long total = getTotalAcquired();
            if (total == 0) return 0.0;
            return (1.0 - ((double) getTotalCreated() / total)) * 100;
        }

        /**
         * Memory used by pooled buffers in KB.
         */
        public long getMemoryUsedKB() {
            return (long) getTotalPooled() * bufferSize / 1024;
        }

        @Override
        public String toString() {
            return String.format(
                    "PoolStats{pooled=%d/%d (direct=%d, heap=%d), " +
                            "acquired=%d, released=%d, created=%d, " +
                            "hitRate=%.1f%%, utilization=%.1f%%, memory=%dKB, bufferSize=%d}",
                    getTotalPooled(), maxPoolSize * 2, directPooled, heapPooled,
                    getTotalAcquired(), getTotalReleased(), getTotalCreated(),
                    getHitRate(), getUtilization(), getMemoryUsedKB(), bufferSize
            );
        }
    }
}