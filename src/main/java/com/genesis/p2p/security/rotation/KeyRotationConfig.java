package com.genesis.p2p.security.rotation;

import java.time.Duration;

/**
 * Configuration for key rotation service.
 *
 * Defines thresholds and intervals for automatic key rotation:
 * - Time-based: Rotate after session reaches certain age
 * - Message-based: Rotate after processing N messages
 * - Scheduled: Background check interval
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public record KeyRotationConfig(
        /** Maximum session age before rotation (default: 1 hour) */
        Duration maxSessionAge,

        /** Maximum messages before rotation (default: 10,000) */
        long maxMessageCount,

        /** Interval for background rotation checks (default: 5 minutes) */
        Duration checkInterval,

        /** Grace period to accept old keys during rotation (default: 30 seconds) */
        Duration gracePeriod,

        /** Enable automatic rotation (default: true) */
        boolean autoRotationEnabled,

        /** Enable forced rotation on security events (default: true) */
        boolean forceRotationEnabled
) {

    /**
     * Creates config with validation.
     */
    public KeyRotationConfig {
        if (maxSessionAge == null || maxSessionAge.isNegative()) {
            maxSessionAge = Duration.ofHours(1);
        }
        if (maxMessageCount <= 0) {
            maxMessageCount = 10_000;
        }
        if (checkInterval == null || checkInterval.isNegative()) {
            checkInterval = Duration.ofMinutes(5);
        }
        if (gracePeriod == null || gracePeriod.isNegative()) {
            gracePeriod = Duration.ofSeconds(30);
        }
    }

    /**
     * Creates default configuration.
     * - 1 hour max session age
     * - 10,000 max messages
     * - 5 minute check interval
     * - 30 second grace period
     */
    public static KeyRotationConfig defaults() {
        return new KeyRotationConfig(
                Duration.ofHours(1),
                10_000,
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                true,
                true
        );
    }

    /**
     * Creates high-security configuration.
     * - 15 minute max session age
     * - 1,000 max messages
     * - 1 minute check interval
     */
    public static KeyRotationConfig highSecurity() {
        return new KeyRotationConfig(
                Duration.ofMinutes(15),
                1_000,
                Duration.ofMinutes(1),
                Duration.ofSeconds(15),
                true,
                true
        );
    }

    /**
     * Creates configuration for testing.
     * - 1 minute max session age
     * - 100 max messages
     * - 10 second check interval
     */
    public static KeyRotationConfig forTesting() {
        return new KeyRotationConfig(
                Duration.ofMinutes(1),
                100,
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                true,
                true
        );
    }

    /**
     * Builder for custom configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Duration maxSessionAge = Duration.ofHours(1);
        private long maxMessageCount = 10_000;
        private Duration checkInterval = Duration.ofMinutes(5);
        private Duration gracePeriod = Duration.ofSeconds(30);
        private boolean autoRotationEnabled = true;
        private boolean forceRotationEnabled = true;

        public Builder maxSessionAge(Duration age) {
            this.maxSessionAge = age;
            return this;
        }

        public Builder maxMessageCount(long count) {
            this.maxMessageCount = count;
            return this;
        }

        public Builder checkInterval(Duration interval) {
            this.checkInterval = interval;
            return this;
        }

        public Builder gracePeriod(Duration period) {
            this.gracePeriod = period;
            return this;
        }

        public Builder autoRotationEnabled(boolean enabled) {
            this.autoRotationEnabled = enabled;
            return this;
        }

        public Builder forceRotationEnabled(boolean enabled) {
            this.forceRotationEnabled = enabled;
            return this;
        }

        public KeyRotationConfig build() {
            return new KeyRotationConfig(
                    maxSessionAge,
                    maxMessageCount,
                    checkInterval,
                    gracePeriod,
                    autoRotationEnabled,
                    forceRotationEnabled
            );
        }
    }
}

