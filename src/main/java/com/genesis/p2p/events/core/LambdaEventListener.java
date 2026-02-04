package com.genesis.p2p.events.core;

import java.util.function.Consumer;

public class LambdaEventListener implements IEventListener {
    private final Consumer<IEvent> handler;
    private final int order;

    public LambdaEventListener(Consumer<IEvent> handler) {
        this(handler, 100);
    }

    public LambdaEventListener(Consumer<IEvent> handler, int order) {
        this.handler = handler;
        this.order = order;
    }

    @Override
    public void onEvent(IEvent event) {
        handler.accept(event);
    }

    @Override
    public int getOrder() {
        return order;
    }
}