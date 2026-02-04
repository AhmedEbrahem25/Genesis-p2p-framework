package com.genesis.p2p.core.peer;

import com.genesis.p2p.core.Peer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Monitors peer health through success/failure tracking and automatic banning.
 * Handles latency updates and last-seen timestamps.
 */
public class PeerHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(PeerHealthMonitor.class);

    private static final int AUTO_BAN_THRESHOLD = 10;

    private final PeerStore store;
    private final PeerReputationService reputationService;

    public PeerHealthMonitor(PeerStore store, PeerReputationService reputationService) {
        this.store = store;
        this.reputationService = reputationService;
        log.debug("PeerHealthMonitor initialized");
    }

    // ========================= Health Tracking =========================

    /**
     * Records a successful interaction with a peer.
     */
    public void recordSuccess(String id) {
        if (id == null) {
            return;
        }

        int successCount = store.incrementSuccessCount(id);
        reputationService.increaseReputation(id);
        refreshLastSeen(id);

        log.debug("Success recorded for {} [total: {}]", id, successCount);
    }

    /**
     * Records a failed interaction with a peer.
     */
    public void recordFailure(String id) {
        if (id == null) {
            return;
        }

        int failures = store.incrementFailureCount(id);
        reputationService.decreaseReputation(id);

        log.debug("Failure recorded for {} [total: {}]", id, failures);

        // Auto-ban on excessive failures
        if (failures >= AUTO_BAN_THRESHOLD) {
            reputationService.banPeer(id, "Excessive failures: " + failures);
        }
    }

    /**
     * Records a message from/to a peer.
     */
    public void recordMessage(String id) {
        if (id == null) {
            return;
        }

        store.incrementMessageCount(id);
        refreshLastSeen(id);
    }

    // ========================= Timestamp Updates =========================

    /**
     * Updates the last seen timestamp for a peer.
     */
    public void refreshLastSeen(String id) {
        if (id == null) {
            return;
        }

        store.updateLastSeen(id);

        // Also update the peer record
        Peer updated = store.computeIfPresent(id, existing -> new Peer(
                existing.id(),
                existing.publicKey(),
                existing.hostName(),
                existing.ip(),
                existing.port(),
                existing.publicIp(),
                existing.publicPort(),
                existing.natType(),
                existing.behindNat(),
                existing.online(),
                Instant.now(),
                existing.lastLatency(),
                existing.trusted(),
                existing.reputation(),
                existing.version(),
                existing.os(),
                existing.agent(),
                existing.identityPublicKey()
        ));
    }

    /**
     * Updates latency for a peer.
     */
    public void updateLatency(String id, long latencyMs) {
        if (id == null) {
            return;
        }

        Peer updated = store.computeIfPresent(id, existing -> new Peer(
                existing.id(),
                existing.publicKey(),
                existing.hostName(),
                existing.ip(),
                existing.port(),
                existing.publicIp(),
                existing.publicPort(),
                existing.natType(),
                existing.behindNat(),
                existing.online(),
                existing.lastSeen(),
                latencyMs,
                existing.trusted(),
                existing.reputation(),
                existing.version(),
                existing.os(),
                existing.agent(),
                existing.identityPublicKey()
        ));

        log.debug("Latency updated for {}: {}ms", id, latencyMs);
    }

    /**
     * Performs a health check on a peer.
     */
    public void performHealthCheck(String id) {
        if (id == null) {
            return;
        }

        store.updateLastHealthCheck(id);
        log.debug("Health check performed for {}", id);
    }

    // ========================= Health Statistics =========================

    /**
     * Gets success rate for a peer (0.0 to 1.0).
     */
    public double getSuccessRate(String id) {
        return store.getMetadata(id)
                .map(meta -> {
                    int total = meta.successCount.get() + meta.failureCount.get();
                    if (total == 0) return 1.0;
                    return (double) meta.successCount.get() / total;
                })
                .orElse(0.0);
    }

    /**
     * Gets failure rate for a peer (0.0 to 1.0).
     */
    public double getFailureRate(String id) {
        return 1.0 - getSuccessRate(id);
    }

    /**
     * Gets total interactions for a peer.
     */
    public int getTotalInteractions(String id) {
        return store.getMetadata(id)
                .map(meta -> meta.successCount.get() + meta.failureCount.get())
                .orElse(0);
    }
}