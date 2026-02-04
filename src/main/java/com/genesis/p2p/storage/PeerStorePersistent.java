package com.genesis.p2p.storage;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent storage for Peer data.
 *
 * Stores peer information to disk for restart recovery.
 * Provides periodic snapshots and on-demand persistence.
 *
 * Features:
 * - Persistent peer storage
 * - Automatic serialization
 * - Periodic snapshots
 * - Restart recovery
 * - Metadata tracking (last seen, connection count)
 *
 * Use Cases:
 * - Peer state recovery after restart
 * - Long-term peer reputation tracking
 * - Network topology persistence
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class PeerStorePersistent implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(PeerStorePersistent.class);
    private static final String PEER_PREFIX = "peer:";
    private static final String METADATA_KEY = "metadata:peers";

    private final KVStore store;
    private final Map<String, PeerMetadata> metadataCache;
    private volatile boolean closed = false;

    /**
     * Creates a persistent peer store.
     *
     * @param dataDir directory for storage
     * @throws KVStore.StorageException if storage cannot be initialized
     */
    public PeerStorePersistent(Path dataDir) throws KVStore.StorageException {
        this.store = new FileKVStore(dataDir.resolve("peers"));
        this.metadataCache = new ConcurrentHashMap<>();

        // Load existing metadata
        loadMetadata();

        log.info("PeerStorePersistent initialized", "dataDir", dataDir);
    }

    /**
     * Saves a peer to persistent storage.
     *
     * @param peer the peer to save
     * @throws KVStore.StorageException if storage fails
     */
    public void savePeer(Peer peer) throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("PeerStore is closed");
        }

        String key = PEER_PREFIX + peer.id();
        byte[] data = serializePeer(peer);
        store.put(key, data);

        // Update metadata
        PeerMetadata metadata = metadataCache.computeIfAbsent(
                peer.id(),
                id -> new PeerMetadata(id)
        );
        metadata.updateLastSeen();

        log.debug("Peer saved to persistent storage", "peerId", peer.id());
    }

    /**
     * Loads a peer from persistent storage.
     *
     * @param peerId the peer ID
     * @return optional peer, empty if not found
     * @throws KVStore.StorageException if storage fails
     */
    public Optional<Peer> loadPeer(String peerId) throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("PeerStore is closed");
        }

        String key = PEER_PREFIX + peerId;
        Optional<byte[]> data = store.get(key);

        if (data.isEmpty()) {
            return Optional.empty();
        }

        Peer peer = deserializePeer(data.get());
        log.debug("Peer loaded from persistent storage", "peerId", peerId);
        return Optional.of(peer);
    }

    /**
     * Deletes a peer from persistent storage.
     *
     * @param peerId the peer ID
     * @throws KVStore.StorageException if storage fails
     */
    public void deletePeer(String peerId) throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("PeerStore is closed");
        }

        String key = PEER_PREFIX + peerId;
        store.delete(key);
        metadataCache.remove(peerId);

        log.debug("Peer deleted from persistent storage", "peerId", peerId);
    }

    /**
     * Loads all peers from persistent storage.
     *
     * @return list of all persisted peers
     * @throws KVStore.StorageException if storage fails
     */
    public List<Peer> loadAllPeers() throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("PeerStore is closed");
        }

        Set<String> keys = store.keysWithPrefix(PEER_PREFIX);
        List<Peer> peers = new ArrayList<>();

        for (String key : keys) {
            Optional<byte[]> data = store.get(key);
            if (data.isPresent()) {
                try {
                    peers.add(deserializePeer(data.get()));
                } catch (Exception e) {
                    log.error("Failed to deserialize peer", e, "key", key);
                }
            }
        }

        log.info("Loaded peers from persistent storage", "count", peers.size());
        return peers;
    }

    /**
     * Gets metadata for a peer.
     *
     * @param peerId the peer ID
     * @return optional metadata, empty if not found
     */
    public Optional<PeerMetadata> getMetadata(String peerId) {
        return Optional.ofNullable(metadataCache.get(peerId));
    }

    /**
     * Gets the number of persisted peers.
     *
     * @return count of persisted peers
     * @throws KVStore.StorageException if storage fails
     */
    public int size() throws KVStore.StorageException {
        return store.keysWithPrefix(PEER_PREFIX).size();
    }

    /**
     * Clears all persisted peers.
     *
     * @throws KVStore.StorageException if storage fails
     */
    public void clear() throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("PeerStore is closed");
        }

        Set<String> keys = store.keysWithPrefix(PEER_PREFIX);
        for (String key : keys) {
            store.delete(key);
        }
        metadataCache.clear();

        log.info("Cleared all persisted peers");
    }

    /**
     * Flushes all changes to disk.
     *
     * @throws KVStore.StorageException if storage fails
     */
    public void flush() throws KVStore.StorageException {
        if (closed) {
            return;
        }

        // Save metadata
        saveMetadata();

        log.debug("Flushed peer store to disk");
    }

    /**
     * Loads metadata from storage.
     */
    private void loadMetadata() throws KVStore.StorageException {
        Optional<byte[]> data = store.get(METADATA_KEY);
        if (data.isEmpty()) {
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(data.get()))) {
            @SuppressWarnings("unchecked")
            Map<String, PeerMetadata> loaded = (Map<String, PeerMetadata>) ois.readObject();
            metadataCache.putAll(loaded);
            log.debug("Loaded peer metadata", "count", loaded.size());
        } catch (IOException | ClassNotFoundException e) {
            log.error("Failed to load peer metadata", e);
        }
    }

    /**
     * Saves metadata to storage.
     */
    private void saveMetadata() throws KVStore.StorageException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(new HashMap<>(metadataCache));
            store.put(METADATA_KEY, baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize metadata", e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        try {
            flush();
            store.close();
            closed = true;
            log.info("PeerStorePersistent closed");
        } catch (Exception e) {
            log.error("Error closing PeerStorePersistent", e);
        }
    }

    // ==================== Serialization ====================

    private byte[] serializePeer(Peer peer) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(peer);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize peer", e);
        }
    }

    private Peer deserializePeer(byte[] data) {
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(data))) {
            return (Peer) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize peer", e);
        }
    }

    // ==================== Data Classes ====================

    /**
     * Peer metadata for tracking.
     */
    public static class PeerMetadata implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String peerId;
        private long lastSeenTimestamp;
        private int connectionCount;

        public PeerMetadata(String peerId) {
            this.peerId = peerId;
            this.lastSeenTimestamp = Time.currentMillis();
            this.connectionCount = 0;
        }

        public void updateLastSeen() {
            this.lastSeenTimestamp = Time.currentMillis();
            this.connectionCount++;
        }

        public String getPeerId() { return peerId; }
        public long getLastSeenTimestamp() { return lastSeenTimestamp; }
        public int getConnectionCount() { return connectionCount; }

        @Override
        public String toString() {
            return String.format("PeerMetadata[id=%s, lastSeen=%d, connections=%d]",
                    peerId, lastSeenTimestamp, connectionCount);
        }
    }
}
