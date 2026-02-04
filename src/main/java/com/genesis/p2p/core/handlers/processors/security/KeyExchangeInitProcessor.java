package com.genesis.p2p.core.handlers.processors.security;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.handlers.ProcessingContext;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.channel.KeyExchangeComplete;
import com.genesis.p2p.security.channel.KeyExchangeInit;
import com.genesis.p2p.security.channel.SecureChannelNegotiator;
import com.genesis.p2p.transport.core.ITransport;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Processor for KEY_EXCHANGE_INIT messages.
 *
 * Handles Phase 1 of the secure channel establishment protocol.
 * When a peer initiates key exchange, this processor:
 * 1. Validates the incoming KEY_EXCHANGE_INIT
 * 2. Verifies the signature on the ephemeral key
 * 3. Performs ECDH to derive shared secret
 * 4. Creates and sends KEY_EXCHANGE_COMPLETE response
 * 5. Establishes the secure session
 *
 * After this processor completes successfully:
 * - Both parties have a shared session key
 * - Subsequent HANDSHAKE messages will be encrypted
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class KeyExchangeInitProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(KeyExchangeInitProcessor.class);
    private static final Gson gson = new Gson();

    private final String nodeId;
    private final SecureChannelNegotiator negotiator;
    private final PeerManager peerManager;
    private final MetricsRegistry metrics;
    private volatile ITransport transport;

    public KeyExchangeInitProcessor(
            String nodeId,
            SecureChannelNegotiator negotiator,
            PeerManager peerManager,
            MetricsRegistry metrics) {
        this.nodeId = nodeId;
        this.negotiator = negotiator;
        this.peerManager = peerManager;
        this.metrics = metrics;
    }

    /**
     * Sets the transport for sending KEY_EXCHANGE_COMPLETE responses.
     */
    public void setTransport(ITransport transport) {
        this.transport = transport;
    }

    @Override
    public void processMessage(Message message) {
        // Context is required for this processor to send responses
        throw new UnsupportedOperationException(
                "KeyExchangeInitProcessor requires ProcessingContext for response routing");
    }

    @Override
    public void processMessage(Message message, ProcessingContext context) {
        String messagePeerId = message.from();  // May be initiator's nodeId

        log.info("KEY_EXCHANGE_INIT received",
                "nodeId", nodeId,
                "messagePeerId", messagePeerId,
                "correlationId", extractCorrelationId(message));

        metrics.incrementCounter("security.key_exchange.init_received");

        try {
            // 1. Parse the KEY_EXCHANGE_INIT message
            KeyExchangeInit init = parseKeyExchangeInit(message);
            if (init == null) {
                log.error("Failed to parse KEY_EXCHANGE_INIT",
                        "messagePeerId", messagePeerId);
                metrics.incrementCounter("security.key_exchange.parse_failed");
                return;
            }

            // ═══════════════════════════════════════════════════════════════════════
            // CRITICAL: RESPONDER IDENTITY RESOLUTION
            // The initiator's peer was added to our PeerStore during discovery with
            // a discovery-generated peerId (e.g., "peer-10.0.0.5:8080").
            // However, message.from() contains the initiator's self-announced nodeId
            // (e.g., "node-abc-xyz"), which does NOT match the PeerStore key.
            //
            // We MUST resolve the initiator's identity to the CORRECT PeerStore key
            // BEFORE attempting any state transitions, or they will fail silently.
            // ═══════════════════════════════════════════════════════════════════════
            String initiatorNodeId = init.getNodeId();

            // STRATEGY 1: Check if initiator already mapped (from prior KEY_EXCHANGE or discovery)
            String resolvedPeerId = negotiator.resolveNodeIdToDiscoveryId(initiatorNodeId);
            Peer existingPeer = null;

            if (resolvedPeerId != null && !resolvedPeerId.equals(initiatorNodeId)) {
                // Found existing mapping - verify peer exists
                existingPeer = peerManager.getPeer(resolvedPeerId);
                if (existingPeer != null) {
                    log.info("RESPONDER_IDENTITY_RESOLVED_VIA_MAPPING",
                            "initiatorNodeId", initiatorNodeId,
                            "messagePeerId", messagePeerId,
                            "resolvedPeerId", resolvedPeerId,
                            "strategy", "IDENTITY_MAPPING");
                } else {
                    // Mapping exists but peer removed - clear stale mapping
                    log.warn("STALE_IDENTITY_MAPPING_DETECTED - peer removed from store",
                            "initiatorNodeId", initiatorNodeId,
                            "resolvedPeerId", resolvedPeerId);
                    resolvedPeerId = null; // Force re-resolution
                }
            }

            // STRATEGY 2: Try direct lookup by messagePeerId (may be discovery ID)
            if (resolvedPeerId == null || existingPeer == null) {
                existingPeer = peerManager.getPeer(messagePeerId);
                if (existingPeer != null) {
                    resolvedPeerId = messagePeerId;
                    log.info("RESPONDER_IDENTITY_RESOLVED_VIA_MESSAGE_PEERID",
                            "initiatorNodeId", initiatorNodeId,
                            "messagePeerId", messagePeerId,
                            "resolvedPeerId", resolvedPeerId,
                            "strategy", "MESSAGE_PEERID_LOOKUP");
                }
            }

            // STRATEGY 3: Try lookup by initiatorNodeId (in case PeerStore keyed by it)
            if (resolvedPeerId == null || existingPeer == null) {
                existingPeer = peerManager.getPeer(initiatorNodeId);
                if (existingPeer != null) {
                    resolvedPeerId = initiatorNodeId;
                    log.info("RESPONDER_IDENTITY_RESOLVED_VIA_NODEID",
                            "initiatorNodeId", initiatorNodeId,
                            "messagePeerId", messagePeerId,
                            "resolvedPeerId", resolvedPeerId,
                            "strategy", "NODEID_LOOKUP");
                }
            }

            // STRATEGY 4: Scan all peers for matching network address (from context)
            if ((resolvedPeerId == null || existingPeer == null) && context != null && context.sourceAddress() != null) {
                java.net.InetSocketAddress sourceAddr = context.sourceAddress();
                String sourceIp = sourceAddr.getAddress().getHostAddress();
                int sourcePort = sourceAddr.getPort();

                for (Peer candidate : peerManager.getAllPeers()) {
                    if (candidate.ip().equals(sourceIp) && candidate.port() == sourcePort) {
                        existingPeer = candidate;
                        resolvedPeerId = candidate.id();
                        log.info("RESPONDER_IDENTITY_RESOLVED_VIA_ADDRESS_SCAN",
                                "initiatorNodeId", initiatorNodeId,
                                "messagePeerId", messagePeerId,
                                "resolvedPeerId", resolvedPeerId,
                                "sourceAddress", sourceAddr,
                                "strategy", "ADDRESS_SCAN");
                        break;
                    }
                }
            }

            // FINAL VALIDATION: If still not found, reject the KEY_EXCHANGE
            if (resolvedPeerId == null || existingPeer == null) {
                log.error("RESPONDER_IDENTITY_RESOLUTION_FAILED - initiator not found in PeerStore",
                        "initiatorNodeId", initiatorNodeId,
                        "messagePeerId", messagePeerId,
                        "knownPeersCount", peerManager.getAllPeers().size(),
                        "sourceAddress", context != null ? context.sourceAddress() : null,
                        "hint", "Initiator must be discovered before KEY_EXCHANGE can succeed");
                metrics.incrementCounter("security.key_exchange.responder_identity_resolution_failed");

                KeyExchangeComplete rejection = KeyExchangeComplete.createRejection(
                        nodeId,
                        init.getCorrelationId(),
                        "PEER_NOT_DISCOVERED: Initiator not found in PeerStore");
                sendKeyExchangeComplete(rejection, messagePeerId, message.header(), context);
                return;
            }

            // Register bidirectional mapping EARLY: initiator's nodeId <-> canonical peerId
            // This prevents future lookups from failing
            negotiator.registerIdentityMapping(initiatorNodeId, resolvedPeerId);

            log.info("RESPONDER_IDENTITY_RESOLUTION_COMPLETE",
                    "initiatorNodeId", initiatorNodeId,
                    "messagePeerId", messagePeerId,
                    "resolvedPeerId", resolvedPeerId,
                    "peerExists", existingPeer != null);

            // 2. Transition peer to CHANNEL_NEGOTIATING state using VERIFIED peerId
            String stateTransitionPeerId = resolvedPeerId;
            boolean transitioned = peerManager.markChannelNegotiating(stateTransitionPeerId);
            if (!transitioned) {
                log.error("CRITICAL: Failed to transition peer to CHANNEL_NEGOTIATING even with verified peerId",
                        "stateTransitionPeerId", stateTransitionPeerId,
                        "currentState", peerManager.getPeerState(stateTransitionPeerId),
                        "hint", "Peer exists but transition failed - may be in invalid state");
                metrics.incrementCounter("security.key_exchange.responder_channel_negotiating_failed");

                // Send rejection - state machine is inconsistent
                KeyExchangeComplete rejection = KeyExchangeComplete.createRejection(
                        nodeId,
                        init.getCorrelationId(),
                        "STATE_TRANSITION_FAILED: Cannot transition to CHANNEL_NEGOTIATING");
                sendKeyExchangeComplete(rejection, stateTransitionPeerId, message.header(), context);
                peerManager.markDisconnected(stateTransitionPeerId);
                return;  // ← EXPLICIT FAILURE
            }

            // 3. Process the init message (signature verification, ECDH, session creation)
            KeyExchangeComplete complete = negotiator.processKeyExchangeInit(init);

            // 4. Handle null response (silent drop due to simultaneous INIT - we won the duel)
            // When both peers send KEY_EXCHANGE_INIT simultaneously and we win (lower nodeId),
            // we silently drop the incoming INIT and wait for the peer to process our INIT
            // and send us KEY_EXCHANGE_COMPLETE as the responder.
            if (complete == null) {
                log.info("SIMULTANEOUS_INIT_SILENT_DROP - we won the duel, awaiting peer's COMPLETE",
                        "nodeId", nodeId,
                        "initiatorNodeId", initiatorNodeId,
                        "resolvedPeerId", resolvedPeerId,
                        "action", "No response sent - peer will respond to our INIT");
                metrics.incrementCounter("security.key_exchange.simultaneous_init_silent_drop");
                // Do NOT change peer state - we're still waiting for COMPLETE
                return;
            }

            // 5. Send the KEY_EXCHANGE_COMPLETE response ONLY if crypto succeeded
            if (complete.isAccepted()) {
                // 5. Transition peer to CHANNEL_ESTABLISHED state using VERIFIED peerId
                boolean established = peerManager.markChannelEstablished(stateTransitionPeerId);
                if (established) {
                    log.info("KEY_EXCHANGE_INIT processed successfully - channel established",
                            "nodeId", nodeId,
                            "stateTransitionPeerId", stateTransitionPeerId,
                            "resolvedPeerId", resolvedPeerId,
                            "initiatorNodeId", initiatorNodeId,
                            "sessionId", negotiator.getChannelSessionId(stateTransitionPeerId));
                    metrics.incrementCounter("security.key_exchange.channel_established_responder");

                    // NOW send success response - state is consistent
                    sendKeyExchangeComplete(complete, stateTransitionPeerId, message.header(), context);
                } else {
                    // CRITICAL: State transition failed - send rejection instead of success
                    log.error("CRITICAL: Failed to transition to CHANNEL_ESTABLISHED after successful crypto",
                            "stateTransitionPeerId", stateTransitionPeerId,
                            "currentState", peerManager.getPeerState(stateTransitionPeerId),
                            "hint", "Crypto succeeded but state machine failed - sending rejection to prevent deadlock");
                    metrics.incrementCounter("security.key_exchange.responder_channel_established_failed");

                    KeyExchangeComplete rejection = KeyExchangeComplete.createRejection(
                            nodeId,
                            init.getCorrelationId(),
                            "STATE_TRANSITION_FAILED: Cannot transition to CHANNEL_ESTABLISHED");
                    sendKeyExchangeComplete(rejection, stateTransitionPeerId, message.header(), context);
                    peerManager.markDisconnected(stateTransitionPeerId);
                    return;  // ← EXPLICIT FAILURE
                }
            } else {
                // Crypto rejection from negotiator
                sendKeyExchangeComplete(complete, stateTransitionPeerId, message.header(), context);
                log.warn("KEY_EXCHANGE_INIT rejected by negotiator",
                        "stateTransitionPeerId", stateTransitionPeerId,
                        "reason", complete.getRejectionReason());
                metrics.incrementCounter("security.key_exchange.rejected." +
                        complete.getRejectionReason().toLowerCase());
                peerManager.markDisconnected(stateTransitionPeerId);
            }

        } catch (Exception e) {
            log.error("Error processing KEY_EXCHANGE_INIT", e,
                    "messagePeerId", messagePeerId);
            metrics.incrementCounter("security.key_exchange.process_error");

            // Try to send rejection response
            try {
                KeyExchangeComplete rejection = KeyExchangeComplete.createRejection(
                        nodeId,
                        extractCorrelationId(message),
                        "INTERNAL_ERROR: " + e.getMessage());
                sendKeyExchangeComplete(rejection, messagePeerId, message.header(), context);
            } catch (Exception sendError) {
                log.error("Failed to send rejection response", sendError,
                        "messagePeerId", messagePeerId);
            }
        }
    }

    @Override
    public boolean supportsContext() {
        return true;
    }

    private KeyExchangeInit parseKeyExchangeInit(Message message) {
        try {
            String content = message.body().content();
            JsonObject json = gson.fromJson(content, JsonObject.class);

            return new KeyExchangeInit.Builder()
                    .nodeId(json.get("nodeId").getAsString())
                    .ephemeralPublicKey(json.get("ephemeralPublicKey").getAsString())
                    .identityPublicKey(json.get("identityPublicKey").getAsString())
                    .timestamp(json.get("timestamp").getAsLong())
                    .signature(json.get("signature").getAsString())
                    .protocolVersion(json.has("protocolVersion") ?
                            json.get("protocolVersion").getAsString() : "1.0")
                    .correlationId(json.get("correlationId").getAsString())
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse KEY_EXCHANGE_INIT", e);
            return null;
        }
    }

    private String extractCorrelationId(Message message) {
        try {
            String content = message.body().content();
            JsonObject json = gson.fromJson(content, JsonObject.class);
            return json.has("correlationId") ?
                    json.get("correlationId").getAsString() : "unknown";
        } catch (Exception e) {
            return "parse_error";
        }
    }

    private void sendKeyExchangeComplete(KeyExchangeComplete complete, String peerId,
                                         MessageHeader incomingHeader, ProcessingContext context) {
        if (transport == null) {
            log.error("Transport not configured - cannot send KEY_EXCHANGE_COMPLETE",
                    "peerId", peerId);
            return;
        }

        try {
            // Get destination address from context
            InetSocketAddress destination = context.sourceAddress();
            if (destination == null) {
                log.error("No source address in context - cannot send response",
                        "peerId", peerId);
                return;
            }

            // Create message body
            JsonObject json = new JsonObject();
            json.addProperty("nodeId", complete.getNodeId());
            json.addProperty("correlationId", complete.getCorrelationId());
            json.addProperty("timestamp", complete.getTimestamp());
            json.addProperty("accepted", complete.isAccepted());

            if (complete.isAccepted()) {
                json.addProperty("ephemeralPublicKey", complete.getEphemeralPublicKey());
                json.addProperty("identityPublicKey", complete.getIdentityPublicKey());
                json.addProperty("signature", complete.getSignature());
            } else {
                json.addProperty("rejectionReason", complete.getRejectionReason());
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("keyExchange", "complete");

            MessageBody body = new MessageBody(gson.toJson(json), metadata);

            MessageHeader header = new MessageHeader(
                    null,                           // messageId - auto-generated
                    complete.getCorrelationId(),    // correlationId
                    incomingHeader.protocolVersion(),
                    incomingHeader.messageVersion(),
                    10, 0,                          // ttl, hopCount
                    KeyExchangeComplete.MESSAGE_TYPE,
                    nodeId,                         // from
                    peerId,                         // to
                    System.currentTimeMillis(),     // timestamp
                    false,                          // encrypted
                    "json",                         // contentType
                    false,                          // authenticated
                    null,                           // signature
                    false                           // requiresAck
            );

            Message response = new Message(header, body);

            // Send via transport (KEY_EXCHANGE messages are signed, not encrypted)
            transport.send(response, destination)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Failed to send KEY_EXCHANGE_COMPLETE", error,
                                    "peerId", peerId);
                            metrics.incrementCounter("security.key_exchange.send_failed");
                        } else {
                            log.debug("KEY_EXCHANGE_COMPLETE sent",
                                    "peerId", peerId,
                                    "accepted", complete.isAccepted());
                            metrics.incrementCounter("security.key_exchange.complete_sent");
                        }
                    });

        } catch (Exception e) {
            log.error("Error sending KEY_EXCHANGE_COMPLETE", e,
                    "peerId", peerId);
        }
    }
}
