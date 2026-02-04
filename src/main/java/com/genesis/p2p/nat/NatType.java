package com.genesis.p2p.nat;

/**
 * NAT types based on RFC 3489 and RFC 5389 (STUN).
 * Ordered by severity (ease of P2P connectivity).
 */
public enum NatType {

    /**
     * No NAT - Direct internet connection.
     * Best case for P2P connectivity.
     */
    OPEN(0, "Open Internet", true, true),

    /**
     * Full Cone NAT.
     * All requests from same internal IP:port → same external IP:port.
     * Any external host can send packets.
     */
    FULL_CONE(1, "Full Cone NAT", true, true),

    /**
     * Restricted Cone NAT.
     * External host can send only if internal host sent to that IP first.
     */
    RESTRICTED_CONE(2, "Restricted Cone NAT", true, false),

    /**
     * Port Restricted Cone NAT.
     * External host can send only if internal host sent to that IP:port.
     */
    PORT_RESTRICTED_CONE(3, "Port Restricted Cone NAT", false, false),

    /**
     * Symmetric NAT.
     * Different mapping for each destination.
     * Most difficult for P2P - usually requires relay.
     */
    SYMMETRIC(4, "Symmetric NAT", false, false),

    /**
     * Detection failed or not performed.
     */
    UNKNOWN(5, "Unknown", false, false);

    private final int severity;
    private final String description;
    private final boolean supportsDirectP2P;
    private final boolean supportsUnsolicited;

    NatType(int severity, String description,
            boolean supportsDirectP2P, boolean supportsUnsolicited) {
        this.severity = severity;
        this.description = description;
        this.supportsDirectP2P = supportsDirectP2P;
        this.supportsUnsolicited = supportsUnsolicited;
    }

    public int getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }

    public boolean supportsDirectP2P() {
        return supportsDirectP2P;
    }

    public boolean supportsUnsolicitedPackets() {
        return supportsUnsolicited;
    }

    /**
     * Checks if hole-punching is possible between two NAT types.
     */
    public static boolean canHolePunch(NatType local, NatType remote) {
        // OPEN or FULL_CONE can connect to anything
        if (local == OPEN || local == FULL_CONE ||
                remote == OPEN || remote == FULL_CONE) {
            return true;
        }

        // Two SYMMETRIC NATs cannot hole-punch
        if (local == SYMMETRIC && remote == SYMMETRIC) {
            return false;
        }

        // One SYMMETRIC with cone NAT can work with coordination
        if (local == SYMMETRIC || remote == SYMMETRIC) {
            return true;
        }

        // Cone NATs can hole-punch with each other
        return true;
    }

    /**
     * Gets recommended discovery strategy for this NAT type.
     */
    public String getRecommendedStrategy() {
        return switch (this) {
            case OPEN, FULL_CONE ->
                    "All discovery methods work well";
            case RESTRICTED_CONE, PORT_RESTRICTED_CONE ->
                    "Prioritize bootstrap for coordination";
            case SYMMETRIC ->
                    "Bootstrap required, consider TURN relay";
            case UNKNOWN ->
                    "Conservative approach, test all methods";
        };
    }
}