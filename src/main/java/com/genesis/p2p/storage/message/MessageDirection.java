package com.genesis.p2p.storage.message;

/**
 * Represents the direction of a message.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public enum MessageDirection {
    /**
     * Message being sent from this node.
     */
    OUTBOUND,

    /**
     * Message being received by this node.
     */
    INBOUND
}

