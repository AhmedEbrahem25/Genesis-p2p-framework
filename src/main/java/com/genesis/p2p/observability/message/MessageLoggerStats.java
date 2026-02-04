package com.genesis.p2p.observability.message;

import com.genesis.p2p.storage.message.MessageStorageStats;

/**
 * Statistics for message logging.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public record MessageLoggerStats(
        long totalOutbound,
        long totalInbound,
        long totalEncrypted,
        long totalHandshakes,
        long totalErrors,
        MessageStorageStats storageStats
) {
    public long totalMessages() {
        return totalOutbound + totalInbound;
    }

    public double encryptionRate() {
        long total = totalMessages();
        return total == 0 ? 0 : (double) totalEncrypted / total;
    }

    public double errorRate() {
        long total = totalMessages();
        return total == 0 ? 0 : (double) totalErrors / total;
    }
}

