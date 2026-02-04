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
 * Processes PING messages - connectivity test.
 *
 * Purpose:
 * - Test peer reachability
 * - Measure round-trip time
 *
 * Responsibilities:
 * - Respond with PONG
 * - Update peer latency
 * - Verify message signatures (if SecurityFacade available)
 */
public class PingProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(PingProcessor.class);

    private final PeerManager peerManager;
    private final MessageSender messageSender;
    private final SecurityFacade security;
    private final Gson gson;
    private final String localNodeId;

    public PingProcessor(PeerManager peerManager, MessageSender messageSender,
                         String localNodeId, SecurityFacade security) {
        this.peerManager = peerManager;
        this.messageSender = messageSender;
        this.security = security;
        this.gson = new Gson();
        this.localNodeId = localNodeId;

        log.info("PingProcessor initialized", "nodeId", localNodeId,
                 "securityEnabled", security != null);
    }

    // Backward compatibility constructor
    public PingProcessor(PeerManager peerManager, MessageSender messageSender,
                         String localNodeId) {
        this(peerManager, messageSender, localNodeId, null);
    }

    @Override
    public void processMessage(Message message) {
        MessageHeader header = message.header();
        MessageBody body = message.body();

        String peerId = header.from();
        long receiveTime = Time.currentMillis();

        log.debug("Processing PING", "peerId", peerId);

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
                        log.warn("Invalid PING signature - rejecting", "peerId", peerId);
                        peerManager.recordFailure(peerId);
                        return; // Reject invalid ping
                    }
                    log.debug("PING signature verified", "peerId", peerId);
                }
            }

            JsonObject payload = gson.fromJson(body.content(), JsonObject.class);
            long sentTime = payload.has("timestamp") ?
                    payload.get("timestamp").getAsLong() : receiveTime;

            // Update peer state
            peerManager.refreshLastSeen(peerId);
            peerManager.recordMessage(peerId);

            // Send PONG response
            sendPongResponse(header, sentTime, receiveTime);

        } catch (Exception e) {
            log.error("Error processing PING", e, "peerId", peerId);
        }
    }

    /**
     * Prepares message data for signature verification.
     */
    private String prepareMessageDataForVerification(Message message) {
        MessageHeader header = message.header();
        return header.type() + "|" +
               header.from() + "|" +
               header.to() + "|" +
               header.timestamp() + "|" +
               message.body().content();
    }

    private void sendPongResponse(MessageHeader incomingHeader, long pingTime, long receiveTime) {
        try {
            Map<String, Object> pongData = new HashMap<>();
            pongData.put("pingTimestamp", pingTime);
            pongData.put("pongTimestamp", receiveTime);
            pongData.put("nodeId", localNodeId);

            MessageHeader pongHeader = new MessageHeader(
                    null,
                    incomingHeader.messageId(),
                    incomingHeader.protocolVersion(),
                    incomingHeader.messageVersion(),
                    10, 0,
                    "PONG",
                    localNodeId,
                    incomingHeader.from(),
                    0, false, "json", false, null,
                    false // requiresAck
            );

            MessageBody pongBody = new MessageBody(gson.toJson(pongData), new HashMap<>());
            Message pongMessage = new Message(pongHeader, pongBody);

            messageSender.sendMessage(pongMessage);

            log.debug("PONG sent", "peerId", incomingHeader.from());

        } catch (Exception e) {
            log.error("Failed to send PONG", e);
        }
    }

    public interface MessageSender {
        void sendMessage(Message message);
    }
}