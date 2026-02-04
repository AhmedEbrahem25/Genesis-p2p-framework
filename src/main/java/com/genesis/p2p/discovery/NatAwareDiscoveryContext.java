package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.nat.INatTraversalService;
import com.genesis.p2p.nat.NatType;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NAT-aware discovery context.
 *
 * Provides NAT information to discovery services so they can:
 * - Include public endpoints in announcements
 * - Filter peers based on NAT compatibility
 * - Provide connection hints
 *
 * @author Genesis P2P Framework
 * @version 2.0.1
 */
public class NatAwareDiscoveryContext {

    private static final NodeLogger log = NodeLogger.getLogger(NatAwareDiscoveryContext.class);

    private final INatTraversalService natService;
    private final MetricsRegistry metrics;
    private final NodeConfig config;

    // Cached NAT state
    private volatile NatType detectedNatType;
    private volatile InetSocketAddress publicEndpoint;
    private volatile boolean natDetectionComplete;

    // NAT compatibility cache
    private final Map<String, Boolean> compatibilityCache;

    public NatAwareDiscoveryContext(NodeConfig config,
                                    INatTraversalService natService,
                                    MetricsRegistry metrics) {
        this.config = config;
        this.natService = natService;
        this.metrics = metrics;
        this.compatibilityCache = new ConcurrentHashMap<>();
        this.natDetectionComplete = false;
    }

    /**
     * Initializes NAT detection. Must be called before discovery starts.
     */
    public CompletableFuture<Void> initialize() {
        log.info("Initializing NAT-aware discovery context");

        return natService.detectNatType()
            .thenCompose(natType -> {
                this.detectedNatType = natType;
                log.info("NAT type detected", "type", natType.getDescription());
                metrics.incrementCounter("nat.discovery.detected." + natType.name().toLowerCase());

                // Get public endpoint
                return natService.getPublicEndpoint(config.tcpPort());
            })
            .thenAccept(endpoint -> {
                this.publicEndpoint = endpoint;
                this.natDetectionComplete = true;

                if (endpoint != null) {
                    log.info("Public endpoint detected",
                            "ip", endpoint.getAddress().getHostAddress(),
                            "port", endpoint.getPort());
                } else {
                    log.warn("No public endpoint detected");
                }
            })
            .exceptionally(ex -> {
                log.error("NAT detection failed", ex);
                this.detectedNatType = NatType.UNKNOWN;
                this.natDetectionComplete = true;
                return null;
            });
    }

    /**
     * Gets the detected NAT type.
     */
    public NatType getNatType() {
        return detectedNatType != null ? detectedNatType : NatType.UNKNOWN;
    }

    /**
     * Gets the detected public endpoint.
     */
    public InetSocketAddress getPublicEndpoint() {
        return publicEndpoint;
    }

    /**
     * Checks if NAT detection is complete.
     */
    public boolean isNatDetectionComplete() {
        return natDetectionComplete;
    }

    /**
     * Checks if local node is behind NAT.
     */
    public boolean isBehindNat() {
        return detectedNatType != null && detectedNatType != NatType.OPEN;
    }

    /**
     * Enhances announcement with NAT information.
     */
    public void enhanceAnnouncement(Map<String, Object> announcement) {
        if (!natDetectionComplete) {
            log.debug("NAT detection not complete - announcement will not include NAT info");
            return;
        }

        // Add NAT type
        if (detectedNatType != null) {
            announcement.put("natType", detectedNatType.name());
            announcement.put("behindNat", isBehindNat());
        }

        // Add public endpoint if available
        if (publicEndpoint != null) {
            announcement.put("publicIp", publicEndpoint.getAddress().getHostAddress());
            announcement.put("publicPort", publicEndpoint.getPort());

            log.debug("Enhanced announcement with NAT info",
                    "natType", detectedNatType,
                    "publicIp", publicEndpoint.getAddress().getHostAddress());
        }
    }

    /**
     * Filters peer based on NAT compatibility.
     *
     * @param peer Discovered peer
     * @return true if peer is reachable from local NAT type
     */
    public boolean isPeerReachable(Peer peer) {
        if (peer == null) {
            return false;
        }

        // Check cache first
        String cacheKey = peer.id();
        Boolean cached = compatibilityCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // If NAT detection not complete, allow all peers
        if (!natDetectionComplete) {
            log.debug("NAT detection incomplete - allowing peer", "peerId", peer.id());
            return true;
        }

        NatType localNat = getNatType();
        NatType remoteNat = peer.natType();

        // OPEN internet - can reach anyone
        if (localNat == NatType.OPEN || remoteNat == NatType.OPEN) {
            compatibilityCache.put(cacheKey, true);
            return true;
        }

        // Check hole punching capability
        boolean canConnect = NatType.canHolePunch(localNat, remoteNat);
        compatibilityCache.put(cacheKey, canConnect);

        if (!canConnect) {
            log.debug("Peer not reachable due to NAT incompatibility",
                    "peerId", peer.id(),
                    "localNat", localNat,
                    "remoteNat", remoteNat);
            metrics.incrementCounter("nat.discovery.peer.filtered");
        }

        return canConnect;
    }

    /**
     * Gets NAT traversal service.
     */
    public INatTraversalService getNatService() {
        return natService;
    }

    /**
     * Clears compatibility cache (useful when NAT type changes).
     */
    public void clearCompatibilityCache() {
        compatibilityCache.clear();
        log.debug("Compatibility cache cleared");
    }
}

