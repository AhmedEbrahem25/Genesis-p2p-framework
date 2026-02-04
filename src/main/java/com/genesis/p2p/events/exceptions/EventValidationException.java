package com.genesis.p2p.events.exceptions;

/**
 * Thrown when event data is invalid.
 *
 * @since 2.1.0
 */
public class EventValidationException extends EventException {
    public EventValidationException(String message) {
        super(message, null);
    }

    public EventValidationException(String message, String eventType) {
        super(message, eventType);
    }
}