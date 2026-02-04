package com.genesis.p2p.storage.message;

import java.util.Map;

/**
 * Statistics about message storage.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public record MessageStorageStats(
        long totalMessages,
        long totalSizeBytes,
        Map<MessageState, Long> messagesByState,
        Map<MessageDirection, Long> messagesByDirection,
        long handshakeMessages,
        long encryptedMessages,
        long cacheSize
) {
    public double averageMessageSize() {
        return totalMessages == 0 ? 0 : (double) totalSizeBytes / totalMessages;
    }

    public long getPendingCount() {
        return messagesByState.getOrDefault(MessageState.PENDING, 0L);
    }

    public long getSentCount() {
        return messagesByState.getOrDefault(MessageState.SENT, 0L);
    }

    public long getReceivedCount() {
        return messagesByState.getOrDefault(MessageState.RECEIVED, 0L);
    }

    public long getDeliveredCount() {
        return messagesByState.getOrDefault(MessageState.DELIVERED, 0L);
    }

    public long getFailedCount() {
        return messagesByState.getOrDefault(MessageState.FAILED, 0L);
    }

    public long getOutboundCount() {
        return messagesByDirection.getOrDefault(MessageDirection.OUTBOUND, 0L);
    }

    public long getInboundCount() {
        return messagesByDirection.getOrDefault(MessageDirection.INBOUND, 0L);
    }
}

