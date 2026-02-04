package com.genesis.p2p.storage;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * File-based implementation of KVStore.
 *
 * Stores key-value pairs in files within a directory.
 * Each key-value pair is stored in a separate file for simplicity.
 *
 * Features:
 * - Simple file-based persistence
 * - Thread-safe with read-write locks
 * - Automatic directory creation
 * - Safe file operations with temp files
 * - Periodic compaction
 *
 * File Structure:
 * <pre>
 * dataDir/
 *   ├── key1.dat
 *   ├── key2.dat
 *   └── ...
 * </pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class FileKVStore implements KVStore {

    private static final NodeLogger log = NodeLogger.getLogger(FileKVStore.class);
    private static final String FILE_EXTENSION = ".dat";
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path dataDir;
    private final ReadWriteLock lock;
    private final Map<String, Object> transactionLocks;
    private volatile boolean closed;

    /**
     * Creates a file-based KV store.
     *
     * @param dataDir directory to store files
     * @throws StorageException if directory cannot be created
     */
    public FileKVStore(Path dataDir) throws StorageException {
        this.dataDir = dataDir;
        this.lock = new ReentrantReadWriteLock();
        this.transactionLocks = new ConcurrentHashMap<>();
        this.closed = false;

        try {
            Files.createDirectories(dataDir);
            log.info("FileKVStore initialized", "dataDir", dataDir);
        } catch (IOException e) {
            throw new StorageException("Failed to create data directory: " + dataDir, e);
        }
    }

    @Override
    public void put(String key, byte[] value) throws StorageException {
        checkNotClosed();
        validateKey(key);
        validateValue(value);

        lock.writeLock().lock();
        try {
            Path filePath = getFilePath(key);
            Path tempPath = getTempPath(key);

            // Write to temp file first
            Files.write(tempPath, value, StandardOpenOption.CREATE,
                       StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);

            // Atomic rename
            Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING,
                      StandardCopyOption.ATOMIC_MOVE);

            log.debug("Put key", "key", key, "size", value.length);
        } catch (IOException e) {
            throw new StorageException("Failed to put key: " + key, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<byte[]> get(String key) throws StorageException {
        checkNotClosed();
        validateKey(key);

        lock.readLock().lock();
        try {
            Path filePath = getFilePath(key);
            if (!Files.exists(filePath)) {
                return Optional.empty();
            }

            byte[] value = Files.readAllBytes(filePath);
            log.debug("Get key", "key", key, "size", value.length);
            return Optional.of(value);
        } catch (IOException e) {
            throw new StorageException("Failed to get key: " + key, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean delete(String key) throws StorageException {
        checkNotClosed();
        validateKey(key);

        lock.writeLock().lock();
        try {
            Path filePath = getFilePath(key);
            boolean existed = Files.deleteIfExists(filePath);
            if (existed) {
                log.debug("Deleted key", "key", key);
            }
            return existed;
        } catch (IOException e) {
            throw new StorageException("Failed to delete key: " + key, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean contains(String key) throws StorageException {
        checkNotClosed();
        validateKey(key);

        lock.readLock().lock();
        try {
            return Files.exists(getFilePath(key));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Set<String> keys() throws StorageException {
        checkNotClosed();

        lock.readLock().lock();
        try {
            return Files.list(dataDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(FILE_EXTENSION))
                    .map(this::fileNameToKey)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new StorageException("Failed to list keys", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Set<String> keysWithPrefix(String prefix) throws StorageException {
        checkNotClosed();

        return keys().stream()
                .filter(key -> key.startsWith(prefix))
                .collect(Collectors.toSet());
    }

    @Override
    public long size() throws StorageException {
        checkNotClosed();

        lock.readLock().lock();
        try {
            return Files.list(dataDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(FILE_EXTENSION))
                    .count();
        } catch (IOException e) {
            throw new StorageException("Failed to get size", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isEmpty() throws StorageException {
        return size() == 0;
    }

    @Override
    public void clear() throws StorageException {
        checkNotClosed();

        lock.writeLock().lock();
        try {
            Files.list(dataDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(FILE_EXTENSION))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete file during clear", "path", path);
                        }
                    });
            log.info("Store cleared", "dataDir", dataDir);
        } catch (IOException e) {
            throw new StorageException("Failed to clear store", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void putAll(Map<String, byte[]> entries) throws StorageException {
        checkNotClosed();

        lock.writeLock().lock();
        try {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
            log.debug("Put all", "count", entries.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Map<String, byte[]> getAll(List<String> keys) throws StorageException {
        checkNotClosed();

        lock.readLock().lock();
        try {
            Map<String, byte[]> result = new HashMap<>();
            for (String key : keys) {
                Optional<byte[]> value = get(key);
                value.ifPresent(bytes -> result.put(key, bytes));
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int deleteAll(List<String> keys) throws StorageException {
        checkNotClosed();

        lock.writeLock().lock();
        try {
            int count = 0;
            for (String key : keys) {
                if (delete(key)) {
                    count++;
                }
            }
            log.debug("Delete all", "count", count);
            return count;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean putIfAbsent(String key, byte[] value) throws StorageException {
        checkNotClosed();
        validateKey(key);
        validateValue(value);

        lock.writeLock().lock();
        try {
            if (contains(key)) {
                return false;
            }
            put(key, value);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean replace(String key, byte[] value) throws StorageException {
        checkNotClosed();
        validateKey(key);
        validateValue(value);

        lock.writeLock().lock();
        try {
            if (!contains(key)) {
                return false;
            }
            put(key, value);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean compareAndSwap(String key, byte[] expectedValue, byte[] newValue)
            throws StorageException {
        checkNotClosed();
        validateKey(key);
        validateValue(newValue);

        lock.writeLock().lock();
        try {
            Optional<byte[]> currentValue = get(key);

            if (expectedValue == null) {
                if (currentValue.isPresent()) {
                    return false;
                }
            } else {
                if (!currentValue.isPresent() ||
                    !Arrays.equals(currentValue.get(), expectedValue)) {
                    return false;
                }
            }

            put(key, newValue);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Transaction beginTransaction() throws StorageException {
        checkNotClosed();
        return new FileTransaction();
    }

    @Override
    public void flush() throws StorageException {
        // File operations are already synced, nothing to flush
        log.debug("Flush called (no-op for FileKVStore)");
    }

    @Override
    public void compact() throws StorageException {
        checkNotClosed();

        lock.writeLock().lock();
        try {
            // For file-based store, compaction is cleaning up temp files
            Files.list(dataDir)
                    .filter(p -> p.toString().endsWith(TEMP_SUFFIX))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                            log.debug("Deleted temp file", "path", path);
                        } catch (IOException e) {
                            log.warn("Failed to delete temp file", "path", path);
                        }
                    });
            log.info("Compaction completed", "dataDir", dataDir);
        } catch (IOException e) {
            throw new StorageException("Failed to compact store", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public StorageStats getStats() {
        try {
            long totalKeys = size();
            long totalBytes = Files.list(dataDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(FILE_EXTENSION))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();

            return new StorageStats(totalKeys, totalBytes,
                    Time.currentMillis(), "FileKVStore");
        } catch (Exception e) {
            log.warn("Failed to get stats", e);
            return new StorageStats(0, 0, 0, "FileKVStore");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        lock.writeLock().lock();
        try {
            closed = true;
            log.info("FileKVStore closed", "dataDir", dataDir);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Helper Methods ====================

    private Path getFilePath(String key) {
        return dataDir.resolve(sanitizeKey(key) + FILE_EXTENSION);
    }

    private Path getTempPath(String key) {
        return dataDir.resolve(sanitizeKey(key) + TEMP_SUFFIX);
    }

    private String sanitizeKey(String key) {
        // Replace unsafe file system characters
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String fileNameToKey(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
    }

    private void checkNotClosed() throws StorageException {
        if (closed) {
            throw new StorageException("Store is closed");
        }
    }

    private void validateKey(String key) throws StorageException {
        if (key == null || key.isEmpty()) {
            throw new StorageException("Key cannot be null or empty");
        }
    }

    private void validateValue(byte[] value) throws StorageException {
        if (value == null) {
            throw new StorageException("Value cannot be null");
        }
    }

    // ==================== Transaction Implementation ====================

    private class FileTransaction implements Transaction {
        private final Map<String, byte[]> puts = new HashMap<>();
        private final Set<String> deletes = new HashSet<>();
        private boolean committed = false;
        private boolean rolledBack = false;

        @Override
        public void put(String key, byte[] value) {
            puts.put(key, value);
            deletes.remove(key);
        }

        @Override
        public void delete(String key) {
            deletes.add(key);
            puts.remove(key);
        }

        @Override
        public void commit() throws StorageException {
            if (committed || rolledBack) {
                throw new StorageException("Transaction already completed");
            }

            lock.writeLock().lock();
            try {
                // Apply all puts
                for (Map.Entry<String, byte[]> entry : puts.entrySet()) {
                    FileKVStore.this.put(entry.getKey(), entry.getValue());
                }

                // Apply all deletes
                for (String key : deletes) {
                    FileKVStore.this.delete(key);
                }

                committed = true;
                log.debug("Transaction committed", "puts", puts.size(), "deletes", deletes.size());
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public void rollback() {
            rolledBack = true;
            puts.clear();
            deletes.clear();
            log.debug("Transaction rolled back");
        }

        @Override
        public void close() {
            if (!committed && !rolledBack) {
                rollback();
            }
        }
    }
}

