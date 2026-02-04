package com.genesis.p2p.nat;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.observability.logging.NodeLogger;

import java.net.InetSocketAddress;

/**
 * NAT-aware connection strategy selector.
 *
 * Determines the optimal connection approach based on:
 * - Local NAT type
 * - Remote peer NAT type
 * - Network capabilities
 *
 * Strategies (in order of preference):
 * 1. DIRECT - Public TCP connection (no NAT or OPEN/FULL_CONE)
 * 2. HOLE_PUNCH - UDP hole punching followed by TCP
 * 3. RELAY - TURN relay (both peers SYMMETRIC or hole punch failed)
 *
 * @author Genesis P2P Framework
 * @version 2.0.1
 */
public enum ConnectionStrategy {

    /**
     * Direct TCP connection to public endpoint.
     * Used when:
     * - Both peers have OPEN internet
     * - At least one peer has FULL_CONE NAT
     * - Peer's public IP is directly reachable
     */
    DIRECT("Direct TCP", true, false, 100),

    /**
     * UDP hole punching followed by TCP connection.
     * Used when:
     * - Both peers behind cone NATs
     * - One peer has SYMMETRIC, other has cone NAT
     * - Requires simultaneous packet exchange
     */
    HOLE_PUNCH("UDP Hole Punch + TCP", true, true, 75),

    /**
     * TURN relay connection.
     * Used when:
     * - Both peers have SYMMETRIC NAT
     * - Hole punching failed
     * - No direct path available
     */
    RELAY("TURN Relay", false, false, 50),

    /**
     * Connection not possible.
     * Used when:
     * - No connection strategy available
     * - Both peers unreachable
     */
    IMPOSSIBLE("No Connection Possible", false, false, 0);

    private static final NodeLogger log = NodeLogger.getLogger(ConnectionStrategy.class);

    private final String description;
    private final boolean isDirect;
    private final boolean requiresCoordination;
    private final int successProbability;

    ConnectionStrategy(String description, boolean isDirect,
                      boolean requiresCoordination, int successProbability) {
        this.description = description;
        this.isDirect = isDirect;
        this.requiresCoordination = requiresCoordination;
        this.successProbability = successProbability;
    }

    public String getDescription() {
        return description;
    }

    public boolean isDirect() {
        return isDirect;
    }

    public boolean requiresCoordination() {
        return requiresCoordination;
    }

    public int getSuccessProbability() {
        return successProbability;
    }

    /**
     * Selects optimal connection strategy based on NAT types.
     *
     * Decision matrix:
     * - OPEN + OPEN = DIRECT
     * - OPEN + CONE = DIRECT (to OPEN peer's public IP)
     * - OPEN + SYMMETRIC = DIRECT (to OPEN peer)
     * - FULL_CONE + FULL_CONE = DIRECT
     * - FULL_CONE + RESTRICTED = HOLE_PUNCH
     * - CONE + CONE = HOLE_PUNCH
     * - CONE + SYMMETRIC = HOLE_PUNCH (with coordination)
     * - SYMMETRIC + SYMMETRIC = RELAY
     *
     * @param localNat Local node's NAT type
     * @param remotePeer Remote peer with NAT information
     * @return Recommended connection strategy
     */
    public static ConnectionStrategy selectStrategy(NatType localNat, Peer remotePeer) {
        NatType remoteNat = remotePeer.natType();

        // Validate inputs
        if (localNat == null || remoteNat == null) {
            log.warn("NAT type unknown - cannot determine strategy",
                    "localNat", localNat,
                    "remoteNat", remoteNat);
            return IMPOSSIBLE;
        }

        // UNKNOWN NAT types - try DIRECT first (most common scenario works)
        if (localNat == NatType.UNKNOWN || remoteNat == NatType.UNKNOWN) {
            log.debug("NAT type UNKNOWN - defaulting to DIRECT strategy",
                    "localNat", localNat,
                    "remoteNat", remoteNat);
            return DIRECT;
        }

        // OPEN internet - always direct
        if (localNat == NatType.OPEN || remoteNat == NatType.OPEN) {
            log.debug("Direct connection possible - OPEN internet detected",
                    "localNat", localNat,
                    "remoteNat", remoteNat);
            return DIRECT;
        }

        // FULL_CONE - can accept connections from anywhere
        if (localNat == NatType.FULL_CONE || remoteNat == NatType.FULL_CONE) {
            log.debug("Direct connection possible - FULL_CONE NAT detected",
                    "localNat", localNat,
                    "remoteNat", remoteNat);
            return DIRECT;
        }

        // Both SYMMETRIC - hole punching extremely difficult, use relay
        if (localNat == NatType.SYMMETRIC && remoteNat == NatType.SYMMETRIC) {
            log.warn("Both peers have SYMMETRIC NAT - relay required",
                    "localNat", localNat,
                    "remoteNat", remoteNat);
            return RELAY;
        }

        // Check if hole punching is possible
        if (NatType.canHolePunch(localNat, remoteNat)) {
            log.info("Hole punching possible",
                    "localNat", localNat,
                    "remoteNat", remoteNat);
            return HOLE_PUNCH;
        }

        // Fallback to relay
        log.warn("No direct connection possible - relay required",
                "localNat", localNat,
                "remoteNat", remoteNat);
        return RELAY;
    }

    /**
     * Determines the target address for connection based on strategy.
     *
     * @param peer Target peer
     * @param strategy Connection strategy
     * @return Address to connect to
     */
    public static InetSocketAddress getTargetAddress(Peer peer, ConnectionStrategy strategy) {
        switch (strategy) {
            case DIRECT:
                // Use public endpoint if available, fallback to local
                if (peer.publicIp() != null && !peer.publicIp().isEmpty()) {
                    return new InetSocketAddress(peer.publicIp(), peer.publicPort());
                }
                return new InetSocketAddress(peer.ip(), peer.port());

            case HOLE_PUNCH:
                // Use public endpoint for hole punching
                return new InetSocketAddress(peer.publicIp(), peer.publicPort());

            case RELAY:
                // TURN relay address (to be implemented)
                log.warn("RELAY strategy selected but TURN not implemented yet");
                return new InetSocketAddress(peer.publicIp(), peer.publicPort());

            case IMPOSSIBLE:
            default:
                log.error("Cannot determine target address for IMPOSSIBLE strategy");
                return null;
        }
    }

    /**
     * Checks if peer is reachable based on NAT types.
     */
    public static boolean isReachable(NatType localNat, Peer remotePeer) {
        ConnectionStrategy strategy = selectStrategy(localNat, remotePeer);
        return strategy != IMPOSSIBLE;
    }

    /**
     * Provides human-readable explanation of connection decision.
     */
    public static String explainStrategy(NatType localNat, Peer remotePeer) {
        ConnectionStrategy strategy = selectStrategy(localNat, remotePeer);
        NatType remoteNat = remotePeer.natType();

        return String.format(
            "Connection Strategy: %s (%.0f%% success)\n" +
            "  Local NAT:  %s\n" +
            "  Remote NAT: %s\n" +
            "  Method:     %s\n" +
            "  Target:     %s:%d",
            strategy.name(),
            strategy.getSuccessProbability() / 100.0 * 100,
            localNat.getDescription(),
            remoteNat.getDescription(),
            strategy.getDescription(),
            remotePeer.publicIp(),
            remotePeer.publicPort()
        );
    }
}

