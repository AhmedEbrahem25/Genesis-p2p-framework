// ============================================================================
// FILE: com/genesis/p2p/nat/NatUtils.java
// ============================================================================
package com.genesis.p2p.nat;

import java.net.*;
import java.util.Enumeration;

/**
 * Utility methods for NAT operations.
 */
public class NatUtils {

    /**
     * Checks if an address is a valid multicast address.
     */
    public static boolean isValidMulticastAddress(String address) {
        try {
            InetAddress addr = InetAddress.getByName(address);
            return addr.isMulticastAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Gets the local IP address (non-loopback).
     */
    public static String getLocalIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                if (ni.isUp() && !ni.isLoopback()) {
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();

                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }

            // Fallback
            return InetAddress.getLocalHost().getHostAddress();

        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * Checks if a port is valid.
     */
    public static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * Creates a NAT-aware peer with NAT information.
     */
    public static java.util.Map<String, Object> createNatMetadata(
            NatType natType,
            InetSocketAddress publicEndpoint) {

        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("natType", natType);
        metadata.put("publicEndpoint", publicEndpoint);
        metadata.put("natDetectionTime", java.time.Instant.now());

        return metadata;
    }

    /**
     * Determines recommended TTL for multicast based on NAT type.
     */
    public static int getRecommendedMulticastTTL(NatType natType) {
        return switch (natType) {
            case OPEN, FULL_CONE -> 255;  // Global
            case RESTRICTED_CONE -> 64;    // Regional
            case PORT_RESTRICTED_CONE -> 32; // Site local
            case SYMMETRIC -> 1;           // Link local
            case UNKNOWN -> 32;            // Conservative
        };
    }

    /**
     * Checks if direct P2P is recommended for NAT combination.
     */
    public static boolean isDirectP2PRecommended(NatType local, NatType remote) {
        // OPEN or FULL_CONE on either side = always recommended
        if (local == NatType.OPEN || local == NatType.FULL_CONE ||
                remote == NatType.OPEN || remote == NatType.FULL_CONE) {
            return true;
        }

        // Both SYMMETRIC = not recommended
        if (local == NatType.SYMMETRIC && remote == NatType.SYMMETRIC) {
            return false;
        }

        // Cone NATs can work with coordination
        return true;
    }
}