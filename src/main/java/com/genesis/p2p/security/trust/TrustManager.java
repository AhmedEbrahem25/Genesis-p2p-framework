
package com.genesis.p2p.security.trust;

import com.genesis.p2p.security.api.ITrustManager;
import com.genesis.p2p.observability.logging.NodeLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trust manager implementation with configurable thresholds and time-based decay.
 *
 * SECURITY FIX (v2.6): Added configurable trust threshold and time-based trust decay.
 * - Trust threshold can be configured per-deployment
 * - Trust levels decay over time for inactive peers
 * - Enhanced monitoring with statistics
 *
 * @author Genesis P2P Framework
 * @version 2.6
 */
public class TrustManager implements ITrustManager {

    private static final NodeLogger log = NodeLogger.getLogger(TrustManager.class);

    private static final int MIN_TRUST_LEVEL = 0;
    private static final int MAX_TRUST_LEVEL = 100;
    private static final int DEFAULT_TRUST_THRESHOLD = 50;

    // SECURITY FIX (v2.6): Default time-based decay settings
    private static final Duration DEFAULT_DECAY_INTERVAL = Duration.ofHours(24);
    private static final int DEFAULT_DECAY_AMOUNT = 5;

    private final ConcurrentHashMap<String, Integer> trustLevels;
    private final ConcurrentHashMap<String, Instant> lastActivityTimes;
    private final int defaultLevel;

    // SECURITY FIX (v2.6): Configurable trust threshold
    private volatile int trustThreshold;

    // SECURITY FIX (v2.6): Time-based trust decay settings
    private volatile boolean decayEnabled = false;
    private volatile Duration decayInterval = DEFAULT_DECAY_INTERVAL;
    private volatile int decayAmount = DEFAULT_DECAY_AMOUNT;

    public TrustManager(int defaultLevel) {
        this(defaultLevel, DEFAULT_TRUST_THRESHOLD);
    }

    /**
     * Creates a trust manager with configurable threshold.
     *
     * SECURITY FIX (v2.6): Allows deployment-specific trust threshold configuration.
     *
     * @param defaultLevel the default trust level for new peers
     * @param trustThreshold the threshold for considering a peer trusted
     */
    public TrustManager(int defaultLevel, int trustThreshold) {
        this.defaultLevel = clamp(defaultLevel);
        this.trustThreshold = clamp(trustThreshold);
        this.trustLevels = new ConcurrentHashMap<>();
        this.lastActivityTimes = new ConcurrentHashMap<>();

        log.info("TrustManager initialized",
                "defaultLevel", this.defaultLevel,
                "trustThreshold", this.trustThreshold);
    }

    @Override
    public void trustPeer(String peerId, int level) {
        trustLevels.put(peerId, clamp(level));
        lastActivityTimes.put(peerId, Instant.now());
        log.debug("Peer trust level set", "peerId", peerId, "level", clamp(level));
    }

    @Override
    public void untrustPeer(String peerId) {
        trustLevels.put(peerId, MIN_TRUST_LEVEL);
        log.info("Peer untrusted", "peerId", peerId);
    }

    @Override
    public boolean isTrusted(String peerId) {
        return getTrustLevel(peerId) >= trustThreshold;
    }

    @Override
    public int getTrustLevel(String peerId) {
        return trustLevels.getOrDefault(peerId, defaultLevel);
    }

    // ========================= SECURITY FIX (v2.6): Configurable Threshold =========================

    /**
     * Sets the trust threshold for considering peers trusted.
     *
     * SECURITY FIX (v2.6): Allows runtime configuration of trust threshold.
     *
     * @param threshold the new threshold (0-100)
     * @throws IllegalArgumentException if threshold is out of range
     */
    public void setTrustThreshold(int threshold) {
        if (threshold < MIN_TRUST_LEVEL || threshold > MAX_TRUST_LEVEL) {
            throw new IllegalArgumentException(
                    "Trust threshold must be between " + MIN_TRUST_LEVEL + " and " + MAX_TRUST_LEVEL);
        }
        int oldThreshold = this.trustThreshold;
        this.trustThreshold = threshold;
        log.info("Trust threshold updated",
                "oldThreshold", oldThreshold,
                "newThreshold", threshold);
    }

