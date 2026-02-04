
// ========================= TracingContext.java =========================
package com.genesis.p2p.observability.tracing;

import java.util.Stack;

/**
 * Thread-local tracing context.
 */
public class TracingContext {

    private static final ThreadLocal<Stack<Span>> spanStack =
            ThreadLocal.withInitial(Stack::new);

    private static final ThreadLocal<String> traceId = new ThreadLocal<>();

    /**
     * Starts a new span in current trace.
     */
    public static Span startSpan(String operationName) {
        String currentTraceId = traceId.get();
        if (currentTraceId == null) {
            currentTraceId = TraceIdGenerator.generate();
            traceId.set(currentTraceId);
        }

        Span span = new Span(currentTraceId, operationName);
        spanStack.get().push(span);
        return span;
    }

    /**
     * Finishes current span.
     */
    public static void finishSpan() {
        Stack<Span> stack = spanStack.get();
        if (!stack.isEmpty()) {
            Span span = stack.pop();
            span.finish();
        }
    }

    /**
     * Gets current span.
     */
    public static Span getCurrentSpan() {
        Stack<Span> stack = spanStack.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * Gets current trace ID.
     */
    public static String getCurrentTraceId() {
        return traceId.get();
    }

    /**
     * Sets trace ID for current thread.
     */
    public static void setTraceId(String id) {
        traceId.set(id);
    }

    /**
     * Clears tracing context.
     */
    public static void clear() {
        spanStack.get().clear();
        traceId.remove();
    }
}