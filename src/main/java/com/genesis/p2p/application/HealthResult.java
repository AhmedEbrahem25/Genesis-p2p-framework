package com.genesis.p2p.application;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map; /**
 * Result of a health check.
 */
  public class HealthResult {
    private final HealthStatus status;
    private final String message;
    private final Map<String, Object> details;
    private Duration duration;

    private HealthResult(HealthStatus status, String message) {
        this.status = status;
        this.message = message;
        this.details = new HashMap<>();
    }

    public static HealthResult healthy(String message) {
        return new HealthResult(HealthStatus.HEALTHY, message);
    }

    public static HealthResult degraded(String message) {
        return new HealthResult(HealthStatus.DEGRADED, message);
    }

    public static HealthResult unhealthy(String message) {
        return new HealthResult(HealthStatus.UNHEALTHY, message);
    }

    public HealthResult withDetail(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public HealthResult withDuration(Duration duration) {
        this.duration = duration;
        return this;
    }

    public HealthStatus getStatus() { return status; }
    public String getMessage() { return message; }
    public Map<String, Object> getDetails() { return details; }
    public Duration getDuration() { return duration; }
}
