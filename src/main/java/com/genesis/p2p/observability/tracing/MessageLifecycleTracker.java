// ========================= MessageLifecycleTracker.java =========================
package com.genesis.p2p.observability.tracing;

import com.genesis.p2p.core.Message;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks message lifecycle through the system.
 */
public class MessageLifecycleTracker {

    private final ConcurrentHashMap<String, MessageTrace> activeTraces;
    private final ConcurrentHashMap<String, MessageTrace> completedTraces;
    private static final int MAX_COMPLETED_TRACES = 1000;

    public MessageLifecycleTracker() {
        this.activeTraces = new ConcurrentHashMap<>();
        this.completedTraces = new ConcurrentHashMap<>();
    }

    /**
     * Starts tracking a message, returns trace ID.
     */
    public String startMessageTrace(Message message) {
        String traceId = generateTraceId();
        MessageTrace trace = new MessageTrace(
                traceId,
                message.header().messageId(),
                message.header().type(),
                message.header().from(),
                Instant.now()
        );

        activeTraces.put(traceId, trace);
        return traceId;
    }

    /**
     * Records a stage in message processing.
     */
    public void recordStage(String traceId, String stageName) {
        MessageTrace trace = activeTraces.get(traceId);
        if (trace != null) {
            trace.addStage(stageName, Instant.now());
        }
    }

    /**
     * Completes a trace.
     */
    public void completeTrace(String traceId) {
        MessageTrace trace = activeTraces.remove(traceId);
        if (trace != null) {
            trace.complete(Instant.now());

            // Store in completed (with size limit)
            if (completedTraces.size() < MAX_COMPLETED_TRACES) {
                completedTraces.put(trace.messageId, trace);
            } else {
                // Remove oldest
                completedTraces.values().stream()
                        .min(Comparator.comparing(MessageTrace::startTime))
                        .ifPresent(oldest -> completedTraces.remove(oldest.messageId));
                completedTraces.put(trace.messageId, trace);
            }
        }
    }

    /**
     * Gets trace for a message.
     */
    public Optional<String> getTrace(String messageId) {
        MessageTrace trace = completedTraces.get(messageId);
        if (trace == null) {
            trace = activeTraces.values().stream()
                    .filter(t -> t.messageId.equals(messageId))
                    .findFirst()
                    .orElse(null);
        }

        return Optional.ofNullable(trace).map(MessageTrace::toString);
    }

    private String generateTraceId() {
        return "trace-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Represents a message trace through the system.
     */
    private static class MessageTrace {
        final String traceId;
        final String messageId;
        final String messageType;
        final String sender;
        final Instant startTime;
        final List<TraceStage> stages;
        Instant endTime;

        MessageTrace(String traceId, String messageId, String messageType,
                     String sender, Instant startTime) {
            this.traceId = traceId;
            this.messageId = messageId;
            this.messageType = messageType;
            this.sender = sender;
            this.startTime = startTime;
            this.stages = new ArrayList<>();
        }

        void addStage(String name, Instant timestamp) {
            stages.add(new TraceStage(name, timestamp));
        }

        void complete(Instant endTime) {
            this.endTime = endTime;
        }

        Instant startTime() {
            return startTime;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("MessageTrace[");
            sb.append("traceId=").append(traceId);
            sb.append(", messageId=").append(messageId);
            sb.append(", type=").append(messageType);
            sb.append(", sender=").append(sender);
            sb.append(", duration=");

            if (endTime != null) {
                long duration = java.time.Duration.between(startTime, endTime).toMillis();
                sb.append(duration).append("ms");
            } else {
                sb.append("in-progress");
            }

            sb.append(", stages=[");
            for (int i = 0; i < stages.size(); i++) {
                if (i > 0) sb.append(", ");
                TraceStage stage = stages.get(i);
                long stageTime = i == 0
                        ? java.time.Duration.between(startTime, stage.timestamp).toMillis()
                        : java.time.Duration.between(stages.get(i-1).timestamp, stage.timestamp).toMillis();
                sb.append(stage.name).append("(").append(stageTime).append("ms)");
            }
            sb.append("]]");

            return sb.toString();
        }
    }

    private record TraceStage(String name, Instant timestamp) {}
}