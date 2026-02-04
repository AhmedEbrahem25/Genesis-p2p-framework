
// ============================================================================
// SYSTEM EVENTS
// ============================================================================
package com.genesis.p2p.events.domain;

import com.genesis.p2p.events.core.AbstractEvent;

/**
 * Events related to system lifecycle and errors.
 */
public class SystemEvent extends AbstractEvent {

    public static final String SYSTEM_STARTED = "SYSTEM_STARTED";
    public static final String SYSTEM_STOPPED = "SYSTEM_STOPPED";
    public static final String SYSTEM_ERROR = "SYSTEM_ERROR";
    public static final String SYSTEM_WARNING = "SYSTEM_WARNING";

    private final Severity severity;

    public enum Severity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }

    private SystemEvent(String type, String source, Severity severity, Object payload) {
        super(type, source, payload);
        this.severity = severity;
    }

    /**
     * Creates a system started event.
     */
    public static SystemEvent started(String source) {
        return new SystemEvent(SYSTEM_STARTED, source, Severity.INFO, null);
    }

    /**
     * Creates a system stopped event.
     */
    public static SystemEvent stopped(String source) {
        return new SystemEvent(SYSTEM_STOPPED, source, Severity.INFO, null);
    }

    /**
     * Creates a system error event.
     */
    public static SystemEvent error(String source, Throwable error) {
        return new SystemEvent(SYSTEM_ERROR, source, Severity.ERROR, error);
    }

    /**
     * Creates a system warning event.
     */
    public static SystemEvent warning(String source, String message) {
        return new SystemEvent(SYSTEM_WARNING, source, Severity.WARNING, message);
    }

    public Severity getSeverity() {
        return severity;
    }

    public Throwable getError() {
        return payload instanceof Throwable ? (Throwable) payload : null;
    }

    public String getMessage() {
        return payload instanceof String ? (String) payload : null;
    }
}
