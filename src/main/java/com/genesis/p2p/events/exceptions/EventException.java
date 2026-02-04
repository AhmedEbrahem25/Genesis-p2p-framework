package com.genesis.p2p.events.exceptions;

import java.time.Instant;

/**
 * Base exception for all event system errors.
 *
 * @since 2.1.0
 */
public class EventException extends RuntimeException {
    private final String eventType;
    private final Instant timestamp;

    public EventException(String message) {
        this(message, null, null);
    }

    public EventException(String message, String eventType) {
        this(message, eventType, null);
    }

    public EventException(String message, String eventType, Throwable cause) {
        super(message, cause);
        this.eventType = eventType;
        this.timestamp = Instant.now();
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}