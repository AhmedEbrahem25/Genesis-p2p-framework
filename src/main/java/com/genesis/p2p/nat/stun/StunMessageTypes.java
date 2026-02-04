package com.genesis.p2p.nat.stun;

// ============================================================================
// FILE: com/genesis/p2p/nat/StunMessageTypes.java
// ============================================================================

/**
 * STUN message type constants for use with MessageHandler.
 */
public class StunMessageTypes {

    public static final String STUN_BINDING_REQUEST = "STUN_BINDING_REQUEST";
    public static final String STUN_BINDING_RESPONSE = "STUN_BINDING_RESPONSE";
    public static final String STUN_ERROR = "STUN_ERROR";
    public static final String HOLE_PUNCH = "HOLE_PUNCH";

    private StunMessageTypes() {
        // Utility class
    }
}