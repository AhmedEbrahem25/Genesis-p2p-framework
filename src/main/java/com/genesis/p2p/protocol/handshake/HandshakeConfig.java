package com.genesis.p2p.protocol.handshake;

import java.time.Duration;

/**
 * Configuration for handshake protocol behavior.
 *
 * Provides configurable settings for:
 * - Timeouts (connection, response, total handshake)
 * - Retry policy (max attempts, backoff)
 * - Rate limiting
 * - Observability (timeline tracing, metrics)
 *
 * Thread-safe and immutable after construction.
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public final class HandshakeConfig {

    // Timeout settings
    private final Duration connectionTimeout;
    private final Duration responseTimeout;
    private final Duration totalHandshakeTimeout;

    // Retry policy
    private final int maxRetryAttempts;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final double retryBackoffMultiplier;
    private final double retryJitterFactor;

    // Rate limiting
    private final int maxAttemptsPerWindow;
    private final Duration rateLimitWindow;

    // Observability
    private final boolean enableTimeline;
    private final boolean enableDetailedMetrics;
    private final Duration metricsFlushInterval;

    // Deduplication
    private final Duration sessionRecordTtl;
    private final Duration pendingHandshakeTimeout;

    private HandshakeConfig(Builder builder) {
        this.connectionTimeout = builder.connectionTimeout;
        this.responseTimeout = builder.responseTimeout;
        this.totalHandshakeTimeout = builder.totalHandshakeTimeout;
        this.maxRetryAttempts = builder.maxRetryAttempts;
        this.initialRetryDelay = builder.initialRetryDelay;
        this.maxRetryDelay = builder.maxRetryDelay;
        this.retryBackoffMultiplier = builder.retryBackoffMultiplier;
        this.retryJitterFactor = builder.retryJitterFactor;
        this.maxAttemptsPerWindow = builder.maxAttemptsPerWindow;
        this.rateLimitWindow = builder.rateLimitWindow;
        this.enableTimeline = builder.enableTimeline;
        this.enableDetailedMetrics = builder.enableDetailedMetrics;
        this.metricsFlushInterval = builder.metricsFlushInterval;
        this.sessionRecordTtl = builder.sessionRecordTtl;
        this.pendingHandshakeTimeout = builder.pendingHandshakeTimeout;
    }

    /**
     * Creates default configuration suitable for most deployments.
     */
    public static HandshakeConfig defaults() {
        return new Builder().build();
    }

    /**
     * Creates configuration optimized for local/development environments.
     */
    public static HandshakeConfig forDevelopment() {
        return new Builder()
                .connectionTimeout(Duration.ofSeconds(2))
                .responseTimeout(Duration.ofSeconds(3))
                .totalHandshakeTimeout(Duration.ofSeconds(10))
                .maxRetryAttempts(5)
                .enableTimeline(true)
                .enableDetailedMetrics(true)
                .build();
    }

    /**
     * Creates configuration optimized for production environments.
     */
    public static HandshakeConfig forProduction() {
        return new Builder()
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(10))
                .totalHandshakeTimeout(Duration.ofSeconds(30))
                .maxRetryAttempts(3)
                .initialRetryDelay(Duration.ofMillis(500))
                .maxRetryDelay(Duration.ofSeconds(10))
                .retryBackoffMultiplier(2.0)
                .retryJitterFactor(0.2)
                .enableTimeline(false)
                .enableDetailedMetrics(true)
                .build();
    }

    /**
     * Creates configuration for high-latency networks.
     */
    public static HandshakeConfig forHighLatency() {
        return new Builder()
                .connectionTimeout(Duration.ofSeconds(15))
                .responseTimeout(Duration.ofSeconds(30))
                .totalHandshakeTimeout(Duration.ofMinutes(2))
                .maxRetryAttempts(5)
                .initialRetryDelay(Duration.ofSeconds(2))
                .maxRetryDelay(Duration.ofSeconds(30))
                .build();
    }

    // ========================= Getters =========================

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public Duration getTotalHandshakeTimeout() {
        return totalHandshakeTimeout;
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public Duration getInitialRetryDelay() {
        return initialRetryDelay;
    }

    public Duration getMaxRetryDelay() {
        return maxRetryDelay;
    }

    public double getRetryBackoffMultiplier() {
        return retryBackoffMultiplier;
    }

    public double getRetryJitterFactor() {
        return retryJitterFactor;
    }

    public int getMaxAttemptsPerWindow() {
        return maxAttemptsPerWindow;
    }

    public Duration getRateLimitWindow() {
        return rateLimitWindow;
    }

    public boolean isTimelineEnabled() {
        return enableTimeline;
    }

    public boolean isDetailedMetricsEnabled() {
        return enableDetailedMetrics;
    }

    public Duration getMetricsFlushInterval() {
        return metricsFlushInterval;
    }

    public Duration getSessionRecordTtl() {
        return sessionRecordTtl;
    }

    public Duration getPendingHandshakeTimeout() {
        return pendingHandshakeTimeout;
    }

    @Override
    public String toString() {
        return String.format(
                "HandshakeConfig[connectionTimeout=%s, responseTimeout=%s, " +
                "totalTimeout=%s, maxRetries=%d, timeline=%s, detailedMetrics=%s]",
                connectionTimeout, responseTimeout, totalHandshakeTimeout,
                maxRetryAttempts, enableTimeline, enableDetailedMetrics
        );
    }

    // ========================= Builder =========================

    public static class Builder {
        private Duration connectionTimeout = Duration.ofSeconds(5);
        private Duration responseTimeout = Duration.ofSeconds(10);
        private Duration totalHandshakeTimeout = Duration.ofSeconds(30);
        private int maxRetryAttempts = 3;
        private Duration initialRetryDelay = Duration.ofMillis(200);
        private Duration maxRetryDelay = Duration.ofSeconds(5);
        private double retryBackoffMultiplier = 2.0;
        private double retryJitterFactor = 0.1;
        private int maxAttemptsPerWindow = 10;
        private Duration rateLimitWindow = Duration.ofMinutes(1);
        private boolean enableTimeline = false;
        private boolean enableDetailedMetrics = true;
        private Duration metricsFlushInterval = Duration.ofSeconds(10);
        private Duration sessionRecordTtl = Duration.ofMinutes(5);
        private Duration pendingHandshakeTimeout = Duration.ofSeconds(30);

        public Builder connectionTimeout(Duration timeout) {
            this.connectionTimeout = timeout;
            return this;
        }

        public Builder responseTimeout(Duration timeout) {
            this.responseTimeout = timeout;
            return this;
        }

        public Builder totalHandshakeTimeout(Duration timeout) {
            this.totalHandshakeTimeout = timeout;
            return this;
        }

        public Builder maxRetryAttempts(int attempts) {
            this.maxRetryAttempts = Math.max(0, attempts);
            return this;
        }

        public Builder initialRetryDelay(Duration delay) {
            this.initialRetryDelay = delay;
            return this;
        }

        public Builder maxRetryDelay(Duration delay) {
            this.maxRetryDelay = delay;
            return this;
        }

        public Builder retryBackoffMultiplier(double multiplier) {
            this.retryBackoffMultiplier = Math.max(1.0, multiplier);
            return this;
        }

        public Builder retryJitterFactor(double jitter) {
            this.retryJitterFactor = Math.max(0.0, Math.min(1.0, jitter));
            return this;
        }

        public Builder maxAttemptsPerWindow(int attempts) {
            this.maxAttemptsPerWindow = Math.max(1, attempts);
            return this;
        }

        public Builder rateLimitWindow(Duration window) {
            this.rateLimitWindow = window;
            return this;
        }

        public Builder enableTimeline(boolean enable) {
            this.enableTimeline = enable;
            return this;
        }

        public Builder enableDetailedMetrics(boolean enable) {
            this.enableDetailedMetrics = enable;
            return this;
        }

        public Builder metricsFlushInterval(Duration interval) {
            this.metricsFlushInterval = interval;
            return this;
        }

        public Builder sessionRecordTtl(Duration ttl) {
            this.sessionRecordTtl = ttl;
            return this;
        }

        public Builder pendingHandshakeTimeout(Duration timeout) {
            this.pendingHandshakeTimeout = timeout;
            return this;
        }

        public HandshakeConfig build() {
            validate();
            return new HandshakeConfig(this);
        }

        private void validate() {
            if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
                throw new IllegalArgumentException("connectionTimeout must be positive");
            }
            if (responseTimeout.isNegative() || responseTimeout.isZero()) {
                throw new IllegalArgumentException("responseTimeout must be positive");
            }
            if (totalHandshakeTimeout.compareTo(connectionTimeout.plus(responseTimeout)) < 0) {
                throw new IllegalArgumentException(
                        "totalHandshakeTimeout must be >= connectionTimeout + responseTimeout");
            }
        }
    }
}
