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

/**
 * Handles cluster-wide critical alerts.
 * Processes, stores, and propagates alerts to monitoring subsystems.
 */
public class AlertProcessor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlertProcessor.class);
    private static final long MAX_ALERT_AGE_MS = 24 * 60 * 60 * 1000; // 24 hours

    private final ConcurrentHashMap<String, Alert> alerts;
    private final EventBus eventBus;
    private final MetricsRegistry metricsRegistry;

    public AlertProcessor(EventBus eventBus, MetricsRegistry metricsRegistry) {
        this.alerts = new ConcurrentHashMap<>();
        this.eventBus = eventBus;
        this.metricsRegistry = metricsRegistry;
        setupEventListeners();
        log.info("AlertProcessor initialized");
    }

    private void setupEventListeners() {
        eventBus.subscribe("alert:trigger", event -> {
            if (event.getPayload() instanceof Alert) {
                processAlert((Alert) event.getPayload());
            }
        });
    }

    /**
     * Process and propagate an alert.
     */
    public void processAlert(Alert alert) {
        if (alert == null || alert.id == null) {
            log.warn("Received invalid alert");
            return;
        }

        // Store alert
        alerts.put(alert.id, alert);
        log.info("Alert processed: {} - {} [severity={}]", alert.id, alert.message, alert.severity);

        // Update metrics
        metricsRegistry.incrementCounter("alerts.total");
        metricsRegistry.incrementCounter("alerts.severity." + alert.severity.name().toLowerCase());
        metricsRegistry.incrementCounter("alerts.source." + sanitizeMetricName(alert.source));

        // Propagate to monitoring subsystem
        propagateToMonitoring(alert);

        // Broadcast to cluster
        eventBus.publish(new GenericEvent("alert:processed", alert));

        // Cleanup old alerts
        cleanupOldAlerts();
    }

    private void propagateToMonitoring(Alert alert) {
        eventBus.publish(new GenericEvent("monitoring:alert", Map.of(
                "type", "alert",
                "data", alert,
                "timestamp", Instant.now().toEpochMilli()
        )));
    }

    private void cleanupOldAlerts() {
        long now = Instant.now().toEpochMilli();
        alerts.entrySet().removeIf(entry ->
                now - entry.getValue().timestamp.toEpochMilli() > MAX_ALERT_AGE_MS
        );
    }

    /**
     * Get active alerts, optionally filtered by severity.
     */
    public List<Alert> getActiveAlerts(Severity severity) {
        List<Alert> result = new ArrayList<>(alerts.values());
        if (severity != null) {
            result.removeIf(a -> a.severity != severity);
        }
        return result;
    }

    /**
     * Clear a specific alert.
     */
    public void clearAlert(String alertId) {
        if (alerts.remove(alertId) != null) {
            eventBus.publish(new GenericEvent("alert:cleared", Map.of("alertId", alertId)));
            log.debug("Alert cleared: {}", alertId);
        }
    }

    private String sanitizeMetricName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    @Override
    public void close() {
        alerts.clear();
        log.info("AlertProcessor closed");
    }

    /**
     * Alert Data Class.
     */
    public static class Alert {
        public final String id;
        public final Severity severity;
        public final String message;
        public final String source;
        public final Instant timestamp;
        public final Map<String, Object> metadata;

        public Alert(String id, Severity severity, String message, String source,
                     Instant timestamp, Map<String, Object> metadata) {
            this.id = id;
            this.severity = severity;
            this.message = message;
            this.source = source;
            this.timestamp = timestamp != null ? timestamp : Instant.now();
            this.metadata = metadata != null ? metadata : Map.of();
        }
    }

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }
}
