package com.genesis.p2p.observability.message;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.observability.context.ObservabilityContext;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.storage.message.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive message logger that provides full observability.
 *
 * Logs every message at every stage:
 * - Before send
 * - After send
 * - Before receive processing
 * - After delivery to handler
 * - On encryption/decryption
 * - On handshake
 * - On error
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class MessageLogger {

    private static final NodeLogger log = NodeLogger.getLogger(MessageLogger.class);
    private final MetricsRegistry metrics;
    private final MessagePersistenceStore persistenceStore;

    // Counters
    private final AtomicLong outboundCount = new AtomicLong(0);
    private final AtomicLong inboundCount = new AtomicLong(0);
    private final AtomicLong encryptedCount = new AtomicLong(0);
    private final AtomicLong handshakeCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    public MessageLogger(MetricsRegistry metrics, MessagePersistenceStore persistenceStore) {
        this.metrics = metrics;
        this.persistenceStore = persistenceStore;

        log.info("Message logger initialized with persistence");
    }

    /**
     * Logs and persists an outbound message before sending.
     * CRITICAL: This must be called BEFORE the message is sent to ensure zero data loss.
     */
    public PersistedMessage logOutboundMessage(
            Message message,
            String transportType,
            String remoteAddress,
            boolean encrypted,
            boolean compressed) {

        long count = outboundCount.incrementAndGet();

        try {
            // Create persisted message
            PersistedMessage pm = PersistedMessage.forOutbound(
                    message,
                    transportType,
                    remoteAddress,
                    encrypted,
                    compressed,
                    ObservabilityContext.getTraceId(),
                    ObservabilityContext.getSpanId()
            );

            // Persist BEFORE sending (critical for zero data loss)
            pm = persistenceStore.persist(pm);

            // Structured logging
            log.info("OUTBOUND_MESSAGE",
                    "messageId", message.messageId(),
                    "persistenceId", pm.persistenceId(),
                    "type", message.type(),
                    "from", message.from(),
                    "to", message.to(),
                    "transport", transportType,
                    "remoteAddress", remoteAddress,
                    "encrypted", String.valueOf(encrypted),
                    "compressed", String.valueOf(compressed),
                    "ttl", String.valueOf(message.header().ttl()),
                    "hopCount", String.valueOf(message.header().hopCount()),
                    "contentType", message.header().contentType(),
                    "traceId", pm.traceId(),
                    "spanId", pm.spanId(),
                    "totalOutbound", String.valueOf(count));

            // Metrics
            metrics.incrementCounter("messages.outbound.total");
            if (encrypted) {
                metrics.incrementCounter("messages.outbound.encrypted");
                encryptedCount.incrementAndGet();
            }
            if (compressed) {
                metrics.incrementCounter("messages.outbound.compressed");
            }

            return pm;

        } catch (Exception e) {
            log.error("Failed to log/persist outbound message", e,
                    "messageId", message.messageId());
            errorCount.incrementAndGet();
            metrics.incrementCounter("messages.logging.errors");
            throw new RuntimeException("Failed to log outbound message", e);
        }
    }

    /**
     * Logs successful send.
     */
    public void logMessageSent(String persistenceId, long bytesWritten) {
        try {
            persistenceStore.updateState(persistenceId, MessageState.SENT);

            log.info("MESSAGE_SENT",
                    "persistenceId", persistenceId,
                    "bytesWritten", String.valueOf(bytesWritten));

            metrics.incrementCounter("messages.outbound.sent");
            metrics.recordTimer("messages.outbound.size", bytesWritten);

        } catch (Exception e) {
            log.error("Failed to update sent state", e,
                    "persistenceId", persistenceId);
        }
    }

    /**
     * Logs and persists an inbound message upon receipt.
     */
    public PersistedMessage logInboundMessage(
            Message message,
            String transportType,
            String remoteAddress,
            boolean encrypted,
            boolean compressed) {

        long count = inboundCount.incrementAndGet();

        try {
            // Create persisted message
            PersistedMessage pm = PersistedMessage.forInbound(
                    message,
                    transportType,
                    remoteAddress,
                    encrypted,
                    compressed,
                    ObservabilityContext.getTraceId(),
                    ObservabilityContext.getSpanId()
            );

            // Persist immediately (critical for zero data loss)
            pm = persistenceStore.persist(pm);

            // Structured logging
            log.info("INBOUND_MESSAGE",
                    "messageId", message.messageId(),
                    "persistenceId", pm.persistenceId(),
                    "type", message.type(),
                    "from", message.from(),
                    "to", message.to(),
                    "transport", transportType,
                    "remoteAddress", remoteAddress,
                    "encrypted", String.valueOf(encrypted),
                    "compressed", String.valueOf(compressed),
                    "ttl", String.valueOf(message.header().ttl()),
                    "hopCount", String.valueOf(message.header().hopCount()),
                    "contentType", message.header().contentType(),
                    "traceId", pm.traceId(),
                    "spanId", pm.spanId(),
                    "totalInbound", String.valueOf(count));

            // Metrics
            metrics.incrementCounter("messages.inbound.total");
            if (encrypted) {
                metrics.incrementCounter("messages.inbound.encrypted");
                encryptedCount.incrementAndGet();
            }
            if (compressed) {
                metrics.incrementCounter("messages.inbound.compressed");
            }

            return pm;

        } catch (Exception e) {
            log.error("Failed to log/persist inbound message", e,
                    "messageId", message.messageId());
            errorCount.incrementAndGet();
            metrics.incrementCounter("messages.logging.errors");
            throw new RuntimeException("Failed to log inbound message", e);
        }
    }

    /**
     * Logs message delivery to handler.
     */
    public void logMessageDelivered(String persistenceId) {
        try {
            persistenceStore.updateState(persistenceId, MessageState.DELIVERED);

            log.info("MESSAGE_DELIVERED",
                    "persistenceId", persistenceId);

            metrics.incrementCounter("messages.inbound.delivered");

        } catch (Exception e) {
            log.error("Failed to update delivered state", e,
                    "persistenceId", persistenceId);
        }
    }

    /**
     * Logs encryption operation.
     */
    public void logEncryption(String messageId, String peerId, int plaintextSize, int ciphertextSize) {
        log.debug("MESSAGE_ENCRYPTED",
                "messageId", messageId,
                "peerId", peerId,
                "plaintextSize", String.valueOf(plaintextSize),
                "ciphertextSize", String.valueOf(ciphertextSize),
                "overhead", String.valueOf(ciphertextSize - plaintextSize));

        metrics.incrementCounter("messages.encryption.operations");
        metrics.recordTimer("messages.encryption.overhead", ciphertextSize - plaintextSize);
    }

    /**
     * Logs decryption operation.
     */
    public void logDecryption(String messageId, String peerId, int ciphertextSize, int plaintextSize) {
        log.debug("MESSAGE_DECRYPTED",
                "messageId", messageId,
                "peerId", peerId,
                "ciphertextSize", String.valueOf(ciphertextSize),
                "plaintextSize", String.valueOf(plaintextSize));

        metrics.incrementCounter("messages.decryption.operations");
    }

    /**
     * Logs handshake message by persistence ID.
     */
    public void logHandshakeMessage(String persistenceId, String handshakeId, String phase) {
        long count = handshakeCount.incrementAndGet();

        try {
            PersistedMessage pm = persistenceStore.get(persistenceId)
                    .orElseThrow(() -> new RuntimeException("Message not found"));

            pm = pm.asHandshakeMessage(handshakeId);
            persistenceStore.persist(pm);

            log.info("HANDSHAKE_MESSAGE",
                    "persistenceId", persistenceId,
                    "handshakeId", handshakeId,
                    "phase", phase,
                    "totalHandshakes", String.valueOf(count));

            metrics.incrementCounter("messages.handshake.total");
            metrics.incrementCounter("messages.handshake." + phase.toLowerCase());

        } catch (Exception e) {
            log.error("Failed to log handshake message", e,
                    "persistenceId", persistenceId);
        }
    }

    /**
     * Logs handshake message by message ID (looks up persistence ID first).
     * Useful when only the message ID is available (e.g., in message processors).
     */
    public void logHandshakeMessageByMessageId(String messageId, String handshakeId, String phase) {
        try {
            // Look up persistence ID by message ID
            PersistedMessage pm = persistenceStore.getByMessageId(messageId)
                    .orElseThrow(() -> new RuntimeException(
                            "Message not found in persistence store: " + messageId));

            // Delegate to main method
            logHandshakeMessage(pm.persistenceId(), handshakeId, phase);

        } catch (Exception e) {
            log.warn("Failed to log handshake message by message ID",
                    "messageId", messageId,
                    "error", e.getMessage());
        }
    }

    /**
     * Logs fragmentation.
     */
    public void logFragmentation(String persistenceId, int fragmentCount) {
        try {
            PersistedMessage pm = persistenceStore.get(persistenceId)
                    .orElseThrow(() -> new RuntimeException("Message not found"));

            pm = pm.withFragmentCount(fragmentCount);
            persistenceStore.persist(pm);

            log.info("MESSAGE_FRAGMENTED",
                    "persistenceId", persistenceId,
                    "fragmentCount", String.valueOf(fragmentCount));

            metrics.incrementCounter("messages.fragmentation.total");
            metrics.recordTimer("messages.fragmentation.count", fragmentCount);

        } catch (Exception e) {
            log.error("Failed to log fragmentation", e,
                    "persistenceId", persistenceId);
        }
    }

    /**
     * Logs message error.
     */
    public void logMessageError(String persistenceId, String error, Throwable cause) {
        long count = errorCount.incrementAndGet();

        try {
            PersistedMessage pm = persistenceStore.get(persistenceId)
                    .orElseThrow(() -> new RuntimeException("Message not found"));

            pm = pm.markFailed(error);
            persistenceStore.persist(pm);

            log.error("MESSAGE_ERROR", cause,
                    "persistenceId", persistenceId,
                    "messageId", pm.message().messageId(),
                    "error", error,
                    "retryCount", String.valueOf(pm.retryCount()),
                    "totalErrors", String.valueOf(count));

            metrics.incrementCounter("messages.errors.total");

        } catch (Exception e) {
            log.error("Failed to log message error", e,
                    "persistenceId", persistenceId);
        }
    }

    /**
     * Logs compression operation.
     */
    public void logCompression(String messageId, int originalSize, int compressedSize) {
        double ratio = (double) compressedSize / originalSize;

        log.debug("MESSAGE_COMPRESSED",
                "messageId", messageId,
                "originalSize", String.valueOf(originalSize),
                "compressedSize", String.valueOf(compressedSize),
                "ratio", String.format("%.2f%%", ratio * 100),
                "saved", String.valueOf(originalSize - compressedSize));

        metrics.incrementCounter("messages.compression.operations");
        metrics.recordTimer("messages.compression.ratio", (long) (ratio * 100));
    }

    /**
     * Gets current statistics.
     */
    public MessageLoggerStats getStats() {
        try {
            MessageStorageStats storageStats = persistenceStore.getStats();

            return new MessageLoggerStats(
                    outboundCount.get(),
                    inboundCount.get(),
                    encryptedCount.get(),
                    handshakeCount.get(),
                    errorCount.get(),
                    storageStats
            );

        } catch (Exception e) {
            log.error("Failed to get message logger stats", e);
            return null;
        }
    }

    /**
     * Logs a custom processing state transition.
     *
     * Use this method for tracking intermediate processing states that are
     * not covered by the standard logMessageSent/logMessageDelivered methods.
     *
     * Example states: PROCESSING, QUEUED, RETRYING, ACKNOWLEDGED
     *
     * @param persistenceId the persistence store ID
     * @param state the custom state description
     * @param metadata additional metadata (key-value pairs)
     */
    public void logProcessingState(String persistenceId, String state,
                                   java.util.Map<String, String> metadata) {
        try {
            log.info("MESSAGE_STATE_TRANSITION",
                    "persistenceId", persistenceId,
                    "state", state,
                    "metadata", metadata != null ? metadata.toString() : "{}");

            metrics.incrementCounter("messages.state." + state.toLowerCase());

            // Note: We don't update the actual MessageState enum here because
            // that's reserved for the standard lifecycle (PENDING->SENT->DELIVERED/FAILED).
            // This method is for logging intermediate/custom states for observability.

        } catch (Exception e) {
            log.error("Failed to log processing state", e,
                    "persistenceId", persistenceId,
                    "state", state);
        }
    }

    /**
     * Logs acknowledgment of a message.
     *
     * Call this when a message has been explicitly acknowledged by the recipient.
     *
     * @param persistenceId the persistence store ID
     */
    public void logMessageAcknowledged(String persistenceId) {
        try {
            persistenceStore.updateState(persistenceId, MessageState.ACKNOWLEDGED);

            log.info("MESSAGE_ACKNOWLEDGED",
                    "persistenceId", persistenceId);

            metrics.incrementCounter("messages.acknowledged");

        } catch (Exception e) {
            log.error("Failed to log message acknowledgment", e,
                    "persistenceId", persistenceId);
        }
    }

    /**
     * Replay messages on restart.
     */
    public void replayMessages(MessageReplayHandler handler) {
        try {
            log.info("Starting message replay...");

            // Get all pending/sent messages that were not delivered
            var pending = persistenceStore.getByState(MessageState.PENDING);
            var sent = persistenceStore.getByState(MessageState.SENT);

            log.info("Found messages to replay",
                    "pending", String.valueOf(pending.size()),
                    "sent", String.valueOf(sent.size()));

            // Replay pending outbound messages
            for (PersistedMessage pm : pending) {
                if (pm.direction() == MessageDirection.OUTBOUND) {
                    log.info("Replaying pending outbound message",
                            "messageId", pm.message().messageId(),
                            "persistenceId", pm.persistenceId());
                    handler.replayOutbound(pm);
                }
            }

            // Replay sent messages that may need acknowledgment
            for (PersistedMessage pm : sent) {
                log.info("Checking sent message",
                        "messageId", pm.message().messageId(),
                        "persistenceId", pm.persistenceId());
                handler.checkSent(pm);
            }

            log.info("Message replay completed");

        } catch (Exception e) {
            log.error("Failed to replay messages", e);
        }
    }
}

