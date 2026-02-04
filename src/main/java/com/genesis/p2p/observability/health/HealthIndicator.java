package com.genesis.p2p.observability.health;

/**
 * Health indicator interface for component health checks.
 *
 * Provides lifecycle-scoped health and readiness signals for:
 * - Startup readiness
 * - Runtime health
 * - Graceful shutdown status
 * - Component-specific diagnostics
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public interface HealthIndicator {

    /**
     * Gets the name of this health indicator.
     *
     * Should be unique and descriptive (e.g., "tcp-transport", "discovery-service").
     */
    String getName();

    /**
     * Performs health check and returns result.
     *
     * Should be fast (<100ms) and non-blocking.
     *
     * @return health check result with status and details
     */
    HealthCheckResult check();

    /**
     * Health check result with status and diagnostic details.
     */
    record HealthCheckResult(
            HealthStatus status,
            String message,
            java.util.Map<String, Object> details,
            long checkDurationMs
    ) {
        public static HealthCheckResult healthy(String message) {
            return new HealthCheckResult(
                    HealthStatus.HEALTHY,
                    message,
                    java.util.Collections.emptyMap(),
                    0
            );
        }

        public static HealthCheckResult healthy(String message, java.util.Map<String, Object> details) {
            return new HealthCheckResult(
                    HealthStatus.HEALTHY,
                    message,
                    details,
                    0
            );
        }

        public static HealthCheckResult degraded(String message) {
            return new HealthCheckResult(
                    HealthStatus.DEGRADED,
                    message,
                    java.util.Collections.emptyMap(),
                    0
            );
        }

        public static HealthCheckResult degraded(String message, java.util.Map<String, Object> details) {
            return new HealthCheckResult(
                    HealthStatus.DEGRADED,
                    message,
                    details,
                    0
            );
        }

        public static HealthCheckResult unhealthy(String message) {
            return new HealthCheckResult(
                    HealthStatus.UNHEALTHY,
                    message,
                    java.util.Collections.emptyMap(),
                    0
            );
        }

        public static HealthCheckResult unhealthy(String message, java.util.Map<String, Object> details) {
            return new HealthCheckResult(
                    HealthStatus.UNHEALTHY,
                    message,
                    details,
                    0
            );
        }

        public HealthCheckResult withDuration(long durationMs) {
            return new HealthCheckResult(status, message, details, durationMs);
        }
    }

    /**
     * Health status enumeration.
     */
    enum HealthStatus {
        /** Component is fully operational */
        HEALTHY,

        /** Component is operational but experiencing issues */
        DEGRADED,

        /** Component is not operational */
        UNHEALTHY
    }
}
