package com.genesis.p2p.core.peer;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.util.crypto.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Advanced peer querying and filtering service.
 * Provides optimized selection strategies for various use cases.
 *
 * Features:
 * - Trust-based filtering
 * - Performance-based ranking (latency, reputation)
 * - Attribute-based queries (version, OS, IP)
 * - Composite queries (routing, reliability)
 * - Statistical queries
 * - Random selection for load balancing
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class PeerQueryService {

    private static final Logger log = LoggerFactory.getLogger(PeerQueryService.class);

    private final PeerStore store;
    private final int trustThreshold;

    /**
     * Creates a new query service.
     *
     * @param store the peer store to query
     * @param trustThreshold minimum reputation for trusted status
     */
    public PeerQueryService(PeerStore store, int trustThreshold) {
        this.store = Objects.requireNonNull(store, "PeerStore cannot be null");
        this.trustThreshold = Math.max(0, Math.min(100, trustThreshold));
        log.info("PeerQueryService initialized [trustThreshold={}]", trustThreshold);
    }

    // ========================= Basic Queries =========================

    /**
     * Gets all trusted peers (reputation >= threshold).
     *
     * @return unmodifiable list of trusted peers
     */
    public List<Peer> getTrustedPeers() {
        return store.streamFiltered(peer -> peer.reputation() >= trustThreshold)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets all online peers.
     *
     * @return unmodifiable list of online peers
     */
    public List<Peer> getOnlinePeers() {
        return store.streamFiltered(Peer::online)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets all offline peers.
     *
     * @return unmodifiable list of offline peers
     */
    public List<Peer> getOfflinePeers() {
        return store.streamFiltered(peer -> !peer.online())
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets peers with a specific state.
     *
     * @param state the desired state
     * @return unmodifiable list of peers in that state
     */
    public List<Peer> getPeersByState(PeerStore.PeerState state) {
        if (state == null) {
            return List.of();
        }

        return store.getAll().stream()
                .filter(peer -> store.getMetadata(peer.id())
                        .map(meta -> meta.state == state)
                        .orElse(false))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Custom filter with any predicate.
     *
     * @param predicate filter condition
     * @return unmodifiable list of matching peers
     */
    public List<Peer> filterPeers(Predicate<Peer> predicate) {
        Objects.requireNonNull(predicate, "Predicate cannot be null");
        return store.streamFiltered(predicate)
                .collect(Collectors.toUnmodifiableList());
    }

    // ========================= Ranked Queries =========================

    /**
     * Gets N peers with highest reputation.
     *
     * @param limit maximum number of peers to return
     * @return unmodifiable list of top peers by reputation
     */
    public List<Peer> getTopPeersByReputation(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return store.stream()
                .sorted(Comparator.comparingInt(Peer::reputation).reversed())
                .limit(limit)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets N peers with lowest reputation.
     *
     * @param limit maximum number of peers to return
     * @return unmodifiable list of peers with lowest reputation
     */
    public List<Peer> getLowestReputationPeers(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return store.stream()
                .sorted(Comparator.comparingInt(Peer::reputation))
                .limit(limit)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets N peers with lowest latency (online only).
     *
     * @param limit maximum number of peers to return
     * @return unmodifiable list of fastest peers
     */
    public List<Peer> getPeersByLowestLatency(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return store.streamFiltered(Peer::online)
                .sorted(Comparator.comparingLong(Peer::lastLatency))
                .limit(limit)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets N most recently seen peers.
     *
     * @param limit maximum number of peers to return
     * @return unmodifiable list of most recent peers
     */
    public List<Peer> getMostRecentPeers(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return store.stream()
                .sorted(Comparator.comparing(Peer::lastSeen).reversed())
                .limit(limit)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets N least recently seen peers.
     *
     * @param limit maximum number of peers to return
     * @return unmodifiable list of stale peers
     */
    public List<Peer> getLeastRecentPeers(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return store.stream()
                .sorted(Comparator.comparing(Peer::lastSeen))
                .limit(limit)
                .collect(Collectors.toUnmodifiableList());
    }

    // ========================= Attribute-based Queries =========================

    /**
     * Finds peers by exact version match.
     *
     * @param version the version string
     * @return unmodifiable list of matching peers
     */
    public List<Peer> getPeersByVersion(String version) {
        if (version == null || version.isEmpty()) {
            return List.of();
        }

        return store.streamFiltered(peer -> version.equals(peer.version()))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Finds peers by operating system.
     *
     * @param os the operating system name
     * @return unmodifiable list of matching peers
     */
    public List<Peer> getPeersByOS(String os) {
        if (os == null || os.isEmpty()) {
            return List.of();
        }

        return store.streamFiltered(peer -> os.equals(peer.os()))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Finds peers by user agent.
     *
     * @param agent the user agent string
     * @return unmodifiable list of matching peers
     */
    public List<Peer> getPeersByAgent(String agent) {
        if (agent == null || agent.isEmpty()) {
            return List.of();
        }

        return store.streamFiltered(peer -> agent.equals(peer.agent()))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Finds peers by IP address pattern (contains).
     *
     * @param ipPattern the IP pattern to search for
     * @return unmodifiable list of matching peers
     */
    public List<Peer> getPeersByIPPattern(String ipPattern) {
        if (ipPattern == null || ipPattern.isEmpty()) {
            return List.of();
        }

        return store.streamFiltered(peer -> peer.ip().contains(ipPattern))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Finds peers by hostname pattern (contains).
     *
     * @param hostnamePattern the hostname pattern
     * @return unmodifiable list of matching peers
     */
    public List<Peer> getPeersByHostnamePattern(String hostnamePattern) {
        if (hostnamePattern == null || hostnamePattern.isEmpty()) {
            return List.of();
        }

        return store.streamFiltered(peer -> peer.hostName().contains(hostnamePattern))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Finds peers by port number.
     *
     * @param port the port number
     * @return unmodifiable list of matching peers
     */
    public List<Peer> getPeersByPort(int port) {
        return store.streamFiltered(peer -> peer.port() == port)
                .collect(Collectors.toUnmodifiableList());
    }

    // ========================= Range Queries =========================

    /**
     * Gets peers with reputation in a specific range.
     *
     * @param minReputation minimum reputation (inclusive)
     * @param maxReputation maximum reputation (inclusive)
     * @return unmodifiable list of matching peers
     */
    public List<Peer> getPeersByReputationRange(int minReputation, int maxReputation) {
        return store.streamFiltered(peer ->
                        peer.reputation() >= minReputation && peer.reputation() <= maxReputation)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets peers with latency below threshold (online only).
     *
     * @param maxLatencyMs maximum latency in milliseconds
     * @return unmodifiable list of matching peers
     */
    public List<Peer> getPeersByMaxLatency(long maxLatencyMs) {
        return store.streamFiltered(peer ->
                        peer.online() && peer.lastLatency() <= maxLatencyMs)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets peers seen after a specific timestamp.
     *
     * @param after the timestamp
     * @return unmodifiable list of matching peers
     */
    public List<Peer> getPeersSeenAfter(Instant after) {
        if (after == null) {
            return List.of();
        }

        return store.streamFiltered(peer -> peer.lastSeen().isAfter(after))
                .collect(Collectors.toUnmodifiableList());
    }

    // ========================= Advanced Selection Strategies =========================

    /**
     * Gets best peers for routing (trusted, online, low latency).
     * These are ideal candidates for message forwarding.
     *
     * @param limit maximum number of peers to return
     * @return unmodifiable list of best routing peers
     */
    public List<Peer> getBestPeersForRouting(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return store.streamFiltered(peer ->
                        peer.online() && peer.reputation() >= trustThreshold)
                .sorted(Comparator.comparingLong(Peer::lastLatency))
                .limit(limit)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets peers with failure rate below threshold.
     *
     * @param maxFailureRate maximum acceptable failure rate (0.0 to 1.0)
     * @return unmodifiable list of reliable peers
     */
    public List<Peer> getReliablePeers(double maxFailureRate) {
        if (maxFailureRate < 0.0 || maxFailureRate > 1.0) {
            throw new IllegalArgumentException("Failure rate must be between 0.0 and 1.0");
        }

        return store.getAll().stream()
                .filter(peer -> {
                    return store.getMetadata(peer.id())
                            .map(meta -> {
                                int total = meta.successCount.get() + meta.failureCount.get();
                                if (total == 0) return true;
                                double failureRate = (double) meta.failureCount.get() / total;
                                return failureRate <= maxFailureRate;
                            })
                            .orElse(false);
                })
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets peers with success rate above threshold.
     *
     * @param minSuccessRate minimum acceptable success rate (0.0 to 1.0)
     * @return unmodifiable list of high-performing peers
     */
    public List<Peer> getHighPerformancePeers(double minSuccessRate) {
        return getReliablePeers(1.0 - minSuccessRate);
    }

    /**
     * Gets random trusted peer for load distribution.
     *
     * @return random trusted peer or null if none available
     */
    public Peer getRandomTrustedPeer() {
        List<Peer> trusted = getTrustedPeers();
        if (trusted.isEmpty()) {
            return null;
        }
        return trusted.get(RandomUtils.randomInt(trusted.size()));
    }

    /**
     * Gets random online peer.
     *
     * @return random online peer or null if none available
     */
    public Peer getRandomOnlinePeer() {
        List<Peer> online = getOnlinePeers();
        if (online.isEmpty()) {
            return null;
        }
        return online.get(RandomUtils.randomInt(online.size()));
    }

    /**
     * Gets N random peers from the pool.
     *
     * @param count number of random peers to select
     * @return unmodifiable list of random peers
     */
    public List<Peer> getRandomPeers(int count) {
        if (count <= 0) {
            return List.of();
        }

        List<Peer> allPeers = new ArrayList<>(store.getAll());
        Collections.shuffle(allPeers);

        return allPeers.stream()
                .limit(count)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Gets diverse peer set (by version and location).
     * Useful for ensuring network diversity.
     *
     * @param limit maximum number of peers
     * @return unmodifiable list of diverse peers
     */
    public List<Peer> getDiversePeers(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        Map<String, List<Peer>> byVersion = store.getAll().stream()
                .collect(Collectors.groupingBy(Peer::version));

        List<Peer> diverse = new ArrayList<>();
        List<String> versions = new ArrayList<>(byVersion.keySet());

        int index = 0;
        while (diverse.size() < limit && !versions.isEmpty()) {
            String version = versions.get(index % versions.size());
            List<Peer> versionPeers = byVersion.get(version);

            if (!versionPeers.isEmpty()) {
                diverse.add(versionPeers.remove(0));
            }

            if (versionPeers.isEmpty()) {
                versions.remove(version);
            }

            index++;
        }

        return Collections.unmodifiableList(diverse);
    }

    // ========================= Statistical Queries =========================

    /**
     * Counts trusted peers.
     *
     * @return number of trusted peers
     */
    public long countTrustedPeers() {
        return store.stream()
                .filter(peer -> peer.reputation() >= trustThreshold)
                .count();
    }

    /**
     * Counts online peers.
     *
     * @return number of online peers
     */
    public long countOnlinePeers() {
        return store.stream()
                .filter(Peer::online)
                .count();
    }

    /**
     * Counts peers by version.
     *
     * @param version the version to count
     * @return number of peers with that version
     */
    public long countByVersion(String version) {
        if (version == null) {
            return 0;
        }
        return store.streamFiltered(peer -> version.equals(peer.version()))
                .count();
    }

    /**
     * Counts peers by state.
     *
     * @param state the state to count
     * @return number of peers in that state
     */
    public long countByState(PeerStore.PeerState state) {
        if (state == null) {
            return 0;
        }
        return getPeersByState(state).size();
    }

    /**
     * Gets version distribution.
     *
     * @return map of version to peer count
     */
    public Map<String, Long> getVersionDistribution() {
        return store.getAll().stream()
                .collect(Collectors.groupingBy(
                        Peer::version,
                        Collectors.counting()
                ));
    }

    /**
     * Gets OS distribution.
     *
     * @return map of OS to peer count
     */
    public Map<String, Long> getOSDistribution() {
        return store.getAll().stream()
                .collect(Collectors.groupingBy(
                        Peer::os,
                        Collectors.counting()
                ));
    }

    // ========================= Utility Methods =========================

    /**
     * Checks if any peers match the predicate.
     *
     * @param predicate the condition to check
     * @return true if at least one peer matches
     */
    public boolean anyMatch(Predicate<Peer> predicate) {
        Objects.requireNonNull(predicate, "Predicate cannot be null");
        return store.stream().anyMatch(predicate);
    }

    /**
     * Checks if all peers match the predicate.
     *
     * @param predicate the condition to check
     * @return true if all peers match
     */
    public boolean allMatch(Predicate<Peer> predicate) {
        Objects.requireNonNull(predicate, "Predicate cannot be null");
        return store.stream().allMatch(predicate);
    }

    /**
     * Checks if no peers match the predicate.
     *
     * @param predicate the condition to check
     * @return true if no peers match
     */
    public boolean noneMatch(Predicate<Peer> predicate) {
        Objects.requireNonNull(predicate, "Predicate cannot be null");
        return store.stream().noneMatch(predicate);
    }
}