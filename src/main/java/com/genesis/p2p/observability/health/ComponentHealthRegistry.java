package com.genesis.p2p.observability.health;

import com.genesis.p2p.util.common.Time;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Central registry for component health indicators.
 *
 * Provides:
 * - Component health aggregation
 * - Overall system health status
 * - Readiness and liveness probes
 * - Health check caching
 * - Cross-component visibility
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ComponentHealthRegistry {

    private final Map<String, HealthIndicator> indicators = new ConcurrentHashMap<>();
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
    private final long cacheTtlMs;

    public ComponentHealthRegistry() {
        this(TimeUnit.SECONDS.toMillis(5)); // 5 second cache by default
    }

    public ComponentHealthRegistry(long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
    }

    /**
     * Registers a health indicator.
     */
    public void register(HealthIndicator indicator) {
        if (indicator == null) {
            throw new IllegalArgumentException("Health indicator cannot be null");
        }
        indicators.put(indicator.getName(), indicator);
    }

    /**
     * Unregisters a health indicator.
     */
    public void unregister(String name) {
        indicators.remove(name);
        cache.remove(name);
    }

    /**
     * Checks health of specific component.
     *
     * Uses cached result if available and fresh.
     */
    public HealthIndicator.HealthCheckResult check(String name) {
        HealthIndicator indicator = indicators.get(name);
        if (indicator == null) {
            return HealthIndicator.HealthCheckResult.unhealthy(
                    "Health indicator not found: " + name
            );
        }

        // Check cache
        CachedResult cached = cache.get(name);
        if (cached != null && !cached.isExpired(cacheTtlMs)) {
            return cached.result;
        }

        // Perform health check with timing
        long startTime = Time.currentMillis();
        HealthIndicator.HealthCheckResult result = indicator.check();
        long duration = Time.currentMillis() - startTime;

        // Add duration to result
        result = result.withDuration(duration);

        // Cache result
        cache.put(name, new CachedResult(result, Instant.now()));

        return result;
    }

    /**
     * Checks health of all registered components.
     *
     * @return aggregated health status
     */
    public AggregatedHealth checkAll() {
        Map<String, HealthIndicator.HealthCheckResult> results = new LinkedHashMap<>();
        HealthIndicator.HealthStatus overallStatus = HealthIndicator.HealthStatus.HEALTHY;

        for (Map.Entry<String, HealthIndicator> entry : indicators.entrySet()) {
            String name = entry.getKey();
            HealthIndicator.HealthCheckResult result = check(name);
            results.put(name, result);

            // Determine overall status (worst case)
            if (result.status() == HealthIndicator.HealthStatus.UNHEALTHY) {
                overallStatus = HealthIndicator.HealthStatus.UNHEALTHY;
            } else if (result.status() == HealthIndicator.HealthStatus.DEGRADED &&
                    overallStatus != HealthIndicator.HealthStatus.UNHEALTHY) {
                overallStatus = HealthIndicator.HealthStatus.DEGRADED;
            }
        }

        return new AggregatedHealth(overallStatus, results, Instant.now());
    }

    /**
     * Liveness probe - is the application running?
     *
     * Returns HEALTHY if at least one component is operational.
     */
    public boolean isAlive() {
        if (indicators.isEmpty()) {
            return true; // No indicators means we can't determine, assume alive
        }

        // At least one component must be healthy
        for (HealthIndicator indicator : indicators.values()) {
            HealthIndicator.HealthCheckResult result = check(indicator.getName());
            if (result.status() != HealthIndicator.HealthStatus.UNHEALTHY) {
                return true;
            }
        }

        return false;
    }

    /**
     * Readiness probe - is the application ready to serve traffic?
     *
     * Returns true only if ALL components are healthy or degraded.
     */
    public boolean isReady() {
        if (indicators.isEmpty()) {
            return true;
        }

        // All components must be at least degraded
        for (HealthIndicator indicator : indicators.values()) {
            HealthIndicator.HealthCheckResult result = check(indicator.getName());
            if (result.status() == HealthIndicator.HealthStatus.UNHEALTHY) {
                return false;
            }
        }

        return true;
    }

    /**
     * Gets all registered indicator names.
     */
    public Set<String> getIndicatorNames() {
        return new HashSet<>(indicators.keySet());
    }

    /**
     * Clears health check cache.
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Aggregated health result across all components.
     */
    public record AggregatedHealth(
            HealthIndicator.HealthStatus overallStatus,
            Map<String, HealthIndicator.HealthCheckResult> componentResults,
            Instant timestamp
    ) {
        public boolean isHealthy() {
            return overallStatus == HealthIndicator.HealthStatus.HEALTHY;
        }

        public boolean isDegraded() {
            return overallStatus == HealthIndicator.HealthStatus.DEGRADED;
        }

        public boolean isUnhealthy() {
            return overallStatus == HealthIndicator.HealthStatus.UNHEALTHY;
        }

        public int getHealthyCount() {
            return (int) componentResults.values().stream()
                    .filter(r -> r.status() == HealthIndicator.HealthStatus.HEALTHY)
                    .count();
        }

        public int getDegradedCount() {
            return (int) componentResults.values().stream()
                    .filter(r -> r.status() == HealthIndicator.HealthStatus.DEGRADED)
                    .count();
        }

        public int getUnhealthyCount() {
            return (int) componentResults.values().stream()
                    .filter(r -> r.status() == HealthIndicator.HealthStatus.UNHEALTHY)
                    .count();
        }
    }

    /**
     * Cached health check result with timestamp.
     */
    private record CachedResult(
            HealthIndicator.HealthCheckResult result,
            Instant timestamp
    ) {
        boolean isExpired(long ttlMs) {
            return Time.currentMillis() - timestamp.toEpochMilli() > ttlMs;
        }
    }
}
