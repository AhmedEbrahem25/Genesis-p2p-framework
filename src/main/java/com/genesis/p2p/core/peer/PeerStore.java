package com.genesis.p2p.core.peer;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.util.collections.LRUCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Thread-safe storage layer for peer data.
 * Handles all CRUD operations with optimized concurrent access.
 * Uses LRUCache for automatic eviction of least recently used peers.
 */
public class PeerStore {

    private static final Logger log = LoggerFactory.getLogger(PeerStore.class);

    private final LRUCache<String, Peer> peers;
    private final ConcurrentHashMap<String, PeerMetadata> metadata;
    private final ConcurrentHashMap<String, AtomicInteger> messageCounters;
    private final int maxCapacity;

    public PeerStore(int maxCapacity) {
        this.maxCapacity = Math.max(1, maxCapacity);

        // Initialize metadata and counters BEFORE LRUCache (needed by eviction listener)
        this.metadata = new ConcurrentHashMap<>();
        this.messageCounters = new ConcurrentHashMap<>();

        // Use LRUCache with eviction listener to clean up associated metadata
        this.peers = new LRUCache<>(maxCapacity, (peerId, peer) -> {
            // Clean up metadata when peer is evicted
            metadata.remove(peerId);
            messageCounters.remove(peerId);
            log.info("Peer evicted from cache [peerId={}, reputation={}]", peerId, peer.reputation());
        });

        log.info("PeerStore initialized with LRUCache [maxCapacity={}]", maxCapacity);
    }

    // ========================= Core CRUD =========================

    public Optional<Peer> get(String id) {
        return Optional.ofNullable(peers.get(id));
    }

    public boolean contains(String id) {
        return id != null && peers.containsKey(id);
    }

    public Peer put(String id, Peer peer) {
        Peer old = peers.put(id, peer);

        if (old == null) {
            // New peer
            metadata.put(id, new PeerMetadata());
            messageCounters.put(id, new AtomicInteger(0));
        }

        return old;
    }

    public Optional<Peer> remove(String id) {
        Peer removed = peers.remove(id);
        if (removed != null) {
            metadata.remove(id);
            messageCounters.remove(id);
        }
        return Optional.ofNullable(removed);
    }

    public void clear() {
        int count = peers.size();
        peers.clear();
        metadata.clear();
        messageCounters.clear();
        log.info("Cleared {} peers from store", count);
    }

    // ========================= Metadata Access =========================

    public Optional<PeerMetadata> getMetadata(String id) {
        return Optional.ofNullable(metadata.get(id));
    }

    public void updateLastSeen(String id) {
        PeerMetadata meta = metadata.get(id);
        if (meta != null) {
            meta.lastSeenAt = Instant.now();
        }
    }

    public void updateLastHealthCheck(String id) {
        PeerMetadata meta = metadata.get(id);
        if (meta != null) {
            meta.lastHealthCheckAt = Instant.now();
        }
    }

    public int incrementSuccessCount(String id) {
        return getMetadata(id)
                .map(m -> m.successCount.incrementAndGet())
                .orElse(0);
    }

    public int incrementFailureCount(String id) {
        return getMetadata(id)
                .map(m -> m.failureCount.incrementAndGet())
                .orElse(0);
    }

    public int incrementMessageCount(String id) {
        AtomicInteger counter = messageCounters.get(id);
        return counter != null ? counter.incrementAndGet() : 0;
    }

    // ========================= Queries =========================

    public Collection<Peer> getAll() {
        return Collections.unmodifiableCollection(peers.values());
    }

    public Stream<Peer> stream() {
        return peers.values().stream();
    }

    public Stream<Peer> streamFiltered(Predicate<Peer> predicate) {
        return stream().filter(predicate);
    }

    public List<Map.Entry<String, PeerMetadata>> getMetadataEntries() {
        return new ArrayList<>(metadata.entrySet());
    }

    // ========================= Additional Methods ==========================

    /**
     * Gets all peers as a map.
     *
     * @return map of peer ID to Peer
     */
    public Map<String, Peer> getAllPeers() {
        return Collections.unmodifiableMap(new HashMap<>(peers));
    }

    /**
     * Adds a peer to the store (alias for put).
     *
     * @param id peer ID
     * @param peer the peer
     * @return previous peer if exists
     */
    public Peer addPeer(String id, Peer peer) {
        return put(id, peer);
    }

    /**
     * Gets the state of a peer.
     *
     * @param peerId the peer ID
     * @return peer state or null if not found
     */
    public PeerState getPeerState(String peerId) {
        PeerMetadata meta = metadata.get(peerId);
        return meta != null ? meta.state : null;
    }

    /**
     * Sets the state of a peer.
     *
     * @param peerId the peer ID
     * @param state the new state
     */
    public void setPeerState(String peerId, PeerState state) {
        PeerMetadata meta = metadata.get(peerId);
        if (meta != null) {
            meta.state = state;
        }
    }

    // ========================= NAT Resolution Status =========================

    /**
     * Gets the NAT resolution status for a peer.
     *
     * @param peerId the peer ID
     * @return NAT resolution status or null if peer not found
     */
    public NatResolutionStatus getNatStatus(String peerId) {
        PeerMetadata meta = metadata.get(peerId);
        return meta != null ? meta.natStatus : null;
    }

