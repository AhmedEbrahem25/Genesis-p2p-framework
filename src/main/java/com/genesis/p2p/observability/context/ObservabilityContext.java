package com.genesis.p2p.observability.context;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Centralized observability context for correlation across components.
 *
 * Provides end-to-end tracing with:
 * - Trace ID: Unique identifier for entire operation flow
 * - Span ID: Identifier for specific operation segment
 * - Correlation ID: For cross-component request tracking
 * - Baggage: Key-value pairs propagated across boundaries
 *
 * Thread-safe using ThreadLocal storage.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ObservabilityContext {

    private static final ThreadLocal<Context> context = ThreadLocal.withInitial(Context::new);

    /**
     * Gets current trace ID, creating one if needed.
     */
    public static String getTraceId() {
        Context ctx = context.get();
        if (ctx.traceId == null) {
            ctx.traceId = generateId();
        }
        return ctx.traceId;
    }

    /**
     * Sets trace ID for current thread.
     */
    public static void setTraceId(String traceId) {
        context.get().traceId = traceId;
    }

    /**
     * Gets current span ID.
     */
    public static String getSpanId() {
        return context.get().spanId;
    }

    /**
     * Sets span ID for current thread.
     */
    public static void setSpanId(String spanId) {
        context.get().spanId = spanId;
    }

    /**
     * Gets correlation ID for cross-component tracking.
     */
    public static String getCorrelationId() {
        Context ctx = context.get();
        if (ctx.correlationId == null) {
            ctx.correlationId = generateId();
        }
        return ctx.correlationId;
    }

    /**
     * Sets correlation ID.
     */
    public static void setCorrelationId(String correlationId) {
        context.get().correlationId = correlationId;
    }

    /**
     * Gets node ID for the current operation.
     */
    public static String getNodeId() {
        return context.get().nodeId;
    }

    /**
     * Sets node ID for current context.
     */
    public static void setNodeId(String nodeId) {
        context.get().nodeId = nodeId;
    }

    /**
     * Gets component name (e.g., "transport", "discovery", "security").
     */
    public static String getComponent() {
        return context.get().component;
    }

    /**
     * Sets component name for current operation.
     */
    public static void setComponent(String component) {
        context.get().component = component;
    }

    /**
     * Adds baggage item to propagate across boundaries.
     */
    public static void putBaggage(String key, String value) {
        context.get().baggage.put(key, value);
    }

    /**
     * Gets baggage item.
     */
    public static String getBaggage(String key) {
        return context.get().baggage.get(key);
    }

    /**
     * Gets all baggage items.
     */
    public static Map<String, String> getAllBaggage() {
        return new HashMap<>(context.get().baggage);
    }

    /**
     * Creates a snapshot of current context for propagation.
     */
    public static ContextSnapshot snapshot() {
        Context ctx = context.get();
        return new ContextSnapshot(
                ctx.traceId,
                ctx.spanId,
                ctx.correlationId,
                ctx.nodeId,
                ctx.component,
                new HashMap<>(ctx.baggage)
        );
    }

    /**
     * Restores context from snapshot.
     */
    public static void restore(ContextSnapshot snapshot) {
        if (snapshot == null) return;

        Context ctx = context.get();
        ctx.traceId = snapshot.traceId();
        ctx.spanId = snapshot.spanId();
        ctx.correlationId = snapshot.correlationId();
        ctx.nodeId = snapshot.nodeId();
        ctx.component = snapshot.component();
        ctx.baggage.clear();
        ctx.baggage.putAll(snapshot.baggage());
    }

    /**
     * Clears all context for current thread.
     */
    public static void clear() {
        context.get().clear();
    }

    /**
     * Removes context completely (for thread cleanup).
     */
    public static void remove() {
        context.remove();
    }

    /**
     * Generates a unique ID for tracing.
     */
    private static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Internal context holder.
     */
    private static class Context {
        String traceId;
        String spanId;
        String correlationId;
        String nodeId;
        String component;
        Map<String, String> baggage = new HashMap<>();

        void clear() {
            traceId = null;
            spanId = null;
            correlationId = null;
            nodeId = null;
            component = null;
            baggage.clear();
        }
    }

    /**
     * Immutable snapshot of observability context.
     */
    public record ContextSnapshot(
            String traceId,
            String spanId,
            String correlationId,
            String nodeId,
            String component,
            Map<String, String> baggage
    ) {}
}
