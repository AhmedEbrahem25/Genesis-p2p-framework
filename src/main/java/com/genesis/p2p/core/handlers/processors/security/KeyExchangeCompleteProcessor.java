package com.genesis.p2p.core.handlers.processors.security;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.handlers.ProcessingContext;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.protocol.handshake.HandshakeProcessor;
import com.genesis.p2p.security.channel.KeyExchangeComplete;
import com.genesis.p2p.security.channel.KeyExchangeResult;
import com.genesis.p2p.security.channel.SecureChannelNegotiator;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Processor for KEY_EXCHANGE_COMPLETE messages.
 *
 * Handles the initiator side of Phase 1 secure channel establishment.
 * When a peer responds to our KEY_EXCHANGE_INIT with KEY_EXCHANGE_COMPLETE:
 * 1. Validates the incoming KEY_EXCHANGE_COMPLETE
 * 2. Verifies the signature on the ephemeral key
 * 3. Performs ECDH to derive shared secret
 * 4. Establishes the secure session
 * 5. Triggers the HANDSHAKE phase (Phase 2)
 *
 * After this processor completes successfully:
 * - Both parties have a shared session key
 * - HandshakeProcessor is invoked to send encrypted HANDSHAKE_REQUEST
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class KeyExchangeCompleteProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(KeyExchangeCompleteProcessor.class);
    private static final Gson gson = new Gson();

    private final String nodeId;
    private final SecureChannelNegotiator negotiator;
    private final PeerManager peerManager;
    private final MetricsRegistry metrics;
    private volatile HandshakeProcessor handshakeProcessor;

    public KeyExchangeCompleteProcessor(
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
     * Sets the HandshakeProcessor for triggering Phase 2 after channel establishment.
     */
    public void setHandshakeProcessor(HandshakeProcessor handshakeProcessor) {
        this.handshakeProcessor = handshakeProcessor;
    }

    @Override
    public void processMessage(Message message) {
        // Context is required for this processor to route handshakes
        throw new UnsupportedOperationException(
                "KeyExchangeCompleteProcessor requires ProcessingContext for handshake routing");
    }

    @Override
    public void processMessage(Message message, ProcessingContext context) {
        String messagePeerId = message.from();  // May differ from PeerStore ID!

        log.info("KEY_EXCHANGE_COMPLETE received",
                "nodeId", nodeId,
                "messagePeerId", messagePeerId,
                "correlationId", extractCorrelationId(message));

        metrics.incrementCounter("security.key_exchange.complete_received");

        try {
            // 1. Parse the KEY_EXCHANGE_COMPLETE message
            KeyExchangeComplete complete = parseKeyExchangeComplete(message);
            if (complete == null) {
                log.error("Failed to parse KEY_EXCHANGE_COMPLETE",
                        "messagePeerId", messagePeerId);
                metrics.incrementCounter("security.key_exchange.parse_failed");
                return;
            }

            // 2. Check if this is a rejection (before full processing)
            if (!complete.isAccepted()) {
                log.warn("KEY_EXCHANGE rejected by peer",
                        "messagePeerId", messagePeerId,
                        "reason", complete.getRejectionReason());
                metrics.incrementCounter("security.key_exchange.rejected_by_peer." +
                        sanitizeMetricLabel(complete.getRejectionReason()));

                // Try to find the correct peerId for state transition
                String resolvedPeerId = negotiator.resolveCorrelationToPeerId(complete.getCorrelationId());
                peerManager.markDisconnected(resolvedPeerId != null ? resolvedPeerId : messagePeerId);
                return;
            }

            // 3. Process the complete message (signature verification, ECDH, session creation)
            // CRITICAL: Returns KeyExchangeResult with RESOLVED peerId for state transitions
            KeyExchangeResult result = negotiator.processKeyExchangeComplete(complete);

            if (result.isSuccess()) {
                // CRITICAL: Use result.getResolvedPeerId(), NOT messagePeerId!
                // The resolvedPeerId matches how the peer is stored in PeerStore
                String resolvedPeerId = result.getResolvedPeerId();

                log.info("KEY_EXCHANGE_COMPLETE negotiator success",
                        "nodeId", nodeId,
                        "messagePeerId", messagePeerId,
                        "resolvedPeerId", resolvedPeerId,
                        "peerIdMatch", messagePeerId.equals(resolvedPeerId),
                        "sessionId", result.getSessionId());

                // CRITICAL: Verify resolvedPeerId exists in PeerStore before state transition
                // If not, attempt to find correct peerId via fallback mechanism
                String stateTransitionPeerId = resolvedPeerId;
                Peer resolvedPeer = peerManager.getPeer(resolvedPeerId);

                if (resolvedPeer == null) {
                    log.warn("RESOLVED_PEERID_NOT_FOUND_IN_PEERSTORE - attempting fallback resolution",
                            "resolvedPeerId", resolvedPeerId,
                            "messagePeerId", messagePeerId);

                    // Fallback: Try messagePeerId (network address or sender's declared ID)
                    Peer messagePeer = peerManager.getPeer(messagePeerId);
                    if (messagePeer != null) {
                        log.info("USING_MESSAGE_PEERID_FOR_STATE_TRANSITION (fallback)",
                                "resolvedPeerId", resolvedPeerId,
                                "messagePeerId", messagePeerId,
                                "reason", "Resolved peerId not found in PeerStore");
                        stateTransitionPeerId = messagePeerId;
                        metrics.incrementCounter("security.key_exchange.identity_resolution_fallback");
                    } else {
                        // Both missing - critical diagnostic needed
                        log.error("IDENTITY_RESOLUTION_COMPLETE_FAILURE - unable to locate peer in PeerStore",
                                "resolvedPeerId", resolvedPeerId,
                                "messagePeerId", messagePeerId,
                                "knownPeersCount", peerManager.getAllPeers().size(),
                                "hint", "Neither resolvedPeerId nor messagePeerId found in PeerStore. " +
                                        "Check discovery/protocol identity mapping configuration.");
                        metrics.incrementCounter("security.key_exchange.identity_resolution_complete_failure");

                        // Do not attempt state transition - it will fail anyway
                        // Mark as failed and abort handshake trigger
                        peerManager.markDisconnected(messagePeerId);
                        return;
                    }
                }

                // 4. Transition peer to CHANNEL_ESTABLISHED using verified peerId
                boolean established = peerManager.markChannelEstablished(stateTransitionPeerId);
                if (established) {
                    log.info("KEY_EXCHANGE_COMPLETE processed successfully - channel established",
                            "nodeId", nodeId,
                            "resolvedPeerId", resolvedPeerId,
                            "stateTransitionPeerId", stateTransitionPeerId,
                            "sessionId", result.getSessionId());
                    metrics.incrementCounter("security.key_exchange.channel_established_initiator");

                    // 5. Trigger Phase 2: HANDSHAKE using verified peerId
                    triggerHandshake(stateTransitionPeerId, context);
                } else {
                    log.error("STATE_TRANSITION_FAILED: markChannelEstablished returned false even after identity verification",
                            "resolvedPeerId", resolvedPeerId,
                            "stateTransitionPeerId", stateTransitionPeerId,
                            "messagePeerId", messagePeerId,
                            "currentState", peerManager.getPeerState(stateTransitionPeerId),
                            "hint", "Peer may have been removed or is in invalid state");
                    metrics.incrementCounter("security.key_exchange.state_transition_failed");

                    // Log additional diagnostic info
                    log.error("STATE_TRANSITION_DIAGNOSTIC",
                            "peerExistsWithResolvedId", peerManager.getPeer(resolvedPeerId) != null,
                            "peerExistsWithMessageId", peerManager.getPeer(messagePeerId) != null,
                            "peerExistsWithVerifiedId", peerManager.getPeer(stateTransitionPeerId) != null,
                            "expectedPreviousState", "CHANNEL_NEGOTIATING");
                }
            } else {
                log.error("Failed to process KEY_EXCHANGE_COMPLETE",
                        "messagePeerId", messagePeerId,
                        "resolvedPeerId", result.getResolvedPeerId(),
                        "failureReason", result.getFailureReason(),
                        "failureMessage", result.getFailureMessage());
                metrics.incrementCounter("security.key_exchange.process_failed." +
                        result.getFailureReason().name().toLowerCase());

                // Mark peer as disconnected using resolved peerId if available
                String peerId = result.getResolvedPeerId() != null ? result.getResolvedPeerId() : messagePeerId;
                peerManager.markDisconnected(peerId);
            }

        } catch (Exception e) {
            log.error("Error processing KEY_EXCHANGE_COMPLETE", e,
                    "messagePeerId", messagePeerId);
            metrics.incrementCounter("security.key_exchange.process_error");

            // Mark peer as disconnected
            try {
                peerManager.markDisconnected(messagePeerId);
            } catch (Exception stateError) {
                log.error("Failed to update peer state after error", stateError,
                        "messagePeerId", messagePeerId);
            }
        }
    }

    @Override
    public boolean supportsContext() {
        return true;
    }

    private KeyExchangeComplete parseKeyExchangeComplete(Message message) {
        try {
            String content = message.body().content();
            JsonObject json = gson.fromJson(content, JsonObject.class);

            boolean accepted = json.has("accepted") && json.get("accepted").getAsBoolean();

            if (accepted) {
                return new KeyExchangeComplete.Builder()
                        .nodeId(json.get("nodeId").getAsString())
                        .correlationId(json.get("correlationId").getAsString())
                        .timestamp(json.get("timestamp").getAsLong())
                        .accepted(true)
                        .ephemeralPublicKey(json.get("ephemeralPublicKey").getAsString())
                        .identityPublicKey(json.get("identityPublicKey").getAsString())
                        .signature(json.get("signature").getAsString())
                        .build();
            } else {
                return new KeyExchangeComplete.Builder()
                        .nodeId(json.get("nodeId").getAsString())
                        .correlationId(json.get("correlationId").getAsString())
                        .timestamp(json.get("timestamp").getAsLong())
                        .accepted(false)
                        .rejectionReason(json.has("rejectionReason") ?
                                json.get("rejectionReason").getAsString() : "Unknown")
                        .build();
            }
        } catch (Exception e) {
            log.error("Failed to parse KEY_EXCHANGE_COMPLETE", e);
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

    private void triggerHandshake(String resolvedPeerId, ProcessingContext context) {
        if (handshakeProcessor == null) {
            log.error("HandshakeProcessor not configured - cannot trigger Phase 2",
                    "resolvedPeerId", resolvedPeerId);
            return;
        }

        try {
            // Transition to CONNECTING state (Phase 2 of secure bootstrap)
            // State flow: CHANNEL_ESTABLISHED → CONNECTING → CONNECTED → AUTHENTICATED
            boolean markedConnecting = peerManager.markConnecting(resolvedPeerId);
            if (!markedConnecting) {
                log.warn("Failed to transition to CONNECTING state",
                        "resolvedPeerId", resolvedPeerId,
                        "currentState", peerManager.getPeerState(resolvedPeerId));
                // Continue anyway - handshake might still work
            }

            log.info("Triggering HANDSHAKE phase after secure channel establishment",
                    "resolvedPeerId", resolvedPeerId,
                    "state", peerManager.getPeerState(resolvedPeerId));

            // Initiate the encrypted handshake using the resolved peer ID
            handshakeProcessor.initiateHandshake(resolvedPeerId);
            metrics.incrementCounter("security.key_exchange.handshake_triggered");

        } catch (Exception e) {
            log.error("Failed to trigger handshake", e,
                    "resolvedPeerId", resolvedPeerId);
            metrics.incrementCounter("security.key_exchange.handshake_trigger_failed");
        }
    }

    private String sanitizeMetricLabel(String label) {
        if (label == null) return "unknown";
        return label.toLowerCase()
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_");
    }
}