    /**
     * Gets the current trust threshold.
     *
     * @return the trust threshold
     */
    public int getTrustThreshold() {
        return trustThreshold;
    }

    // ========================= SECURITY FIX (v2.6): Time-Based Trust Decay =========================

    /**
     * Enables or disables time-based trust decay.
     *
     * SECURITY FIX (v2.6): When enabled, trust levels decay over time for inactive peers.
     * This prevents stale high trust levels from remaining indefinitely.
     *
     * @param enabled true to enable decay
     */
    public void setDecayEnabled(boolean enabled) {
        this.decayEnabled = enabled;
        log.info("Trust decay " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Configures time-based trust decay parameters.
     *
     * @param interval the interval between decay applications
     * @param amount the amount to decay each interval
     */
    public void setDecayParameters(Duration interval, int amount) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Decay interval must be positive");
        }
        if (amount < 0 || amount > MAX_TRUST_LEVEL) {
            throw new IllegalArgumentException("Decay amount must be between 0 and " + MAX_TRUST_LEVEL);
        }
        this.decayInterval = interval;
        this.decayAmount = amount;
        log.info("Trust decay parameters configured",
                "interval", interval,
                "amount", amount);
    }

    /**
     * Applies time-based trust decay to all peers.
     *
     * SECURITY FIX (v2.6): Call this periodically (e.g., every hour) to decay
     * trust levels for inactive peers.
     *
     * @return number of peers whose trust was decayed
     */
    public int applyTrustDecay() {
        if (!decayEnabled) {
            return 0;
        }

        int decayedCount = 0;
        Instant decayThreshold = Instant.now().minus(decayInterval);

        for (Map.Entry<String, Instant> entry : lastActivityTimes.entrySet()) {
            String peerId = entry.getKey();
            Instant lastActivity = entry.getValue();

            if (lastActivity.isBefore(decayThreshold)) {
                Integer currentLevel = trustLevels.get(peerId);
                if (currentLevel != null && currentLevel > MIN_TRUST_LEVEL) {
                    int newLevel = Math.max(MIN_TRUST_LEVEL, currentLevel - decayAmount);
                    trustLevels.put(peerId, newLevel);

                    // Update last activity to avoid repeated decay in same period
                    lastActivityTimes.put(peerId, Instant.now());

                    decayedCount++;
                    log.debug("Trust decayed for peer",
                            "peerId", peerId,
                            "oldLevel", currentLevel,
                            "newLevel", newLevel);
                }
            }
        }

        if (decayedCount > 0) {
            log.info("Trust decay applied", "decayedPeers", decayedCount);
        }

        return decayedCount;
    }

    /**
     * Records activity for a peer, resetting their decay timer.
     *
     * @param peerId the peer ID
     */
    public void recordActivity(String peerId) {
        if (peerId != null) {
            lastActivityTimes.put(peerId, Instant.now());
        }
    }

    // ========================= Monitoring =========================

    /**
     * Gets trust manager statistics.
     *
     * @return statistics about the trust manager state
     */
    public TrustStats getStats() {
        int totalPeers = trustLevels.size();
        int trustedCount = (int) trustLevels.values().stream()
                .filter(level -> level >= trustThreshold)
                .count();

        return new TrustStats(totalPeers, trustedCount, defaultLevel, trustThreshold, decayEnabled);
    }

    /**
     * Removes a peer from the trust system entirely.
     *
     * @param peerId the peer to remove
     */
    public void removePeer(String peerId) {
        trustLevels.remove(peerId);
        lastActivityTimes.remove(peerId);
        log.debug("Peer removed from trust system", "peerId", peerId);
    }

    /**
     * Clears all trust data.
     */
    public void clear() {
        trustLevels.clear();
        lastActivityTimes.clear();
        log.info("Trust manager cleared");
    }

    private int clamp(int level) {
        return Math.max(MIN_TRUST_LEVEL, Math.min(MAX_TRUST_LEVEL, level));
    }

    /**
     * Trust manager statistics.
     */
    public record TrustStats(
            int totalPeers,
            int trustedPeers,
            int defaultLevel,
            int trustThreshold,
            boolean decayEnabled
    ) {}
}
