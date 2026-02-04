package com.genesis.p2p.core.handlers.backpressure;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages backpressure to prevent system overload.
 *
 * Features:
 * - Token bucket rate limiting
 * - Semaphore-based concurrency control
 * - Adaptive backpressure based on system load
 * - Queue depth monitoring
 * - Rejection statistics
 * - Gradual recovery
 */
public class BackpressureManager {

    private static final NodeLogger log = NodeLogger.getLogger(BackpressureManager.class);

    // Configuration
    private final int maxConcurrentMessages;
    private final int maxQueueDepth;
    private final int tokensPerSecond;
    private final int tokenBucketCapacity;

    // Backpressure state
    private final Semaphore concurrencyLimiter;
    private final TokenBucket tokenBucket;
    private final AtomicInteger currentQueueDepth;
    private final MetricsRegistry metrics;

    // Statistics
    private final AtomicLong totalRequests;
    private final AtomicLong acceptedRequests;
    private final AtomicLong rejectedRequests;
    private final AtomicLong throttledRequests;

    // Adaptive thresholds
    private volatile BackpressureLevel currentLevel;
    private volatile Instant lastLevelChange;

    /**
     * Creates BackpressureManager with default configuration.
     */
    public BackpressureManager(MetricsRegistry metrics) {
        this(100, 1000, 1000, 2000, metrics);
    }

    /**
     * Creates BackpressureManager with custom configuration.
     *
     * @param maxConcurrentMessages maximum concurrent messages
     * @param maxQueueDepth maximum queue depth
     * @param tokensPerSecond tokens added per second (rate limit)
     * @param tokenBucketCapacity maximum tokens in bucket
     * @param metrics metrics registry
     */
    public BackpressureManager(int maxConcurrentMessages, int maxQueueDepth,
                               int tokensPerSecond, int tokenBucketCapacity,
                               MetricsRegistry metrics) {
        this.maxConcurrentMessages = maxConcurrentMessages;
        this.maxQueueDepth = maxQueueDepth;
        this.tokensPerSecond = tokensPerSecond;
        this.tokenBucketCapacity = tokenBucketCapacity;
        this.metrics = metrics;

        this.concurrencyLimiter = new Semaphore(maxConcurrentMessages, true);
        this.tokenBucket = new TokenBucket(tokenBucketCapacity, tokensPerSecond);
        this.currentQueueDepth = new AtomicInteger(0);

        this.totalRequests = new AtomicLong(0);
        this.acceptedRequests = new AtomicLong(0);
        this.rejectedRequests = new AtomicLong(0);
        this.throttledRequests = new AtomicLong(0);

        this.currentLevel = BackpressureLevel.NORMAL;
        this.lastLevelChange = Instant.now();

        log.info("BackpressureManager initialized",
                "maxConcurrent", maxConcurrentMessages,
                "maxQueueDepth", maxQueueDepth,
                "tokensPerSecond", tokensPerSecond);
    }

    // ========================= Admission Control =========================

    /**
     * Attempts to acquire permission to process a message.
     *
     * @return true if permission granted, false if backpressure applied
     */
    public boolean tryAcquire() {
        return tryAcquire(Duration.ZERO);
    }

