
// ============================================================================
// FILE: com/genesis/p2p/core/handlers/processors/nat/NatProcessorFactory.java
// ============================================================================
package com.genesis.p2p.core.handlers.processors.nat;

import com.genesis.p2p.core.MessageHandler;

/**
 * Factory for registering NAT-related message processors.
 */
public class NatProcessorFactory {

    /**
     * Registers all NAT processors with MessageHandler.
     */
    public static void registerNatProcessors(String nodeId, MessageHandler messageHandler) {
        // Register STUN binding request processor
        messageHandler.registerProcessor(
                "STUN_BINDING_REQUEST",
                new StunBindingRequestProcessor(nodeId)
        );


        // STUN_BINDING_RESPONSE and STUN_ERROR are handled by StunClient
        // They don't need separate processors as they're handled internally
    }
}
