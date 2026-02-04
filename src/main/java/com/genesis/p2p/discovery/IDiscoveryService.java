package com.genesis.p2p.discovery;

import com.genesis.p2p.core.Peer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Core discovery service interface.
 * All peer discovery mechanisms must implement this interface.
 */
public interface IDiscoveryService {

    /**
     * Starts the discovery service.
     * @throws Exception if service fails to start
     */
    void start() throws Exception;

    /**
     * Stops the discovery service.
     */
    void stop();

    /**
     * Checks if the discovery service is running.
     * @return true if running, false otherwise
     */
    boolean isRunning();

    /**
     * Discovers peers in the network.
     * @return future containing list of discovered peers
     */
    CompletableFuture<List<Peer>> discoverPeers();

    /**
     * Announces this node's presence to the network.
     */
    void announceSelf();

    /**
     * Sets the discovery listener for peer events.
     * @param listener the listener to notify
     */
    void setDiscoveryListener(IDiscoveryListener listener);
}
