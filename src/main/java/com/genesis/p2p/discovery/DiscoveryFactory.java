package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.nat.INatTraversalService;
import com.genesis.p2p.nat.NatAwareDiscovery;

import java.time.Duration;
import java.util.List;

/**
 * Factory for creating discovery service instances.
 *
 * <p>Centralizes discovery service creation to eliminate code duplication
 * and ensure consistent configuration.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class DiscoveryFactory {

    /**
     * Creates a multicast discovery service with default configuration.
     */
    public static MulticastDiscovery createMulticast(NodeConfig config, PeerManager peerManager,
                                                     com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        return new MulticastDiscovery(
                config,
                peerManager,
                metrics,
                config.multicastGroup(),
                config.multicastPort(),
                Duration.ofSeconds(30) // Default announce interval
        );
    }

    /**
     * Creates a multicast discovery service with custom announce interval.
     */
    public static MulticastDiscovery createMulticast(
            NodeConfig config,
            PeerManager peerManager,
            com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
            Duration announceInterval
    ) {
        return new MulticastDiscovery(
                config,
                peerManager,
                metrics,
                config.multicastGroup(),
                config.multicastPort(),
                announceInterval
        );
    }

    /**
     * Creates a broadcast discovery service with default configuration.
     */
    public static BroadcastDiscovery createBroadcast(NodeConfig config, PeerManager peerManager,
                                                     com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        return new BroadcastDiscovery(
                config,
                peerManager,
                metrics,
                config.broadcastPort(),
                Duration.ofSeconds(30) // Default announce interval
        );
    }

    /**
     * Creates a broadcast discovery service with custom announce interval.
     */
    public static BroadcastDiscovery createBroadcast(
            NodeConfig config,
            PeerManager peerManager,
            com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
            Duration announceInterval
    ) {
        return new BroadcastDiscovery(
                config,
                peerManager,
                metrics,
                config.broadcastPort(),
                announceInterval
        );
    }

    /**
     * Creates a bootstrap discovery service.
     */
//    public static BootstrapDiscovery createBootstrap(
//            NodeConfig config,
//            PeerManager peerManager,
//            com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
//            List<String> bootstrapPeers
//    ) {
//        return new BootstrapDiscovery(config, peerManager, metrics, bootstrapPeers);
//    }

    /**
     * Creates a composite discovery service with all three strategies.
     *
     * <p>Combines multicast, broadcast, and bootstrap discovery.
     */
    public static CompositeDiscovery createComposite(
            NodeConfig config,
            PeerManager peerManager,
            com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
            List<String> bootstrapPeers
    ) {
        MulticastDiscovery multicast = createMulticast(config, peerManager, metrics);
        BroadcastDiscovery broadcast = createBroadcast(config, peerManager, metrics);
     //   BootstrapDiscovery bootstrap = createBootstrap(config, peerManager, metrics, bootstrapPeers);

        return new CompositeDiscovery(multicast, broadcast/*, bootstrap*/);
    }

    /**
     * Creates a hybrid discovery service.
     *
     * <p>Uses both multicast and broadcast for maximum reach.
     */
    public static HybridDiscovery createHybrid(
            NodeConfig config,
            PeerManager peerManager,
            com.genesis.p2p.observability.metrics.MetricsRegistry metrics
    ) {
        return new HybridDiscovery(config, peerManager, metrics);
    }

    /**
     * Creates a NAT-aware discovery service.
     *
     * <p>Combines regular discovery with NAT traversal capabilities.
     */
    public static NatAwareDiscovery createNatAware(
            NodeConfig config,
            PeerManager peerManager,
            com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
            INatTraversalService natService
    ) {
        return new NatAwareDiscovery(config, peerManager, metrics, natService);
    }

    /**
     * Creates discovery service based on configuration.
     *
     * <p>Automatically selects the appropriate discovery strategy based on config.
     */
    public static IDiscoveryService createFromConfig(
            NodeConfig config,
            PeerManager peerManager,
            com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
            DiscoveryConfig discoveryConfig
    ) {
        if (discoveryConfig.enableMulticast() && discoveryConfig.enableBroadcast() && discoveryConfig.enableBootstrap()) {
            return createComposite(config, peerManager, metrics, discoveryConfig.bootstrapPeers());
        } else if (discoveryConfig.enableMulticast() && discoveryConfig.enableBroadcast()) {
            return createHybrid(config, peerManager, metrics);
        } else if (discoveryConfig.enableMulticast()) {
            return createMulticast(config, peerManager, metrics, discoveryConfig.announceInterval());
        } else if (discoveryConfig.enableBroadcast()) {
            return createBroadcast(config, peerManager, metrics, discoveryConfig.announceInterval());
        } /*else if (discoveryConfig.enableBootstrap()) {
            return createBootstrap(config, peerManager, metrics, discoveryConfig.bootstrapPeers());
        }*/ else {
            throw new IllegalArgumentException("At least one discovery method must be enabled");
        }
    }
}
