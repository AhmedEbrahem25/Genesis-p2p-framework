package com.genesis.p2p.core.peer;

import com.genesis.p2p.core.Peer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for managing peer reputation scores.
 * Handles reputation updates, clamping, and trust calculations.
 */
public class PeerReputationService {

    private static final Logger log = LoggerFactory.getLogger(PeerReputationService.class);

    // Reputation bounds
    private static final int MIN_REPUTATION = 0;
    private static final int MAX_REPUTATION = 100;
    private static final int INITIAL_REPUTATION = 50;

    // Reputation deltas
    private static final int GOOD_BEHAVIOR_DELTA = 5;
    private static final int BAD_BEHAVIOR_DELTA = -10;
    private static final int BAN_DELTA = -100;

    private final int trustThreshold;
    private final PeerStore store;
    private final PeerEventBus eventBus;
    private final AtomicInteger totalUpdates;

    public PeerReputationService(int trustThreshold, PeerStore store, PeerEventBus eventBus) {
        this.trustThreshold = Math.max(0, Math.min(100, trustThreshold));
        this.store = store;
        this.eventBus = eventBus;
        this.totalUpdates = new AtomicInteger(0);

        log.info("PeerReputationService initialized [trustThreshold={}]", trustThreshold);
    }

    // ========================= Core Reputation Operations =========================

    /**
     * Updates reputation by adding delta and returns new value.
     */
    public int updateReputation(String id, int delta) {
        if (id == null) {
            return -1;
        }

        AtomicInteger result = new AtomicInteger(-1);

        Peer updated = store.computeIfPresent(id, existing -> {
            int oldRep = existing.reputation();
            int newRep = clampReputation(oldRep + delta);

            result.set(newRep);
            totalUpdates.incrementAndGet();

            Peer updatedPeer = createPeerWithNewReputation(existing, newRep);

            if (oldRep != newRep) {
                // Determine the reason for reputation change based on delta
                String reason = determineReputationChangeReason(delta);

                // Log reputation change with comprehensive details
                log.info("PEER_REPUTATION_CHANGED",
                        "peerId", id,
                        "oldReputation", oldRep,
                        "newReputation", newRep,
                        "change", delta,
                        "reason", reason,
                        "trusted", updatedPeer.trusted(),
                        "peerAddr", updatedPeer.ip());

                eventBus.fireReputationChanged(updatedPeer, oldRep, newRep);
            }

            return updatedPeer;
        });

        return result.get();
    }

    /**
     * Increases reputation for good behavior.
     */
    public int increaseReputation(String id) {
        return updateReputation(id, GOOD_BEHAVIOR_DELTA);
    }

    /**
     * Decreases reputation for bad behavior.
     */
    public int decreaseReputation(String id) {
        return updateReputation(id, BAD_BEHAVIOR_DELTA);
    }

    /**
     * Bans a peer by setting minimum reputation.
     */
    public boolean banPeer(String id, String reason) {
        if (id == null) {
            return false;
        }

        updateReputation(id, BAN_DELTA);
        log.warn("Peer {} banned: {}", id, reason);
        return true;
    }

    // ========================= Trust Evaluation =========================

    /**
     * Checks if a peer is trusted based on reputation threshold.
     */
    public boolean isTrusted(String id) {
        return store.get(id)
                .map(peer -> peer.reputation() >= trustThreshold)
                .orElse(false);
    }

    /**
     * Gets the trust threshold.
     */
    public int getTrustThreshold() {
        return trustThreshold;
    }

    // ========================= Statistics =========================

    public int getTotalUpdates() {
        return totalUpdates.get();
    }

    public double getAverageReputation() {
        return store.stream()
                .mapToInt(Peer::reputation)
                .average()
                .orElse(0.0);
    }

    public long countTrustedPeers() {
        return store.stream()
                .filter(peer -> peer.reputation() >= trustThreshold)
                .count();
    }

    // ========================= Helper Methods =========================

    private int clampReputation(int value) {
        return Math.max(MIN_REPUTATION, Math.min(MAX_REPUTATION, value));
    }

    private String determineReputationChangeReason(int delta) {
        if (delta == GOOD_BEHAVIOR_DELTA) {
            return "GOOD_BEHAVIOR";
        } else if (delta == BAD_BEHAVIOR_DELTA) {
            return "BAD_BEHAVIOR";
        } else if (delta <= BAN_DELTA) {
            return "BANNED";
        } else if (delta > 0) {
            return "POSITIVE_EVENT";
        } else {
            return "NEGATIVE_EVENT";
        }
    }

    private Peer createPeerWithNewReputation(Peer existing, int newReputation) {
        boolean trusted = newReputation >= trustThreshold;

        return new Peer(
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
                existing.lastLatency(),
                trusted,
                newReputation,
                existing.version(),
                existing.os(),
                existing.agent(),
                existing.identityPublicKey()
        );
    }
}