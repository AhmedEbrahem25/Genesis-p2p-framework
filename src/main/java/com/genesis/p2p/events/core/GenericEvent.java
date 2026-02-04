package com.genesis.p2p.events.core;

public class GenericEvent extends AbstractEvent {

    public GenericEvent(String type, Object payload) {
        super(type, "GenericEvent", payload);
    }

    public GenericEvent(String type, String source, Object payload) {
        super(type, source, payload);
    }
}