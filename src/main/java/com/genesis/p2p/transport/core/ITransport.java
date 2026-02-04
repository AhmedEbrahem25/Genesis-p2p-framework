package com.genesis.p2p.transport.core;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.util.resource.ResourceLeakDetector;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Core transport interface for all network communication.
 *
 * Design Pattern: Strategy Pattern
 * - Allows different transport implementations (TCP, UDP, WebSocket)
 *
 * Phase 5.3 Integration: Resource Leak Detection
 * - All transports now track resource leaks via ResourceLeakDetector
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public interface ITransport extends AutoCloseable {

    /**
     * Starts the transport layer.
     */
    void start() throws Exception;

    /**
     * Stops the transport layer.
     */
    void stop();

    /**
     * Gets transport type.
     */
    TransportType getType();

    /**
     * Gets current transport state.
     */
    TransportState getState();

    /**
     * Checks if transport is running.
     */
    boolean isRunning();

    /**
     * Sends a message to a peer.
     */
    CompletableFuture<Void> send(Message message, InetSocketAddress destination);

    /**
     * Sends raw bytes to a destination.
     */
    CompletableFuture<Void> sendRaw(byte[] data, InetSocketAddress destination);

    /**
     * Sets the envelope handler for incoming messages.
     */
    void setEnvelopeHandler(ITransportEnvelopeHandler handler);

    /**
     * Gets transport statistics.
     */
    TransportStats getStats();

    /**
     * Gets local bind address.
     */
    InetSocketAddress getLocalAddress();

    /**
     * Gets resource leak detection statistics for this transport.
     * Added in Phase 5.3 integration.
     *
     * @return leak statistics
     */
    ResourceLeakDetector.LeakStats getLeakStats();

    /**
     * Gets the number of detected resource leaks for this transport.
     * Added in Phase 5.3 integration.
     *
     * @return number of leaks detected
     */
    long getLeakCount();
}