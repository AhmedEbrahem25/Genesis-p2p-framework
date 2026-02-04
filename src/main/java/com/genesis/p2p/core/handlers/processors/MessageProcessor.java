package com.genesis.p2p.core.handlers.processors;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.ProcessingContext;

/**
 * Interface for message processors with lifecycle management.
 *
 * Processors can implement either:
 * - processMessage(Message) for basic processing
 * - processMessage(Message, ProcessingContext) for context-aware processing (e.g., response routing)
 *
 * @author Genesis P2P Framework
 * @version 1.1
 */
public interface MessageProcessor extends AutoCloseable {
    /**
     * Process an incoming message (basic).
     *
     * @param message the message to process
     */
    void processMessage(Message message);

    /**
     * Process an incoming message with context (context-aware).
     * The context provides additional metadata like source address for response routing.
     *
     * Default implementation delegates to processMessage(Message) for backward compatibility.
     *
     * @param message the message to process
     * @param context the processing context with source address and tracing info
     */
    default void processMessage(Message message, ProcessingContext context) {
        // Default: delegate to basic method (backward compatible)
        processMessage(message);
    }

    /**
     * Check if this processor supports context-aware processing.
     * Processors that override processMessage(Message, ProcessingContext) should return true.
     *
     * @return true if this processor uses the ProcessingContext
     */
    default boolean supportsContext() {
        return false;
    }

    /**
     * Initialize the processor.
     * Called once before processing begins.
     * Default implementation does nothing.
     */
    default void initialize() {
        // Default: no initialization needed
    }

    /**
     * Clean up processor resources.
     * Called when the processor is no longer needed.
     * Default implementation does nothing.
     */
    @Override
    default void close() {
        // Default: no cleanup needed
    }
}
