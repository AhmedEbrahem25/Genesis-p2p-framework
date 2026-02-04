package com.genesis.p2p.discovery;
/**
 * Utilities for discovery operations.
 */

class DiscoveryUtils {

    /**
     * Gets recommended multicast TTL based on scope.
     */
    public static int getRecommendedTTL(MulticastScope scope) {
        return switch (scope) {
            case LINK_LOCAL -> 1;      // Same subnet
            case SITE_LOCAL -> 32;     // Same site/organization
            case REGION -> 64;         // Same region
            case WORLDWIDE -> 255;     // Global
        };
    }

    /**
     * Multicast scope enumeration.
     */
    public enum MulticastScope {
        LINK_LOCAL,   // 224.0.0.0 - 224.0.0.255
        SITE_LOCAL,   // 239.255.0.0 - 239.255.255.255
        REGION,       // Regional scope
        WORLDWIDE     // Global scope
    }

    /**
     * Gets multicast scope from address.
     */
    public static MulticastScope getMulticastScope(String address) {
        if (address.startsWith("224.0.0.")) {
            return MulticastScope.LINK_LOCAL;
        } else if (address.startsWith("239.255.")) {
            return MulticastScope.SITE_LOCAL;
        } else if (address.startsWith("239.")) {
            return MulticastScope.REGION;
        }
        return MulticastScope.WORLDWIDE;
    }

    /**
     * Sanitizes node ID for network transmission.
     */
    public static String sanitizeNodeId(String nodeId) {
        if (nodeId == null) {
            return "unknown";
        }
        // Remove any potentially problematic characters
        return nodeId.replaceAll("[^a-zA-Z0-9-_.]", "_");
    }

    /**
     * Calculates exponential backoff delay.
     */
    public static long calculateBackoffDelay(int attempt, long baseDelay, long maxDelay) {
        long delay = baseDelay * (long) Math.pow(2, attempt);
        return Math.min(delay, maxDelay);
    }
}