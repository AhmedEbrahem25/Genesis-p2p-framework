package com.genesis.p2p.nat;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.discovery.AbstractDiscoveryService;
import com.genesis.p2p.discovery.DiscoveryFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * NAT-aware discovery service that adapts strategy based on detected NAT type.
 * Extends AbstractDiscoveryService for seamless integration.
 */
public class NatAwareDiscovery extends AbstractDiscoveryService {

    private final INatTraversalService natService;
    private final List<AbstractDiscoveryService> discoveryServices;
    private volatile NatType detectedNatType = NatType.UNKNOWN;

    public NatAwareDiscovery(
            NodeConfig config,
            PeerManager peerManager,
            com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
            INatTraversalService natService) {
        super(config, peerManager, metrics);
        this.natService = natService;
        this.discoveryServices = new ArrayList<>();

        initializeDiscoveryServices(config, peerManager, metrics);
    }

    private void initializeDiscoveryServices(NodeConfig config, PeerManager peerManager, com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        // Add all available discovery services
        discoveryServices.add(DiscoveryFactory.createMulticast(config, peerManager, metrics));
        discoveryServices.add(DiscoveryFactory.createBroadcast(config, peerManager, metrics));

        // Note: Bootstrap discovery would need bootstrap peers from config
        // discoveryServices.add(DiscoveryFactory.createBootstrap(config, peerManager, metrics, bootstrapPeers));

        log.info("Initialized {} discovery services", discoveryServices.size());
    }

    @Override
    protected void doStart() throws Exception {
        log.info("Starting NAT-aware discovery");

        // Start NAT detection
        natService.start();

        // Detect NAT type asynchronously
        natService.detectNatType().thenAccept(natType -> {
            detectedNatType = natType;
            log.info("NAT type detected, adjusting discovery strategy",
                    "type", natType.getDescription(),
                    "strategy", natType.getRecommendedStrategy());

            adaptDiscoveryStrategy(natType);
        });

        // Start all discovery services
        for (AbstractDiscoveryService service : discoveryServices) {
            try {
                service.start();
                log.info("Discovery service started",
                        "type", service.getClass().getSimpleName());
            } catch (Exception e) {
                log.error("Failed to start discovery service", e,
                        "type", service.getClass().getSimpleName());
            }
        }
    }

    @Override
    protected void doStop() {
        log.info("Stopping NAT-aware discovery");

        // Stop all discovery services
        for (AbstractDiscoveryService service : discoveryServices) {
            try {
                service.stop();
                log.info("Discovery service stopped",
                        "type", service.getClass().getSimpleName());
            } catch (Exception e) {
                log.error("Error stopping discovery service", e);
            }
        }

        // Stop NAT service
        natService.stop();
    }

    @Override
    protected CompletableFuture<List<Peer>> doDiscoverPeers() {
        // Discover using all services
        List<CompletableFuture<List<Peer>>> futures = discoveryServices.stream()
                .filter(AbstractDiscoveryService::isRunning)
                .map(AbstractDiscoveryService::discoverPeers)
                .collect(Collectors.toList());

        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // Collect all discovered peers
                    List<Peer> allPeers = new ArrayList<>();
                    for (CompletableFuture<List<Peer>> future : futures) {
                        try {
                            allPeers.addAll(future.getNow(Collections.emptyList()));
                        } catch (Exception e) {
                            log.debug("Error getting peers from future", e);
                        }
                    }

                    // Deduplicate
                    List<Peer> deduplicated = deduplicatePeers(allPeers);

                    // Filter by NAT compatibility
                    List<Peer> compatible = filterCompatiblePeers(deduplicated);

                    log.info("Discovery complete",
                            "total", allPeers.size(),
                            "unique", deduplicated.size(),
                            "compatible", compatible.size());

                    return compatible;
                });
    }

    /**
     * Adapts discovery strategy based on NAT type.
     */
    private void adaptDiscoveryStrategy(NatType natType) {
        switch (natType) {
            case OPEN, FULL_CONE -> {
                log.info("Favorable NAT - all discovery methods work well");
                // All methods enabled, no changes needed
            }
            case RESTRICTED_CONE, PORT_RESTRICTED_CONE -> {
                log.info("Moderate NAT - prioritizing methods that support coordination");
                // Prioritize bootstrap if available
            }
            case SYMMETRIC -> {
                log.warn("Symmetric NAT - direct P2P limited, may need relay");
                // Consider disabling multicast/broadcast, focus on bootstrap
            }
            case UNKNOWN -> {
                log.warn("Unknown NAT - using conservative strategy");
            }
        }
    }

    /**
     * Filters peers based on NAT compatibility.
     */
    private List<Peer> filterCompatiblePeers(List<Peer> peers) {
        if (detectedNatType == NatType.UNKNOWN) {
            // If we don't know our NAT, return all peers
            return peers;
        }

        return peers.stream()
                .filter(peer -> {
                    NatType peerNatType = extractPeerNatType(peer);
                    boolean compatible = NatType.canHolePunch(
                            detectedNatType, peerNatType
                    );

                    if (!compatible) {
                        log.debug("Peer filtered due to NAT incompatibility",
                                "peer", peer.id(),
                                "peerNat", peerNatType,
                                "myNat", detectedNatType);
                    }

                    return compatible;
                })
                .collect(Collectors.toList());
    }

    /**
     * Extracts NAT type from peer metadata.
     */
    private NatType extractPeerNatType(Peer peer) {
        // In a real implementation, NAT type would be in peer's agent string
        // or we could enhance the Peer record to include NAT info

        // For now, assume RESTRICTED_CONE as conservative default
        return NatType.RESTRICTED_CONE;
    }

    /**
     * Deduplicates peers by ID.
     */
    private List<Peer> deduplicatePeers(List<Peer> peers) {
        Map<String, Peer> unique = new LinkedHashMap<>();
        peers.forEach(p -> unique.putIfAbsent(p.id(), p));
        return new ArrayList<>(unique.values());
    }

    /**
     * Gets detected NAT type.
     */
    public NatType getDetectedNatType() {
        return detectedNatType;
    }

    /**
     * Gets NAT traversal service.
     */
    public INatTraversalService getNatService() {
        return natService;
    }

    @Override
    public void announceSelf() {
        // Announce through all running discovery services
        for (AbstractDiscoveryService service : discoveryServices) {
            if (service.isRunning()) {
                try {
                    service.announceSelf();
                    log.debug("Announced self via discovery service",
                            "type", service.getClass().getSimpleName());
                } catch (Exception e) {
                    log.error("Error announcing self via discovery service", e,
                            "type", service.getClass().getSimpleName());
                }
            }
        }
    }
}