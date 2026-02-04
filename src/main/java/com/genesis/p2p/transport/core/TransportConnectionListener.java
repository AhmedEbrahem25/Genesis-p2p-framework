package com.genesis.p2p.transport.core;

import java.net.InetSocketAddress;

/**
 * Listener for TCP connection lifecycle events.
 * FIX #2: CONNECTION LIFECYCLE EVENTS
 */
public interface TransportConnectionListener {

    /**
     * Called when a TCP connection is established.
     * @param peerId the peer identifier
     * @param address the remote address of the connection
     * @param isInbound true if peer connected to us, false if we connected to peer
     */
    void onConnectionEstablished(String peerId, InetSocketAddress address, boolean isInbound) throws Exception;

    /**
     * Called when a TCP connection is closed.
     * @param peerId the peer identifier
     * @param reason the reason for closure
     */
    void onConnectionClosed(String peerId, String reason);
}

