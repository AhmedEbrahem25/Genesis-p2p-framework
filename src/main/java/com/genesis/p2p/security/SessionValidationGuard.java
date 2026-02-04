package com.genesis.p2p.security;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.facade.SecurityFacade;

/**
 * Validates session state before allowing peer state transitions.
 * FIX #4: SESSION VALIDATION GUARDS
 */
public class SessionValidationGuard {

    private static final NodeLogger log = NodeLogger.getLogger(SessionValidationGuard.class);

    private final SecurityFacade security;
    private final MetricsRegistry metrics;
    private final HmacVerificationHook hmacHook;

    public interface HmacVerificationHook {
        boolean isHandshakeHmacVerified(String peerId);
    }

    public SessionValidationGuard(SecurityFacade security,
                                 MetricsRegistry metrics,
                                 HmacVerificationHook hmacHook) {
        this.security = security;
        this.metrics = metrics;
        this.hmacHook = hmacHook;
        log.info("SessionValidationGuard created");
    }

    public boolean validateChannelEstablished(String peerId) {
        if (!security.hasValidSession(peerId)) {
            log.error("Cannot transition to CHANNEL_ESTABLISHED: session missing", "peerId", peerId);
            metrics.incrementCounter("session_guard.channel_established_blocked_no_session");
            return false;
        }

        // Session exists and is active - ECDH completed, shared secret derived
        log.debug("Session validation passed for CHANNEL_ESTABLISHED", "peerId", peerId);
        metrics.incrementCounter("session_guard.channel_established_validated");
        return true;
    }

    public boolean validateAuthenticated(String peerId) {
        if (!security.hasValidSession(peerId)) {
            log.error("Cannot transition to AUTHENTICATED: session missing", "peerId", peerId);
            metrics.incrementCounter("session_guard.authenticated_blocked_no_session");
            return false;
        }

        if (hmacHook != null && !hmacHook.isHandshakeHmacVerified(peerId)) {
            log.error("Cannot transition to AUTHENTICATED: HMAC not verified", "peerId", peerId);
            metrics.incrementCounter("session_guard.authenticated_blocked_no_hmac");
            return false;
        }

        log.debug("Session validation passed for AUTHENTICATED", "peerId", peerId);
        metrics.incrementCounter("session_guard.authenticated_validated");
        return true;
    }

    public boolean validateEncryptionReady(String peerId) {
        if (!security.hasValidSession(peerId)) {
            log.debug("Encryption not ready: session missing", "peerId", peerId);
            return false;
        }
        return true;
    }

    public boolean validateDecryptionReady(String peerId) {
        if (!security.hasValidSession(peerId)) {
            log.debug("Decryption not ready: session missing", "peerId", peerId);
            return false;
        }
        return true;
    }

    public void onPeerDisconnected(String peerId) {
        log.debug("Session validation state cleaned for disconnected peer", "peerId", peerId);
    }
}

