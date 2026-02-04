package com.genesis.p2p.nat;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Core NAT traversal service interface.
 * Provides NAT detection and traversal capabilities.
 */
public interface INatTraversalService {

    /**
     * Detects the NAT type of the current network.
     * @return future containing detected NAT type
     */
    CompletableFuture<NatType> detectNatType();

    /**
     * Gets the public IP address and port mapping for a local port.
     * @param localPort the local port to map
     * @return future containing public endpoint, or null if failed
     */
    CompletableFuture<InetSocketAddress> getPublicEndpoint(int localPort);

    /**
     * Checks if NAT traversal is possible between two NAT types.
     * @param localNat local NAT type
     * @param remoteNat remote peer's NAT type
     * @return future containing true if traversal is possible
     */
    CompletableFuture<Boolean> canTraverse(NatType localNat, NatType remoteNat);

    /**
     * Establishes a hole-punched connection to a remote endpoint.
     * @param remoteEndpoint the remote endpoint to connect to
     * @return future containing true if successful
     */
    CompletableFuture<Boolean> establishConnection(InetSocketAddress remoteEndpoint);

    /**
     * Starts the NAT detection service.
     */
    void start();

    /**
     * Stops the NAT detection service.
     */
    void stop();

    /**
     * Checks if the service is running.
     * @return true if running
     */
    boolean isRunning();

    /**
     * Gets detection statistics.
     * @return current statistics
     */
    NatDetectionStats getStats();
}
