package com.genesis.p2p.security.gateway;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.ProcessingContext;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.peer.PeerStore.PeerState;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.policy.MessageSecurityPolicy;
import com.genesis.p2p.security.policy.MessageSecurityPolicy.SecurityRequirement;
import com.genesis.p2p.transport.core.ITransportEnvelopeHandler;

import java.net.InetSocketAddress;

/**
 * Security Gateway - Central enforcement point for secure channel requirements.
 *
 * FAIL-CLOSED design: Rejects messages that violate security policy.
 * This is the single choke point between transport layer and MessageHandler.
 *
 * Responsibilities:
 * 1. Validate message type against security policy
 * 2. Validate peer state allows this message type
 * 3. Validate encryption requirements are met
 * 4. Route valid messages to downstream handler
 * 5. Reject invalid messages with appropriate metrics/logging
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class SecurityGateway implements ITransportEnvelopeHandler {

    private static final NodeLogger log = NodeLogger.getLogger(SecurityGateway.class);

    // Metric names (pre-computed for hot path performance)
    private static final String METRIC_PASSED = "security.gateway.passed";
    private static final String METRIC_REJECTED_UNKNOWN = "security.gateway.rejected.unknown_peer";
    private static final String METRIC_REJECTED_STATE = "security.gateway.rejected.invalid_state";
    private static final String METRIC_REJECTED_NO_SESSION = "security.gateway.rejected.no_session";
    private static final String METRIC_REJECTED_UNENCRYPTED = "security.gateway.rejected.unencrypted";
    private static final String METRIC_LEGACY_BYPASS = "security.gateway.legacy_bypass";
    private static final String METRIC_SECURITY_VIOLATION = "security.gateway.security_violation";

    private final ITransportEnvelopeHandler downstream;
    private final SecurityFacade security;
    private final PeerManager peerManager;
    private final MessageSecurityPolicy policy;
    private final MetricsRegistry metrics;
    private final boolean legacyPlaintextMode;

    /**
     * Creates a SecurityGateway with all required dependencies.
     *
     * @param downstream the downstream handler to route valid messages to
     * @param security the security facade for session validation
     * @param peerManager the peer manager for state lookups
     * @param policy the message security policy
     * @param metrics the metrics registry
     * @param legacyPlaintextMode if true, bypasses encryption requirements (for migration)
     */
    public SecurityGateway(
            ITransportEnvelopeHandler downstream,
            SecurityFacade security,
            PeerManager peerManager,
            MessageSecurityPolicy policy,
            MetricsRegistry metrics,
            boolean legacyPlaintextMode) {
        this.downstream = downstream;
        this.security = security;
        this.peerManager = peerManager;
        this.policy = policy;
        this.metrics = metrics;
        this.legacyPlaintextMode = legacyPlaintextMode;

        log.info("SECURITY_GATEWAY_INITIALIZED",
                "legacyPlaintextMode", legacyPlaintextMode,
                "policySize", policy.size());
    }

    @Override
    public void handleEnvelope(Message message, InetSocketAddress source, ProcessingContext context) {
        String messageType = message.type();
        String peerId = message.from();  // Use the sender ID from the message

        // Get security requirement for this message type
        SecurityRequirement requirement = policy.getRequirement(messageType);

        // ═══════════════════════════════════════════════════════════════════════════
        // VALIDATION 1: Check peer state allows this message type
        // ═══════════════════════════════════════════════════════════════════════════
        PeerState currentState = peerManager.getPeerState(peerId);

        if (currentState == null) {
            // Unknown peer - only allow bootstrap/discovery messages
            if (!isBootstrapOrDiscovery(requirement)) {
                rejectUnknownPeer(message, peerId, messageType);
                return;
            }
            // Bootstrap/discovery from unknown peer is allowed - proceed
        } else if (!requirement.isStateAllowed(currentState)) {
            rejectInvalidState(message, peerId, messageType, currentState, requirement);
            return;
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // VALIDATION 2: Check encryption requirements
        // ═══════════════════════════════════════════════════════════════════════════
        if (requirement.requiresEncryption()) {
            // Legacy mode bypass
            if (legacyPlaintextMode) {
                log.warn("SECURITY_GATEWAY_LEGACY_BYPASS",
                        "messageType", messageType,
                        "peerId", peerId,
                        "messageId", message.messageId(),
                        "warning", "Legacy plaintext mode - encryption requirement bypassed");
                metrics.incrementCounter(METRIC_LEGACY_BYPASS);
            } else {
                // Check for valid session
                if (security != null && !security.hasValidSession(peerId)) {
                    rejectNoSession(message, peerId, messageType);
                    return;
                }

                // Check message was actually encrypted (header flag)
                if (!message.header().encrypted()) {
                    rejectUnencrypted(message, peerId, messageType);
                    return;
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // VALIDATION PASSED - Route to downstream handler
        // ═══════════════════════════════════════════════════════════════════════════
        log.debug("SECURITY_GATEWAY_PASSED",
                "messageType", messageType,
                "messageId", message.messageId(),
                "peerId", peerId,
                "peerState", currentState != null ? currentState.toString() : "unknown",
                "securityLevel", requirement.level(),
                "category", requirement.category());

        metrics.incrementCounter(METRIC_PASSED);

        downstream.handleEnvelope(message, source, context);
    }

    @Override
    public void handleEnvelope(Message message, InetSocketAddress source) {
        // Legacy method - create empty context and delegate
        handleEnvelope(message, source, ProcessingContext.empty());
    }

    /**
     * Checks if the requirement is for bootstrap or discovery messages.
     */
    private boolean isBootstrapOrDiscovery(SecurityRequirement requirement) {
        return requirement.category() == MessageSecurityPolicy.MessageCategory.BOOTSTRAP ||
               requirement.category() == MessageSecurityPolicy.MessageCategory.DISCOVERY;
    }

    /**
     * Rejects a message from an unknown peer attempting non-bootstrap communication.
     */
    private void rejectUnknownPeer(Message message, String peerId, String messageType) {
        log.warn("SECURITY_GATEWAY_REJECTED_UNKNOWN_PEER",
                "messageId", message.messageId(),
                "messageType", messageType,
                "peerId", peerId,
                "reason", "Unknown peer attempting non-bootstrap message",
                "action", "DROPPED",
                "hint", "Peer must complete key exchange before sending application messages");

        metrics.incrementCounter(METRIC_REJECTED_UNKNOWN);
    }

    /**
     * Rejects a message due to invalid peer state for the message type.
     */
    private void rejectInvalidState(Message message, String peerId, String messageType,
                                    PeerState currentState, SecurityRequirement requirement) {
        log.warn("SECURITY_GATEWAY_REJECTED_INVALID_STATE",
                "messageId", message.messageId(),
                "messageType", messageType,
                "peerId", peerId,
                "currentState", currentState.toString(),
                "allowedStates", requirement.allowedStates().toString(),
                "category", requirement.category(),
                "reason", "Peer state does not allow this message type",
                "action", "DROPPED");

        metrics.incrementCounter(METRIC_REJECTED_STATE);
    }

    /**
     * Rejects a message that requires encryption but has no valid session.
     */
    private void rejectNoSession(Message message, String peerId, String messageType) {
        log.warn("SECURITY_GATEWAY_REJECTED_NO_SESSION",
                "messageId", message.messageId(),
                "messageType", messageType,
                "peerId", peerId,
                "reason", "No valid session - message requires encryption",
                "action", "DROPPED",
                "hint", "Complete key exchange to establish secure session");

        metrics.incrementCounter(METRIC_REJECTED_NO_SESSION);
    }

    /**
     * Rejects a message that requires encryption but was sent in plaintext.
     * This is a HIGH severity security violation.
     */
    private void rejectUnencrypted(Message message, String peerId, String messageType) {
        log.error("SECURITY_GATEWAY_REJECTED_UNENCRYPTED",
                "messageId", message.messageId(),
                "messageType", messageType,
                "peerId", peerId,
                "reason", "Message requires encryption but was sent in plaintext",
                "action", "DROPPED",
                "severity", "HIGH",
                "securityAlert", "Possible protocol downgrade attack or misconfigured peer");

        metrics.incrementCounter(METRIC_REJECTED_UNENCRYPTED);
        metrics.incrementCounter(METRIC_SECURITY_VIOLATION);
    }

    /**
     * Returns whether legacy plaintext mode is enabled.
     */
    public boolean isLegacyPlaintextMode() {
        return legacyPlaintextMode;
    }
}
