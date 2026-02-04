package com.genesis.p2p.storage.message;

/**
 * Represents the lifecycle state of a message.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public enum MessageState {
    /**
     * Message created but not yet sent.
     */
    PENDING,

    /**
     * Message sent to transport layer.
     */
    SENT,

    /**
     * Message received from transport layer.
     */
    RECEIVED,

    /**
     * Message delivered to application handler.
     */
    DELIVERED,

    /**
     * Message failed (after retries).
     */
    FAILED,

    /**
     * Message acknowledged by remote peer.
     */
    ACKNOWLEDGED,

    /**
     * Message expired (TTL exceeded).
     */
    EXPIRED
}

