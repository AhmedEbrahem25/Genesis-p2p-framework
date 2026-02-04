
package com.genesis.p2p.observability.metrics;

import com.genesis.p2p.util.common.Time;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced metrics registry with tag/label support for multi-dimensional metrics.
 *
 * Provides:
 * - Tagged metrics for cross-component correlation
 * - Component-scoped metrics (transport, discovery, security, etc.)
 * - Hierarchical metric naming
 * - Metric aggregation by tags
 * - Production-grade observability
 *
 * Metric Naming Convention:
 * - {component}.{operation}.{metric_type}
 * - Example: "transport.tcp.messages.sent", "discovery.multicast.peers.found"
 *
 * Standard Tags:
 * - component: transport, discovery, security, messaging, etc.
 * - operation: send, receive, process, etc.
 * - status: success, failure, timeout, etc.
 * - nodeId: node identifier
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class TaggedMetricsRegistry {

    private final String nodeId;
    private final Map<MetricKey, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<MetricKey, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final Map<MetricKey, TimerMetric> timers = new ConcurrentHashMap<>();

    public TaggedMetricsRegistry(String nodeId) {
        this.nodeId = nodeId;
    }

    // ========================= Tagged Counters =========================

    /**
     * Increments a counter with tags.
     *
     * @param name metric name (e.g., "messages.sent")
     * @param tags key-value pairs for tags (must be even number)
     */
    public void incrementCounter(String name, String... tags) {
        incrementCounter(name, 1, tags);
    }

    /**
     * Increments a counter by delta with tags.
     */
    public void incrementCounter(String name, long delta, String... tags) {
        MetricKey key = new MetricKey(name, parseTags(tags));
        counters.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(delta);
    }

    /**
     * Gets counter value for specific tags.
     */
    public long getCounter(String name, String... tags) {
        MetricKey key = new MetricKey(name, parseTags(tags));
        AtomicLong counter = counters.get(key);
        return counter != null ? counter.get() : 0L;
    }

    /**
     * Gets all counter values for a metric name (all tag combinations).
     */
    public Map<Map<String, String>, Long> getAllCounters(String name) {
        Map<Map<String, String>, Long> result = new HashMap<>();
        counters.forEach((key, value) -> {
            if (key.name.equals(name)) {
                result.put(key.tags, value.get());
            }
        });
        return result;
    }

    // ========================= Tagged Gauges =========================

    /**
     * Sets a gauge value with tags.
     */
    public void setGauge(String name, long value, String... tags) {
        MetricKey key = new MetricKey(name, parseTags(tags));
        gauges.computeIfAbsent(key, k -> new AtomicLong(0)).set(value);
    }

    /**
     * Gets gauge value for specific tags.
     */
    public long getGauge(String name, String... tags) {
        MetricKey key = new MetricKey(name, parseTags(tags));
        AtomicLong gauge = gauges.get(key);
        return gauge != null ? gauge.get() : 0L;
    }

    /**
     * Increments a gauge.
     */
    public void incrementGauge(String name, String... tags) {
        MetricKey key = new MetricKey(name, parseTags(tags));
        gauges.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Decrements a gauge.
     */
    public void decrementGauge(String name, String... tags) {
        MetricKey key = new MetricKey(name, parseTags(tags));
        gauges.computeIfAbsent(key, k -> new AtomicLong(0)).decrementAndGet();
    }

    // ========================= Tagged Timers =========================

    /**
     * Records a timer duration with tags.
     */
    public void recordTimer(String name, long durationMs, String... tags) {
        MetricKey key = new MetricKey(name, parseTags(tags));
        timers.computeIfAbsent(key, k -> new TimerMetric()).record(durationMs);
    }

    /**
     * Gets timer statistics for specific tags.
     */
    public TimerStats getTimer(String name, String... tags) {
        MetricKey key = new MetricKey(name, parseTags(tags));
        TimerMetric timer = timers.get(key);
        return timer != null ? timer.getStats() : new TimerStats(0, 0, 0, 0, 0);
    }

    // ========================= Component Metrics =========================

    /**
     * Records a component operation metric.
     *
     * Automatically adds nodeId and component tags.
     */
    public void recordComponentMetric(String component, String operation, String metricType, long value) {
        String name = component + "." + operation + "." + metricType;
        incrementCounter(name, value, "component", component, "nodeId", nodeId);
    }

    /**
     * Records component operation timing.
     */
    public void recordComponentTiming(String component, String operation, long durationMs) {
        String name = component + "." + operation + ".duration";
        recordTimer(name, durationMs, "component", component, "nodeId", nodeId);
    }

    // ========================= Aggregation =========================

    /**
     * Gets snapshot of all metrics.
     */
    public MetricsSnapshot getSnapshot() {
        return new MetricsSnapshot(
                nodeId,
                Time.currentMillis(),
                new HashMap<>(counters),
                new HashMap<>(gauges),
                new HashMap<>(timers)
        );
    }

    /**
     * Gets metrics for specific component.
     */
    public Map<String, Object> getComponentMetrics(String component) {
        Map<String, Object> result = new HashMap<>();

        counters.forEach((key, value) -> {
            if (component.equals(key.tags.get("component"))) {
                result.put(key.name + formatTags(key.tags), value.get());
            }
        });

        return result;
    }

    /**
     * Resets all metrics.
     */
    public void reset() {
        counters.clear();
        gauges.clear();
        timers.clear();
    }

    // ========================= Helper Methods =========================

    private Map<String, String> parseTags(String... tags) {
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException("Tags must be key-value pairs");
        }

        Map<String, String> tagMap = new LinkedHashMap<>();
        for (int i = 0; i < tags.length; i += 2) {
            tagMap.put(tags[i], tags[i + 1]);
        }
        return tagMap;
    }

    private String formatTags(Map<String, String> tags) {
        if (tags.isEmpty()) return "";

        StringJoiner sj = new StringJoiner(",", "{", "}");
        tags.forEach((k, v) -> sj.add(k + "=" + v));
        return sj.toString();
    }

    // ========================= Data Classes =========================

    /**
     * Metric key with name and tags.
     */
    private record MetricKey(String name, Map<String, String> tags) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MetricKey that)) return false;
            return Objects.equals(name, that.name) && Objects.equals(tags, that.tags);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, tags);
        }
    }

    /**
     * Timer metric with statistics.
     */
    private static class TimerMetric {
        private final AtomicLong totalTime = new AtomicLong(0);
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

        void record(long duration) {
            totalTime.addAndGet(duration);
            count.incrementAndGet();
            min.updateAndGet(current -> Math.min(current, duration));
            max.updateAndGet(current -> Math.max(current, duration));
        }

        TimerStats getStats() {
            long cnt = count.get();
            long total = totalTime.get();
            long average = cnt > 0 ? total / cnt : 0;
            long minValue = min.get();
            long maxValue = max.get();

            return new TimerStats(
                    cnt,
                    total,
                    average,
                    minValue == Long.MAX_VALUE ? 0 : minValue,
                    maxValue == Long.MIN_VALUE ? 0 : maxValue
            );
        }
    }

    /**
     * Timer statistics.
     */
    public record TimerStats(
            long count,
            long totalMs,
            long averageMs,
            long minMs,
            long maxMs
    ) {}

    /**
     * Metrics snapshot.
     */
    public record MetricsSnapshot(
            String nodeId,
            long timestamp,
            Map<MetricKey, AtomicLong> counters,
            Map<MetricKey, AtomicLong> gauges,
            Map<MetricKey, TimerMetric> timers
    ) {}
}
