package com.genesis.p2p.events.exceptions;

/**
 * Thrown when attempting to use a closed EventBus.
 *
 * @since 2.1.0
 */
public class EventBusClosedException extends EventPublishException {
    public EventBusClosedException() {
        super("Cannot publish event - EventBus is closed", null);
    }
}