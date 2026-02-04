package com.genesis.p2p.events.exceptions;

import com.genesis.p2p.events.core.IEventListener;

/**
 * Thrown when a listener fails to execute.
 *
 * @since 2.1.0
 */
public class ListenerExecutionException extends EventPublishException {
    private final IEventListener listener;

    public ListenerExecutionException(IEventListener listener, String eventType, Throwable cause) {
        super("Listener execution failed", eventType, cause);
        this.listener = listener;
    }

    public IEventListener getListener() {
        return listener;
    }
}