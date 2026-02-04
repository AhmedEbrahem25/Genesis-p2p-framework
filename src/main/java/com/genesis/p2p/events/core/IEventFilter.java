package com.genesis.p2p.events.core;

/**
 * Filter for accepting or rejecting events before processing.
 *
 * @since 2.2.0
 */
@FunctionalInterface
public interface IEventFilter {
    /**
     * Returns true if the event should be processed.
     */
    boolean accept(IEvent event);

    /**
     * Filter execution order (lower = earlier).
     */
    default int getOrder() {
        return 100;
    }
}