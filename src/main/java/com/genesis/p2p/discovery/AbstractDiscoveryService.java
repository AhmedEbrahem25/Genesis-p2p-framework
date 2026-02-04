package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract base class for all discovery services.
 * Implements Template Method pattern for common discovery logic.
 */
public abstract class AbstractDiscoveryService implements IDiscoveryService {

    protected static final NodeLogger log = NodeLogger.getLogger(AbstractDiscoveryService.class);

    protected final NodeConfig config;
    protected final PeerManager peerManager;
    protected final MetricsRegistry metrics;
    protected final AtomicBoolean running;
    protected IDiscoveryListener listener;
    protected NatAwareDiscoveryContext natContext; // NAT-aware context

    /**
     * Creates a new abstract discovery service.
     *
     * @param config node configuration
     * @param peerManager peer manager for registering discovered peers
     * @param metrics metrics registry for observability (required - must not be null)
     * @throws NullPointerException if metrics is null
     */
    protected AbstractDiscoveryService(NodeConfig config, PeerManager peerManager, MetricsRegistry metrics) {
        this.config = java.util.Objects.requireNonNull(config, "NodeConfig cannot be null");
        this.peerManager = java.util.Objects.requireNonNull(peerManager, "PeerManager cannot be null");
        this.metrics = java.util.Objects.requireNonNull(metrics, "MetricsRegistry cannot be null - use shared instance from Node");
        this.running = new AtomicBoolean(false);
        this.listener = new DefaultDiscoveryListener();
        this.natContext = null; // Will be injected
    }

    /**
     * Sets NAT context for NAT-aware discovery.
     */
    public void setNatContext(NatAwareDiscoveryContext natContext) {
        this.natContext = natContext;
        log.info("NAT context configured for discovery", "type", getClass().getSimpleName());
    }

    @Override
    public final void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            log.info("Starting discovery service", "type", getClass().getSimpleName());
            try {
                doStart();
                log.info("Discovery service started", "type", getClass().getSimpleName());
            } catch (Exception e) {
                running.set(false);
                log.error("Failed to start discovery service", e);
                throw e;
            }
        } else {
            log.warn("Discovery service already running");
        }
    }

    @Override
    public final void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping discovery service", "type", getClass().getSimpleName());
            try {
                doStop();
                log.info("Discovery service stopped", "type", getClass().getSimpleName());
            } catch (Exception e) {
                log.error("Error stopping discovery service", e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public final CompletableFuture<List<Peer>> discoverPeers() {
        if (!isRunning()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Discovery service is not running")
            );
        }

        return doDiscoverPeers()
                .whenComplete((peers, error) -> {
                    if (error != null) {
                        notifyDiscoveryError(error);
                    } else if (peers != null) {
                        peers.forEach(this::notifyPeerDiscovered);
                    }
                });
    }

    @Override
    public void setDiscoveryListener(IDiscoveryListener listener) {
        this.listener = listener != null ? listener : new DefaultDiscoveryListener();
    }

    // ========================= Template Methods =========================

    /**
     * Template method for starting the discovery service.
     * Subclasses must implement their specific startup logic.
     */
    protected abstract void doStart() throws Exception;

    /**
     * Template method for stopping the discovery service.
     * Subclasses must implement their specific shutdown logic.
     */
    protected abstract void doStop();

    /**
     * Template method for discovering peers.
     * Subclasses must implement their specific discovery logic.
     *
     * @return future containing list of discovered peers
     */
    protected abstract CompletableFuture<List<Peer>> doDiscoverPeers();

    // ========================= Helper Methods =========================


    /**
     * Notifies listener of discovered peer and registers with peer manager.
     * ENFORCES NAT-aware filtering.
     */
    protected void notifyPeerDiscovered(Peer peer) {
        try {
            // Validate nodeId
            if (peer.id() == null || peer.id().trim().isEmpty()) {
                log.warn("Invalid peer discovered - empty nodeId");
                return;
            }

            // Ignore our own announcements
            if (peer.id().equals(config.nodeId())) {
                log.debug("Ignoring self-discovery");
                return;
            }

            // NAT-AWARE FILTERING - Check if peer is reachable
            if (natContext != null && !natContext.isPeerReachable(peer)) {
                log.warn("Peer filtered due to NAT incompatibility",
                        "peerId", peer.id(),
                        "peerNat", peer.natType(),
                        "localNat", natContext.getNatType());
                metrics.incrementCounter("discovery.peers.filtered.nat");
                return; // REJECT peer
            }

            if (listener != null) {
                listener.onPeerDiscovered(peer);
            }

            // Register with peer manager (guaranteed non-null by constructor)
            boolean added = peerManager.upsertPeer(peer);
            if (added) {
                log.info("Peer registered with PeerManager",
                        "peerId", peer.id(),
                        "ip", peer.ip(),
                        "publicIp", peer.publicIp(),
                        "natType", peer.natType());

                // Note: Event firing is handled by integration layer
                // PeerConnectionOrchestrator will be notified via PeerEventBus
            }

            metrics.incrementCounter("discovery.peers.discovered");
            log.debug("Peer discovered", "peerId", peer.id(), "ip", peer.ip());

        } catch (Exception e) {
            log.error("Error notifying peer discovered", e);
        }
    }

    /**
     * Notifies listener of lost peer.
     */
    protected void notifyPeerLost(Peer peer) {
        try {
            if (listener != null) {
                listener.onPeerLost(peer);
            }

            metrics.incrementCounter("discovery.peers.lost");
            log.debug("Peer lost", "peerId", peer.id());

        } catch (Exception e) {
            log.error("Error notifying peer lost", e);
        }
    }

    /**
     * Notifies listener of discovery error.
     */
    protected void notifyDiscoveryError(Throwable error) {
        try {
            if (listener != null) {
                listener.onDiscoveryError(error);
            }

            metrics.incrementCounter("discovery.errors");
            log.error("Discovery error", error);

        } catch (Exception e) {
            log.error("Error notifying discovery error", e);
        }
    }

    /**
     * Default discovery listener that does nothing.
     */
    private static class DefaultDiscoveryListener implements IDiscoveryListener {
        @Override
        public void onPeerDiscovered(Peer peer) {
            // Default: do nothing
        }

        @Override
        public void onPeerLost(Peer peer) {
            // Default: do nothing
        }

        @Override
        public void onDiscoveryError(Throwable error) {
            // Default: do nothing
        }
    }
}