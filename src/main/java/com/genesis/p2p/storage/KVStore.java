package com.genesis.p2p.storage;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Key-Value Store interface for persistent storage.
 *
 * Provides a simple, flexible storage abstraction that can be implemented
 * using different backends (file-based, RocksDB, in-memory, etc.).
 *
 * Features:
 * - Basic CRUD operations (Create, Read, Update, Delete)
 * - Batch operations for efficiency
 * - Prefix scanning for range queries
 * - Atomic operations
 * - Transaction support
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public interface KVStore extends Closeable {

    /**
     * Stores a key-value pair.
     *
     * @param key the key (must not be null)
     * @param value the value (must not be null)
     * @throws StorageException if storage operation fails
     */
    void put(String key, byte[] value) throws StorageException;

    /**
     * Retrieves a value by key.
     *
     * @param key the key to lookup
     * @return Optional containing the value if found, empty otherwise
     * @throws StorageException if storage operation fails
     */
    Optional<byte[]> get(String key) throws StorageException;

    /**
     * Deletes a key-value pair.
     *
     * @param key the key to delete
     * @return true if the key existed and was deleted, false otherwise
     * @throws StorageException if storage operation fails
     */
    boolean delete(String key) throws StorageException;

    /**
     * Checks if a key exists.
     *
     * @param key the key to check
     * @return true if the key exists, false otherwise
     * @throws StorageException if storage operation fails
     */
    boolean contains(String key) throws StorageException;

    /**
     * Gets all keys in the store.
     *
     * @return set of all keys
     * @throws StorageException if storage operation fails
     */
    Set<String> keys() throws StorageException;

    /**
     * Gets all keys with a specific prefix.
     *
     * @param prefix the prefix to match
     * @return set of matching keys
     * @throws StorageException if storage operation fails
     */
    Set<String> keysWithPrefix(String prefix) throws StorageException;

    /**
     * Gets the number of entries in the store.
     *
     * @return the size of the store
     * @throws StorageException if storage operation fails
     */
    long size() throws StorageException;

    /**
     * Checks if the store is empty.
     *
     * @return true if empty, false otherwise
     * @throws StorageException if storage operation fails
     */
    boolean isEmpty() throws StorageException;

    /**
     * Clears all entries from the store.
     *
     * @throws StorageException if storage operation fails
     */
    void clear() throws StorageException;

    // ==================== Batch Operations ====================

    /**
     * Stores multiple key-value pairs in a single operation.
     *
     * @param entries map of keys to values
     * @throws StorageException if storage operation fails
     */
    void putAll(Map<String, byte[]> entries) throws StorageException;

    /**
     * Retrieves multiple values by keys.
     *
     * @param keys the keys to lookup
     * @return map of keys to values (only existing keys included)
     * @throws StorageException if storage operation fails
     */
    Map<String, byte[]> getAll(List<String> keys) throws StorageException;

    /**
     * Deletes multiple keys in a single operation.
     *
     * @param keys the keys to delete
     * @return number of keys deleted
     * @throws StorageException if storage operation fails
     */
    int deleteAll(List<String> keys) throws StorageException;

    // ==================== Atomic Operations ====================

    /**
     * Atomically puts a value only if the key doesn't exist.
     *
     * @param key the key
     * @param value the value
     * @return true if the value was inserted, false if key already exists
     * @throws StorageException if storage operation fails
     */
    boolean putIfAbsent(String key, byte[] value) throws StorageException;

    /**
     * Atomically replaces a value only if the key exists.
     *
     * @param key the key
     * @param value the new value
     * @return true if the value was replaced, false if key doesn't exist
     * @throws StorageException if storage operation fails
     */
    boolean replace(String key, byte[] value) throws StorageException;

    /**
     * Atomically compares and swaps a value.
     *
     * @param key the key
     * @param expectedValue the expected current value (null means key shouldn't exist)
     * @param newValue the new value
     * @return true if the swap was successful, false otherwise
     * @throws StorageException if storage operation fails
     */
    boolean compareAndSwap(String key, byte[] expectedValue, byte[] newValue) 
            throws StorageException;

    // ==================== Transaction Support ====================

    /**
     * Begins a transaction.
     *
     * @return transaction handle
     * @throws StorageException if transaction cannot be started
     */
    Transaction beginTransaction() throws StorageException;

    /**
     * Transaction interface for atomic multi-operation changes.
     */
    interface Transaction extends AutoCloseable {
        /**
         * Puts a value in this transaction.
         */
        void put(String key, byte[] value);

        /**
         * Deletes a key in this transaction.
         */
        void delete(String key);

        /**
         * Commits the transaction.
         *
         * @throws StorageException if commit fails
         */
        void commit() throws StorageException;

        /**
         * Rolls back the transaction.
         */
        void rollback();

        /**
         * Closes the transaction (rolls back if not committed).
         */
        @Override
        void close();
    }

    // ==================== Utility Operations ====================

    /**
     * Flushes any pending writes to disk.
     *
     * @throws StorageException if flush fails
     */
    void flush() throws StorageException;

    /**
     * Compacts the storage (implementation-dependent).
     *
     * @throws StorageException if compaction fails
     */
    void compact() throws StorageException;

    /**
     * Gets storage statistics.
     *
     * @return statistics about the storage
     */
    StorageStats getStats();

    /**
     * Storage statistics.
     */
    class StorageStats {
        private final long totalKeys;
        private final long totalBytes;
        private final long lastCompactionTime;
        private final String backendType;

        public StorageStats(long totalKeys, long totalBytes, 
                          long lastCompactionTime, String backendType) {
            this.totalKeys = totalKeys;
            this.totalBytes = totalBytes;
            this.lastCompactionTime = lastCompactionTime;
            this.backendType = backendType;
        }

        public long getTotalKeys() { return totalKeys; }
        public long getTotalBytes() { return totalBytes; }
        public long getLastCompactionTime() { return lastCompactionTime; }
        public String getBackendType() { return backendType; }

        @Override
        public String toString() {
            return String.format("StorageStats[keys=%d, bytes=%d, backend=%s]",
                    totalKeys, totalBytes, backendType);
        }
    }

    // ==================== Exception ====================

    /**
     * Exception thrown when storage operations fail.
     */
    class StorageException extends Exception {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

