package com.genesis.p2p.storage;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Persistent storage for Dead Letter Queue.
 *
 * Stores failed messages to disk for later analysis and retry.
 * Messages that cannot be processed are saved here instead of being lost.
 *
 * Features:
 * - Persistent message storage
 * - Automatic serialization
 * - FIFO queue semantics
 * - Batch operations
 * - Size limits with rotation
 * - Metadata tracking (timestamp, reason, retry count)
 *
 * Use Cases:
 * - Message processing failures
 * - Network timeout recovery
 * - Manual intervention queue
 * - Debugging failed messages
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class DeadLetterQueuePersistent implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(DeadLetterQueuePersistent.class);
    private static final String DLQ_PREFIX = "dlq:";
    private static final String METADATA_KEY = "metadata:dlq";
    private static final int DEFAULT_MAX_SIZE = 10000;

    private final KVStore store;
    private final int maxSize;
    private final Queue<String> keyQueue; // Track insertion order
    private volatile long messageCounter;
    private volatile boolean closed = false;

    /**
     * Creates a persistent dead letter queue.
     *
     * @param dataDir directory for storage
     * @throws KVStore.StorageException if storage cannot be initialized
     */
    public DeadLetterQueuePersistent(Path dataDir) throws KVStore.StorageException {
        this(dataDir, DEFAULT_MAX_SIZE);
    }

    /**
     * Creates a persistent dead letter queue with size limit.
     *
     * @param dataDir directory for storage
     * @param maxSize maximum number of messages to store
     * @throws KVStore.StorageException if storage cannot be initialized
     */
    public DeadLetterQueuePersistent(Path dataDir, int maxSize)
            throws KVStore.StorageException {
        this.store = new FileKVStore(dataDir.resolve("dlq"));
        this.maxSize = maxSize;
        this.keyQueue = new ConcurrentLinkedQueue<>();
        this.messageCounter = 0;

        // Load existing keys
        loadKeys();

        log.info("DeadLetterQueuePersistent initialized",
                "dataDir", dataDir, "maxSize", maxSize);
    }

    /**
     * Adds a failed message to the DLQ.
     *
     * @param message the failed message
     * @param reason the failure reason
     * @param retryCount number of retry attempts
     * @throws KVStore.StorageException if storage fails
     */
    public void add(Message message, String reason, int retryCount)
            throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("DLQ is closed");
        }

        // Create DLQ entry
        DLQEntry entry = new DLQEntry(
                message,
                reason,
                retryCount,
                Time.currentMillis()
        );

        // Generate key
        String key = DLQ_PREFIX + messageCounter++;

        // Enforce size limit (FIFO eviction)
        if (keyQueue.size() >= maxSize) {
            String oldestKey = keyQueue.poll();
            if (oldestKey != null) {
                store.delete(oldestKey);
                log.debug("Evicted oldest DLQ entry", "key", oldestKey);
            }
        }

        // Store entry
        byte[] data = serialize(entry);
        store.put(key, data);
        keyQueue.offer(key);

        log.info("Message added to DLQ",
                "key", key,
                "reason", reason,
                "retryCount", retryCount);
    }

    /**
     * Gets the next message from the DLQ (without removing).
     *
     * @return optional DLQ entry, empty if queue is empty
     * @throws KVStore.StorageException if storage fails
     */
    public Optional<DLQEntry> peek() throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("DLQ is closed");
        }

        String key = keyQueue.peek();
        if (key == null) {
            return Optional.empty();
        }

        Optional<byte[]> data = store.get(key);
        if (data.isEmpty()) {
            // Key exists in queue but not in store - cleanup
            keyQueue.poll();
            return peek(); // Try next
        }

        return Optional.of(deserialize(data.get()));
    }

    /**
     * Gets and removes the next message from the DLQ.
     *
     * @return optional DLQ entry, empty if queue is empty
     * @throws KVStore.StorageException if storage fails
     */
    public Optional<DLQEntry> poll() throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("DLQ is closed");
        }

        String key = keyQueue.poll();
        if (key == null) {
            return Optional.empty();
        }

        Optional<byte[]> data = store.get(key);
        if (data.isEmpty()) {
            return poll(); // Try next
        }

        // Delete from store
        store.delete(key);

        DLQEntry entry = deserialize(data.get());
        log.debug("Message polled from DLQ", "key", key);
        return Optional.of(entry);
    }

    /**
     * Gets all messages from the DLQ.
     *
     * @return list of all DLQ entries
     * @throws KVStore.StorageException if storage fails
     */
    public List<DLQEntry> getAll() throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("DLQ is closed");
        }

        List<DLQEntry> entries = new ArrayList<>();

        for (String key : keyQueue) {
            Optional<byte[]> data = store.get(key);
            if (data.isPresent()) {
                entries.add(deserialize(data.get()));
            }
        }

        return entries;
    }

    /**
     * Gets messages matching a filter.
     *
     * @param messageType the message type to filter by
     * @return list of matching DLQ entries
     * @throws KVStore.StorageException if storage fails
     */
    public List<DLQEntry> getByMessageType(String messageType)
            throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("DLQ is closed");
        }

        List<DLQEntry> entries = new ArrayList<>();

        for (String key : keyQueue) {
            Optional<byte[]> data = store.get(key);
            if (data.isPresent()) {
                DLQEntry entry = deserialize(data.get());
                if (entry.message.type().equals(messageType)) {
                    entries.add(entry);
                }
            }
        }

        return entries;
    }

    /**
     * Gets the number of messages in the DLQ.
     *
     * @return size of the DLQ
     */
    public int size() {
        return keyQueue.size();
    }

    /**
     * Checks if the DLQ is empty.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return keyQueue.isEmpty();
    }

    /**
     * Clears all messages from the DLQ.
     *
     * @throws KVStore.StorageException if storage fails
     */
    public void clear() throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("DLQ is closed");
        }

        for (String key : keyQueue) {
            store.delete(key);
        }
        keyQueue.clear();
        log.info("DLQ cleared");
    }

    /**
     * Removes messages older than the specified age.
     *
     * @param maxAgeMillis maximum age in milliseconds
     * @return number of messages removed
     * @throws KVStore.StorageException if storage fails
     */
    public int removeOlderThan(long maxAgeMillis) throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("DLQ is closed");
        }

        long cutoffTime = Time.currentMillis() - maxAgeMillis;
        int removed = 0;

        Iterator<String> iterator = keyQueue.iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            Optional<byte[]> data = store.get(key);

            if (data.isPresent()) {
                DLQEntry entry = deserialize(data.get());
                if (entry.timestamp < cutoffTime) {
                    store.delete(key);
                    iterator.remove();
                    removed++;
                }
            }
        }

        log.info("Removed old DLQ entries", "count", removed);
        return removed;
    }

    /**
     * Gets DLQ statistics.
     *
     * @return statistics
     * @throws KVStore.StorageException if storage fails
     */
    public DLQStats getStats() throws KVStore.StorageException {
        if (closed) {
            throw new IllegalStateException("DLQ is closed");
        }

        Map<String, Integer> reasonCounts = new HashMap<>();
        int totalRetries = 0;
        long oldestTimestamp = Long.MAX_VALUE;

        for (String key : keyQueue) {
            Optional<byte[]> data = store.get(key);
            if (data.isPresent()) {
                DLQEntry entry = deserialize(data.get());
                reasonCounts.merge(entry.reason, 1, Integer::sum);
                totalRetries += entry.retryCount;
                oldestTimestamp = Math.min(oldestTimestamp, entry.timestamp);
            }
        }

        return new DLQStats(
                keyQueue.size(),
                reasonCounts,
                totalRetries,
                oldestTimestamp
        );
    }

    /**
     * Flushes all changes to disk.
     */
    public void flush() {
        if (closed) {
            return;
        }

        log.debug("Flushed DLQ to disk");
    }

    /**
     * Loads existing keys from storage.
     */
    private void loadKeys() throws KVStore.StorageException {
        Set<String> keys = store.keysWithPrefix(DLQ_PREFIX);

        // Sort keys by numeric suffix to maintain order
        List<String> sortedKeys = new ArrayList<>(keys);
        sortedKeys.sort(Comparator.comparingLong(this::extractCounter));

        keyQueue.addAll(sortedKeys);

        // Update counter to be higher than any existing key
        if (!sortedKeys.isEmpty()) {
            String lastKey = sortedKeys.get(sortedKeys.size() - 1);
            messageCounter = extractCounter(lastKey) + 1;
        }

        log.info("Loaded DLQ keys", "count", keyQueue.size());
    }

    /**
     * Extracts counter from key.
     */
    private long extractCounter(String key) {
        try {
            return Long.parseLong(key.substring(DLQ_PREFIX.length()));
        } catch (NumberFormatException e) {
            return 0;
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
            log.info("DeadLetterQueuePersistent closed");
        } catch (Exception e) {
            log.error("Error closing DeadLetterQueuePersistent", e);
        }
    }

    // ==================== Serialization ====================

    private byte[] serialize(DLQEntry entry) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(entry);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize DLQ entry", e);
        }
    }

    private DLQEntry deserialize(byte[] data) {
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(data))) {
            return (DLQEntry) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize DLQ entry", e);
        }
    }

    // ==================== Data Classes ====================

    /**
     * Dead Letter Queue entry.
     */
    public static class DLQEntry implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Message message;
        private final String reason;
        private final int retryCount;
        private final long timestamp;

        public DLQEntry(Message message, String reason, int retryCount, long timestamp) {
            this.message = message;
            this.reason = reason;
            this.retryCount = retryCount;
            this.timestamp = timestamp;
        }

        public Message getMessage() { return message; }
        public String getReason() { return reason; }
        public int getRetryCount() { return retryCount; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("DLQEntry[type=%s, reason=%s, retries=%d, timestamp=%d]",
                    message.type(), reason, retryCount, timestamp);
        }
    }

    /**
     * DLQ statistics.
     */
    public static class DLQStats {
        private final int totalMessages;
        private final Map<String, Integer> reasonCounts;
        private final int totalRetries;
        private final long oldestTimestamp;

        public DLQStats(int totalMessages, Map<String, Integer> reasonCounts,
                       int totalRetries, long oldestTimestamp) {
            this.totalMessages = totalMessages;
            this.reasonCounts = reasonCounts;
            this.totalRetries = totalRetries;
            this.oldestTimestamp = oldestTimestamp;
        }

        public int getTotalMessages() { return totalMessages; }
        public Map<String, Integer> getReasonCounts() { return reasonCounts; }
        public int getTotalRetries() { return totalRetries; }
        public long getOldestTimestamp() { return oldestTimestamp; }

        @Override
        public String toString() {
            return String.format("DLQStats[messages=%d, retries=%d, reasons=%d]",
                    totalMessages, totalRetries, reasonCounts.size());
        }
    }
}
