package com.genesis.p2p.core.handlers.processors.alert;

import com.genesis.p2p.events.core.EventBus;
import com.genesis.p2p.events.core.GenericEvent;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WarningProcessor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WarningProcessor.class);

    private static final long MAX_WARNING_AGE_MS = 60 * 60 * 1000;       // 1 hour
    private static final int ESCALATION_THRESHOLD = 3;
    private static final long ESCALATION_WINDOW_MS = 5 * 60 * 1000;     // 5 min

    private final ConcurrentHashMap<String, Warning> warnings;
    private final ConcurrentHashMap<String, Integer> warningThresholds;
    private final EventBus eventBus;
    private final MetricsRegistry metricsRegistry;

    public WarningProcessor(EventBus eventBus, MetricsRegistry metricsRegistry) {
        this.warnings = new ConcurrentHashMap<>();
        this.warningThresholds = new ConcurrentHashMap<>();
        this.eventBus = eventBus;
        this.metricsRegistry = metricsRegistry;
        initializeThresholds();
        setupEventListeners();
        log.info("WarningProcessor initialized");
    }

    private void initializeThresholds() {
        warningThresholds.put("cpu_usage", 80);
        warningThresholds.put("memory_usage", 85);
        warningThresholds.put("connection_count", 1000);
        warningThresholds.put("error_rate", 5);
    }

    private void setupEventListeners() {
        eventBus.subscribe("warning:trigger", event -> {
            if (event.getPayload() instanceof Warning) {
                processWarning((Warning) event.getPayload());
            }
        });
    }

    public void processWarning(Warning warning) {
        if (warning == null || warning.id == null) {
            log.warn("Received invalid warning");
            return;
        }

        Integer threshold = warningThresholds.get(warning.type);

        if (threshold != null && warning.currentValue != null) {
            if (warning.currentValue > threshold) {
                log.warn("Warning exceeded threshold: {} [{} > {}]", warning.type, warning.currentValue, threshold);
            }
        }

        if (shouldEscalate(warning)) {
            escalateToAlert(warning);
            return;
        }

        warnings.put(warning.id, warning);
        log.debug("Warning processed: {} - {} [type={}]", warning.id, warning.message, warning.type);

        metricsRegistry.incrementCounter("warnings.total");
        metricsRegistry.incrementCounter("warnings.type." + warning.type);
        metricsRegistry.incrementCounter("warnings.source." + sanitizeMetricName(warning.source));

        eventBus.publish(new GenericEvent("warning:processed", warning));
        cleanupOldWarnings();
    }

    private boolean shouldEscalate(Warning warning) {
        long now = Instant.now().toEpochMilli();
        long recentCount = warnings.values().stream()
                .filter(w -> w.type.equals(warning.type))
                .filter(w -> now - w.timestamp.toEpochMilli() < ESCALATION_WINDOW_MS)
                .count();

        return recentCount >= ESCALATION_THRESHOLD;
    }

    private void escalateToAlert(Warning warning) {
        log.warn("Escalating warning to alert: {} [type={}]", warning.id, warning.type);

        AlertProcessor.Alert alert = new AlertProcessor.Alert(
                "escalated_" + warning.id,
                AlertProcessor.Severity.HIGH,
                "Escalated: " + warning.message,
                warning.source,
                Instant.now(),
                Map.of("originalWarning", warning)
        );

        eventBus.publish(new GenericEvent("alert:trigger", alert));
        metricsRegistry.incrementCounter("warnings.escalated");
    }

    private void cleanupOldWarnings() {
        long now = Instant.now().toEpochMilli();
        warnings.entrySet().removeIf(entry ->
                now - entry.getValue().timestamp.toEpochMilli() > MAX_WARNING_AGE_MS
        );
    }

    public List<Warning> getActiveWarnings(String type) {
        List<Warning> result = new ArrayList<>(warnings.values());
        if (type != null) {
            result.removeIf(w -> !w.type.equals(type));
        }
        return result;
    }

    private String sanitizeMetricName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    @Override
    public void close() {
        warnings.clear();
        log.info("WarningProcessor closed");
    }

    public static class Warning {
        public final String id;
        public final String type;
        public final String message;
        public final String source;
        public final Instant timestamp;
        public final Integer threshold;
        public final Integer currentValue;

        public Warning(String id, String type, String message, String source,
                       Instant timestamp, Integer threshold, Integer currentValue) {
            this.id = id;
            this.type = type;
            this.message = message;
            this.source = source;
            this.timestamp = timestamp != null ? timestamp : Instant.now();
            this.threshold = threshold;
            this.currentValue = currentValue;
        }
    }
}
