package com.genesis.p2p.events.exceptions;

/**
 * Thrown when event publishing fails.
 *
 * @since 2.1.0
 */
public class EventPublishException extends EventException {
    public EventPublishException(String message, String eventType) {
        super(message, eventType);
    }

    public EventPublishException(String message, String eventType, Throwable cause) {
        super(message, eventType, cause);
    }
}