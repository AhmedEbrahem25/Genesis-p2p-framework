package com.genesis.p2p.util.config;

import java.time.Duration;

/**
 * Performance tuning configuration.
 *
 * <p>Configures thread pools, queue sizes, timeouts, and performance limits.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public record PerformanceConfig(
        int coreThreadPoolSize,
        int maxThreadPoolSize,
        int queueSize,
        Duration threadKeepAliveTime,
        int maxPeers,
        Duration peerCleanupInterval,
        int messageQueueCapacity,
        Duration messageTimeout,
        int maxRetries,
        Duration retryBackoff,
        Duration connectionTimeout,
        Duration readTimeout,
        Duration writeTimeout
) {
    // ==================== Constants ====================

    public static final int DEFAULT_CORE_THREADS = 4;
    public static final int DEFAULT_MAX_THREADS = 16;
    public static final int DEFAULT_QUEUE_SIZE = 1000;
    public static final Duration DEFAULT_THREAD_KEEP_ALIVE = Duration.ofMinutes(1);
    public static final int DEFAULT_MAX_PEERS = 1000;
    public static final Duration DEFAULT_PEER_CLEANUP_INTERVAL = Duration.ofSeconds(10);
    public static final int DEFAULT_MESSAGE_QUEUE_CAPACITY = 10000;
    public static final Duration DEFAULT_MESSAGE_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(1);
    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

    // ==================== Factory Methods ====================

    /**
     * Creates default performance configuration.
     * Suitable for most deployments.
     */
    public static PerformanceConfig defaults() {
        return new PerformanceConfig(
                DEFAULT_CORE_THREADS,
                DEFAULT_MAX_THREADS,
                DEFAULT_QUEUE_SIZE,
                DEFAULT_THREAD_KEEP_ALIVE,
                DEFAULT_MAX_PEERS,
                DEFAULT_PEER_CLEANUP_INTERVAL,
                DEFAULT_MESSAGE_QUEUE_CAPACITY,
                DEFAULT_MESSAGE_TIMEOUT,
                DEFAULT_MAX_RETRIES,
                DEFAULT_RETRY_BACKOFF,
                DEFAULT_CONNECTION_TIMEOUT,
                DEFAULT_READ_TIMEOUT,
                DEFAULT_WRITE_TIMEOUT
        );
    }

    /**
     * Creates high-performance configuration.
     * For servers with many cores and high traffic.
     */
    public static PerformanceConfig highPerformance() {
        return new PerformanceConfig(
                8,   // core threads
                32,  // max threads
                5000, // queue size
                Duration.ofMinutes(2),
                5000, // max peers
                Duration.ofSeconds(5),
                50000, // message queue
                Duration.ofSeconds(60),
                5,    // max retries
                Duration.ofMillis(500),
                Duration.ofSeconds(15),
                Duration.ofSeconds(60),
                Duration.ofSeconds(60)
        );
    }

    /**
     * Creates low-resource configuration.
     * For embedded systems or resource-constrained environments.
     */
    public static PerformanceConfig lowResource() {
        return new PerformanceConfig(
                2,   // core threads
                4,   // max threads
                100, // queue size
                Duration.ofSeconds(30),
                100, // max peers
                Duration.ofSeconds(30),
                1000, // message queue
                Duration.ofSeconds(15),
                2,    // max retries
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(15)
        );
    }

    /**
     * Creates a builder for custom configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Builder ====================

    public static class Builder {
        private int coreThreadPoolSize = DEFAULT_CORE_THREADS;
        private int maxThreadPoolSize = DEFAULT_MAX_THREADS;
        private int queueSize = DEFAULT_QUEUE_SIZE;
        private Duration threadKeepAliveTime = DEFAULT_THREAD_KEEP_ALIVE;
        private int maxPeers = DEFAULT_MAX_PEERS;
        private Duration peerCleanupInterval = DEFAULT_PEER_CLEANUP_INTERVAL;
        private int messageQueueCapacity = DEFAULT_MESSAGE_QUEUE_CAPACITY;
        private Duration messageTimeout = DEFAULT_MESSAGE_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Duration retryBackoff = DEFAULT_RETRY_BACKOFF;
        private Duration connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;

        public Builder coreThreads(int size) {
            this.coreThreadPoolSize = size;
            return this;
        }

        public Builder maxThreads(int size) {
            this.maxThreadPoolSize = size;
            return this;
        }

        public Builder queueSize(int size) {
            this.queueSize = size;
            return this;
        }

        public Builder threadKeepAlive(Duration duration) {
            this.threadKeepAliveTime = duration;
            return this;
        }

        public Builder maxPeers(int max) {
            this.maxPeers = max;
            return this;
        }

        public Builder peerCleanupInterval(Duration interval) {
            this.peerCleanupInterval = interval;
            return this;
        }

        public Builder messageQueueCapacity(int capacity) {
            this.messageQueueCapacity = capacity;
            return this;
        }

        public Builder messageTimeout(Duration timeout) {
            this.messageTimeout = timeout;
            return this;
        }

        public Builder maxRetries(int retries) {
            this.maxRetries = retries;
            return this;
        }

        public Builder retryBackoff(Duration backoff) {
            this.retryBackoff = backoff;
            return this;
        }

        public Builder connectionTimeout(Duration timeout) {
            this.connectionTimeout = timeout;
            return this;
        }

        public Builder readTimeout(Duration timeout) {
            this.readTimeout = timeout;
            return this;
        }

        public Builder writeTimeout(Duration timeout) {
            this.writeTimeout = timeout;
            return this;
        }

        public PerformanceConfig build() {
            return new PerformanceConfig(
                    coreThreadPoolSize,
                    maxThreadPoolSize,
                    queueSize,
                    threadKeepAliveTime,
                    maxPeers,
                    peerCleanupInterval,
                    messageQueueCapacity,
                    messageTimeout,
                    maxRetries,
                    retryBackoff,
                    connectionTimeout,
                    readTimeout,
                    writeTimeout
            );
        }
    }

    // ==================== Validation ====================

    public PerformanceConfig {
        if (coreThreadPoolSize <= 0) {
            throw new IllegalArgumentException("Core thread pool size must be positive");
        }
        if (maxThreadPoolSize < coreThreadPoolSize) {
            throw new IllegalArgumentException("Max thread pool size must be >= core size");
        }
        if (queueSize <= 0) {
            throw new IllegalArgumentException("Queue size must be positive");
        }
        if (maxPeers <= 0) {
            throw new IllegalArgumentException("Max peers must be positive");
        }
        if (messageQueueCapacity <= 0) {
            throw new IllegalArgumentException("Message queue capacity must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }
    }
}
