package com.genesis.p2p.core.handlers.ack;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.handlers.ProcessingContext;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.UUID;

/**
 * Processor for ACK and NACK messages.
 *
 * Handles:
 * - MSG_ACK: Positive acknowledgment
 * - MSG_NACK: Negative acknowledgment (rejection)
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class AckProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(AckProcessor.class);

    public static final String MSG_ACK = "MSG_ACK";
    public static final String MSG_NACK = "MSG_NACK";

    private final AckTracker tracker;
    private final MetricsRegistry metrics;
    private final Gson gson;

    /**
     * Creates ACK processor.
     */
    public AckProcessor(AckTracker tracker, MetricsRegistry metrics) {
        this.tracker = tracker;
        this.metrics = metrics;
        this.gson = new Gson();
    }

    @Override
    public void processMessage(Message message) {
        processMessage(message, null);
    }

    @Override
    public void processMessage(Message message, ProcessingContext context) {
        String type = message.type();

        if (MSG_ACK.equals(type)) {
            handleAck(message);
        } else if (MSG_NACK.equals(type)) {
            handleNack(message);
        } else {
            log.warn("Unknown ACK message type", "type", type);
        }
    }

    @Override
    public boolean supportsContext() {
        return true;
    }

    /**
     * Handles ACK message.
     */
    private void handleAck(Message message) {
        try {
            JsonObject payload = gson.fromJson(message.body().content(), JsonObject.class);

            String originalMessageId = payload.has("originalMessageId")
                    ? payload.get("originalMessageId").getAsString()
                    : null;

            if (originalMessageId == null) {
                log.warn("ACK missing originalMessageId");
                return;
            }

            boolean handled = tracker.handleAck(originalMessageId);

            if (handled) {
                log.debug("ACK processed",
                        "originalMessageId", originalMessageId,
                        "from", message.header().from());
            } else {
                log.debug("ACK for unknown/expired message",
                        "originalMessageId", originalMessageId);
            }

        } catch (Exception e) {
            log.error("Failed to process ACK", e);
            metrics.incrementCounter("ack.process.error");
        }
    }

    /**
     * Handles NACK message.
     */
    private void handleNack(Message message) {
        try {
            JsonObject payload = gson.fromJson(message.body().content(), JsonObject.class);

            String originalMessageId = payload.has("originalMessageId")
                    ? payload.get("originalMessageId").getAsString()
                    : null;

            String reason = payload.has("reason")
                    ? payload.get("reason").getAsString()
                    : "Unknown";

            if (originalMessageId == null) {
                log.warn("NACK missing originalMessageId");
                return;
            }

            boolean handled = tracker.handleNack(originalMessageId, reason);

            if (handled) {
                log.debug("NACK processed",
                        "originalMessageId", originalMessageId,
                        "reason", reason,
                        "from", message.header().from());
            }

        } catch (Exception e) {
            log.error("Failed to process NACK", e);
            metrics.incrementCounter("ack.process.error");
        }
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates an ACK message for a received message.
     */
    public static Message createAckMessage(Message originalMessage, String localNodeId) {
        return createAckMessage(originalMessage.header().messageId(),
                originalMessage.header().from(), localNodeId);
    }

    /**
     * Creates an ACK message.
     */
    public static Message createAckMessage(String originalMessageId, String targetPeerId, String localNodeId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("originalMessageId", originalMessageId);
        payload.addProperty("timestamp", Instant.now().toEpochMilli());

        MessageHeader header = new MessageHeader(
                UUID.randomUUID().toString(),  // messageId
                originalMessageId,             // correlationId
                "1.0",                         // protocolVersion
                "1.0",                         // messageVersion
                1,                             // ttl
                0,                             // hopCount
                MSG_ACK,                       // type
                localNodeId,                   // from
                targetPeerId,                  // to
                System.currentTimeMillis(),    // timestamp
                false,                         // encrypted
                "json",                        // contentType
                false,                         // authenticated
                "",                            // signature
                false                          // requiresAck
        );

        MessageBody body = new MessageBody(new Gson().toJson(payload), null);
        return new Message(header, body);
    }

    /**
     * Creates a NACK message.
     */
    public static Message createNackMessage(String originalMessageId, String targetPeerId,
                                            String localNodeId, String reason) {
        JsonObject payload = new JsonObject();
        payload.addProperty("originalMessageId", originalMessageId);
        payload.addProperty("reason", reason);
        payload.addProperty("timestamp", Instant.now().toEpochMilli());

        MessageHeader header = new MessageHeader(
                UUID.randomUUID().toString(),  // messageId
                originalMessageId,             // correlationId
                "1.0",                         // protocolVersion
                "1.0",                         // messageVersion
                1,                             // ttl
                0,                             // hopCount
                MSG_NACK,                      // type
                localNodeId,                   // from
                targetPeerId,                  // to
                System.currentTimeMillis(),    // timestamp
                false,                         // encrypted
                "json",                        // contentType
                false,                         // authenticated
                "",                            // signature
                false                          // requiresAck
        );

        MessageBody body = new MessageBody(new Gson().toJson(payload), null);
        return new Message(header, body);
    }

    @Override
    public void close() {
        // No resources to clean up
    }
}

