package com.genesis.p2p.core.handlers.ack;

import java.time.Duration;

/**
 * Configuration for message acknowledgment system.
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public record AckConfig(
        /** Default timeout for waiting ACK (default: 5 seconds) */
        Duration defaultTimeout,

        /** Maximum retry attempts (default: 3) */
        int maxRetries,

        /** Initial retry delay (default: 1 second) */
        Duration retryDelay,

        /** Retry delay multiplier for exponential backoff (default: 2.0) */
        double retryMultiplier,

        /** Maximum retry delay (default: 30 seconds) */
        Duration maxRetryDelay,

        /** Cleanup interval for stale entries (default: 1 minute) */
        Duration cleanupInterval,

        /** Enable automatic ACK for received messages (default: true) */
        boolean autoAckEnabled,

        /** Enable NACK (negative acknowledgment) support (default: true) */
        boolean nackEnabled
) {

    public AckConfig {
        if (defaultTimeout == null || defaultTimeout.isNegative()) {
            defaultTimeout = Duration.ofSeconds(5);
        }
        if (maxRetries < 0) {
            maxRetries = 3;
        }
        if (retryDelay == null || retryDelay.isNegative()) {
            retryDelay = Duration.ofSeconds(1);
        }
        if (retryMultiplier <= 0) {
            retryMultiplier = 2.0;
        }
        if (maxRetryDelay == null || maxRetryDelay.isNegative()) {
            maxRetryDelay = Duration.ofSeconds(30);
        }
        if (cleanupInterval == null || cleanupInterval.isNegative()) {
            cleanupInterval = Duration.ofMinutes(1);
        }
    }

    public static AckConfig defaults() {
        return new AckConfig(
                Duration.ofSeconds(5),
                3,
                Duration.ofSeconds(1),
                2.0,
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                true,
                true
        );
    }

    public static AckConfig highReliability() {
        return new AckConfig(
                Duration.ofSeconds(10),
                5,
                Duration.ofMillis(500),
                1.5,
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                true,
                true
        );
    }

    public static AckConfig lowLatency() {
        return new AckConfig(
                Duration.ofSeconds(2),
                2,
                Duration.ofMillis(200),
                2.0,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                true,
                true
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Duration defaultTimeout = Duration.ofSeconds(5);
        private int maxRetries = 3;
        private Duration retryDelay = Duration.ofSeconds(1);
        private double retryMultiplier = 2.0;
        private Duration maxRetryDelay = Duration.ofSeconds(30);
        private Duration cleanupInterval = Duration.ofMinutes(1);
        private boolean autoAckEnabled = true;
        private boolean nackEnabled = true;

        public Builder defaultTimeout(Duration timeout) {
            this.defaultTimeout = timeout;
            return this;
        }

        public Builder maxRetries(int retries) {
            this.maxRetries = retries;
            return this;
        }

        public Builder retryDelay(Duration delay) {
            this.retryDelay = delay;
            return this;
        }

        public Builder retryMultiplier(double multiplier) {
            this.retryMultiplier = multiplier;
            return this;
        }

        public Builder maxRetryDelay(Duration delay) {
            this.maxRetryDelay = delay;
            return this;
        }

        public Builder cleanupInterval(Duration interval) {
            this.cleanupInterval = interval;
            return this;
        }

        public Builder autoAckEnabled(boolean enabled) {
            this.autoAckEnabled = enabled;
            return this;
        }

        public Builder nackEnabled(boolean enabled) {
            this.nackEnabled = enabled;
            return this;
        }

        public AckConfig build() {
            return new AckConfig(
                    defaultTimeout, maxRetries, retryDelay, retryMultiplier,
                    maxRetryDelay, cleanupInterval, autoAckEnabled, nackEnabled
            );
        }
    }
}

