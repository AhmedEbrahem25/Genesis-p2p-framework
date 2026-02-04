package com.genesis.p2p.transport.core;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.ProcessingContext;
import java.net.InetSocketAddress;

/**
 * Handler for incoming transport envelopes.
 *
 * Design Pattern: Observer Pattern
 * - Notifies interested parties of incoming messages
 *
 * Extended to support message persistence tracking via ProcessingContext.
 * The context carries persistenceId for state updates through the processing pipeline.
 *
 * @author Genesis P2P Framework
 * @version 2.1
 */
@FunctionalInterface
public interface ITransportEnvelopeHandler {

    /**
     * Handles an incoming message envelope with processing context.
     *
     * This is the primary method that should be implemented for full observability.
     * The context carries persistenceId for tracking message state transitions:
     * RECEIVED -> PROCESSING -> DELIVERED/FAILED
     *
     * @param message the received message
     * @param source the source address
     * @param context the processing context with persistenceId and traceId
     */
    void handleEnvelope(Message message, InetSocketAddress source, ProcessingContext context);

    /**
     * Handles an incoming message envelope (legacy method for backwards compatibility).
     *
     * @param message the received message
     * @param source the source address
     * @deprecated Use {@link #handleEnvelope(Message, InetSocketAddress, ProcessingContext)} instead
     */
    @Deprecated
    default void handleEnvelope(Message message, InetSocketAddress source) {
        handleEnvelope(message, source, ProcessingContext.empty());
    }
}