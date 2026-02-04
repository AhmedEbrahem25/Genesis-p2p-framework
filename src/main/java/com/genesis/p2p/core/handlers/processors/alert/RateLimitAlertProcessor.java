package com.genesis.p2p.core.handlers.processors.alert;

import com.genesis.p2p.events.core.EventBus;
import com.genesis.p2p.events.core.GenericEvent;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.util.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors and alerts on rate limit violations.
 */
public class RateLimitAlertProcessor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAlertProcessor.class);
    private static final long HISTORY_RETENTION_MS = 60 * 60 * 1000; // 1 hour
    private static final long ALERT_WINDOW_MS = 5 * 60 * 1000; // 5 minutes
    private static final int ALERT_THRESHOLD = 5;
    private static final int STRICT_LIMIT_THRESHOLD = 10;

    private final EventBus eventBus;
    private final MetricsRegistry metricsRegistry;
    private final ConcurrentHashMap<String, List<RateLimitViolation>> violations;

    public RateLimitAlertProcessor(EventBus eventBus, MetricsRegistry metricsRegistry) {
        this.eventBus = eventBus;
        this.metricsRegistry = metricsRegistry;
        this.violations = new ConcurrentHashMap<>();
        setupEventListeners();
        log.info("RateLimitAlertProcessor initialized");
    }

    private void setupEventListeners() {
        eventBus.subscribe("rate limit:violation", event -> {
            if (event.getPayload() instanceof RateLimitViolation) {
                processViolation((RateLimitViolation) event.getPayload());
            }
        });
        eventBus.subscribe("rate limit:exceeded", event -> {
            if (event.getPayload() instanceof Map) {
                processExceeded((Map<String, String>) event.getPayload());
            }
        });
    }

    public void processViolation(RateLimitViolation violation) {
        if (violation == null || violation.identifier == null) {
            log.warn("Received invalid rate limit violation");
            return;
        }

        recordViolation(violation);

        metricsRegistry.incrementCounter("rate limit.violations");
        metricsRegistry.incrementCounter("rate limit.violations.type." + violation.type.name().toLowerCase());
        metricsRegistry.incrementCounter("rate limit.violations.endpoint." + sanitizeEndpoint(violation.endpoint));
        metricsRegistry.incrementCounter("rate limit.violations.identifier." + sanitizeId(violation.identifier));

        if (shouldGenerateAlert(violation.identifier)) {
            generateRateLimitAlert(violation);
        }

        if (shouldApplyStrictLimit(violation.identifier)) {
            applyStrictLimit(violation.identifier);
        }

        eventBus.publish(new GenericEvent("monitoring:rate_limit", violation));

        log.debug("Rate limit violation processed: {} on {} [type={}, limit={}, current={}]",
                violation.identifier, violation.endpoint, violation.type, violation.limit, violation.currentCount);
    }

    private void recordViolation(RateLimitViolation violation) {
        violations.computeIfAbsent(violation.identifier, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(violation);
        cleanupViolations(violation.identifier);
    }

    private void cleanupViolations(String identifier) {
        List<RateLimitViolation> history = violations.get(identifier);
        if (history != null) {
            long cutoff = System.currentTimeMillis() - HISTORY_RETENTION_MS;
            synchronized (history) {
                history.removeIf(v -> v.timestamp < cutoff);
            }
        }
    }

    private boolean shouldGenerateAlert(String identifier) {
        List<RateLimitViolation> history = violations.get(identifier);
        if (history == null) return false;

        long now = System.currentTimeMillis();
        long recentCount;

        synchronized (history) {
            recentCount = history.stream()
                    .filter(v -> now - v.timestamp < ALERT_WINDOW_MS)
                    .count();
        }

        return recentCount >= ALERT_THRESHOLD;
    }

    private void generateRateLimitAlert(RateLimitViolation violation) {
        List<RateLimitViolation> history = violations.get(violation.identifier);
        long recentCount = 0;

        if (history != null) {
            long now = System.currentTimeMillis();
            synchronized (history) {
                recentCount = history.stream()
                        .filter(v -> now - v.timestamp < ALERT_WINDOW_MS)
                        .count();
            }
        }

        AlertProcessor.Severity severity = violation.type == ViolationType.HARD
                ? AlertProcessor.Severity.HIGH
                : AlertProcessor.Severity.MEDIUM;

        AlertProcessor.Alert alert = new AlertProcessor.Alert(
                "rate limit_" + violation.identifier + "_" + Instant.now().toEpochMilli(),
                severity,
                String.format("Rate limit violations for %s: %d in %d minutes",
                        violation.identifier, recentCount, ALERT_WINDOW_MS / 60000),
                "RateLimitAlertProcessor",
                Instant.now(),
                Map.of(
                        "identifier", violation.identifier,
                        "violationCount", recentCount,
                        "latestViolation", violation
                )
        );

        eventBus.publish(new GenericEvent("alert:trigger", alert));
        metricsRegistry.incrementCounter("rate limit.alerts");

        log.warn("Rate limit alert generated for {}: {} violations", violation.identifier, recentCount);
    }

    private boolean shouldApplyStrictLimit(String identifier) {
        List<RateLimitViolation> history = violations.get(identifier);
        if (history == null) return false;

        long now = Time.currentMillis();
        long recentCount;

        synchronized (history) {
            recentCount = history.stream()
                    .filter(v -> now - v.timestamp < ALERT_WINDOW_MS)
                    .count();
        }

        return recentCount >= STRICT_LIMIT_THRESHOLD;
    }

    private void applyStrictLimit(String identifier) {
        log.warn("Applying strict rate limit for {}", identifier);

        eventBus.publish(new GenericEvent("rate limit:strict_limit", Map.of(
                "identifier", identifier,
                "timestamp", Instant.now().toEpochMilli()
        )));

        metricsRegistry.incrementCounter("rate limit.strict_limits_applied");

        AlertProcessor.Alert alert = new AlertProcessor.Alert(
                "rate limit_strict_" + identifier + "_" + Instant.now().toEpochMilli(),
                AlertProcessor.Severity.CRITICAL,
                String.format("Strict rate limit applied to %s due to excessive violations", identifier),
                "RateLimitAlertProcessor",
                Instant.now(),
                Map.of("identifier", identifier)
        );

        eventBus.publish(new GenericEvent("alert:trigger", alert));
    }

    public void processExceeded(Map<String, String> data) {
        String identifier = data.get("identifier");
        String endpoint = data.get("endpoint");

        if (identifier == null || endpoint == null) {
            log.warn("Received invalid rate limit exceeded event");
            return;
        }

        WarningProcessor.Warning warning = new WarningProcessor.Warning(
                "rate limit_warn_" + identifier + "_" + Instant.now().toEpochMilli(),
                "rate_limit_exceeded",
                String.format("%s exceeded rate limit on %s", identifier, endpoint),
                "RateLimitAlertProcessor",
                Instant.now(),
                null,
                null
        );

        eventBus.publish(new GenericEvent("warning:trigger", warning));
        metricsRegistry.incrementCounter("rate limit.warnings");

        log.debug("Rate limit warning generated for {} on {}", identifier, endpoint);
    }

    public List<RateLimitViolation> getViolationHistory(String identifier) {
        List<RateLimitViolation> history = violations.get(identifier);
        return history != null ? new ArrayList<>(history) : List.of();
    }

    public List<Map.Entry<String, Integer>> getTopViolators(int limit) {
        Map<String, Integer> violatorCounts = new HashMap<>();
        for (Map.Entry<String, List<RateLimitViolation>> entry : violations.entrySet()) {
            violatorCounts.put(entry.getKey(), entry.getValue().size());
        }

        return violatorCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(limit)
                .toList();
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String sanitizeEndpoint(String endpoint) {
        return endpoint.replaceAll("[^a-zA-Z0-9_/]", "_");
    }

    @Override
    public void close() {
        violations.clear();
        log.info("RateLimitAlertProcessor closed");
    }

    public static class RateLimitViolation {
        public final String identifier;
        public final String endpoint;
        public final int limit;
        public final int currentCount;
        public final long timestamp;
        public final ViolationType type;

        public RateLimitViolation(String identifier, String endpoint, int limit,
                                  int currentCount, long timestamp, ViolationType type) {
            this.identifier = identifier;
            this.endpoint = endpoint;
            this.limit = limit;
            this.currentCount = currentCount;
            this.timestamp = timestamp;
            this.type = type != null ? type : ViolationType.SOFT;
        }
    }

    public enum ViolationType {
        SOFT,
        HARD
    }
}
