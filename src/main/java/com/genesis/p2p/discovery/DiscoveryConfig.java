package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import java.time.Duration;
import java.util.List;

/**
 * Discovery configuration record.
 *
 * <p>Integrates with NodeConfig to provide discovery-specific settings
 * while reusing network configuration from the main node config.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
record DiscoveryConfig(
        List<String> bootstrapPeers,
        String multicastGroup,
        int multicastPort,
        int broadcastPort,
        Duration announceInterval,
        Duration discoveryTimeout,
        boolean enableBootstrap,
        boolean enableMulticast,
        boolean enableBroadcast
) {
    // ==================== Constants ====================

    public static final Duration DEFAULT_ANNOUNCE_INTERVAL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_DISCOVERY_TIMEOUT = Duration.ofSeconds(10);

    // ==================== Factory Methods ====================

    /**
     * Creates default discovery configuration.
     * Uses NodeConfig defaults for network settings.
     */
    public static DiscoveryConfig defaults() {
        return new DiscoveryConfig(
                List.of(),
                NodeConfig.DEFAULT_MULTICAST_GROUP,
                NodeConfig.DEFAULT_MULTICAST_PORT,
                NodeConfig.DEFAULT_BROADCAST_PORT,
                DEFAULT_ANNOUNCE_INTERVAL,
                DEFAULT_DISCOVERY_TIMEOUT,
                true,
                true,
                true
        );
    }

    /**
     * Creates discovery configuration from NodeConfig.
     * Integrates network settings from the main node configuration.
     *
     * @param nodeConfig the node configuration
     * @return discovery config derived from node config
     */
    public static DiscoveryConfig fromNodeConfig(NodeConfig nodeConfig) {
        return new DiscoveryConfig(
                List.of(), // Bootstrap peers would come from NodeBuilder
                nodeConfig.multicastGroup(),
                nodeConfig.multicastPort(),
                nodeConfig.broadcastPort(),
                DEFAULT_ANNOUNCE_INTERVAL,
                DEFAULT_DISCOVERY_TIMEOUT,
                true,
                true,
                true
        );
    }

    /**
     * Creates a builder for custom configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Builder ====================

    /**
     * Builder for DiscoveryConfig.
     */
    public static class Builder {
        private List<String> bootstrapPeers = List.of();
        private String multicastGroup = NodeConfig.DEFAULT_MULTICAST_GROUP;
        private int multicastPort = NodeConfig.DEFAULT_MULTICAST_PORT;
        private int broadcastPort = NodeConfig.DEFAULT_BROADCAST_PORT;
        private Duration announceInterval = DEFAULT_ANNOUNCE_INTERVAL;
        private Duration discoveryTimeout = DEFAULT_DISCOVERY_TIMEOUT;
        private boolean enableBootstrap = true;
        private boolean enableMulticast = true;
        private boolean enableBroadcast = true;

        public Builder bootstrapPeers(List<String> peers) {
            this.bootstrapPeers = peers != null ? List.copyOf(peers) : List.of();
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

        public Builder announceInterval(Duration interval) {
            this.announceInterval = interval;
            return this;
        }

        public Builder discoveryTimeout(Duration timeout) {
            this.discoveryTimeout = timeout;
            return this;
        }

        public Builder enableBootstrap(boolean enable) {
            this.enableBootstrap = enable;
            return this;
        }

        public Builder enableMulticast(boolean enable) {
            this.enableMulticast = enable;
            return this;
        }

        public Builder enableBroadcast(boolean enable) {
            this.enableBroadcast = enable;
            return this;
        }

        /**
         * Applies settings from NodeConfig.
         */
        public Builder fromNodeConfig(NodeConfig nodeConfig) {
            this.multicastGroup = nodeConfig.multicastGroup();
            this.multicastPort = nodeConfig.multicastPort();
            this.broadcastPort = nodeConfig.broadcastPort();
            return this;
        }

        public DiscoveryConfig build() {
            return new DiscoveryConfig(
                bootstrapPeers,
                multicastGroup,
                multicastPort,
                broadcastPort,
                announceInterval,
                discoveryTimeout,
                enableBootstrap,
                enableMulticast,
                enableBroadcast
            );
        }
    }
}