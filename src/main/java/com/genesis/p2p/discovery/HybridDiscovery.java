package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.observability.logging.NodeLogger;

import java.util.*;
import java.util.concurrent.*;

/**
 * Hybrid discovery service with intelligent fallback.
 *
 * Combines multiple discovery mechanisms with a priority-based fallback strategy.
 * Tries discovery methods in order until enough peers are found.
 *
 * Features:
 * - Priority-based discovery
 * - Automatic fallback
 * - Configurable strategy
 * - Efficient resource usage
 */
class HybridDiscovery implements IDiscoveryService {

    private static final NodeLogger log = NodeLogger.getLogger(HybridDiscovery.class);
    private static final int MIN_PEERS_THRESHOLD = 3;

    private final CompositeDiscovery composite;
    private final List<Class<? extends IDiscoveryService>> fallbackOrder;
    private final int minPeersThreshold;

    /**
     * Creates a hybrid discovery service.
     */
    public HybridDiscovery(NodeConfig config, PeerManager peerManager, com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        this(config, peerManager, metrics, MIN_PEERS_THRESHOLD);
    }

    /**
     * Creates a hybrid discovery service with custom threshold.
     */
    public HybridDiscovery(NodeConfig config, PeerManager peerManager, com.genesis.p2p.observability.metrics.MetricsRegistry metrics, int minPeersThreshold) {
        this.composite = new CompositeDiscovery();
        this.minPeersThreshold = minPeersThreshold;

        // Define fallback order: Bootstrap -> Multicast -> Broadcast
        this.fallbackOrder = Arrays.asList(
            //    BootstrapDiscovery.class,
                MulticastDiscovery.class,
                BroadcastDiscovery.class
        );

        initializeServices(config, peerManager, metrics);
    }

    /**
     * Initializes discovery services based on configuration.
     */
    private void initializeServices(NodeConfig config, PeerManager peerManager, com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        log.info("Initializing hybrid discovery services");

        // Add Bootstrap discovery if configured
        // In real implementation, bootstrap peers would come from config
        List<String> bootstrapPeers = List.of(); // config.getBootstrapPeers()
//        if (!bootstrapPeers.isEmpty()) {
//            composite.add(DiscoveryFactory.createBootstrap(config, peerManager, metrics, bootstrapPeers));
//            log.info("Bootstrap discovery added");
//        }

        // Add Multicast discovery
        composite.add(DiscoveryFactory.createMulticast(config, peerManager, metrics));
        log.info("Multicast discovery added");

        // Add Broadcast discovery
        composite.add(DiscoveryFactory.createBroadcast(config, peerManager, metrics));
        log.info("Broadcast discovery added");
    }

    @Override
    public void start() throws Exception {
        log.info("Starting hybrid discovery");
        composite.start();
    }

    @Override
    public void stop() {
        log.info("Stopping hybrid discovery");
        composite.stop();
    }

    @Override
    public boolean isRunning() {
        return composite.isRunning();
    }

    @Override
    public CompletableFuture<List<Peer>> discoverPeers() {
        log.info("Starting intelligent peer discovery");
        return discoverWithFallback();
    }

    @Override
    public void announceSelf() {
        composite.announceSelf();
    }

    @Override
    public void setDiscoveryListener(IDiscoveryListener listener) {
        composite.setDiscoveryListener(listener);
    }

    // ========================= Private Methods =========================

    /**
     * Discovers peers with intelligent fallback.
     */
    private CompletableFuture<List<Peer>> discoverWithFallback() {
        // Try all methods in parallel first
        return composite.discoverPeers()
                .thenCompose(peers -> {
                    if (peers.size() >= minPeersThreshold) {
                        log.info("Sufficient peers discovered", "count", peers.size());
                        return CompletableFuture.completedFuture(peers);
                    }

                    log.info("Insufficient peers, trying fallback",
                            "found", peers.size(),
                            "threshold", minPeersThreshold);

                    // If not enough peers, continue trying
                    return tryNextDiscoveryMethod(peers, 0);
                });
    }

    /**
     * Tries next discovery method in fallback order.
     */
    private CompletableFuture<List<Peer>> tryNextDiscoveryMethod(
            List<Peer> currentPeers, int methodIndex) {

        if (methodIndex >= composite.getServices().size()) {
            // No more methods to try
            log.warn("All discovery methods exhausted", "peers", currentPeers.size());
            return CompletableFuture.completedFuture(currentPeers);
        }

        IDiscoveryService service = composite.getServices().get(methodIndex);

        return service.discoverPeers()
                .thenCompose(newPeers -> {
                    List<Peer> combined = new ArrayList<>(currentPeers);
                    combined.addAll(newPeers);

                    // Deduplicate
                    Map<String, Peer> unique = new LinkedHashMap<>();
                    combined.forEach(p -> unique.putIfAbsent(p.id(), p));
                    List<Peer> deduplicated = new ArrayList<>(unique.values());

                    if (deduplicated.size() >= minPeersThreshold) {
                        log.info("Threshold reached",
                                "peers", deduplicated.size(),
                                "method", service.getClass().getSimpleName());
                        return CompletableFuture.completedFuture(deduplicated);
                    }

                    // Try next method
                    return tryNextDiscoveryMethod(deduplicated, methodIndex + 1);
                });
    }

    /**
     * Gets the composite discovery instance.
     */
    public CompositeDiscovery getComposite() {
        return composite;
    }
}