    /**
     * Sets the NAT resolution status for a peer.
     *
     * @param peerId the peer ID
     * @param status the new NAT status
     */
    public void setNatStatus(String peerId, NatResolutionStatus status) {
        PeerMetadata meta = metadata.get(peerId);
        if (meta != null) {
            meta.natStatus = status;
            if (status == NatResolutionStatus.RESOLVING) {
                meta.natResolutionStartedAt = Instant.now();
            } else if (status == NatResolutionStatus.RESOLVED ||
                       status == NatResolutionStatus.FAILED ||
                       status == NatResolutionStatus.SKIPPED) {
                meta.natResolutionCompletedAt = Instant.now();
            }
        }
    }

    /**
     * Sets NAT resolution as failed with error message.
     *
     * @param peerId the peer ID
     * @param error the error message (non-fatal)
     */
    public void setNatFailed(String peerId, String error) {
        PeerMetadata meta = metadata.get(peerId);
        if (meta != null) {
            meta.natStatus = NatResolutionStatus.FAILED;
            meta.natResolutionCompletedAt = Instant.now();
            meta.natResolutionError = error;
        }
    }

    /**
     * Checks if NAT resolution is complete for a peer.
     *
     * @param peerId the peer ID
     * @return true if NAT is resolved, failed, or skipped
     */
    public boolean isNatResolutionComplete(String peerId) {
        NatResolutionStatus status = getNatStatus(peerId);
        return status == NatResolutionStatus.RESOLVED ||
               status == NatResolutionStatus.FAILED ||
               status == NatResolutionStatus.SKIPPED;
    }

    // ========================= Capacity Management =========================

    public int size() {
        return peers.size();
    }

    public boolean isFull() {
        return peers.size() >= maxCapacity;
    }

    public int remainingCapacity() {
        return Math.max(0, maxCapacity - peers.size());
    }

    public Optional<Peer> findLowestReputationPeer() {
        return peers.values().stream()
                .min(Comparator.comparingInt(Peer::reputation));
    }

    // ========================= Atomic Updates =========================

    public Peer computeIfPresent(String id, PeerUpdateFunction updateFn) {
        return peers.computeIfPresent(id, (k, existing) -> updateFn.apply(existing));
    }

    @FunctionalInterface
    public interface PeerUpdateFunction {
        Peer apply(Peer existing);
    }

    // ========================= Cache Statistics =========================

    /**
     * Gets the cache hit rate (percentage of gets that found a peer).
     *
     * @return hit rate between 0.0 and 1.0
     */
    public double getCacheHitRate() {
        return peers.getHitRate();
    }

    /**
     * Gets detailed cache statistics.
     *
     * @return cache statistics
     */
    public LRUCache.CacheStats getCacheStats() {
        return peers.getStats();
    }

    /**
     * Gets the number of cache evictions.
     *
     * @return total evictions
     */
    public long getCacheEvictions() {
        return peers.getEvictions();
    }

    /**
     * Resets cache statistics.
     */
    public void resetCacheStats() {
        peers.resetStats();
    }

    // ========================= Metadata Class =========================

    public static class PeerMetadata {
        public final Instant addedAt;
        public volatile Instant lastSeenAt;
        public volatile Instant lastHealthCheckAt;
        public final AtomicInteger failureCount;
        public final AtomicInteger successCount;
        public volatile PeerState state;

        // NAT resolution - decoupled from connection state
        public volatile NatResolutionStatus natStatus;
        public volatile Instant natResolutionStartedAt;
        public volatile Instant natResolutionCompletedAt;
        public volatile String natResolutionError;

        public PeerMetadata() {
            Instant now = Instant.now();
            this.addedAt = now;
            this.lastSeenAt = now;
            this.lastHealthCheckAt = now;
            this.failureCount = new AtomicInteger(0);
            this.successCount = new AtomicInteger(0);
            this.state = PeerState.DISCOVERED;
            this.natStatus = NatResolutionStatus.PENDING;
        }
    }

    /**
     * Peer connection state machine states.
     *
     * State flow with secure channel (v2.1):
     * DISCOVERED -> CHANNEL_NEGOTIATING -> CHANNEL_ESTABLISHED -> CONNECTING -> CONNECTED -> AUTHENTICATED
     *
     * Legacy state flow (without secure channel):
     * DISCOVERED -> CONNECTING -> CONNECTED -> AUTHENTICATED
     */
    public enum PeerState {
        /** Peer discovered via broadcast/multicast/bootstrap */
        DISCOVERED,

        /** Secure channel negotiation in progress (KEY_EXCHANGE phase) */
        CHANNEL_NEGOTIATING,

        /** Secure channel established, ready for handshake */
        CHANNEL_ESTABLISHED,

        /** Connection/handshake in progress (over secure channel) */
        CONNECTING,

        /** Connected and handshake complete */
        CONNECTED,

        /** Fully authenticated and verified */
        AUTHENTICATED,

        /** Disconnected (can reconnect) */
        DISCONNECTED,

        /** Banned (terminal state) */
        BANNED
    }

    /**
     * NAT resolution status - decoupled from connection state.
     *
     * This allows peers to be CONNECTED/AUTHENTICATED immediately after
     * successful handshake, while NAT resolution proceeds asynchronously.
     * NAT failures are non-fatal state signals, not errors.
     */
    public enum NatResolutionStatus {
        /** NAT resolution not yet started */
        PENDING,

        /** NAT resolution in progress */
        RESOLVING,

        /** NAT resolution completed successfully */
        RESOLVED,

        /** NAT resolution failed (non-fatal - connection still valid) */
        FAILED,

        /** NAT resolution skipped (e.g., local connection, no NAT service) */
        SKIPPED,

        /** NAT type is UNKNOWN but connection is still usable */
        UNKNOWN
    }
}