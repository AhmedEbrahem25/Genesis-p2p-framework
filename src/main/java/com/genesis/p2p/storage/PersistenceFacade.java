package com.genesis.p2p.storage;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified facade for all persistence operations.
 *
 * Provides single access point for persistent storage of:
 * - Peer data (for restart recovery)
 * - Dead letter queue (failed messages)
 *
 * Design Patterns:
 * - Facade Pattern: Unified interface to persistence subsystem
 * - Lazy Initialization: Only created when persistence enabled
 * - AutoCloseable: Proper resource management
 *
 * Features:
 * - Configuration-driven (persistence.enabled)
 * - Lifecycle management (init, start, stop)
 * - Metrics integration
 * - Health monitoring
 * - Thread-safe operations
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class PersistenceFacade implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(PersistenceFacade.class);

    private final NodeConfig config;
    private final MetricsRegistry metrics;
    private final Path dataDir;

    // Persistence components
    private PeerStorePersistent peerStore;
    private DeadLetterQueuePersistent dlq;

    // State tracking
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Metrics
    private final AtomicLong peerSaveCount = new AtomicLong(0);
    private final AtomicLong peerLoadCount = new AtomicLong(0);
    private final AtomicLong dlqAddCount = new AtomicLong(0);
    private final AtomicLong dlqPollCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    /**
     * Creates persistence facade.
     *
     * @param config node configuration
     * @param metrics metrics registry
     */
    public PersistenceFacade(NodeConfig config, MetricsRegistry metrics) {
        this.config = config;
        this.metrics = metrics;
        this.dataDir = Paths.get(config.persistenceDir());

        log.info("PersistenceFacade created",
                "enabled", config.persistenceEnabled(),
                "dataDir", dataDir);
    }

    /**
     * Initializes persistence subsystem.
     * Creates storage directories and initializes stores.
     *
     * @throws KVStore.StorageException if initialization fails
     */
    public void init() throws KVStore.StorageException {
        if (!config.persistenceEnabled()) {
            log.info("Persistence disabled - skipping initialization");
            return;
        }

        if (started.get()) {
            log.warn("Persistence already initialized");
            return;
        }

        log.info("Initializing persistence subsystem...");

        try {
            // Create data directory structure
            log.info("Creating persistence directory structure", "baseDir", dataDir);

            boolean created = dataDir.toFile().mkdirs();
            if (created) {
                log.info("✓ Data directory created", "path", dataDir.toAbsolutePath());
            } else if (dataDir.toFile().exists()) {
                log.info("✓ Data directory already exists", "path", dataDir.toAbsolutePath());
            } else {
                throw new KVStore.StorageException("Failed to create data directory: " + dataDir);
            }

            // Create subdirectories
            Path peersDir = dataDir.resolve("peers");
            peersDir.toFile().mkdirs();
            log.debug("✓ Peers directory: " + peersDir);

            Path messagesDir = dataDir.resolve("messages");
            messagesDir.toFile().mkdirs();
            log.debug("✓ Messages directory: " + messagesDir);

            // Initialize peer store
            peerStore = new PeerStorePersistent(dataDir);
            log.info("✓ Peer store initialized");

            // Initialize DLQ
            dlq = new DeadLetterQueuePersistent(dataDir);
            log.info("✓ Dead letter queue initialized");

            started.set(true);
            log.info("Persistence subsystem initialized successfully");

        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to initialize persistence subsystem", e);
            throw e;
        }
    }

    /**
     * Starts persistence subsystem.
     * Performs any startup tasks like loading cached data.
     */
    public void start() {
        if (!config.persistenceEnabled()) {
            return;
        }

        if (!started.get()) {
            log.warn("Persistence not initialized - call init() first");
            return;
        }

        log.info("Starting persistence subsystem...");

        // Register metrics
        registerMetrics();

        log.info("Persistence subsystem started");
    }

    /**
     * Stops persistence subsystem.
     * Flushes all data to disk.
     */
    public void stop() {
        if (!config.persistenceEnabled() || !started.get()) {
            return;
        }

        log.info("Stopping persistence subsystem...");

        try {
            flush();
            log.info("Persistence subsystem stopped");
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error stopping persistence subsystem", e);
        }
    }

    // ==================== Peer Store Operations ====================

    /**
     * Saves a peer to persistent storage.
     *
     * @param peer the peer to save
     */
    public void savePeer(Peer peer) {
        if (!isAvailable()) {
            return;
        }

        try {
            peerStore.savePeer(peer);
            peerSaveCount.incrementAndGet();
            metrics.incrementCounter("persistence.peer.save");
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to save peer", e, "peerId", peer.id());
            metrics.incrementCounter("persistence.error.peer_save");
        }
    }

    /**
     * Loads a peer from persistent storage.
     *
     * @param peerId the peer ID
     * @return optional peer, empty if not found
     */
    public Optional<Peer> loadPeer(String peerId) {
        if (!isAvailable()) {
            return Optional.empty();
        }

        try {
            Optional<Peer> peer = peerStore.loadPeer(peerId);
            if (peer.isPresent()) {
                peerLoadCount.incrementAndGet();
                metrics.incrementCounter("persistence.peer.load");
            }
            return peer;
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to load peer", e, "peerId", peerId);
            metrics.incrementCounter("persistence.error.peer_load");
            return Optional.empty();
        }
    }

    /**
     * Loads all peers from persistent storage.
     *
     * @return list of all persisted peers
     */
    public List<Peer> loadAllPeers() {
        if (!isAvailable()) {
            return List.of();
        }

        try {
            List<Peer> peers = peerStore.loadAllPeers();
            peerLoadCount.addAndGet(peers.size());
            log.info("Loaded all peers from storage", "count", peers.size());
            return peers;
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to load all peers", e);
            metrics.incrementCounter("persistence.error.peer_load_all");
            return List.of();
        }
    }

    /**
     * Deletes a peer from persistent storage.
     *
     * @param peerId the peer ID
     */
    public void deletePeer(String peerId) {
        if (!isAvailable()) {
            return;
        }

        try {
            peerStore.deletePeer(peerId);
            metrics.incrementCounter("persistence.peer.delete");
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to delete peer", "peerId", peerId, e);
            metrics.incrementCounter("persistence.error.peer_delete");
        }
    }

    // ==================== DLQ Operations ====================

    /**
     * Adds a failed message to the DLQ.
     *
     * @param message the failed message
     * @param reason the failure reason
     * @param retryCount number of retry attempts
     */
    public void addToDeadLetterQueue(Message message, String reason, int retryCount) {
        if (!isAvailable()) {
            return;
        }

        try {
            dlq.add(message, reason, retryCount);
            dlqAddCount.incrementAndGet();
            metrics.incrementCounter("persistence.dlq.add");
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to add message to DLQ", e, "reason", reason);
            metrics.incrementCounter("persistence.error.dlq_add");
        }
    }

    /**
     * Gets and removes the next message from the DLQ.
     *
     * @return optional DLQ entry, empty if queue is empty
     */
    public Optional<DeadLetterQueuePersistent.DLQEntry> pollDeadLetter() {
        if (!isAvailable()) {
            return Optional.empty();
        }

        try {
            Optional<DeadLetterQueuePersistent.DLQEntry> entry = dlq.poll();
            if (entry.isPresent()) {
                dlqPollCount.incrementAndGet();
                metrics.incrementCounter("persistence.dlq.poll");
            }
            return entry;
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to poll DLQ", e);
            metrics.incrementCounter("persistence.error.dlq_poll");
            return Optional.empty();
        }
    }

    /**
     * Gets all messages from the DLQ without removing them.
     *
     * @return list of all DLQ entries
     */
    public List<DeadLetterQueuePersistent.DLQEntry> getAllDeadLetters() {
        if (!isAvailable()) {
            return List.of();
        }

        try {
            return dlq.getAll();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to get all DLQ entries", e);
            metrics.incrementCounter("persistence.error.dlq_get_all");
            return List.of();
        }
    }

    /**
     * Gets DLQ statistics.
     *
     * @return DLQ stats, or null if not available
     */
    public DeadLetterQueuePersistent.DLQStats getDLQStats() {
        if (!isAvailable()) {
            return null;
        }

        try {
            return dlq.getStats();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to get DLQ stats", e);
            return null;
        }
    }

    // ==================== Lifecycle ====================

    /**
     * Flushes all pending data to disk.
     */
    public void flush() {
        if (!isAvailable()) {
            return;
        }

        try {
            log.debug("Flushing persistence to disk...");
            peerStore.flush();
            dlq.flush();
            log.debug("Persistence flushed successfully");
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Failed to flush persistence", e);
            metrics.incrementCounter("persistence.error.flush");
        }
    }

    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }

        if (!config.persistenceEnabled()) {
            return;
        }

        log.info("Closing persistence subsystem...");

        try {
            if (peerStore != null) {
                peerStore.close();
            }
            if (dlq != null) {
                dlq.close();
            }
            log.info("Persistence subsystem closed");
        } catch (Exception e) {
            log.error("Error closing persistence subsystem", e);
        }
    }

    // ==================== Status & Health ====================

    /**
     * Checks if persistence is available for use.
     *
     * @return true if enabled and initialized
     */
    public boolean isAvailable() {
        return config.persistenceEnabled() && started.get() && !closed.get();
    }

    /**
     * Gets persistence health status.
     *
     * @return health status
     */
    public PersistenceHealth getHealth() {
        if (!config.persistenceEnabled()) {
            return new PersistenceHealth(false, "disabled", 0, 0, 0);
        }

        if (!started.get()) {
            return new PersistenceHealth(false, "not_started", 0, 0, errorCount.get());
        }

        if (closed.get()) {
            return new PersistenceHealth(false, "closed", 0, 0, errorCount.get());
        }

        try {
            int peerCount = peerStore.size();
            int dlqSize = dlq.size();
            return new PersistenceHealth(true, "healthy", peerCount, dlqSize, errorCount.get());
        } catch (Exception e) {
            return new PersistenceHealth(false, "error: " + e.getMessage(), 0, 0, errorCount.get());
        }
    }

    /**
     * Gets persistence statistics.
     *
     * @return statistics
     */
    public PersistenceStats getStats() {
        return new PersistenceStats(
                peerSaveCount.get(),
                peerLoadCount.get(),
                dlqAddCount.get(),
                dlqPollCount.get(),
                errorCount.get()
        );
    }

    // ==================== Metrics ====================

    private void registerMetrics() {
        metrics.setGauge("persistence.enabled", config.persistenceEnabled() ? 1 : 0);
        log.debug("Registered persistence metrics");
    }

    // ==================== Data Classes ====================

    /**
     * Persistence health status.
     */
    public record PersistenceHealth(
            boolean healthy,
            String status,
            int persistedPeerCount,
            int dlqSize,
            long errorCount
    ) {
        @Override
        public String toString() {
            return String.format("PersistenceHealth[healthy=%s, status=%s, peers=%d, dlq=%d, errors=%d]",
                    healthy, status, persistedPeerCount, dlqSize, errorCount);
        }
    }

    /**
     * Persistence statistics.
     */
    public record PersistenceStats(
            long peerSaves,
            long peerLoads,
            long dlqAdds,
            long dlqPolls,
            long errors
    ) {
        @Override
        public String toString() {
            return String.format("PersistenceStats[saves=%d, loads=%d, dlqAdds=%d, dlqPolls=%d, errors=%d]",
                    peerSaves, peerLoads, dlqAdds, dlqPolls, errors);
        }
    }
}
