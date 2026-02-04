package com.genesis.p2p.storage.message;

import com.genesis.p2p.core.Message;
import java.time.Instant;

/**
 * Represents a persisted message with full lifecycle metadata.
 *
 * This record captures complete message state for:
 * - Message traceability (end-to-end tracking)
 * - Replay capability (on restart)
 * - Audit trail (compliance/debugging)
 * - Zero data loss guarantee
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public record PersistedMessage(
        // Core message data
        Message message,

        // Persistence metadata
        String persistenceId,           // Unique ID for this persistence record
        Instant persistedAt,            // When was this message persisted

        // Message lifecycle state
        MessageState state,             // Current state (PENDING, SENT, RECEIVED, DELIVERED, FAILED)
        MessageDirection direction,     // OUTBOUND or INBOUND

        // Transport metadata
        String transportType,           // UDP, TCP, WebSocket
        String remoteAddress,           // Peer address (IP:port)

        // Protocol metadata
        boolean wasEncrypted,           // Was encryption applied?
        boolean wasCompressed,          // Was compression applied?
        int fragmentCount,              // Number of fragments (0 if not fragmented)

        // Timing information
        Instant sentAt,                 // When was this sent (null if not sent yet)
        Instant receivedAt,             // When was this received (null if outbound)
        Instant deliveredAt,            // When was this delivered to handler (null if pending)

        // Error tracking
        int retryCount,                 // Number of retry attempts
        String lastError,               // Last error message (null if no error)
        Instant lastErrorAt,            // When did last error occur

        // Handshake tracking
        String handshakeId,             // Associated handshake ID (null if not handshake)
        boolean isHandshakeMessage,     // Is this a handshake message?

        // Observability
        String traceId,                 // Distributed trace ID
        String spanId,                  // Span ID for this operation

        // Storage metadata
        int persistenceVersion,         // Version of persistence format
        long sizeBytes                  // Size in bytes (for metrics)
) {

    /**
     * Creates a new persisted message for an outbound message.
     */
    public static PersistedMessage forOutbound(
            Message message,
            String transportType,
            String remoteAddress,
            boolean encrypted,
            boolean compressed,
            String traceId,
            String spanId) {

        return new PersistedMessage(
                message,
                java.util.UUID.randomUUID().toString(),
                Instant.now(),
                MessageState.PENDING,
                MessageDirection.OUTBOUND,
                transportType,
                remoteAddress,
                encrypted,
                compressed,
                0,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                false,
                traceId,
                spanId,
                1,
                0
        );
    }

    /**
     * Creates a new persisted message for an inbound message.
     */
    public static PersistedMessage forInbound(
            Message message,
            String transportType,
            String remoteAddress,
            boolean encrypted,
            boolean compressed,
            String traceId,
            String spanId) {

        Instant now = Instant.now();
        return new PersistedMessage(
                message,
                java.util.UUID.randomUUID().toString(),
                now,
                MessageState.RECEIVED,
                MessageDirection.INBOUND,
                transportType,
                remoteAddress,
                encrypted,
                compressed,
                0,
                null,
                now,
                null,
                0,
                null,
                null,
                null,
                false,
                traceId,
                spanId,
                1,
                0
        );
    }

    /**
     * Marks this message as sent.
     */
    public PersistedMessage markSent() {
        return new PersistedMessage(
                message, persistenceId, persistedAt,
                MessageState.SENT, direction,
                transportType, remoteAddress,
                wasEncrypted, wasCompressed, fragmentCount,
                Instant.now(), receivedAt, deliveredAt,
                retryCount, lastError, lastErrorAt,
                handshakeId, isHandshakeMessage,
                traceId, spanId,
                persistenceVersion, sizeBytes
        );
    }

    /**
     * Marks this message as delivered.
     */
    public PersistedMessage markDelivered() {
        return new PersistedMessage(
                message, persistenceId, persistedAt,
                MessageState.DELIVERED, direction,
                transportType, remoteAddress,
                wasEncrypted, wasCompressed, fragmentCount,
                sentAt, receivedAt, Instant.now(),
                retryCount, lastError, lastErrorAt,
                handshakeId, isHandshakeMessage,
                traceId, spanId,
                persistenceVersion, sizeBytes
        );
    }

    /**
     * Marks this message as failed with error.
     */
    public PersistedMessage markFailed(String error) {
        return new PersistedMessage(
                message, persistenceId, persistedAt,
                MessageState.FAILED, direction,
                transportType, remoteAddress,
                wasEncrypted, wasCompressed, fragmentCount,
                sentAt, receivedAt, deliveredAt,
                retryCount + 1, error, Instant.now(),
                handshakeId, isHandshakeMessage,
                traceId, spanId,
                persistenceVersion, sizeBytes
        );
    }

    /**
     * Updates with fragment count.
     */
    public PersistedMessage withFragmentCount(int count) {
        return new PersistedMessage(
                message, persistenceId, persistedAt,
                state, direction,
                transportType, remoteAddress,
                wasEncrypted, wasCompressed, count,
                sentAt, receivedAt, deliveredAt,
                retryCount, lastError, lastErrorAt,
                handshakeId, isHandshakeMessage,
                traceId, spanId,
                persistenceVersion, sizeBytes
        );
    }

    /**
     * Marks as handshake message.
     */
    public PersistedMessage asHandshakeMessage(String handshakeId) {
        return new PersistedMessage(
                message, persistenceId, persistedAt,
                state, direction,
                transportType, remoteAddress,
                wasEncrypted, wasCompressed, fragmentCount,
                sentAt, receivedAt, deliveredAt,
                retryCount, lastError, lastErrorAt,
                handshakeId, true,
                traceId, spanId,
                persistenceVersion, sizeBytes
        );
    }

    /**
     * Updates size in bytes.
     */
    public PersistedMessage withSize(long size) {
        return new PersistedMessage(
                message, persistenceId, persistedAt,
                state, direction,
                transportType, remoteAddress,
                wasEncrypted, wasCompressed, fragmentCount,
                sentAt, receivedAt, deliveredAt,
                retryCount, lastError, lastErrorAt,
                handshakeId, isHandshakeMessage,
                traceId, spanId,
                persistenceVersion, size
        );
    }
}

