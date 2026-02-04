package com.genesis.p2p.security.config;

import java.time.Duration;

/**
 * Security configuration.
 */
public record SecurityConfig(
        boolean encryptionEnabled,
        String cryptoAlgorithm,
        int keySize,
        Duration sessionTimeout,
        boolean requireSignatures,
        int defaultTrustLevel
) {
    public static SecurityConfig defaults() {
        return new SecurityConfig(
                true,                       // encryption enabled
                "AES-GCM",                 // algorithm
                256,                        // 256-bit keys
                Duration.ofHours(1),       // 1 hour session timeout
                false,                      // signatures optional
                50                          // medium trust default
        );
    }

    public static SecurityConfig highSecurity() {
        return new SecurityConfig(
                true,
                "AES-GCM",
                256,
                com.genesis.p2p.util.constants.TimeoutConstants.SECURE_CHANNEL_TIMEOUT,
                true,                       // require signatures
                0                           // no default trust
        );
    }

    /**
     * Creates a builder for custom security configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for SecurityConfig.
     */
    public static class Builder {
        private boolean encryptionEnabled = true;
        private String cryptoAlgorithm = "AES-GCM";
        private int keySize = 256;
        private Duration sessionTimeout = Duration.ofHours(1);
        private boolean requireSignatures = false;
        private int defaultTrustLevel = 50;

        public Builder encryptionEnabled(boolean enabled) {
            this.encryptionEnabled = enabled;
            return this;
        }

        public Builder cryptoAlgorithm(String algorithm) {
            this.cryptoAlgorithm = algorithm;
            return this;
        }

        public Builder keySize(int size) {
            this.keySize = size;
            return this;
        }

        public Builder sessionTimeout(Duration timeout) {
            this.sessionTimeout = timeout;
            return this;
        }

        public Builder requireSignatures(boolean require) {
            this.requireSignatures = require;
            return this;
        }

        public Builder defaultTrustLevel(int level) {
            this.defaultTrustLevel = level;
            return this;
        }

        public SecurityConfig build() {
            return new SecurityConfig(
                    encryptionEnabled,
                    cryptoAlgorithm,
                    keySize,
                    sessionTimeout,
                    requireSignatures,
                    defaultTrustLevel
            );
        }
    }
}