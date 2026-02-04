package com.genesis.p2p.application;

import java.util.*;

/**
 * NodeConfig - Immutable configuration for P2P Node.
 *
 * Design Patterns Applied:
 * - Builder Pattern: Fluent configuration construction
 * - Immutable Object: Thread-safe configuration
 * - Validation: Configuration constraints enforcement
 *
 * Contains all configuration needed for a P2P node:
 * - Network settings (ports, addresses)
 * - Discovery settings (multicast, broadcast)
 * - Security settings (encryption keys)
 * - Protocol settings (version)
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public record NodeConfig(
        String nodeId,
        int listenPort,
        int tcpPort,
        String multicastGroup,
        int multicastPort,
        int broadcastPort,
        String preSharedKeyHex,
        boolean persistenceEnabled,
        String persistenceDir
) {
    // ==================== Constants ====================

    public static final String DEFAULT_MULTICAST_GROUP = "239.255.0.1";
    public static final int DEFAULT_LISTEN_PORT = 8080;
    public static final int DEFAULT_TCP_PORT = 8081;
    public static final int DEFAULT_MULTICAST_PORT = 5000;
    public static final int DEFAULT_BROADCAST_PORT = 5001;
    public static final String DEFAULT_PERSISTENCE_DIR = "./data";
    public static final String PROTOCOL_VERSION = "2.0";

    // ==================== Validation ====================

    /**
     * Compact constructor with validation.
     */
    public NodeConfig {
        // Validate nodeId
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("Node ID cannot be null or empty");
        }

        // Validate ports
        validatePort(listenPort, "listenPort");
        validatePort(tcpPort, "tcpPort");
        validatePort(multicastPort, "multicastPort");
        validatePort(broadcastPort, "broadcastPort");

        // Validate multicast group
        if (multicastGroup == null || multicastGroup.isBlank()) {
            multicastGroup = DEFAULT_MULTICAST_GROUP;
        }

        // Normalize preSharedKeyHex
        if (preSharedKeyHex == null) {
            preSharedKeyHex = "";
        }

        // Normalize persistenceDir
        if (persistenceDir == null || persistenceDir.isBlank()) {
            persistenceDir = DEFAULT_PERSISTENCE_DIR;
        }
    }

    private static void validatePort(int port, String name) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(name + " must be between 0 and 65535, got: " + port);
        }
    }

    // ==================== Protocol ====================

    /**
     * Gets the protocol version.
     */
    public String protocolVersion() {
        return PROTOCOL_VERSION;
    }

    // ==================== Derived Properties ====================

    /**
     * Checks if security is enabled (has pre-shared key).
     */
    public boolean isSecurityEnabled() {
        return preSharedKeyHex != null && !preSharedKeyHex.isBlank();
    }

    /**
     * Gets the multicast address in format "group:port".
     */
    public String getMulticastAddress() {
        return multicastGroup + ":" + multicastPort;
    }

    /**
     * Gets the TCP bind address in format "0.0.0.0:port".
     */
    public String getTcpBindAddress() {
        return "0.0.0.0:" + tcpPort;
    }

    /**
     * Gets the UDP bind address in format "0.0.0.0:port".
     */
    public String getUdpBindAddress() {
        return "0.0.0.0:" + listenPort;
    }

    // ==================== Builder Pattern ====================

    /**
     * Creates a builder with default values.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder from an existing config.
     */
    public static Builder builder(NodeConfig source) {
        return new Builder(source);
    }

    /**
     * Creates default configuration with random node ID.
     */
    public static NodeConfig defaults() {
        return builder().build();
    }

    /**
     * Creates configuration with specific node ID.
     */
    public static NodeConfig withNodeId(String nodeId) {
        return builder().nodeId(nodeId).build();
    }

    /**
     * Creates configuration for testing (minimal settings).
     */
    public static NodeConfig forTesting() {
        return builder()
                .nodeId("test-" + UUID.randomUUID().toString().substring(0, 8))
                .listenPort(0) // Random port
                .tcpPort(0)    // Random port
                .build();
    }

    /**
     * Builder for NodeConfig.
     */
    public static class Builder {
        private String nodeId;
        private int listenPort = DEFAULT_LISTEN_PORT;
        private int tcpPort = DEFAULT_TCP_PORT;
        private String multicastGroup = DEFAULT_MULTICAST_GROUP;
        private int multicastPort = DEFAULT_MULTICAST_PORT;
        private int broadcastPort = DEFAULT_BROADCAST_PORT;
        private String preSharedKeyHex = "";
        private boolean persistenceEnabled = true;
        private String persistenceDir = DEFAULT_PERSISTENCE_DIR;

        public Builder() {
            this.nodeId = "node-" + UUID.randomUUID().toString().substring(0, 8);
        }

        public Builder(NodeConfig source) {
            this.nodeId = source.nodeId();
            this.listenPort = source.listenPort();
            this.tcpPort = source.tcpPort();
            this.multicastGroup = source.multicastGroup();
            this.multicastPort = source.multicastPort();
            this.broadcastPort = source.broadcastPort();
            this.preSharedKeyHex = source.preSharedKeyHex();
            this.persistenceEnabled = source.persistenceEnabled();
            this.persistenceDir = source.persistenceDir();
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder listenPort(int port) {
            this.listenPort = port;
            return this;
        }

        public Builder tcpPort(int port) {
            this.tcpPort = port;
            return this;
        }

        public Builder multicastGroup(String group) {
            this.multicastGroup = group;
            return this;
        }

        public Builder multicastPort(int port) {
            this.multicastPort = port;
            return this;
        }

        public Builder broadcastPort(int port) {
            this.broadcastPort = port;
            return this;
        }

        public Builder preSharedKey(String keyHex) {
            this.preSharedKeyHex = keyHex;
            return this;
        }

        public Builder persistenceEnabled(boolean enabled) {
            this.persistenceEnabled = enabled;
            return this;
        }

        public Builder persistenceDir(String dir) {
            this.persistenceDir = dir;
            return this;
        }

        /**
         * Sets both listen and TCP ports.
         */
        public Builder ports(int listenPort, int tcpPort) {
            this.listenPort = listenPort;
            this.tcpPort = tcpPort;
            return this;
        }

        /**
         * Sets discovery ports.
         */
        public Builder discoveryPorts(int multicastPort, int broadcastPort) {
            this.multicastPort = multicastPort;
            this.broadcastPort = broadcastPort;
            return this;
        }

        public NodeConfig build() {
            return new NodeConfig(
                    nodeId,
                    listenPort,
                    tcpPort,
                    multicastGroup,
                    multicastPort,
                    broadcastPort,
                    preSharedKeyHex,
                    persistenceEnabled,
                    persistenceDir
            );
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Creates a copy with different node ID.
     */
    public NodeConfig copyWithNodeId(String newNodeId) {
        return new NodeConfig(newNodeId, listenPort, tcpPort, multicastGroup,
                multicastPort, broadcastPort, preSharedKeyHex, persistenceEnabled, persistenceDir);
    }

    /**
     * Creates a copy with different ports.
     */
    public NodeConfig withPorts(int newListenPort, int newTcpPort) {
        return new NodeConfig(nodeId, newListenPort, newTcpPort, multicastGroup,
                multicastPort, broadcastPort, preSharedKeyHex, persistenceEnabled, persistenceDir);
    }

    /**
     * Converts to map for serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", nodeId);
        map.put("listenPort", listenPort);
        map.put("tcpPort", tcpPort);
        map.put("multicastGroup", multicastGroup);
        map.put("multicastPort", multicastPort);
        map.put("broadcastPort", broadcastPort);
        map.put("preSharedKeyHex", preSharedKeyHex);
        map.put("persistenceEnabled", persistenceEnabled);
        map.put("persistenceDir", persistenceDir);
        map.put("protocolVersion", protocolVersion());
        return map;
    }

    /**
     * Creates from map (deserialization).
     */
    public static NodeConfig fromMap(Map<String, Object> map) {
        return builder()
                .nodeId((String) map.getOrDefault("nodeId", "node-" + UUID.randomUUID().toString().substring(0, 8)))
                .listenPort(((Number) map.getOrDefault("listenPort", DEFAULT_LISTEN_PORT)).intValue())
                .tcpPort(((Number) map.getOrDefault("tcpPort", DEFAULT_TCP_PORT)).intValue())
                .multicastGroup((String) map.getOrDefault("multicastGroup", DEFAULT_MULTICAST_GROUP))
                .multicastPort(((Number) map.getOrDefault("multicastPort", DEFAULT_MULTICAST_PORT)).intValue())
                .broadcastPort(((Number) map.getOrDefault("broadcastPort", DEFAULT_BROADCAST_PORT)).intValue())
                .preSharedKey((String) map.getOrDefault("preSharedKeyHex", ""))
                .persistenceEnabled((Boolean) map.getOrDefault("persistenceEnabled", true))
                .persistenceDir((String) map.getOrDefault("persistenceDir", DEFAULT_PERSISTENCE_DIR))
                .build();
    }

    @Override
    public String toString() {
        return String.format("NodeConfig[nodeId=%s, tcp=%d, udp=%d, multicast=%s:%d]",
                nodeId, tcpPort, listenPort, multicastGroup, multicastPort);
    }
}