    /**
     * Attempts to acquire permission with timeout.
     *
     * @param timeout maximum wait time
     * @return true if permission granted, false if rejected
     */
    public boolean tryAcquire(Duration timeout) {
        totalRequests.incrementAndGet();

        // Check queue depth first
        if (currentQueueDepth.get() >= maxQueueDepth) {
            rejectedRequests.incrementAndGet();
            metrics.incrementCounter("backpressure.rejected.queue_full");
            log.warn("Request rejected - queue full",
                    "queueDepth", currentQueueDepth.get(),
                    "maxDepth", maxQueueDepth);
            return false;
        }

        // Try to get token from bucket (rate limiting)
        if (!tokenBucket.tryConsume()) {
            throttledRequests.incrementAndGet();
            metrics.incrementCounter("backpressure.throttled.rate_limit");
            log.debug("Request throttled - rate limit exceeded");
            return false;
        }

        // Try to acquire concurrency permit
        try {
            boolean acquired;
            if (timeout.isZero()) {
                acquired = concurrencyLimiter.tryAcquire();
            } else {
                acquired = concurrencyLimiter.tryAcquire(
                        timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            if (acquired) {
                currentQueueDepth.incrementAndGet();
                acceptedRequests.incrementAndGet();
                metrics.incrementCounter("backpressure.accepted");
                updateBackpressureLevel();
                return true;
            } else {
                rejectedRequests.incrementAndGet();
                metrics.incrementCounter("backpressure.rejected.no_permits");
                log.debug("Request rejected - no concurrency permits available");
                return false;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejectedRequests.incrementAndGet();
            metrics.incrementCounter("backpressure.rejected.interrupted");
            return false;
        }
    }

    /**
     * Releases a processing permit.
     */
    public void release() {
        concurrencyLimiter.release();
        currentQueueDepth.decrementAndGet();
        updateBackpressureLevel();
    }

    // ========================= Backpressure Level Management =========================

    /**
     * Updates backpressure level based on current system state.
     */
    private void updateBackpressureLevel() {
        int depth = currentQueueDepth.get();
        double utilization = (double) depth / maxQueueDepth;

        BackpressureLevel newLevel;
        if (utilization < 0.5) {
            newLevel = BackpressureLevel.NORMAL;
        } else if (utilization < 0.7) {
            newLevel = BackpressureLevel.WARNING;
        } else if (utilization < 0.9) {
            newLevel = BackpressureLevel.HIGH;
        } else {
            newLevel = BackpressureLevel.CRITICAL;
        }

        if (newLevel != currentLevel) {
            BackpressureLevel oldLevel = currentLevel;
            currentLevel = newLevel;
            lastLevelChange = Instant.now();

            log.info("Backpressure level changed",
                    "from", oldLevel,
                    "to", newLevel,
                    "queueDepth", depth,
                    "utilization", String.format("%.1f%%", utilization * 100));

            metrics.setGauge("backpressure.level", newLevel.ordinal());
        }

        metrics.setGauge("backpressure.queue_depth", depth);
        metrics.setGauge("backpressure.utilization", (long) (utilization * 100));
    }

    /**
     * Gets current backpressure level.
     */
    public BackpressureLevel getBackpressureLevel() {
        return currentLevel;
    }

    /**
     * Checks if system is under high backpressure.
     */
    public boolean isUnderPressure() {
        return currentLevel.ordinal() >= BackpressureLevel.HIGH.ordinal();
    }

    // ========================= Statistics =========================

    /**
     * Gets backpressure statistics.
     */
    public BackpressureStatistics getStatistics() {
        return new BackpressureStatistics(
                totalRequests.get(),
                acceptedRequests.get(),
                rejectedRequests.get(),
                throttledRequests.get(),
                currentQueueDepth.get(),
                maxQueueDepth,
                concurrencyLimiter.availablePermits(),
                maxConcurrentMessages,
                currentLevel
        );
    }

    /**
     * Resets statistics.
     */
    public void resetStatistics() {
        totalRequests.set(0);
        acceptedRequests.set(0);
        rejectedRequests.set(0);
        throttledRequests.set(0);
        log.info("Backpressure statistics reset");
    }

    // ========================= Token Bucket Implementation =========================

    /**
     * Token bucket for rate limiting.
     */
    private static class TokenBucket {
        private final int capacity;
        private final double refillRate; // tokens per millisecond
        private volatile double tokens;
        private volatile long lastRefillTime;

        TokenBucket(int capacity, int tokensPerSecond) {
            this.capacity = capacity;
            this.refillRate = tokensPerSecond / 1000.0;
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            refill();

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;

            if (elapsed > 0) {
                double newTokens = elapsed * refillRate;
                tokens = Math.min(capacity, tokens + newTokens);
                lastRefillTime = now;
            }
        }
    }

    // ========================= Enums and Records =========================

    /**
     * Backpressure severity levels.
     */
    public enum BackpressureLevel {
        NORMAL,    // < 50% utilization
        WARNING,   // 50-70% utilization
        HIGH,      // 70-90% utilization
        CRITICAL   // > 90% utilization
    }

    /**
     * Backpressure statistics.
     */
    public record BackpressureStatistics(
            long totalRequests,
            long acceptedRequests,
            long rejectedRequests,
            long throttledRequests,
            int currentQueueDepth,
            int maxQueueDepth,
            int availablePermits,
            int maxPermits,
            BackpressureLevel currentLevel
    ) {
        public double getAcceptanceRate() {
            if (totalRequests == 0) return 100.0;
            return (double) acceptedRequests / totalRequests * 100.0;
        }

        public double getRejectionRate() {
            if (totalRequests == 0) return 0.0;
            return (double) rejectedRequests / totalRequests * 100.0;
        }

        public double getQueueUtilization() {
            return (double) currentQueueDepth / maxQueueDepth * 100.0;
        }

        public double getConcurrencyUtilization() {
            return (double) (maxPermits - availablePermits) / maxPermits * 100.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "BackpressureStats[level=%s, accepted=%.1f%%, rejected=%d, " +
                            "queueUtil=%.1f%%, concUtil=%.1f%%]",
                    currentLevel,
                    getAcceptanceRate(),
                    rejectedRequests,
                    getQueueUtilization(),
                    getConcurrencyUtilization()
            );
        }
    }
}