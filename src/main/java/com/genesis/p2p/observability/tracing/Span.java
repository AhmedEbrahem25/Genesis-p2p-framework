package com.genesis.p2p.observability.tracing;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a span of work in distributed tracing.
 */
public class Span {

    private final String spanId;
    private final String traceId;
    private final String operationName;
    private final Instant startTime;
    private final Map<String, String> tags;
    private Instant endTime;
    private boolean finished;

    public Span(String traceId, String operationName) {
        this.spanId = TraceIdGenerator.generate();
        this.traceId = traceId;
        this.operationName = operationName;
        this.startTime = Instant.now();
        this.tags = new HashMap<>();
        this.finished = false;
    }

    public Span setTag(String key, String value) {
        tags.put(key, value);
        return this;
    }

    public void finish() {
        if (!finished) {
            this.endTime = Instant.now();
            this.finished = true;
        }
    }

    public Duration getDuration() {
        Instant end = finished ? endTime : Instant.now();
        return Duration.between(startTime, end);
    }

    public String getSpanId() {
        return spanId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getOperationName() {
        return operationName;
    }

    public Map<String, String> getTags() {
        return new HashMap<>(tags);
    }

    @Override
    public String toString() {
        return String.format("Span[id=%s, trace=%s, op=%s, duration=%dms, tags=%s]",
                spanId, traceId, operationName, getDuration().toMillis(), tags);
    }
}
