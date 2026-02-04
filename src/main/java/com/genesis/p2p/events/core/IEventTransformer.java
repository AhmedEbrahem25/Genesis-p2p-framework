package com.genesis.p2p.events.core;

/**
 * Transformer for modifying events before delivery to listeners.
 *
 * @since 2.2.0
 */
@FunctionalInterface
public interface IEventTransformer {
    /**
     * Transform the event. Return original if no transformation needed.
     * Never return null.
     */
    IEvent transform(IEvent event);

    /**
     * Transformer execution order (lower = earlier).
     */
    default int getOrder() {
        return 100;
    }
}