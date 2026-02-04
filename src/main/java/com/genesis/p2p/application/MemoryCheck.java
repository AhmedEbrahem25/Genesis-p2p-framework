package com.genesis.p2p.application;

/**
 * Checks memory usage.
 */
public class MemoryCheck implements HealthCheckStrategy {
    private final double threshold;

    public MemoryCheck(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public HealthResult check() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double usageRatio = (double) usedMemory / maxMemory;

        HealthResult result;
        if (usageRatio >= threshold) {
            result = HealthResult.unhealthy(
                    String.format("Memory usage critical: %.1f%%", usageRatio * 100));
        } else if (usageRatio >= threshold * 0.8) {
            result = HealthResult.degraded(
                    String.format("Memory usage high: %.1f%%", usageRatio * 100));
        } else {
            result = HealthResult.healthy(
                    String.format("Memory usage normal: %.1f%%", usageRatio * 100));
        }

        return result
                .withDetail("usedMemory", usedMemory)
                .withDetail("maxMemory", maxMemory)
                .withDetail("usagePercent", usageRatio * 100);
    }
}
