package com.genesis.p2p.storage.message;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.storage.KVStore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Persistent storage for messages with full lifecycle tracking.
 *
 * Features:
 * - Zero data loss: All messages persisted before processing
 * - Replay capability: Messages can be replayed on restart
 * - Full traceability: Complete audit trail of all messages
 * - Query support: Find messages by various criteria
 * - Automatic cleanup: Old messages can be purged
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class MessagePersistenceStore {

    private static final NodeLogger log = NodeLogger.getLogger(MessagePersistenceStore.class);

    private final KVStore store;
    private final MessageSerializer serializer;
    private final Map<String, PersistedMessage> cache; // In-memory cache for fast access
    private final MessagePersistenceConfig config;

    // Key prefixes for different message types
    private static final String PREFIX_OUTBOUND = "msg:out:";
    private static final String PREFIX_INBOUND = "msg:in:";
    private static final String PREFIX_HANDSHAKE = "msg:hs:";
    private static final String PREFIX_INDEX_TIME = "idx:time:";
    private static final String PREFIX_INDEX_STATE = "idx:state:";

    public MessagePersistenceStore(KVStore store, MessagePersistenceConfig config) {
        this.store = store;
        this.serializer = new MessageSerializer();
        this.cache = new ConcurrentHashMap<>();
        this.config = config;

        log.info("Message persistence store initialized",
                "cacheEnabled", config.enableCache(),
                "maxCacheSize", config.maxCacheSize());
    }

    /**
     * Persists a message before sending/processing.
     * This is the critical path for zero data loss.
     */
    public PersistedMessage persist(PersistedMessage message) throws KVStore.StorageException {
        long startTime = System.nanoTime();

        try {
            // Calculate size
            byte[] serialized = serializer.serialize(message);
            PersistedMessage withSize = message.withSize(serialized.length);

            // Build key
            String key = buildKey(withSize);

            // Store to disk (CRITICAL: this must succeed)
            store.put(key, serialized);

            // Update indexes
            updateIndexes(withSize);

            // Update cache if enabled
            if (config.enableCache() && cache.size() < config.maxCacheSize()) {
                cache.put(withSize.persistenceId(), withSize);
            }

            long durationNanos = System.nanoTime() - startTime;
            log.debug("Message persisted",
                    "persistenceId", withSize.persistenceId(),
                    "messageId", withSize.message().messageId(),
                    "direction", withSize.direction().name(),
                    "state", withSize.state().name(),
                    "sizeBytes", withSize.sizeBytes(),
                    "durationMs", durationNanos / 1_000_000.0);

            return withSize;

        } catch (Exception e) {
            log.error("Failed to persist message",  e,
                    "messageId", message.message().messageId());
            throw new KVStore.StorageException("Failed to persist message", e);
        }
    }

    /**
     * Updates the state of a persisted message.
     */
    public PersistedMessage updateState(String persistenceId, MessageState newState)
            throws KVStore.StorageException {

        PersistedMessage original = get(persistenceId)
                .orElseThrow(() -> new KVStore.StorageException(
                        "Message not found: " + persistenceId));

        PersistedMessage updated = switch (newState) {
            case SENT -> original.markSent();
            case DELIVERED -> original.markDelivered();
            case FAILED -> original.markFailed("State updated to FAILED");
            default -> new PersistedMessage(
                    original.message(),
                    original.persistenceId(),
                    original.persistedAt(),
                    newState,
                    original.direction(),
                    original.transportType(),
                    original.remoteAddress(),
                    original.wasEncrypted(),
                    original.wasCompressed(),
                    original.fragmentCount(),
                    original.sentAt(),
                    original.receivedAt(),
                    original.deliveredAt(),
                    original.retryCount(),
                    original.lastError(),
                    original.lastErrorAt(),
                    original.handshakeId(),
                    original.isHandshakeMessage(),
                    original.traceId(),
                    original.spanId(),
                    original.persistenceVersion(),
                    original.sizeBytes()
            );
        };

        return persist(updated);
    }

    /**
     * Retrieves a message by persistence ID.
     */
    public Optional<PersistedMessage> get(String persistenceId) throws KVStore.StorageException {
        // Check cache first
        if (config.enableCache()) {
            PersistedMessage cached = cache.get(persistenceId);
            if (cached != null) {
                return Optional.of(cached);
            }
        }

        // Search in all prefixes
        for (String prefix : Arrays.asList(PREFIX_OUTBOUND, PREFIX_INBOUND, PREFIX_HANDSHAKE)) {
            String key = prefix + persistenceId;
            Optional<byte[]> data = store.get(key);
            if (data.isPresent()) {
                PersistedMessage message = serializer.deserialize(data.get());

                // Update cache
                if (config.enableCache() && cache.size() < config.maxCacheSize()) {
                    cache.put(persistenceId, message);
                }

                return Optional.of(message);
            }
        }

        return Optional.empty();
    }

    /**
     * Retrieves a message by message ID.
     */
    public Optional<PersistedMessage> getByMessageId(String messageId) throws KVStore.StorageException {
        // This is slower - need to scan
        Set<String> allKeys = store.keys();
        for (String key : allKeys) {
            if (key.startsWith("msg:")) {
                Optional<byte[]> data = store.get(key);
                if (data.isPresent()) {
                    PersistedMessage msg = serializer.deserialize(data.get());
                    if (msg.message().messageId().equals(messageId)) {
                        return Optional.of(msg);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Gets all messages (for replay on restart).
     */
    public List<PersistedMessage> getAllMessages() throws KVStore.StorageException {
        List<PersistedMessage> messages = new ArrayList<>();
        Set<String> keys = store.keysWithPrefix("msg:");

        for (String key : keys) {
            Optional<byte[]> data = store.get(key);
            if (data.isPresent()) {
                try {
                    messages.add(serializer.deserialize(data.get()));
                } catch (Exception e) {
                    log.warn("Failed to deserialize message",
                            "key", key,
                            "error", e.getMessage());
                }
            }
        }

        log.info("Loaded all messages from storage", "count", messages.size());
        return messages;
    }

    /**
     * Gets messages by state.
     */
    public List<PersistedMessage> getByState(MessageState state) throws KVStore.StorageException {
        return getAllMessages().stream()
                .filter(m -> m.state() == state)
                .collect(Collectors.toList());
    }

    /**
     * Gets messages by direction.
     */
    public List<PersistedMessage> getByDirection(MessageDirection direction)
            throws KVStore.StorageException {
        String prefix = direction == MessageDirection.OUTBOUND ? PREFIX_OUTBOUND : PREFIX_INBOUND;
        List<PersistedMessage> messages = new ArrayList<>();
        Set<String> keys = store.keysWithPrefix(prefix);

        for (String key : keys) {
            Optional<byte[]> data = store.get(key);
            if (data.isPresent()) {
                messages.add(serializer.deserialize(data.get()));
            }
        }

        return messages;
    }

    /**
     * Gets messages in a time range.
     */
    public List<PersistedMessage> getByTimeRange(Instant start, Instant end)
            throws KVStore.StorageException {
        return getAllMessages().stream()
                .filter(m -> {
                    Instant t = m.persistedAt();
                    return !t.isBefore(start) && !t.isAfter(end);
                })
                .collect(Collectors.toList());
    }

    /**
     * Gets handshake messages.
     */
    public List<PersistedMessage> getHandshakeMessages() throws KVStore.StorageException {
        List<PersistedMessage> messages = new ArrayList<>();
        Set<String> keys = store.keysWithPrefix(PREFIX_HANDSHAKE);

        for (String key : keys) {
            Optional<byte[]> data = store.get(key);
            if (data.isPresent()) {
                messages.add(serializer.deserialize(data.get()));
            }
        }

        return messages;
    }

    /**
     * Deletes a message.
     */
    public boolean delete(String persistenceId) throws KVStore.StorageException {
        cache.remove(persistenceId);

        // Try all prefixes
        for (String prefix : Arrays.asList(PREFIX_OUTBOUND, PREFIX_INBOUND, PREFIX_HANDSHAKE)) {
            String key = prefix + persistenceId;
            if (store.delete(key)) {
                log.debug("Message deleted", "persistenceId", persistenceId);
                return true;
            }
        }

        return false;
    }

    /**
     * Purges old messages based on retention policy.
     */
    public int purgeOldMessages() throws KVStore.StorageException {
        if (config.retentionDays() <= 0) {
            return 0; // Retention disabled
        }

        Instant cutoff = Instant.now().minusSeconds(config.retentionDays() * 24 * 3600);
        List<PersistedMessage> oldMessages = getAllMessages().stream()
                .filter(m -> m.persistedAt().isBefore(cutoff))
                .toList();

        int deleted = 0;
        for (PersistedMessage msg : oldMessages) {
            if (delete(msg.persistenceId())) {
                deleted++;
            }
        }

        log.info("Purged old messages",
                "deleted", deleted,
                "cutoffDate", cutoff.toString(),
                "retentionDays", config.retentionDays());

        return deleted;
    }

    /**
     * Gets storage statistics.
     */
    public MessageStorageStats getStats() throws KVStore.StorageException {
        List<PersistedMessage> all = getAllMessages();

        long totalSize = all.stream().mapToLong(PersistedMessage::sizeBytes).sum();

        Map<MessageState, Long> byState = all.stream()
                .collect(Collectors.groupingBy(PersistedMessage::state, Collectors.counting()));

        Map<MessageDirection, Long> byDirection = all.stream()
                .collect(Collectors.groupingBy(PersistedMessage::direction, Collectors.counting()));

        long handshakeCount = all.stream().filter(PersistedMessage::isHandshakeMessage).count();
        long encryptedCount = all.stream().filter(PersistedMessage::wasEncrypted).count();

        return new MessageStorageStats(
                all.size(),
                totalSize,
                byState,
                byDirection,
                handshakeCount,
                encryptedCount,
                cache.size()
        );
    }

    /**
     * Clears the cache.
     */
    public void clearCache() {
        cache.clear();
        log.info("Message cache cleared");
    }

    /**
     * Closes the store.
     */
    public void close() throws Exception {
        cache.clear();
        store.close();
        log.info("Message persistence store closed");
    }

    // ==================== Private Methods ====================

    private String buildKey(PersistedMessage message) {
        String prefix;
        if (message.isHandshakeMessage()) {
            prefix = PREFIX_HANDSHAKE;
        } else if (message.direction() == MessageDirection.OUTBOUND) {
            prefix = PREFIX_OUTBOUND;
        } else {
            prefix = PREFIX_INBOUND;
        }

        return prefix + message.persistenceId();
    }

    private void updateIndexes(PersistedMessage message) throws KVStore.StorageException {
        // Time index: idx:time:{timestamp}:{persistenceId}
        String timeKey = PREFIX_INDEX_TIME + message.persistedAt().toEpochMilli() + ":"
                + message.persistenceId();
        store.put(timeKey, message.persistenceId().getBytes());

        // State index: idx:state:{state}:{persistenceId}
        String stateKey = PREFIX_INDEX_STATE + message.state().name() + ":"
                + message.persistenceId();
        store.put(stateKey, message.persistenceId().getBytes());
    }
}

