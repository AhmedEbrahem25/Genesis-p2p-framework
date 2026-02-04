package com.genesis.p2p.events.core;

public abstract class AbstractEventListener implements IEventListener {
    protected final int order;

    protected AbstractEventListener() {
        this(100);
    }

    protected AbstractEventListener(int order) {
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }
}