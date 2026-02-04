package com.genesis.p2p.events.core;

import java.util.concurrent.atomic.AtomicBoolean;

class EventSubscription implements Subscription {
    private final EventBus eventBus;
    private final String eventType;
    private final IEventListener listener;
    private final AtomicBoolean active;

    EventSubscription(EventBus eventBus, String eventType, IEventListener listener) {
        this.eventBus = eventBus;
        this.eventType = eventType;
        this.listener = listener;
        this.active = new AtomicBoolean(true);
    }

    @Override
    public void unsubscribe() {
        if (active.compareAndSet(true, false)) {
            eventBus.unsubscribe(eventType, listener);
        }
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public String getEventType() {
        return eventType;
    }
}