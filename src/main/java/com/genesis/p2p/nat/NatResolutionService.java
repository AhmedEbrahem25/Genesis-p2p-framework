package com.genesis.p2p.nat;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerStore;
import com.genesis.p2p.events.core.EventBus;
import com.genesis.p2p.events.core.GenericEvent;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous NAT resolution service - fully decoupled from handshake.
 *
 * This service handles NAT detection and resolution as a separate, non-blocking
 * process. Key principles:
 *
 * 1. NAT resolution is ASYNC - never blocks connection/authentication
 * 2. NAT failures are NON-FATAL state signals - peers remain connected
 * 3. Peers can send/receive messages (ping, etc.) pre-NAT resolution
 * 4. NAT info is used for OPTIMIZATION (hole punch, relay) not gating
 *
 * Flow:
 * 1. Handshake completes → Peer is CONNECTED/AUTHENTICATED
 * 2. NatResolutionService starts async NAT detection
 * 3. Updates peer metadata with NAT info when available
 * 4. Fires events for interested components
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class NatResolutionService {

    private static final NodeLogger log = NodeLogger.getLogger(NatResolutionService.class);

    private final String nodeId;
    private final PeerStore peerStore;
    private final INatTraversalService natService;
    private final EventBus eventBus;
    private final MetricsRegistry metrics;

    private final AtomicBoolean running;
    private final ExecutorService executor;
    private final Map<String, CompletableFuture<NatResolutionResult>> pendingResolutions;

    // Configuration
    private static final int MAX_CONCURRENT_RESOLUTIONS = 20;
    private static final long RESOLUTION_TIMEOUT_MS = 10000; // 10 seconds
    private final Semaphore resolutionSemaphore;

    // Local NAT info (cached)
    private volatile NatType localNatType;
    private volatile InetSocketAddress localPublicEndpoint;
    private volatile boolean localNatDetected;

    public NatResolutionService(
            String nodeId,
            PeerStore peerStore,
            INatTraversalService natService,
            EventBus eventBus,
            MetricsRegistry metrics) {

        this.nodeId = nodeId;
        this.peerStore = peerStore;
        this.natService = natService;
        this.eventBus = eventBus;
        this.metrics = metrics;

        this.running = new AtomicBoolean(false);
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "NatResolution-" + System.currentTimeMillis() % 1000);
            t.setDaemon(true);
            return t;
        });
        this.pendingResolutions = new ConcurrentHashMap<>();
        this.resolutionSemaphore = new Semaphore(MAX_CONCURRENT_RESOLUTIONS);

        this.localNatType = NatType.UNKNOWN;
        this.localNatDetected = false;
    }

    /**
     * Starts the NAT resolution service.
     * Detects local NAT type asynchronously (non-blocking).
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting NAT resolution service");

            // Detect local NAT type asynchronously (NON-BLOCKING)
            if (natService != null) {
                detectLocalNatAsync();
            } else {
                log.info("NAT service not available - NAT resolution will be skipped for all peers");
                localNatType = NatType.UNKNOWN;
                localNatDetected = true;
            }

            log.info("NAT resolution service started - ready to resolve peer NAT types");
        }
    }

    /**
     * Detects local NAT type asynchronously.
     * This is NON-BLOCKING - the service is immediately ready to handle requests.
     */
    private void detectLocalNatAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("Detecting local NAT type (async)...");

                NatType detectedType = natService.detectNatType()
                        .get(RESOLUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                this.localNatType = detectedType;
                this.localNatDetected = true;

                log.info("Local NAT type detected",
                        "type", detectedType.name(),
                        "description", detectedType.getDescription());

                metrics.setGauge("nat.local.type", detectedType.getSeverity());

                // Get public endpoint
                InetSocketAddress endpoint = natService.getPublicEndpoint(0)
                        .get(RESOLUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (endpoint != null) {
                    this.localPublicEndpoint = endpoint;
                    log.info("Local public endpoint detected",
                            "ip", endpoint.getAddress().getHostAddress(),
                            "port", endpoint.getPort());
                }

                // Fire event
                Map<String, Object> eventData = new HashMap<>();
                eventData.put("natType", detectedType.name());
                eventData.put("publicEndpoint", endpoint != null ? endpoint.toString() : "unknown");
                eventBus.publish(new GenericEvent("nat.local.detected", nodeId, eventData));

            } catch (Exception e) {
                log.warn("Local NAT detection failed (non-fatal)",
                        "error", e.getMessage());
                this.localNatType = NatType.UNKNOWN;
                this.localNatDetected = true;
                metrics.incrementCounter("nat.local.detection.failed");
            }
        }, executor);
    }

    /**
     * Resolves NAT information for a peer asynchronously.
     *
     * This is NON-BLOCKING and NON-FATAL:
     * - Returns immediately with a future
     * - Peer remains CONNECTED even if NAT resolution fails
     * - NAT info is used for optimization, not gating
     *
     * @param peerId the peer ID
     * @return future with NAT resolution result
     */
    public CompletableFuture<NatResolutionResult> resolveAsync(String peerId) {
        // Check if already resolving
        CompletableFuture<NatResolutionResult> existing = pendingResolutions.get(peerId);
        if (existing != null) {
            log.debug("NAT resolution already in progress", "peerId", peerId);
            return existing;
        }

        // Check if NAT service is available
        if (natService == null) {
            log.debug("NAT service not available - skipping resolution", "peerId", peerId);
            peerStore.setNatStatus(peerId, PeerStore.NatResolutionStatus.SKIPPED);
            return CompletableFuture.completedFuture(
                    NatResolutionResult.skipped(peerId, "NAT service not available"));
        }

        // Mark as resolving
        peerStore.setNatStatus(peerId, PeerStore.NatResolutionStatus.RESOLVING);
        log.debug("Starting NAT resolution", "peerId", peerId);
        metrics.incrementCounter("nat.resolution.started");

        // Create resolution future
        CompletableFuture<NatResolutionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                // Acquire semaphore
                if (!resolutionSemaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                    log.warn("NAT resolution semaphore timeout", "peerId", peerId);
                    return NatResolutionResult.failed(peerId, "Resolution queue full");
                }

                try {
                    return performResolution(peerId);
                } finally {
                    resolutionSemaphore.release();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return NatResolutionResult.failed(peerId, "Resolution interrupted");
            } catch (Exception e) {
                log.warn("NAT resolution error (non-fatal)",
                        "peerId", peerId,
                        "error", e.getMessage());
                return NatResolutionResult.failed(peerId, e.getMessage());
            }
        }, executor);

        // Store pending resolution
        pendingResolutions.put(peerId, future);

        // Cleanup on completion
        future.whenComplete((result, error) -> {
            pendingResolutions.remove(peerId);

            if (result != null) {
                updatePeerNatStatus(peerId, result);
                fireNatResolvedEvent(peerId, result);
            }
        });

        return future;
    }

    /**
     * Performs the actual NAT resolution.
     */
    private NatResolutionResult performResolution(String peerId) {
        Peer peer = peerStore.get(peerId).orElse(null);
        if (peer == null) {
            return NatResolutionResult.failed(peerId, "Peer not found");
        }

        // If peer already has NAT info, use it
        if (peer.natType() != null && peer.natType() != NatType.UNKNOWN) {
            log.debug("Peer already has NAT info", "peerId", peerId, "natType", peer.natType());
            return NatResolutionResult.resolved(peerId, peer.natType(),
                    new InetSocketAddress(peer.publicIp(), peer.publicPort()));
        }

        // Determine connection strategy (informational only, not blocking)
        ConnectionStrategy strategy = ConnectionStrategy.selectStrategy(localNatType, peer);

        log.info("NAT resolution complete",
                "peerId", peerId,
                "localNat", localNatType.name(),
                "remoteNat", peer.natType().name(),
                "strategy", strategy.name(),
                "successProbability", strategy.getSuccessProbability() + "%");

        metrics.incrementCounter("nat.resolution.completed");
        metrics.incrementCounter("nat.strategy." + strategy.name().toLowerCase());

        return NatResolutionResult.resolved(peerId, peer.natType(),
                peer.getPublicAddress());
    }

    /**
     * Updates peer NAT status based on resolution result.
     */
    private void updatePeerNatStatus(String peerId, NatResolutionResult result) {
        if (result.isResolved()) {
            peerStore.setNatStatus(peerId, PeerStore.NatResolutionStatus.RESOLVED);
            log.debug("Peer NAT status updated to RESOLVED", "peerId", peerId);
        } else if (result.isSkipped()) {
            peerStore.setNatStatus(peerId, PeerStore.NatResolutionStatus.SKIPPED);
            log.debug("Peer NAT status updated to SKIPPED", "peerId", peerId);
        } else {
            peerStore.setNatFailed(peerId, result.getError());
            log.debug("Peer NAT status updated to FAILED (non-fatal)",
                    "peerId", peerId,
                    "error", result.getError());
        }
    }

    /**
     * Fires NAT resolved event.
     */
    private void fireNatResolvedEvent(String peerId, NatResolutionResult result) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("peerId", peerId);
        eventData.put("status", result.getStatus().name());
        eventData.put("natType", result.getNatType() != null ? result.getNatType().name() : "UNKNOWN");
        eventData.put("strategy", result.getRecommendedStrategy() != null ?
                result.getRecommendedStrategy().name() : "UNKNOWN");

        eventBus.publish(new GenericEvent("nat.peer.resolved", nodeId, eventData));
    }

    /**
     * Gets the local NAT type.
     */
    public NatType getLocalNatType() {
        return localNatType;
    }

    /**
     * Gets the local public endpoint.
     */
    public InetSocketAddress getLocalPublicEndpoint() {
        return localPublicEndpoint;
    }

    /**
     * Checks if local NAT has been detected.
     */
    public boolean isLocalNatDetected() {
        return localNatDetected;
    }

    /**
     * Gets the recommended connection strategy for a peer.
     * This is NON-BLOCKING and returns immediately based on cached info.
     */
    public ConnectionStrategy getRecommendedStrategy(Peer peer) {
        if (peer == null) {
            return ConnectionStrategy.IMPOSSIBLE;
        }

        // If local NAT not yet detected, assume DIRECT is possible
        if (!localNatDetected) {
            log.debug("Local NAT not yet detected - assuming DIRECT strategy",
                    "peerId", peer.id());
            return ConnectionStrategy.DIRECT;
        }

        return ConnectionStrategy.selectStrategy(localNatType, peer);
    }

    /**
     * Checks if a connection strategy is viable (informational only).
     * This does NOT block the connection - it's for optimization hints.
     */
    public boolean isStrategyViable(ConnectionStrategy strategy) {
        switch (strategy) {
            case DIRECT:
                return true; // Always try direct first
            case HOLE_PUNCH:
                return natService != null;
            case RELAY:
                return false; // Not yet implemented
            case IMPOSSIBLE:
                return false;
            default:
                return true;
        }
    }

    /**
     * Stops the NAT resolution service.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping NAT resolution service");

            // Cancel all pending resolutions
            pendingResolutions.values().forEach(f -> f.cancel(true));
            pendingResolutions.clear();

            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            log.info("NAT resolution service stopped");
        }
    }

    /**
     * Checks if the service is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    // ========================= Result Class =========================

    /**
     * Result of NAT resolution for a peer.
     */
    public static class NatResolutionResult {

        public enum Status {
            RESOLVED,
            FAILED,
            SKIPPED
        }

        private final String peerId;
        private final Status status;
        private final NatType natType;
        private final InetSocketAddress publicEndpoint;
        private final ConnectionStrategy recommendedStrategy;
        private final String error;

        private NatResolutionResult(String peerId, Status status, NatType natType,
                                    InetSocketAddress publicEndpoint,
                                    ConnectionStrategy recommendedStrategy, String error) {
            this.peerId = peerId;
            this.status = status;
            this.natType = natType;
            this.publicEndpoint = publicEndpoint;
            this.recommendedStrategy = recommendedStrategy;
            this.error = error;
        }

        public static NatResolutionResult resolved(String peerId, NatType natType,
                                                   InetSocketAddress publicEndpoint) {
            ConnectionStrategy strategy = natType != null && natType != NatType.UNKNOWN ?
                    ConnectionStrategy.DIRECT : ConnectionStrategy.DIRECT;
            return new NatResolutionResult(peerId, Status.RESOLVED, natType,
                    publicEndpoint, strategy, null);
        }

        public static NatResolutionResult failed(String peerId, String error) {
            return new NatResolutionResult(peerId, Status.FAILED, NatType.UNKNOWN,
                    null, ConnectionStrategy.DIRECT, error);
        }

        public static NatResolutionResult skipped(String peerId, String reason) {
            return new NatResolutionResult(peerId, Status.SKIPPED, NatType.UNKNOWN,
                    null, ConnectionStrategy.DIRECT, reason);
        }

        public String getPeerId() {
            return peerId;
        }

        public Status getStatus() {
            return status;
        }

        public NatType getNatType() {
            return natType;
        }

        public InetSocketAddress getPublicEndpoint() {
            return publicEndpoint;
        }

        public ConnectionStrategy getRecommendedStrategy() {
            return recommendedStrategy;
        }

        public String getError() {
            return error;
        }

        public boolean isResolved() {
            return status == Status.RESOLVED;
        }

        public boolean isFailed() {
            return status == Status.FAILED;
        }

        public boolean isSkipped() {
            return status == Status.SKIPPED;
        }

        @Override
        public String toString() {
            return String.format("NatResolutionResult[peer=%s, status=%s, nat=%s, strategy=%s]",
                    peerId, status, natType, recommendedStrategy);
        }
    }
}
