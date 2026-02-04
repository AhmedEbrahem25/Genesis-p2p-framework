package com.genesis.p2p.core.handlers.processors.system;

import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.util.common.Time;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Processes HEARTBEAT messages - keep-alive mechanism.
 *
 * Purpose:
 * - Monitor peer health
 * - Update latency metrics
 * - Maintain connection state
 *
 * Responsibilities:
 * - Update peer last seen timestamp
 * - Calculate and record latency
 * - Send HEARTBEAT_ACK response
 * - Reward reputation for consistent heartbeats
 * - Verify message signatures (if SecurityFacade available)
 */
public class HeartbeatProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(HeartbeatProcessor.class);
    private static final int REPUTATION_REWARD = 1;

    private final PeerManager peerManager;
    private final MessageSender messageSender;
    private final SecurityFacade security;
    private final Gson gson;
    private final String localNodeId;

    public HeartbeatProcessor(PeerManager peerManager, MessageSender messageSender,
                              String localNodeId, SecurityFacade security) {
        this.peerManager = peerManager;
        this.messageSender = messageSender;
        this.security = security;
        this.gson = new Gson();
        this.localNodeId = localNodeId;

        log.info("HeartbeatProcessor initialized", "nodeId", localNodeId,
                 "securityEnabled", security != null);
    }

    // Backward compatibility constructor
    public HeartbeatProcessor(PeerManager peerManager, MessageSender messageSender,
                              String localNodeId) {
        this(peerManager, messageSender, localNodeId, null);
    }

    @Override
    public void processMessage(Message message) {
        MessageHeader header = message.header();
        MessageBody body = message.body();

        String peerId = header.from();
        long receiveTime = Time.currentMillis();

        log.debug("Processing HEARTBEAT", "peerId", peerId);

        try {
            // SECURITY: Verify message signature if SecurityFacade is available
            if (security != null && header.authenticated()) {
                String signature = header.signature();
                if (signature != null && !signature.isEmpty()) {
                    String messageData = prepareMessageDataForVerification(message);
                    boolean isValid = security.verify(
                        messageData.getBytes(),
                        signature.getBytes(),
                        peerId
                    );

                    if (!isValid) {
                        log.warn("Invalid HEARTBEAT signature - rejecting", "peerId", peerId);
                        peerManager.recordFailure(peerId);
                        peerManager.updateReputation(peerId, -5); // Penalty for invalid signature
                        return; // Reject unsigned/invalid heartbeat
                    }
                    log.debug("HEARTBEAT signature verified", "peerId", peerId);
                } else {
                    log.debug("HEARTBEAT marked as authenticated but no signature present", "peerId", peerId);
                }
            }

            JsonObject payload = gson.fromJson(body.content(), JsonObject.class);
            long sentTime = payload.has("timestamp") ?
                    payload.get("timestamp").getAsLong() : receiveTime;
            int sequence = payload.has("sequence") ?
                    payload.get("sequence").getAsInt() : 0;

            // Calculate latency
            long latency = receiveTime - sentTime;

            // Update peer state
            peerManager.refreshLastSeen(peerId);
            peerManager.recordMessage(peerId);
            peerManager.updateLatency(peerId, latency);

            // Reward reputation for heartbeat (extra reward if signed)
            int reputationBonus = (security != null && header.authenticated()) ?
                REPUTATION_REWARD + 1 : REPUTATION_REWARD;
            peerManager.updateReputation(peerId, reputationBonus);

            // Send ACK response
            sendHeartbeatAck(header, sequence);

            log.debug("HEARTBEAT processed", "peerId", peerId,
                     "sequence", sequence, "latency", latency,
                     "signed", header.authenticated());

        } catch (Exception e) {
            log.error("Error processing HEARTBEAT", e, "peerId", peerId);
            peerManager.recordFailure(peerId);
        }
    }

    /**
     * Prepares message data for signature verification.
     */
    private String prepareMessageDataForVerification(Message message) {
        // Concatenate key message fields for verification
        MessageHeader header = message.header();
        return header.type() + "|" +
               header.from() + "|" +
               header.to() + "|" +
               header.timestamp() + "|" +
               message.body().content();
    }

    private void sendHeartbeatAck(MessageHeader incomingHeader, int sequence) {
        try {
            Map<String, Object> ackData = new HashMap<>();
            ackData.put("timestamp", Time.currentMillis());
            ackData.put("sequence", sequence);
            ackData.put("nodeId", localNodeId);

            MessageHeader ackHeader = new MessageHeader(
                    null,
                    incomingHeader.messageId(),
                    incomingHeader.protocolVersion(),
                    incomingHeader.messageVersion(),
                    5, 0,
                    "HEARTBEAT_ACK",
                    localNodeId,
                    incomingHeader.from(),
                    0, false, "json", false, null,
                    false // requiresAck
            );

            MessageBody ackBody = new MessageBody(gson.toJson(ackData), new HashMap<>());
            Message ackMessage = new Message(ackHeader, ackBody);

            messageSender.sendMessage(ackMessage);

            log.debug("HEARTBEAT_ACK sent", "peerId", incomingHeader.from());

        } catch (Exception e) {
            log.error("Failed to send HEARTBEAT_ACK", e);
        }
    }

    /**
     * Message sender interface for sending responses.
     */
    public interface MessageSender {
        void sendMessage(Message message);
    }
}

