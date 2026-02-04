package com.genesis.p2p.security.gateway;

import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.policy.MessageSecurityPolicy;
import com.genesis.p2p.transport.core.ITransportEnvelopeHandler;

/**
 * Builder for SecurityGateway with sensible defaults.
 *
 * Usage:
 * <pre>
 * SecurityGateway gateway = new SecurityGatewayBuilder()
 *     .downstream(messageHandler::handleMessage)
 *     .security(securityFacade)
 *     .peerManager(peerManager)
 *     .policy(securityPolicy)
 *     .metrics(metricsRegistry)
 *     .legacyPlaintextMode(false)
 *     .build();
 * </pre>
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class SecurityGatewayBuilder {

    private ITransportEnvelopeHandler downstream;
    private SecurityFacade security;
    private PeerManager peerManager;
    private MessageSecurityPolicy policy;
    private MetricsRegistry metrics;
    private boolean legacyPlaintextMode = false;

    /**
     * Sets the downstream handler that receives validated messages.
     *
     * @param handler the downstream handler
     * @return this builder
     */
    public SecurityGatewayBuilder downstream(ITransportEnvelopeHandler handler) {
        this.downstream = handler;
        return this;
    }

    /**
     * Sets the security facade for session validation.
     *
     * @param security the security facade
     * @return this builder
     */
    public SecurityGatewayBuilder security(SecurityFacade security) {
        this.security = security;
        return this;
    }

    /**
     * Sets the peer manager for state lookups.
     *
     * @param peerManager the peer manager
     * @return this builder
     */
    public SecurityGatewayBuilder peerManager(PeerManager peerManager) {
        this.peerManager = peerManager;
        return this;
    }

    /**
     * Sets the message security policy.
     * If not set, a default policy will be used.
     *
     * @param policy the security policy
     * @return this builder
     */
    public SecurityGatewayBuilder policy(MessageSecurityPolicy policy) {
        this.policy = policy;
        return this;
    }

    /**
     * Sets the metrics registry for observability.
     * If not set, a default registry will be created.
     *
     * @param metrics the metrics registry
     * @return this builder
     */
    public SecurityGatewayBuilder metrics(MetricsRegistry metrics) {
        this.metrics = metrics;
        return this;
    }

    /**
     * Enables or disables legacy plaintext mode.
     *
     * When enabled, encryption requirements are bypassed (logged but not enforced).
     * This is for migration purposes only and should not be used in production.
     *
     * @param enabled true to enable legacy mode
     * @return this builder
     */
    public SecurityGatewayBuilder legacyPlaintextMode(boolean enabled) {
        this.legacyPlaintextMode = enabled;
        return this;
    }

    /**
     * Builds the SecurityGateway with the configured parameters.
     *
     * @return the configured SecurityGateway
     * @throws IllegalStateException if required parameters are missing
     */
    public SecurityGateway build() {
        // Validate required parameters
        if (downstream == null) {
            throw new IllegalStateException("Downstream handler is required");
        }
        if (peerManager == null) {
            throw new IllegalStateException("PeerManager is required");
        }

        // Apply defaults for optional parameters
        if (policy == null) {
            policy = new MessageSecurityPolicy();
        }
        if (metrics == null) {
            metrics = new MetricsRegistry("security-gateway");
        }

        // Security can be null for plaintext-only deployments
        // but warn if not in legacy mode
        if (security == null && !legacyPlaintextMode) {
            throw new IllegalStateException(
                    "SecurityFacade is required when legacyPlaintextMode is disabled. " +
                    "Either provide a SecurityFacade or enable legacyPlaintextMode for migration."
            );
        }

        return new SecurityGateway(
                downstream,
                security,
                peerManager,
                policy,
                metrics,
                legacyPlaintextMode
        );
    }
}
