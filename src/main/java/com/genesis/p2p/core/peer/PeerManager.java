package com.genesis.p2p.core.peer;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.storage.PersistenceFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Main facade for peer management in Genesis P2P Framework.
 * Orchestrates all peer-related services with a clean, unified API.
 *
 * This refactored version delegates to specialized services:
 * - PeerStore: Data storage and retrieval
 * - PeerReputationService: Reputation management
 * - PeerQueryService: Advanced querying
 * - PeerHealthMonitor: Health tracking
 * - PeerStateMachine: State transitions
 * - PeerCleanupService: TTL cleanup
 * - PeerMetricsService: Metrics collection
 * - PeerEventBus: Event notifications
 *
 * @author Genesis P2P Framework
 * @version 3.0 (Modular Architecture)
 */
public class PeerManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PeerManager.class);

    // Configuration
    private static final int DEFAULT_TRUST_THRESHOLD = 50;
    private static final int DEFAULT_MAX_PEERS = 1000;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofSeconds(10);

    // Services
    private final PeerStore store;
    private final PeerReputationService reputationService;
    private final PeerQueryService queryService;
    private final PeerHealthMonitor healthMonitor;
    private final PeerStateMachine stateMachine;
    private final PeerCleanupService cleanupService;
    private final PeerMetricsService metricsService;
    private final PeerEventBus eventBus;

    // Persistence
    private final PersistenceFacade persistence;

    // State
    private volatile boolean isRunning;

    // ========================= Constructors =========================

    /**
     * Creates a PeerManager with default configuration (no persistence).
     */
    public PeerManager() {
        this(null, DEFAULT_TRUST_THRESHOLD, DEFAULT_MAX_PEERS, DEFAULT_TTL, DEFAULT_CLEANUP_INTERVAL);
    }

    /**
     * Creates a PeerManager with persistence support.
     */
    public PeerManager(PersistenceFacade persistence) {
        this(persistence, DEFAULT_TRUST_THRESHOLD, DEFAULT_MAX_PEERS, DEFAULT_TTL, DEFAULT_CLEANUP_INTERVAL);
    }

    /**
     * Creates a PeerManager with custom configuration and optional persistence.
     */
    public PeerManager(PersistenceFacade persistence, int trustThreshold, int maxPeers, Duration ttl, Duration cleanupInterval) {
        log.info("Initializing PeerManager [trust={}, max={}, ttl={}, cleanup={}, persistence={}]",
                trustThreshold, maxPeers, ttl, cleanupInterval, persistence != null);

        this.persistence = persistence;

        // Initialize core components
        this.eventBus = new PeerEventBus();
        this.store = new PeerStore(maxPeers);

        // Initialize services
        this.reputationService = new PeerReputationService(trustThreshold, store, eventBus);
        this.queryService = new PeerQueryService(store, trustThreshold);
        this.healthMonitor = new PeerHealthMonitor(store, reputationService);
        this.stateMachine = new PeerStateMachine(store, eventBus);
        this.metricsService = new PeerMetricsService(store, queryService, reputationService);

        this.cleanupService = new PeerCleanupService(
                store, ttl, cleanupInterval,
                this::removePeer
        );

        // Restore persisted peers on startup
        if (persistence != null && persistence.isAvailable()) {
            try {
                List<Peer> restored = persistence.loadAllPeers();
                for (Peer peer : restored) {
                    store.put(peer.id(), peer);
                }
                log.info("✓ Restored {} peers from persistent storage", restored.size());
            } catch (Exception e) {
                log.error("Failed to restore peers from persistence", e);
            }
        }

        // Start background tasks
        this.cleanupService.start();
        this.isRunning = true;

        log.info("PeerManager initialized successfully");
    }

    // ========================= Core Operations =========================

    /**
     * Adds or updates a peer in the registry.
     */
    public boolean upsertPeer(Peer peer) {
        validatePeer(peer);
        ensureRunning();

        // Handle capacity
        if (!store.contains(peer.id()) && store.isFull()) {
            log.warn("Capacity reached, evicting lowest reputation peer");
            evictLowestReputationPeer();
        }

        Peer oldPeer = store.put(peer.id(), peer);

        if (oldPeer == null) {
            // New peer
            metricsService.recordPeerDiscovered();
            log.info("Peer added: {} [{}:{}]", peer.id(), peer.ip(), peer.port());
            eventBus.firePeerAdded(peer);
        } else {
            // Updated peer
            log.debug("Peer updated: {}", peer.id());
            eventBus.firePeerUpdated(oldPeer, peer);
        }

        healthMonitor.refreshLastSeen(peer.id());

        // Persist to storage
        if (persistence != null && persistence.isAvailable()) {
            try {
                persistence.savePeer(peer);
            } catch (Exception e) {
                log.error("Failed to persist peer to storage", "peerId", peer.id(), e);
                // Continue - in-memory data is still valid
            }
        }

        return true;
    }

    /**
     * Retrieves a peer by ID.
     */
    public Peer getPeer(String id) {
        return store.get(id).orElse(null);
    }

    /**
     * Gets all peers.
     */
    public Collection<Peer> getAllPeers() {
        return store.getAll();
    }

    /**
     * Removes a peer from the registry.
     */
    public boolean removePeer(String id, String reason) {
        boolean removed = store.remove(id).map(peer -> {
            metricsService.recordPeerRemoved();
            log.info("Peer removed: {} - Reason: {}", id, reason);
            eventBus.firePeerRemoved(peer, reason);

            // Delete from persistence
            if (persistence != null && persistence.isAvailable()) {
                try {
                    persistence.deletePeer(id);
                } catch (Exception e) {
                    log.error("Failed to delete peer from persistent storage", "peerId", id, e);
                }
            }

            return true;
        }).orElse(false);

        return removed;
    }

    /**
     * Checks if a peer exists.
     */
    public boolean containsPeer(String id) {
        return store.contains(id);
    }

    /**
     * Clears all peers.
     */
    public void clear() {
        store.clear();
    }

    /**
     * Gets current peer count.
     */
    public int size() {
        return store.size();
    }

    // ========================= Reputation Management =========================

    public int updateReputation(String id, int delta) {
        return reputationService.updateReputation(id, delta);
    }

    public int increaseReputation(String id) {
        return reputationService.increaseReputation(id);
    }

    public int decreaseReputation(String id) {
        return reputationService.decreaseReputation(id);
    }

    public boolean banPeer(String id, String reason) {
        boolean banned = reputationService.banPeer(id, reason);
        if (banned) {
            stateMachine.markBanned(id);
        }
        return banned;
    }

    // ========================= Querying =========================

    public List<Peer> getTrustedPeers() {
        return queryService.getTrustedPeers();
    }

    public List<Peer> getOnlinePeers() {
        return queryService.getOnlinePeers();
    }

    public List<Peer> filterPeers(Predicate<Peer> predicate) {
        return queryService.filterPeers(predicate);
    }

    public List<Peer> getTopPeersByReputation(int limit) {
        return queryService.getTopPeersByReputation(limit);
    }

    public List<Peer> getPeersByLowestLatency(int limit) {
        return queryService.getPeersByLowestLatency(limit);
    }

    public List<Peer> getPeersByVersion(String version) {
        return queryService.getPeersByVersion(version);
    }

    public List<Peer> getBestPeersForRouting(int limit) {
        return queryService.getBestPeersForRouting(limit);
    }

    public Peer getRandomTrustedPeer() {
        return queryService.getRandomTrustedPeer();
    }

    // ========================= Health Monitoring =========================

    public void refreshLastSeen(String id) {
        healthMonitor.refreshLastSeen(id);
    }

    public void updateLatency(String id, long latencyMs) {
        healthMonitor.updateLatency(id, latencyMs);
    }

    public void recordSuccess(String id) {
        healthMonitor.recordSuccess(id);
    }

    public void recordFailure(String id) {
        healthMonitor.recordFailure(id);
    }

    public void recordMessage(String id) {
        healthMonitor.recordMessage(id);
    }

    // ========================= State Management =========================

    public boolean setPeerState(String id, PeerStore.PeerState newState) {
        return stateMachine.transitionTo(id, newState);
    }

    public PeerStore.PeerState getPeerState(String id) {
        return stateMachine.getState(id);
    }

    public boolean markConnected(String id) {
        return stateMachine.markConnected(id);
    }

    public boolean markConnecting(String id) {
        return stateMachine.transitionTo(id, PeerStore.PeerState.CONNECTING);
    }

    public boolean isConnected(String id) {
        PeerStore.PeerState state = stateMachine.getState(id);
        return state == PeerStore.PeerState.CONNECTED || state == PeerStore.PeerState.AUTHENTICATED;
    }

    public boolean markAuthenticated(String id) {
        return stateMachine.markAuthenticated(id);
    }

    public boolean markDisconnected(String id) {
        return stateMachine.markDisconnected(id);
    }

    /**
     * Marks a peer as negotiating secure channel (Phase 1 of bootstrap).
     */
    public boolean markChannelNegotiating(String id) {
        return stateMachine.markChannelNegotiating(id);
    }

    /**
     * Marks a peer as having an established secure channel.
     */
    public boolean markChannelEstablished(String id) {
        return stateMachine.markChannelEstablished(id);
    }

    /**
     * Checks if a peer has a secure channel established.
     */
    public boolean hasSecureChannel(String id) {
        return stateMachine.hasSecureChannel(id);
    }

    // ========================= Cleanup & Maintenance =========================

    public int cleanupStalePeers() {
        return cleanupService.cleanupStalePeers();
    }

    private void evictLowestReputationPeer() {
        store.findLowestReputationPeer()
                .ifPresent(peer -> removePeer(peer.id(), "Capacity limit - lowest reputation"));
    }

    // ========================= Metrics =========================

    public PeerMetricsService.PeerMetrics getMetrics() {
        return metricsService.getMetrics();
    }

    // ========================= Event Management =========================

    public void addEventListener(PeerEventBus.PeerEventListener listener) {
        eventBus.register(listener);
    }

    public void removeEventListener(PeerEventBus.PeerEventListener listener) {
        eventBus.unregister(listener);
    }

    // ========================= Component Access =========================

    public PeerEventBus getEventBus() {
        return eventBus;
    }

    public PeerReputationService getReputationService() {
        return reputationService;
    }

    public PeerStore getPeerStore() {
        return store;
    }

    public PeerQueryService getQueryService() {
        return queryService;
    }

    public PeerHealthMonitor getHealthMonitor() {
        return healthMonitor;
    }

    // ========================= Lifecycle =========================

    private void ensureRunning() {
        if (!isRunning) {
            throw new IllegalStateException("PeerManager is shutdown");
        }
    }

    private void validatePeer(Peer peer) {
        if (peer == null) {
            throw new IllegalArgumentException("Peer cannot be null");
        }
    }

    @Override
    public void close() {
        if (!isRunning) {
            return;
        }

        log.info("Shutting down PeerManager...");
        isRunning = false;

        // Flush all peers to persistent storage before shutdown
        if (persistence != null && persistence.isAvailable()) {
            try {
                log.info("Flushing {} peers to persistent storage...", store.size());
                int flushed = 0;
                for (Peer peer : store.getAll()) {
                    try {
                        persistence.savePeer(peer);
                        flushed++;
                    } catch (Exception e) {
                        log.error("Failed to flush peer during shutdown", "peerId", peer.id(), e);
                    }
                }
                log.info("✓ Flushed {} peers to persistent storage", flushed);
            } catch (Exception e) {
                log.error("Error during peer persistence flush", e);
            }
        }

        cleanupService.close();

        log.info("PeerManager shutdown complete [finalPeerCount={}]", store.size());
    }

    @Override
    public String toString() {
        PeerMetricsService.PeerMetrics metrics = getMetrics();
        return String.format(
                "PeerManager[peers=%d, trusted=%d, online=%d, avgRep=%.2f, avgLatency=%.2fms]",
                metrics.totalPeers(), metrics.trustedPeers(), metrics.onlinePeers(),
                metrics.averageReputation(), metrics.averageLatency()
        );
    }
}