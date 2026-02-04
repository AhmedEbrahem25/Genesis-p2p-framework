package com.genesis.p2p.core.handlers.dedup;

import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.util.collections.EvictingQueue;
import java.time.Duration;
import java.util.concurrent.*;

/**
 * Message deduplication service using EvictingQueue.
 * Tracks the last N message IDs to detect duplicates efficiently.
 */
public class DeduplicationService implements AutoCloseable {

    private final EvictingQueue<String> recentMessages;
    private final ConcurrentHashMap<String, Boolean> cache;
    private final int windowSize;
    private final MetricsRegistry metrics;

    /**
     * Creates a deduplication service.
     *
     * @param window duration window (used to calculate size: seconds * 10 messages/sec)
     * @param metrics metrics registry
     */
    public DeduplicationService(Duration window, MetricsRegistry metrics) {
        // Calculate window size: assume ~10 messages per second
        this.windowSize = (int) (window.getSeconds() * 10);
        this.recentMessages = new EvictingQueue<>(windowSize);
        this.cache = new ConcurrentHashMap<>();
        this.metrics = metrics;
    }

    /**
     * Tries to add a message ID. Returns true if unique, false if duplicate.
     *
     * @param messageId the message ID to check
     * @return true if unique (added), false if duplicate
     */
    public synchronized boolean tryAdd(String messageId) {
        // Check if already in cache (duplicate)
        if (cache.containsKey(messageId)) {
            metrics.incrementCounter("deduplication.duplicate");
            return false;
        }

        // Add to cache and queue
        cache.put(messageId, Boolean.TRUE);

        // Add to queue (automatically evicts oldest if at capacity)
        String evicted = recentMessages.peek();
        recentMessages.offer(messageId);

        // If queue evicted an item, remove from cache
        if (recentMessages.isFull() && evicted != null && !messageId.equals(evicted)) {
            String oldest = recentMessages.oldest();
            if (oldest != null && !cache.containsKey(oldest)) {
                // The oldest item was evicted, remove from cache
                cache.remove(evicted);
            }
        }

        // Clean up cache if it's larger than queue (shouldn't happen but safety check)
        if (cache.size() > windowSize * 2) {
            cache.clear();
            recentMessages.asList().forEach(id -> cache.put(id, Boolean.TRUE));
        }

        metrics.incrementCounter("deduplication.unique");
        metrics.setGauge("deduplication.cache.size", cache.size());

        return true;
    }

    /**
     * Gets the current cache size.
     *
     * @return number of tracked message IDs
     */
    public int size() {
        return cache.size();
    }

    /**
     * Gets the window size (max tracked messages).
     *
     * @return window size
     */
    public int getWindowSize() {
        return windowSize;
    }

    @Override
    public void close() {
        cache.clear();
    }
}