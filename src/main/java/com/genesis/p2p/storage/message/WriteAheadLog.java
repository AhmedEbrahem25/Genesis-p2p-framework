package com.genesis.p2p.storage.message;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Write-Ahead Log (WAL) for message persistence with crash recovery.
 *
 * Provides:
 * - Durability: All writes logged before being applied
 * - Atomicity: Write operations are atomic
 * - Crash recovery: Can replay WAL entries on restart
 * - Performance: Batched writes with fsync control
 *
 * WAL Entry Format:
 * [4 bytes: entry type] [8 bytes: timestamp] [4 bytes: key length] [key bytes]
 * [4 bytes: value length] [value bytes] [4 bytes: checksum]
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class WriteAheadLog implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(WriteAheadLog.class);

    // Entry types
    private static final int TYPE_PUT = 1;
    private static final int TYPE_DELETE = 2;
    private static final int TYPE_CHECKPOINT = 3;

    private final Path walFile;
    private final FileChannel channel;
    private final ReentrantReadWriteLock lock;
    private final boolean syncOnWrite;
    private long entriesWritten;
    private long bytesWritten;

    /**
     * Creates a WAL with the given file path.
     *
     * @param walPath path to WAL file
     * @param syncOnWrite whether to fsync after every write (slower but more durable)
     */
    public WriteAheadLog(Path walPath, boolean syncOnWrite) throws IOException {
        this.walFile = walPath;
        this.syncOnWrite = syncOnWrite;
        this.lock = new ReentrantReadWriteLock();
        this.entriesWritten = 0;
        this.bytesWritten = 0;

        // Create parent directory if needed
        if (walPath.getParent() != null) {
            Files.createDirectories(walPath.getParent());
        }

        // Open channel with append mode
        this.channel = FileChannel.open(
                walPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.SYNC
        );

        log.info("WAL initialized",
                "walFile", walPath.toString(),
                "syncOnWrite", syncOnWrite);
    }

    /**
     * Logs a PUT operation.
     *
     * @param key the key
     * @param value the value
     */
    public void logPut(String key, byte[] value) throws IOException {
        lock.writeLock().lock();
        try {
            writeEntry(TYPE_PUT, key, value);
            entriesWritten++;

            if (syncOnWrite) {
                channel.force(true); // fsync to disk
            }

            log.debug("WAL entry logged",
                    "type", "PUT",
                    "key", key,
                    "valueSize", value.length,
                    "totalEntries", entriesWritten);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Logs a DELETE operation.
     *
     * @param key the key
     */
    public void logDelete(String key) throws IOException {
        lock.writeLock().lock();
        try {
            writeEntry(TYPE_DELETE, key, new byte[0]);
            entriesWritten++;

            if (syncOnWrite) {
                channel.force(true); // fsync to disk
            }

            log.debug("WAL entry logged",
                    "type", "DELETE",
                    "key", key,
                    "totalEntries", entriesWritten);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Logs a checkpoint (for faster recovery).
     */
    public void logCheckpoint() throws IOException {
        lock.writeLock().lock();
        try {
            writeEntry(TYPE_CHECKPOINT, "checkpoint-" + Instant.now().toEpochMilli(), new byte[0]);
            channel.force(true); // Always fsync checkpoints

            log.info("WAL checkpoint created",
                    "totalEntries", entriesWritten,
                    "bytesWritten", bytesWritten);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Replays WAL entries for crash recovery.
     *
     * @param handler handler for replayed entries
     */
    public void replay(WALReplayHandler handler) throws IOException {
        log.info("Replaying WAL for crash recovery", "walFile", walFile.toString());

        List<WALEntry> entries = readAllEntries();

        log.info("WAL entries loaded",
                "count", entries.size(),
                "walFile", walFile.toString());

        int replayed = 0;
        for (WALEntry entry : entries) {
            try {
                switch (entry.type()) {
                    case TYPE_PUT -> {
                        handler.onPut(entry.key(), entry.value());
                        replayed++;
                    }
                    case TYPE_DELETE -> {
                        handler.onDelete(entry.key());
                        replayed++;
                    }
                    case TYPE_CHECKPOINT -> {
                        handler.onCheckpoint(entry.timestamp());
                        log.debug("Checkpoint reached", "timestamp", entry.timestamp());
                    }
                    default -> log.warn("Unknown WAL entry type",
                            "type", entry.type(),
                            "key", entry.key());
                }
            } catch (Exception e) {
                log.error("Error replaying WAL entry", e,
                        "type", entry.type(),
                        "key", entry.key());
                // Continue with next entry
            }
        }

        log.info("WAL replay completed",
                "totalEntries", entries.size(),
                "replayed", replayed);
    }

    /**
     * Truncates the WAL (after successful checkpoint).
     */
    public void truncate() throws IOException {
        lock.writeLock().lock();
        try {
            channel.truncate(0);
            channel.force(true);
            entriesWritten = 0;
            bytesWritten = 0;

            log.info("WAL truncated", "walFile", walFile.toString());

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets WAL statistics.
     */
    public WALStats getStats() {
        lock.readLock().lock();
        try {
            return new WALStats(
                    entriesWritten,
                    bytesWritten,
                    walFile.toString()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            channel.force(true); // Final fsync
            channel.close();

            log.info("WAL closed",
                    "walFile", walFile.toString(),
                    "entriesWritten", entriesWritten,
                    "bytesWritten", bytesWritten);

        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Private Methods ====================

    /**
     * Writes a single WAL entry.
     */
    private void writeEntry(int type, String key, byte[] value) throws IOException {
        byte[] keyBytes = key.getBytes();
        long timestamp = System.currentTimeMillis();

        // Calculate total size
        int totalSize = 4 + 8 + 4 + keyBytes.length + 4 + value.length + 4;

        // Build entry buffer
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(type);
        buffer.putLong(timestamp);
        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(value.length);
        buffer.put(value);

        // Simple checksum (CRC32 would be better)
        int checksum = calculateChecksum(type, timestamp, keyBytes, value);
        buffer.putInt(checksum);

        buffer.flip();

        // Write to channel
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            bytesWritten += written;
        }
    }

    /**
     * Reads all WAL entries from file.
     */
    private List<WALEntry> readAllEntries() throws IOException {
        List<WALEntry> entries = new ArrayList<>();

        // Reset channel position to beginning
        FileChannel readChannel = FileChannel.open(walFile, StandardOpenOption.READ);

        try {
            ByteBuffer headerBuffer = ByteBuffer.allocate(16); // type + timestamp + key length

            while (readChannel.position() < readChannel.size()) {
                headerBuffer.clear();

                // Read header
                int bytesRead = readChannel.read(headerBuffer);
                if (bytesRead < 16) {
                    break; // Incomplete entry
                }

                headerBuffer.flip();
                int type = headerBuffer.getInt();
                long timestamp = headerBuffer.getLong();
                int keyLength = headerBuffer.getInt();

                // Read key
                ByteBuffer keyBuffer = ByteBuffer.allocate(keyLength);
                readChannel.read(keyBuffer);
                keyBuffer.flip();
                String key = new String(keyBuffer.array());

                // Read value length
                ByteBuffer valueLengthBuffer = ByteBuffer.allocate(4);
                readChannel.read(valueLengthBuffer);
                valueLengthBuffer.flip();
                int valueLength = valueLengthBuffer.getInt();

                // Read value
                ByteBuffer valueBuffer = ByteBuffer.allocate(valueLength);
                readChannel.read(valueBuffer);
                valueBuffer.flip();
                byte[] value = valueBuffer.array();

                // Read checksum
                ByteBuffer checksumBuffer = ByteBuffer.allocate(4);
                readChannel.read(checksumBuffer);
                checksumBuffer.flip();
                int checksum = checksumBuffer.getInt();

                // Verify checksum
                int expectedChecksum = calculateChecksum(type, timestamp, key.getBytes(), value);
                if (checksum != expectedChecksum) {
                    log.warn("WAL entry checksum mismatch, skipping",
                            "key", key,
                            "expected", expectedChecksum,
                            "actual", checksum);
                    continue;
                }

                entries.add(new WALEntry(type, timestamp, key, value));
            }

        } finally {
            readChannel.close();
        }

        return entries;
    }

    /**
     * Calculates simple checksum for WAL entry.
     */
    private int calculateChecksum(int type, long timestamp, byte[] key, byte[] value) {
        int checksum = type;
        checksum ^= (int) (timestamp & 0xFFFFFFFF);
        checksum ^= (int) ((timestamp >> 32) & 0xFFFFFFFF);

        for (byte b : key) {
            checksum ^= b;
        }

        for (byte b : value) {
            checksum ^= b;
        }

        return checksum;
    }

    // ==================== Inner Classes ====================

    /**
     * WAL entry record.
     */
    public record WALEntry(int type, long timestamp, String key, byte[] value) {}

    /**
     * WAL replay handler interface.
     */
    public interface WALReplayHandler {
        void onPut(String key, byte[] value) throws Exception;
        void onDelete(String key) throws Exception;
        void onCheckpoint(long timestamp);
    }

    /**
     * WAL statistics.
     */
    public record WALStats(long entriesWritten, long bytesWritten, String walFile) {
        @Override
        public String toString() {
            return String.format("WALStats[entries=%d, bytes=%d, file=%s]",
                    entriesWritten, bytesWritten, walFile);
        }
    }
}
