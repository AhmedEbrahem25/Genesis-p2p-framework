
// ============================================================================
// FILE: com/genesis/p2p/nat/NatDetectionFactory.java (UPDATED for MessageHandler)
// ============================================================================
package com.genesis.p2p.nat;

import com.genesis.p2p.core.MessageHandler;
import com.genesis.p2p.nat.stun.StunNatDetector;

import java.time.Duration;
import java.util.List;

/**
 * Factory for creating NAT detection services.
 * Requires MessageHandler for full integration.
 */
public class NatDetectionFactory {

    /**
     * Creates default STUN-based NAT detector.
     * Uses default STUN servers and 5-second timeout.
     */
    public static INatTraversalService createDefault(
            String nodeId,
            MessageHandler messageHandler) {
        return new StunNatDetector(nodeId, messageHandler);
    }

    /**
     * Creates STUN detector with custom configuration.
     */
    public static INatTraversalService createStunBased(
            String nodeId,
            MessageHandler messageHandler,
            Duration timeout,
            List<String> stunServers) {
        return new StunNatDetector(nodeId, messageHandler, timeout, stunServers);
    }

    /**
     * Creates STUN detector with custom timeout only.
     */
    public static INatTraversalService createWithTimeout(
            String nodeId,
            MessageHandler messageHandler,
            Duration timeout) {
        return new StunNatDetector(nodeId, messageHandler, timeout, null);
    }
}