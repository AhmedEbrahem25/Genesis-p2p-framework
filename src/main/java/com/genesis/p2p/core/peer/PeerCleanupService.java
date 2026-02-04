package com.genesis.p2p.core.peer;

import com.genesis.p2p.util.threading.ThreadPoolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automatic TTL-based cleanup service for stale peers.
 * Runs periodic cleanup tasks on a background thread.
 *
 * Features:
 * - Automatic TTL-based expiration
 * - Configurable cleanup intervals
 * - Manual cleanup triggers
 * - Cleanup statistics
 * - Graceful shutdown
 * - Multiple cleanup strategies
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class PeerCleanupService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PeerCleanupService.class);

    private final PeerStore store;
    private final Duration ttl;
    private final Duration cleanupInterval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;
    private final RemovalCallback removalCallback;

    // Statistics
    private final AtomicLong totalCleanupRuns;
    private final AtomicLong totalPeersRemoved;
    private final AtomicInteger lastCleanupCount;
    private volatile Instant lastCleanupTime;
    private volatile Duration lastCleanupDuration;

    // Cleanup strategies
    private final Set<CleanupStrategy> strategies;

    /**
     * Callback interface for peer removal.
     */
    @FunctionalInterface
    public interface RemovalCallback {
        /**
         * Called when a peer should be removed.
         *
         * @param id peer ID
         * @param reason removal reason
         */
        void onRemove(String id, String reason);
    }

    /**
     * Creates a cleanup service with default strategies.
     *
     * @param store peer storage
     * @param ttl time-to-live for peers
     * @param cleanupInterval interval between cleanup runs
     * @param removalCallback callback for peer removal
     */
    public PeerCleanupService(PeerStore store, Duration ttl, Duration cleanupInterval,
                              RemovalCallback removalCallback) {
        this(store, ttl, cleanupInterval, removalCallback,
                EnumSet.of(CleanupStrategy.TTL_EXPIRED));
    }

    /**
     * Creates a cleanup service with custom strategies.
     *
     * @param store peer storage
     * @param ttl time-to-live for peers
     * @param cleanupInterval interval between cleanup runs
     * @param removalCallback callback for peer removal
     * @param strategies cleanup strategies to use
     */
    public PeerCleanupService(PeerStore store, Duration ttl, Duration cleanupInterval,
                              RemovalCallback removalCallback, Set<CleanupStrategy> strategies) {
        this.store = Objects.requireNonNull(store, "PeerStore cannot be null");
        this.ttl = Objects.requireNonNull(ttl, "TTL cannot be null");
        this.cleanupInterval = Objects.requireNonNull(cleanupInterval, "Cleanup interval cannot be null");
        this.removalCallback = Objects.requireNonNull(removalCallback, "Removal callback cannot be null");
        this.strategies = EnumSet.copyOf(strategies);
        this.running = new AtomicBoolean(false);

        this.scheduler = ThreadPoolFactory.createNamedScheduler(
                "PeerCleanup", "service");

        this.totalCleanupRuns = new AtomicLong(0);
        this.totalPeersRemoved = new AtomicLong(0);
        this.lastCleanupCount = new AtomicInteger(0);
        this.lastCleanupTime = null;
        this.lastCleanupDuration = Duration.ZERO;

        log.info("PeerCleanupService initialized [ttl={}, interval={}, strategies={}]",
                ttl, cleanupInterval, strategies);
    }

    // ========================= Lifecycle =========================

    /**
     * Starts the automatic cleanup task.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(
                    this::performCleanup,
                    cleanupInterval.toMillis(),
                    cleanupInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            log.info("Cleanup task started [interval={}]", cleanupInterval);
        } else {
            log.warn("Cleanup service already running");
        }
    }

    /**
     * Stops the cleanup service.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping cleanup service...");
        }
    }

    /**
     * Checks if cleanup service is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        stop();

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> pending = scheduler.shutdownNow();
                log.warn("Cleanup service forcibly shutdown [pending={}]", pending.size());
            } else {
                log.info("Cleanup service shutdown gracefully");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("Cleanup service shutdown interrupted", e);
        }

        log.info("PeerCleanupService closed [totalRuns={}, totalRemoved={}]",
                totalCleanupRuns.get(), totalPeersRemoved.get());
    }

    // ========================= Cleanup Operations =========================

    /**
     * Performs a cleanup sweep using configured strategies.
     */
    private void performCleanup() {
        if (!running.get()) {
            return;
        }

        Instant startTime = Instant.now();

        try {
            totalCleanupRuns.incrementAndGet();

            int removed = 0;
            for (CleanupStrategy strategy : strategies) {
                removed += executeStrategy(strategy);
            }

            lastCleanupCount.set(removed);
            totalPeersRemoved.addAndGet(removed);
            lastCleanupTime = Instant.now();
            lastCleanupDuration = Duration.between(startTime, lastCleanupTime);

            if (removed > 0) {
                log.info("Cleanup completed: {} peers removed [duration={}ms]",
                        removed, lastCleanupDuration.toMillis());
            } else {
                log.debug("Cleanup completed: no peers removed");
            }

        } catch (Exception e) {
            log.error("Error during cleanup sweep", e);
        }
    }

    private int executeStrategy(CleanupStrategy strategy) {
        return switch (strategy) {
            case TTL_EXPIRED -> cleanupByTTL();
            case OFFLINE_PEERS -> cleanupOfflinePeers();
            case ZERO_REPUTATION -> cleanupZeroReputationPeers();
            case BANNED_PEERS -> cleanupBannedPeers();
        };
    }

    /**
     * Removes peers that haven't been seen within TTL period.
     *
     * @return number of peers removed
     */
    public int cleanupByTTL() {
        Instant cutoff = Instant.now().minus(ttl);
        List<String> staleIds = new ArrayList<>();

        store.getMetadataEntries().forEach(entry -> {
            String id = entry.getKey();
            PeerStore.PeerMetadata meta = entry.getValue();

            if (meta.lastSeenAt.isBefore(cutoff)) {
                staleIds.add(id);
            }
        });

        staleIds.forEach(id -> removalCallback.onRemove(id, "TTL expired"));

        return staleIds.size();
    }

    /**
     * Removes offline peers that have been offline for too long.
     *
     * @return number of peers removed
     */
    public int cleanupOfflinePeers() {
        Instant cutoff = Instant.now().minus(ttl.multipliedBy(2));
        List<String> offlineIds = new ArrayList<>();

        store.getAll().forEach(peer -> {
            if (!peer.online() && peer.lastSeen().isBefore(cutoff)) {
                offlineIds.add(peer.id());
            }
        });

        offlineIds.forEach(id -> removalCallback.onRemove(id, "Offline too long"));

        return offlineIds.size();
    }

    /**
     * Removes peers with zero reputation.
     *
     * @return number of peers removed
     */
    public int cleanupZeroReputationPeers() {
        List<String> zeroRepIds = new ArrayList<>();

        store.getAll().forEach(peer -> {
            if (peer.reputation() == 0) {
                zeroRepIds.add(peer.id());
            }
        });

        zeroRepIds.forEach(id -> removalCallback.onRemove(id, "Zero reputation"));

        return zeroRepIds.size();
    }

    /**
     * Removes banned peers.
     *
     * @return number of peers removed
     */
    public int cleanupBannedPeers() {
        List<String> bannedIds = new ArrayList<>();

        store.getMetadataEntries().forEach(entry -> {
            if (entry.getValue().state == PeerStore.PeerState.BANNED) {
                bannedIds.add(entry.getKey());
            }
        });

        bannedIds.forEach(id -> removalCallback.onRemove(id, "Banned"));

        return bannedIds.size();
    }

    /**
     * Performs all cleanup strategies immediately.
     *
     * @return total number of peers removed
     */
    public int cleanupStalePeers() {
        return cleanupByTTL();
    }

    /**
     * Manual trigger for immediate cleanup.
     *
     * @return number of peers removed
     */
    public int forceCleanup() {
        log.info("Manual cleanup triggered");

        Instant startTime = Instant.now();
        int removed = 0;

        for (CleanupStrategy strategy : strategies) {
            removed += executeStrategy(strategy);
        }

        Duration duration = Duration.between(startTime, Instant.now());
        log.info("Manual cleanup completed: {} peers removed [duration={}ms]",
                removed, duration.toMillis());

        return removed;
    }

    /**
     * Performs cleanup using a specific strategy.
     *
     * @param strategy the cleanup strategy to use
     * @return number of peers removed
     */
    public int cleanupWithStrategy(CleanupStrategy strategy) {
        Objects.requireNonNull(strategy, "Strategy cannot be null");
        log.info("Cleanup triggered with strategy: {}", strategy);
        return executeStrategy(strategy);
    }

    // ========================= Configuration =========================

    /**
     * Adds a cleanup strategy.
     *
     * @param strategy strategy to add
     */
    public void addStrategy(CleanupStrategy strategy) {
        Objects.requireNonNull(strategy, "Strategy cannot be null");
        if (strategies.add(strategy)) {
            log.info("Cleanup strategy added: {}", strategy);
        }
    }

    /**
     * Removes a cleanup strategy.
     *
     * @param strategy strategy to remove
     */
    public void removeStrategy(CleanupStrategy strategy) {
        if (strategies.remove(strategy)) {
            log.info("Cleanup strategy removed: {}", strategy);
        }
    }

    /**
     * Gets active cleanup strategies.
     *
     * @return unmodifiable set of strategies
     */
    public Set<CleanupStrategy> getStrategies() {
        return Collections.unmodifiableSet(strategies);
    }

    // ========================= Statistics =========================

    /**
     * Gets cleanup statistics.
     *
     * @return cleanup statistics
     */
    public CleanupStatistics getStatistics() {
        return new CleanupStatistics(
                totalCleanupRuns.get(),
                totalPeersRemoved.get(),
                lastCleanupCount.get(),
                lastCleanupTime,
                lastCleanupDuration
        );
    }

    /**
     * Resets cleanup statistics.
     */
    public void resetStatistics() {
        totalCleanupRuns.set(0);
        totalPeersRemoved.set(0);
        lastCleanupCount.set(0);
        lastCleanupTime = null;
        lastCleanupDuration = Duration.ZERO;
        log.info("Cleanup statistics reset");
    }

    // ========================= Getters =========================

    public Duration getTtl() {
        return ttl;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public Instant getLastCleanupTime() {
        return lastCleanupTime;
    }

    public int getLastCleanupCount() {
        return lastCleanupCount.get();
    }

    // ========================= Data Classes =========================

    /**
     * Cleanup strategy enumeration.
     */
    public enum CleanupStrategy {
        /** Remove peers that exceeded TTL */
        TTL_EXPIRED,

        /** Remove offline peers */
        OFFLINE_PEERS,

        /** Remove peers with zero reputation */
        ZERO_REPUTATION,

        /** Remove banned peers */
        BANNED_PEERS
    }

    /**
     * Cleanup statistics snapshot.
     */
    public record CleanupStatistics(
            long totalRuns,
            long totalRemoved,
            int lastCleanupCount,
            Instant lastCleanupTime,
            Duration lastCleanupDuration
    ) {
        public double averageRemovedPerRun() {
            return totalRuns > 0 ? (double) totalRemoved / totalRuns : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "CleanupStats[runs=%d, totalRemoved=%d, avgPerRun=%.2f, " +
                            "lastCount=%d, lastDuration=%dms]",
                    totalRuns, totalRemoved, averageRemovedPerRun(),
                    lastCleanupCount, lastCleanupDuration.toMillis()
            );
        }
    }
}