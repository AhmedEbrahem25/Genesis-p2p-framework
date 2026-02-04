package com.genesis.p2p.discovery;


import com.genesis.p2p.core.Peer;

/**
 * Listener interface for discovery events.
 */
public interface IDiscoveryListener {

    /**
     * Called when a new peer is discovered.
     * @param peer the discovered peer
     */
    void onPeerDiscovered(Peer peer);

    /**
     * Called when a peer is lost/disconnected.
     * @param peer the lost peer
     */
    void onPeerLost(Peer peer);

    /**
     * Called when a discovery error occurs.
     * @param error the error that occurred
     */
    void onDiscoveryError(Throwable error);
}
