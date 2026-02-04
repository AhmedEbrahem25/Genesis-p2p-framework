package com.genesis.p2p.observability;

import com.genesis.p2p.observability.context.ObservabilityContext;
import com.genesis.p2p.observability.health.ComponentHealthRegistry;
import com.genesis.p2p.observability.health.HealthIndicator;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.metrics.TaggedMetricsRegistry;

/**
 * Unified observability facade providing end-to-end operational insight.
 *
 * Integrates:
 * - Structured logging with automatic correlation
 * - Metrics with cross-component tags
 * - Health checks with lifecycle awareness
 * - Tracing context propagation
 *
 * Usage Pattern:
 * <pre>{@code
 * // Initialize once per node
 * ObservabilityFacade observability = new ObservabilityFacade(nodeId);
 *
 * // Register component health indicators
 * observability.registerHealthIndicator(transportHealthIndicator);
 *
 * // Set component context for automatic tagging
 * observability.setComponent("transport");
 *
 * // Log with automatic correlation
 * observability.getLogger(MyClass.class).info("Message sent", "size", 1024);
 *
 * // Record metrics with component tags
 * observability.recordMetric("messages.sent", 1);
 *
 * // Record timing
 * long start = System.currentTimeMillis();
 * // ... operation ...
 * observability.recordTiming("message.processing", System.currentTimeMillis() - start);
 * }</pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ObservabilityFacade {

    private final String nodeId;
    private final MetricsRegistry legacyMetrics;
    private final TaggedMetricsRegistry taggedMetrics;
    private final ComponentHealthRegistry healthRegistry;

    public ObservabilityFacade(String nodeId) {
        this.nodeId = nodeId;
        this.legacyMetrics = new MetricsRegistry(nodeId);
        this.taggedMetrics = new TaggedMetricsRegistry(nodeId);
        this.healthRegistry = new ComponentHealthRegistry();

        // Set node ID in observability context
        ObservabilityContext.setNodeId(nodeId);
    }

    // ========================= Logging =========================

    /**
     * Gets a logger for the specified class with automatic correlation.
     *
     * All log statements will include:
     * - traceId
     * - spanId
     * - correlationId
     * - nodeId
     * - component (if set)
     */
    public NodeLogger getLogger(Class<?> clazz) {
        return NodeLogger.getLogger(clazz);
    }

    // ========================= Context Management =========================

    /**
     * Sets the component name for current operation.
     *
     * This will be included in all logs and metrics until cleared.
     */
    public void setComponent(String component) {
        ObservabilityContext.setComponent(component);
    }

    /**
     * Clears the component name.
     */
    public void clearComponent() {
        ObservabilityContext.setComponent(null);
    }

    /**
     * Gets current trace ID, creating one if needed.
     */
    public String getTraceId() {
        return ObservabilityContext.getTraceId();
    }

    /**
     * Sets trace ID for correlation.
     */
    public void setTraceId(String traceId) {
        ObservabilityContext.setTraceId(traceId);
    }

    /**
     * Gets correlation ID.
     */
    public String getCorrelationId() {
        return ObservabilityContext.getCorrelationId();
    }

    /**
     * Captures current context for propagation across threads.
     */
    public ObservabilityContext.ContextSnapshot captureContext() {
        return ObservabilityContext.snapshot();
    }

    /**
     * Restores context from snapshot.
     */
    public void restoreContext(ObservabilityContext.ContextSnapshot snapshot) {
        ObservabilityContext.restore(snapshot);
    }

    /**
     * Clears all observability context.
     */
    public void clearContext() {
        ObservabilityContext.clear();
    }

    // ========================= Metrics =========================

    /**
     * Records a metric for current component.
     *
     * Automatically adds component and nodeId tags.
     */
    public void recordMetric(String metricName, long value) {
        String component = ObservabilityContext.getComponent();
        if (component != null) {
            taggedMetrics.incrementCounter(metricName, value,
                    "component", component,
                    "nodeId", nodeId);
        } else {
            taggedMetrics.incrementCounter(metricName, value,
                    "nodeId", nodeId);
        }

        // Also update legacy metrics for backward compatibility
        legacyMetrics.incrementCounter(metricName, value);
    }

    /**
     * Records a timing metric.
     */
    public void recordTiming(String operation, long durationMs) {
        String component = ObservabilityContext.getComponent();
        if (component != null) {
            taggedMetrics.recordTimer(operation + ".duration", durationMs,
                    "component", component,
                    "nodeId", nodeId);
        } else {
            taggedMetrics.recordTimer(operation + ".duration", durationMs,
                    "nodeId", nodeId);
        }

        // Also update legacy metrics
        legacyMetrics.recordTimer(operation + ".duration", durationMs);
    }

    /**
     * Sets a gauge value.
     */
    public void setGauge(String gaugeName, long value) {
        String component = ObservabilityContext.getComponent();
        if (component != null) {
            taggedMetrics.setGauge(gaugeName, value,
                    "component", component,
                    "nodeId", nodeId);
        } else {
            taggedMetrics.setGauge(gaugeName, value,
                    "nodeId", nodeId);
        }

        legacyMetrics.setGauge(gaugeName, value);
    }

    /**
     * Gets legacy metrics registry for backward compatibility.
     */
    public MetricsRegistry getMetricsRegistry() {
        return legacyMetrics;
    }

    /**
     * Gets tagged metrics registry for advanced usage.
     */
    public TaggedMetricsRegistry getTaggedMetrics() {
        return taggedMetrics;
    }

    // ========================= Health =========================

    /**
     * Registers a component health indicator.
     */
    public void registerHealthIndicator(HealthIndicator indicator) {
        healthRegistry.register(indicator);
    }

    /**
     * Unregisters a health indicator.
     */
    public void unregisterHealthIndicator(String name) {
        healthRegistry.unregister(name);
    }

    /**
     * Checks health of specific component.
     */
    public HealthIndicator.HealthCheckResult checkHealth(String componentName) {
        return healthRegistry.check(componentName);
    }

    /**
     * Checks health of all components.
     */
    public ComponentHealthRegistry.AggregatedHealth checkAllHealth() {
        return healthRegistry.checkAll();
    }

    /**
     * Liveness probe - is the system alive?
     */
    public boolean isAlive() {
        return healthRegistry.isAlive();
    }

    /**
     * Readiness probe - is the system ready?
     */
    public boolean isReady() {
        return healthRegistry.isReady();
    }

    /**
     * Gets health registry for advanced usage.
     */
    public ComponentHealthRegistry getHealthRegistry() {
        return healthRegistry;
    }

    // ========================= Lifecycle =========================

    /**
     * Cleans up all observability resources.
     */
    public void shutdown() {
        ObservabilityContext.clear();
        healthRegistry.clearCache();
        // Metrics are preserved for final collection
    }

    /**
     * Gets node ID.
     */
    public String getNodeId() {
        return nodeId;
    }
}
