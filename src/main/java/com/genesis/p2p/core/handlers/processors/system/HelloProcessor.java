package com.genesis.p2p.core.handlers.processors.system;

import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Processes HELLO messages - initial peer discovery and registration.
 *
 * Purpose:
 * - Node A tells Node B: "I'm here, introduce yourself"
 *
 * Responsibilities:
 * - Register the peer in PeerManager
 * - Extract peer information from message
 * - Send WELCOME response
 * - Record initial reputation
 */
public class HelloProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(HelloProcessor.class);

    private final PeerManager peerManager;
    private final MessageSender messageSender;
    private final Gson gson;
    private final String localNodeId;

    public HelloProcessor(PeerManager peerManager, MessageSender messageSender,
                          String localNodeId) {
        this.peerManager = peerManager;
        this.messageSender = messageSender;
        this.gson = new Gson();
        this.localNodeId = localNodeId;

        log.info("HelloProcessor initialized", "nodeId", localNodeId);
    }

    @Override
    public void processMessage(Message message) {
        MessageHeader header = message.header();
        MessageBody body = message.body();

        String peerId = header.from();
        log.info("Processing HELLO", "peerId", peerId);

        try {
            // Parse HELLO payload
            JsonObject payload = gson.fromJson(body.content(), JsonObject.class);

            String publicKey = payload.has("publicKey") ?
                    payload.get("publicKey").getAsString() : "";
            String ip = payload.has("ip") ?
                    payload.get("ip").getAsString() : "unknown";
            int port = payload.has("port") ?
                    payload.get("port").getAsInt() : 0;
            String version = payload.has("version") ?
                    payload.get("version").getAsString() : "1.0";
            String os = payload.has("os") ?
                    payload.get("os").getAsString() : "unknown";
            String agent = payload.has("agent") ?
                    payload.get("agent").getAsString() : "genesis-agent";

            // Create Peer object
            Peer newPeer = Peer.withoutNat(
                    peerId,
                    publicKey,
                    payload.has("hostname") ? payload.get("hostname").getAsString() : "unknown",
                    ip,
                    port,
                    true, // online
                    Instant.now(),
                    0, // no latency yet
                    false, // not trusted yet
                    50, // initial reputation
                    version,
                    os,
                    agent
            );

            // Register peer
            boolean added = peerManager.upsertPeer(newPeer);

            if (added) {
                log.info("New peer registered",
                        "peerId", peerId,
                        "ip", ip,
                        "port", port,
                        "version", version);

                // Mark as connected
                peerManager.markConnected(peerId);

                // Send WELCOME response
                sendWelcomeResponse(header);

            } else {
                log.warn("Failed to register peer", "peerId", peerId);
            }

        } catch (Exception e) {
            log.error("Error processing HELLO", e, "peerId", peerId);
            // Don't throw - we don't want to fail the entire processor
        }
    }

    /**
     * Sends WELCOME response with local node information.
     */
    private void sendWelcomeResponse(MessageHeader incomingHeader) {
        try {
            // Build WELCOME payload
            Map<String, Object> welcomeData = new HashMap<>();
            welcomeData.put("nodeId", localNodeId);
            welcomeData.put("timestamp", Time.currentMillis());
            welcomeData.put("version", "1.0");
            welcomeData.put("status", "CONNECTED");

            String welcomeContent = gson.toJson(welcomeData);

            // Create WELCOME message
            MessageHeader welcomeHeader = new MessageHeader(
                    null, // will generate new ID
                    incomingHeader.messageId(), // correlation ID
                    incomingHeader.protocolVersion(),
                    incomingHeader.messageVersion(),
                    10, // TTL
                    0,  // hopCount
                    "WELCOME",
                    localNodeId,
                    incomingHeader.from(), // send to original sender
                    0,  // timestamp
                    false,
                    "json",
                    false,
                    null,
                    false // requiresAck
            );

            MessageBody welcomeBody = new MessageBody(welcomeContent, new HashMap<>());
            Message welcomeMessage = new Message(welcomeHeader, welcomeBody);

            // Send the message
            messageSender.sendMessage(welcomeMessage);

            log.info("WELCOME sent", "peerId", incomingHeader.from());

        } catch (Exception e) {
            log.error("Failed to send WELCOME", e);
        }
    }

    /**
     * Interface for sending messages (to be injected).
     */
    public interface MessageSender {
        void sendMessage(Message message);
    }
}