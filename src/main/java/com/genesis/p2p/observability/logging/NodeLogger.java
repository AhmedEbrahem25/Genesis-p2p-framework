package com.genesis.p2p.observability.logging;

import com.genesis.p2p.observability.context.ObservabilityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Structured logger with automatic correlation ID injection and MDC support.
 *
 * Features:
 * - Automatic traceId/spanId/correlationId injection into all logs
 * - Structured key-value logging
 * - MDC propagation across threads
 * - Component and node identification
 * - Production-grade correlation for distributed tracing
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class NodeLogger {

    private final Logger delegate;

    private NodeLogger(Logger delegate) {
        this.delegate = delegate;
    }

    public static NodeLogger getLogger(Class<?> clazz) {
        return new NodeLogger(LoggerFactory.getLogger(clazz));
    }

    public void info(String message, Object... keyValues) {
        logWithContext(() -> delegate.info(message), keyValues);
    }

    public void warn(String message, Object... keyValues) {
        logWithContext(() -> delegate.warn(message), keyValues);
    }

    public void error(String message, Throwable t, Object... keyValues) {
        logWithContext(() -> delegate.error(message, t), keyValues);
    }

    public void error(String message, Object... keyValues) {
        logWithContext(() -> delegate.error(message), keyValues);
    }

    public void debug(String message, Object... keyValues) {
        logWithContext(() -> delegate.debug(message), keyValues);
    }

    public void debug(String message, Throwable t) {
        // Even debug with throwable should have correlation
        injectCorrelationIds();
        try {
            delegate.debug(message, t);
        } finally {
            clearCorrelationIds();
        }
    }

    /**
     * Logs with full observability context.
     *
     * Automatically injects:
     * - traceId: End-to-end trace identifier
     * - spanId: Current operation segment
     * - correlationId: Cross-component correlation
     * - nodeId: Node identifier
     * - component: Component name
     * - Plus all user-provided key-value pairs
     */
    private void logWithContext(Runnable logAction, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Key-values must be in pairs");
        }

        // Inject correlation IDs automatically
        injectCorrelationIds();

        try {
            // Add user-provided key-values
            for (int i = 0; i < keyValues.length; i += 2) {
                MDC.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
            }
            logAction.run();
        } finally {
            // Clean up user key-values
            for (int i = 0; i < keyValues.length; i += 2) {
                MDC.remove(String.valueOf(keyValues[i]));
            }
            // Clear correlation IDs
            clearCorrelationIds();
        }
    }

    /**
     * Injects correlation IDs from ObservabilityContext into MDC.
     */
    private void injectCorrelationIds() {
        String traceId = ObservabilityContext.getTraceId();
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }

        String spanId = ObservabilityContext.getSpanId();
        if (spanId != null) {
            MDC.put("spanId", spanId);
        }

        String correlationId = ObservabilityContext.getCorrelationId();
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }

        String nodeId = ObservabilityContext.getNodeId();
        if (nodeId != null) {
            MDC.put("nodeId", nodeId);
        }

        String component = ObservabilityContext.getComponent();
        if (component != null) {
            MDC.put("component", component);
        }
    }

    /**
     * Clears correlation IDs from MDC.
     */
    private void clearCorrelationIds() {
        MDC.remove("traceId");
        MDC.remove("spanId");
        MDC.remove("correlationId");
        MDC.remove("nodeId");
        MDC.remove("component");
    }
}