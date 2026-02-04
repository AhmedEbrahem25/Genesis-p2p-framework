// ============================================================================
// NETWORK EVENTS
// ============================================================================
package com.genesis.p2p.events.domain;

import com.genesis.p2p.events.core.AbstractEvent;

/**
 * Events related to network operations.
 */
public class NetworkEvent extends AbstractEvent {

    public static final String CONNECTION_ESTABLISHED = "CONNECTION_ESTABLISHED";
    public static final String CONNECTION_CLOSED = "CONNECTION_CLOSED";
    public static final String CONNECTION_ERROR = "CONNECTION_ERROR";

    private final String remoteAddress;
    private final int port;

    private NetworkEvent(String type, String source, String remoteAddress, int port) {
        super(type, source);
        this.remoteAddress = remoteAddress;
        this.port = port;
    }

    /**
     * Creates a connection established event.
     */
    public static NetworkEvent connectionEstablished(String source,
                                                     String remoteAddress, int port) {
        return new NetworkEvent(CONNECTION_ESTABLISHED, source, remoteAddress, port);
    }

    /**
     * Creates a connection closed event.
     */
    public static NetworkEvent connectionClosed(String source,
                                                String remoteAddress, int port) {
        return new NetworkEvent(CONNECTION_CLOSED, source, remoteAddress, port);
    }

    /**
     * Creates a connection error event.
     */
    public static NetworkEvent connectionError(String source,
                                               String remoteAddress, int port,
                                               Throwable error) {
        NetworkEvent event = new NetworkEvent(CONNECTION_ERROR, source, remoteAddress, port);
        event.error = error;
        return event;
    }

    private Throwable error;

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public int getPort() {
        return port;
    }

    public Throwable getError() {
        return error;
    }
}