package com.genesis.p2p.observability.metrics;

import com.genesis.p2p.util.common.Time;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Central registry for metrics (counters, gauges, timers).
 */
public class MetricsRegistry {

    private final String nodeId;
    private final ConcurrentHashMap<String, AtomicLong> counters;
    private final ConcurrentHashMap<String, AtomicLong> gauges;
    private final ConcurrentHashMap<String, TimerMetric> timers;


    public MetricsRegistry() {
        this("default");
    }

    public MetricsRegistry(String nodeId) {
        this.nodeId = nodeId;
        this.counters = new ConcurrentHashMap<>();
        this.gauges = new ConcurrentHashMap<>();
        this.timers = new ConcurrentHashMap<>();
    }

    // ========================= Counters =========================

    public void incrementCounter(String name) {
        incrementCounter(name, 1);
    }

    public void incrementCounter(String name, long delta) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(delta);
    }

    public long getCounter(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0L;
    }

    public void resetCounter(String name) {
        AtomicLong counter = counters.get(name);
        if (counter != null) {
            counter.set(0);
        }
    }

    // ========================= Gauges =========================

    public void setGauge(String name, long value) {
        gauges.computeIfAbsent(name, k -> new AtomicLong(0)).set(value);
    }

    public long getGauge(String name) {
        AtomicLong gauge = gauges.get(name);
        return gauge != null ? gauge.get() : 0L;
    }

    public void incrementGauge(String name) {
        gauges.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void decrementGauge(String name) {
        gauges.computeIfAbsent(name, k -> new AtomicLong(0)).decrementAndGet();
    }

    // ========================= Timers =========================

    public void recordTimer(String name, long durationMs) {
        timers.computeIfAbsent(name, k -> new TimerMetric()).record(durationMs);
    }

    public TimerMetric getTimer(String name) {
        return timers.computeIfAbsent(name, k -> new TimerMetric());
    }

    // ========================= Snapshot =========================

    public MetricsSnapshot getSnapshot() {
        return new MetricsSnapshot(
                nodeId,
                Time.currentMillis(),
                new ConcurrentHashMap<>(counters),
                new ConcurrentHashMap<>(gauges),
                new ConcurrentHashMap<>(timers)
        );
    }

    public void reset() {
        counters.clear();
        gauges.clear();
        timers.clear();
    }

    /**
     * Timer metric with average/min/max tracking.
     */
    public static class TimerMetric {
        private final LongAdder totalTime = new LongAdder();
        private final LongAdder count = new LongAdder();
        private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

        public void record(long duration) {
            totalTime.add(duration);
            count.increment();

            min.updateAndGet(current -> Math.min(current, duration));
            max.updateAndGet(current -> Math.max(current, duration));
        }

        public long getAverage() {
            long cnt = count.sum();
            return cnt > 0 ? totalTime.sum() / cnt : 0;
        }

        public long getMin() {
            long value = min.get();
            return value == Long.MAX_VALUE ? 0 : value;
        }

        public long getMax() {
            long value = max.get();
            return value == Long.MIN_VALUE ? 0 : value;
        }

        public long getCount() {
            return count.sum();
        }

        public long getTotal() {
            return totalTime.sum();
        }
    }

    /**
     * Immutable snapshot of metrics.
     */
    public record MetricsSnapshot(
            String nodeId,
            long timestamp,
            ConcurrentHashMap<String, AtomicLong> counters,
            ConcurrentHashMap<String, AtomicLong> gauges,
            ConcurrentHashMap<String, TimerMetric> timers
    ) {}
}
