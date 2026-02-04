package com.genesis.p2p.core.handlers.processors.system;

import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.util.common.Time;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Processes WELCOME messages - response to HELLO.
 *
 * Purpose:
 * - Acknowledge peer connection
 * - Establish authenticated session
 *
 * Responsibilities:
 * - Mark peer as authenticated
 * - Update peer status
 * - Establish session state
 */
public class WelcomeProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(WelcomeProcessor.class);

    private final PeerManager peerManager;
    private final SessionManager sessionManager;
    private final SecurityFacade security;
    private final Gson gson;

    public WelcomeProcessor(PeerManager peerManager, SessionManager sessionManager, SecurityFacade security) {
        this.peerManager = peerManager;
        this.sessionManager = sessionManager;
        this.security = security;
        this.gson = new Gson();

        log.info("WelcomeProcessor initialized", "securityEnabled", security != null);
    }

    // Backward compatibility constructor
    public WelcomeProcessor(PeerManager peerManager, SessionManager sessionManager) {
        this(peerManager, sessionManager, null);
    }

    @Override
    public void processMessage(Message message) {
        MessageHeader header = message.header();
        MessageBody body = message.body();

        String peerId = header.from();
        log.info("Processing WELCOME", "peerId", peerId);

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
                        log.warn("Invalid WELCOME signature - rejecting session", "peerId", peerId);
                        peerManager.recordFailure(peerId);
                        peerManager.updateReputation(peerId, -10); // High penalty for session hijacking attempt
                        return; // Reject invalid welcome
                    }
                    log.debug("WELCOME signature verified", "peerId", peerId);
                }
            }

            JsonObject payload = gson.fromJson(body.content(), JsonObject.class);

            // Mark peer as authenticated
            // Update peer status
            peerManager.refreshLastSeen(peerId);
            peerManager.recordSuccess(peerId);

            // Establish session
            long timestamp = Time.currentMillis();
            sessionManager.createSession(peerId, timestamp);

            log.info("WELCOME processed successfully", "peerId", peerId,
                    "signed", header.authenticated());

        } catch (Exception e) {
            log.error("Error processing WELCOME", e, "peerId", peerId);
            peerManager.recordFailure(peerId);
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

    /**
     * Session manager interface for managing peer sessions.
     */
    public interface SessionManager {
        void createSession(String peerId, long timestamp);
        void closeSession(String peerId);
        boolean hasSession(String peerId);
    }
}
