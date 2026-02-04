package com.genesis.p2p.events.core;

/**
 * Subscription handle for managing event subscriptions.
 *
 * @since 2.0.0
 */
public interface Subscription {
    void unsubscribe();
    boolean isActive();
    String getEventType();
}