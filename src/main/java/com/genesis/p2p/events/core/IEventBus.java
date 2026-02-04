package com.genesis.p2p.events.core;

import java.util.concurrent.CompletableFuture;

/**
 * Central event bus for publishing and subscribing to events.
 *
 * @since 2.0.0
 */
public interface IEventBus {
    Subscription subscribe(String eventType, IEventListener listener);
    <T extends IEvent> Subscription subscribe(Class<T> eventClass, IEventListener listener);
    void unsubscribe(String eventType, IEventListener listener);
    void publish(IEvent event);
    CompletableFuture<Void> publishAsync(IEvent event);
    int getListenerCount(String eventType);
}