
// ============================================================================
// FILE: com/genesis/p2p/nat/NatConfig.java
// ============================================================================
package com.genesis.p2p.nat;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for NAT detection and traversal.
 */
public record NatConfig(
        Duration detectionTimeout,
        Duration detectionInterval,
        List<String> stunServers,
        boolean enableHolePunching,
        boolean enableRelay,
        int maxRetries
) {

    /**
     * Creates default NAT configuration.
     */
    public static NatConfig defaults() {
        return new NatConfig(
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                List.of(
                        "stun.l.google.com:19302",
                        "stun1.l.google.com:19302"
                ),
                true,
                false,
                3
        );
    }

    /**
     * Creates configuration for LAN-only operation.
     */
    public static NatConfig lanOnly() {
        return new NatConfig(
                Duration.ofSeconds(2),
                Duration.ofMinutes(10),
                List.of(),
                false,
                false,
                1
        );
    }

    /**
     * Creates configuration for aggressive detection.
     */
    public static NatConfig aggressive() {
        return new NatConfig(
                Duration.ofSeconds(10),
                Duration.ofMinutes(1),
                List.of(
                        "stun.l.google.com:19302",
                        "stun1.l.google.com:19302",
                        "stun2.l.google.com:19302",
                        "stun.stunprotocol.org:3478"
                ),
                true,
                true,
                5
        );
    }
}