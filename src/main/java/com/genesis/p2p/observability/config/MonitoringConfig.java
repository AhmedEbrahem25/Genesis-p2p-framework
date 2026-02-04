package com.genesis.p2p.observability.config;

import java.time.Duration;

/**
 * Monitoring and health check configuration.
 *
 * <p>Configures all monitoring, health checks, and alerting thresholds.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public record MonitoringConfig(
        Duration healthCheckInterval,
        Duration heartbeatTimeout,
        long highLatencyThresholdMs,
        int criticalReputationThreshold,
        Duration metricsCollectionInterval,
        boolean enableHealthAlerts,
        boolean enablePerformanceAlerts,
        boolean enableSecurityAlerts,
        int maxAlertsPerMinute
) {
    // ==================== Constants ====================

    public static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_HEARTBEAT_TIMEOUT = Duration.ofSeconds(60);
    public static final long DEFAULT_HIGH_LATENCY_MS = 5000;
    public static final int DEFAULT_CRITICAL_REPUTATION = 10;
    public static final Duration DEFAULT_METRICS_INTERVAL = Duration.ofSeconds(10);
    public static final int DEFAULT_MAX_ALERTS_PER_MINUTE = 100;

    // ==================== Factory Methods ====================

    /**
     * Creates default monitoring configuration.
     */
    public static MonitoringConfig defaults() {
        return new MonitoringConfig(
                DEFAULT_HEALTH_CHECK_INTERVAL,
                DEFAULT_HEARTBEAT_TIMEOUT,
                DEFAULT_HIGH_LATENCY_MS,
                DEFAULT_CRITICAL_REPUTATION,
                DEFAULT_METRICS_INTERVAL,
                true,  // health alerts enabled
                true,  // performance alerts enabled
                true,  // security alerts enabled
                DEFAULT_MAX_ALERTS_PER_MINUTE
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
        private Duration healthCheckInterval = DEFAULT_HEALTH_CHECK_INTERVAL;
        private Duration heartbeatTimeout = DEFAULT_HEARTBEAT_TIMEOUT;
        private long highLatencyThresholdMs = DEFAULT_HIGH_LATENCY_MS;
        private int criticalReputationThreshold = DEFAULT_CRITICAL_REPUTATION;
        private Duration metricsCollectionInterval = DEFAULT_METRICS_INTERVAL;
        private boolean enableHealthAlerts = true;
        private boolean enablePerformanceAlerts = true;
        private boolean enableSecurityAlerts = true;
        private int maxAlertsPerMinute = DEFAULT_MAX_ALERTS_PER_MINUTE;

        public Builder healthCheckInterval(Duration interval) {
            this.healthCheckInterval = interval;
            return this;
        }

        public Builder heartbeatTimeout(Duration timeout) {
            this.heartbeatTimeout = timeout;
            return this;
        }

        public Builder highLatencyThreshold(long milliseconds) {
            this.highLatencyThresholdMs = milliseconds;
            return this;
        }

        public Builder criticalReputationThreshold(int threshold) {
            this.criticalReputationThreshold = threshold;
            return this;
        }

        public Builder metricsCollectionInterval(Duration interval) {
            this.metricsCollectionInterval = interval;
            return this;
        }

        public Builder enableHealthAlerts(boolean enable) {
            this.enableHealthAlerts = enable;
            return this;
        }

        public Builder enablePerformanceAlerts(boolean enable) {
            this.enablePerformanceAlerts = enable;
            return this;
        }

        public Builder enableSecurityAlerts(boolean enable) {
            this.enableSecurityAlerts = enable;
            return this;
        }

        public Builder maxAlertsPerMinute(int max) {
            this.maxAlertsPerMinute = max;
            return this;
        }

        public MonitoringConfig build() {
            return new MonitoringConfig(
                    healthCheckInterval,
                    heartbeatTimeout,
                    highLatencyThresholdMs,
                    criticalReputationThreshold,
                    metricsCollectionInterval,
                    enableHealthAlerts,
                    enablePerformanceAlerts,
                    enableSecurityAlerts,
                    maxAlertsPerMinute
            );
        }
    }

    // ==================== Validation ====================

    public MonitoringConfig {
        if (healthCheckInterval.isNegative() || healthCheckInterval.isZero()) {
            throw new IllegalArgumentException("Health check interval must be positive");
        }
        if (heartbeatTimeout.isNegative() || heartbeatTimeout.isZero()) {
            throw new IllegalArgumentException("Heartbeat timeout must be positive");
        }
        if (highLatencyThresholdMs <= 0) {
            throw new IllegalArgumentException("High latency threshold must be positive");
        }
        if (criticalReputationThreshold < 0) {
            throw new IllegalArgumentException("Critical reputation threshold cannot be negative");
        }
        if (maxAlertsPerMinute <= 0) {
            throw new IllegalArgumentException("Max alerts per minute must be positive");
        }
    }
}
