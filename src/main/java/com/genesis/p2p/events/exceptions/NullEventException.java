package com.genesis.p2p.events.exceptions;

/**
 * Thrown when null event is provided.
 *
 * @since 2.1.0
 */
public class NullEventException extends EventValidationException {
    public NullEventException() {
        super("Event cannot be null");
    }
}