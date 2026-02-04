package com.genesis.p2p.events.core;

@FunctionalInterface
public interface IEventListener {
    void onEvent(IEvent event);

    default int getOrder() {
        return 100;
    }
}