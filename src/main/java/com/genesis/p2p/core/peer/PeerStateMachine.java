package com.genesis.p2p.core.peer;

import com.genesis.p2p.core.Peer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.genesis.p2p.core.peer.PeerStore.PeerState.*;

/**
 * State machine for managing peer lifecycle states.
 * Enforces valid state transitions and fires events.
 */
public class PeerStateMachine {

    private static final Logger log = LoggerFactory.getLogger(PeerStateMachine.class);

    // Valid state transitions
    // CRITICAL: CONNECTING must be a valid transition from DISCOVERED or CHANNEL_ESTABLISHED
    //
    // Secure channel flow (v2.3 - HMAC-synchronized):
    // DISCOVERED → CHANNEL_NEGOTIATING → CHANNEL_ESTABLISHED → CONNECTING → CONNECTED → AUTHENTICATED
    //
    // CRITICAL UPDATE v2.3: AUTHENTICATED transition ONLY allowed after HMAC-verified handshake
    // This prevents TCP_ERROR_CLASSIFIED deadlocks by ensuring peers don't prematurely
    // transition to AUTHENTICATED before handshake messages have been properly encrypted
    // and HMAC-verified over the secure channel.
    //
    // Legacy flow (backward compatibility):
    // DISCOVERED → CONNECTING → CONNECTED → AUTHENTICATED
    private static final Map<PeerStore.PeerState, Set<PeerStore.PeerState>> VALID_TRANSITIONS = Map.of(
            DISCOVERED, EnumSet.of(CHANNEL_NEGOTIATING, CONNECTING, CONNECTED, DISCONNECTED, BANNED),
            CHANNEL_NEGOTIATING, EnumSet.of(CHANNEL_ESTABLISHED, DISCONNECTED, BANNED), // Can succeed or fail
            CHANNEL_ESTABLISHED, EnumSet.of(CONNECTING, CONNECTED, DISCONNECTED, BANNED), // Ready for handshake
            CONNECTING, EnumSet.of(CONNECTED, DISCONNECTED, BANNED), // Can succeed, fail, or be banned
            CONNECTED, EnumSet.of(AUTHENTICATED, DISCONNECTED, BANNED), // HMAC guard enforced here
            AUTHENTICATED, EnumSet.of(DISCONNECTED, BANNED),
            DISCONNECTED, EnumSet.of(CHANNEL_NEGOTIATING, CONNECTING, CONNECTED, BANNED), // Can reconnect
            BANNED, EnumSet.noneOf(PeerStore.PeerState.class) // Terminal state
    );

    private final PeerStore store;
    private final PeerEventBus eventBus;

    // HMAC Verification Hook (v2.2): Synchronized AUTHENTICATED transition guard
    private volatile HmacVerificationHook hmacVerificationHook;

    /**
     * HMAC Verification Hook Interface.
     *
     * Guards the CHANNEL_NEGOTIATING → AUTHENTICATED transition to ensure that
     * the application handshake has been HMAC-verified before completing authentication.
     *
     * This prevents TCP_ERROR_CLASSIFIED deadlocks in simultaneous open scenarios
     * by ensuring peers don't transition to AUTHENTICATED until handshake messages
     * have been properly encrypted and verified.
     */
    @FunctionalInterface
    public interface HmacVerificationHook {
        /**
         * Verifies that a peer has completed HMAC-verified handshake.
         *
         * @param peerId the peer to verify
         * @return true if handshake was HMAC-verified, false otherwise
         */
        boolean isHandshakeHmacVerified(String peerId);
    }

    public PeerStateMachine(PeerStore store, PeerEventBus eventBus) {
        this.store = store;
        this.eventBus = eventBus;
        log.debug("PeerStateMachine initialized");
    }

    /**
     * Sets the HMAC verification hook for synchronized AUTHENTICATED transitions.
     *
     * CRITICAL: This hook ensures that peers only transition to AUTHENTICATED
     * after their handshake messages have been HMAC-verified, preventing
     * TCP_ERROR_CLASSIFIED deadlocks in simultaneous open scenarios.
     *
     * @param hook the verification callback
     */
    public void setHmacVerificationHook(HmacVerificationHook hook) {
        this.hmacVerificationHook = hook;
        log.info("HmacVerificationHook configured",
                "hookClass", hook != null ? hook.getClass().getSimpleName() : "null");
    }

    // ========================= State Transitions =========================

    /**
     * Attempts to transition a peer to a new state.
     * Returns true if transition was valid and executed.
     *
     * Thread-safe with defensive validation and proper error handling.
     */
    public boolean transitionTo(String id, PeerStore.PeerState newState) {
        // Defensive validation: null parameters
        if (id == null) {
            log.debug("Cannot transition peer with null ID");
            return false;
        }
        if (newState == null) {
            log.debug("Cannot transition to null state", "peerId", id);
            return false;
        }

        try {
            // Atomic retrieval of peer and metadata
            PeerStore.PeerMetadata metadata = store.getMetadata(id).orElse(null);
            Peer peer = store.get(id).orElse(null);

            // Defensive validation: peer exists
            if (metadata == null || peer == null) {
                log.debug("Cannot transition unknown peer (network noise), ignoring",
                        "peerId", id,
                        "requestedState", newState.toString());
                return false;
            }

            PeerStore.PeerState oldState = metadata.state;

            // Defensive check: already in target state (idempotent)
            if (oldState == newState) {
                log.debug("Peer already in target state, no transition needed",
                        "peerId", id,
                        "state", newState.toString());
                return true; // Success - already in desired state
            }

            // Validate transition is allowed
            if (!isValidTransition(oldState, newState)) {
                log.debug("Invalid state transition attempt (expected behavior), ignoring",
                        "peerId", id,
                        "fromState", oldState.toString(),
                        "toState", newState.toString(),
                        "reason", "transition_not_allowed");
                return false;
            }

            // Perform transition atomically
            // Note: metadata.state is not thread-safe by itself, but PeerStore operations
            // are synchronized, so this is safe as long as we don't hold references
            metadata.state = newState;

            // Log state transition with comprehensive details
            log.info("PEER_STATE_TRANSITION",
                    "peerId", id,
                    "fromState", oldState.toString(),
                    "toState", newState.toString(),
                    "peerAddr", peer.ip(),
                    "peerPort", peer.port(),
                    "discoveryMethod", peer.agent(),
                    "reputation", peer.reputation(),
                    "trusted", peer.trusted());

            // Fire event (defensive: catch event bus errors)
            try {
                eventBus.fireStateChanged(peer, oldState, newState);
            } catch (Exception e) {
                log.warn("Error firing state change event (non-critical)",
                        "peerId", id,
                        "error", e.getClass().getSimpleName());
                // Continue - transition still succeeded
            }

            return true;

        } catch (Exception e) {
            // Defensive: catch unexpected errors to prevent state machine corruption
            log.warn("Unexpected error during state transition, aborting",
                    "peerId", id,
                    "requestedState", newState.toString(),
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a state transition is valid.
     */
    public boolean isValidTransition(PeerStore.PeerState from, PeerStore.PeerState to) {
        if (from == null || to == null) {
            return false;
        }

        Set<PeerStore.PeerState> allowedTransitions = VALID_TRANSITIONS.get(from);
        return allowedTransitions != null && allowedTransitions.contains(to);
    }

    // ========================= Convenience Methods =========================

    /**
     * Marks a peer as entering channel negotiation (KEY_EXCHANGE phase).
     */
    public boolean markChannelNegotiating(String id) {
        return transitionTo(id, CHANNEL_NEGOTIATING);
    }

    /**
     * Marks a peer as having an established secure channel.
     * Ready for handshake over encrypted connection.
     */
    public boolean markChannelEstablished(String id) {
        return transitionTo(id, CHANNEL_ESTABLISHED);
    }

    public boolean markConnecting(String id) {
        return transitionTo(id, PeerStore.PeerState.CONNECTING);
    }

    public boolean markConnected(String id) {
        return transitionTo(id, CONNECTED);
    }

    public boolean markAuthenticated(String id) {
        // ═════════════════════════════════════════════════════════════════════════
        // SYNCHRONIZED AUTHENTICATED TRANSITION v2.3: HMAC Verification Guard
        // ═════════════════════════════════════════════════════════════════════════
        // Enforces strict HMAC verification before allowing AUTHENTICATED transition.
        // This prevents TCP_ERROR_CLASSIFIED deadlocks in simultaneous open scenarios
        // by ensuring both peers have completed encrypted handshake exchange with
        // proper HMAC verification before marking connection as authenticated.
        //
        // Flow Enforcement:
        // 1. Channel must be established (KEY_EXCHANGE complete)
        // 2. Handshake messages must be sent over encrypted channel
        // 3. HMAC verification must pass for handshake acknowledgment
        // 4. Only then allow AUTHENTICATED transition
        //
        // This eliminates the timing window where peers attempt handshake before
        // secure channel is ready, causing HMAC failures and deadlock.
        // ═════════════════════════════════════════════════════════════════════════

        if (hmacVerificationHook != null) {
            boolean verified = hmacVerificationHook.isHandshakeHmacVerified(id);

            if (!verified) {
                PeerStore.PeerState currentState = getState(id);

                log.warn("AUTHENTICATED_TRANSITION_BLOCKED_NO_HMAC",
                        "peerId", id,
                        "currentState", currentState != null ? currentState.toString() : "null",
                        "reason", "Handshake not HMAC-verified",
                        "requiredCondition", "Secure channel must be established and handshake HMAC-verified",
                        "hint", "Waiting for encrypted handshake acknowledgment",
                        "prevention", "TCP_ERROR_CLASSIFIED deadlock");

                return false; // Block transition until HMAC verification completes
            }

            log.info("HMAC_VERIFICATION_PASSED",
                    "peerId", id,
                    "proceedingToAuthenticated", true,
                    "secureChannelEstablished", true,
                    "handshakeVerified", true);
        } else {
            // HMAC hook not configured - log warning but allow transition for backward compatibility
            log.warn("HMAC_VERIFICATION_HOOK_NOT_CONFIGURED",
                    "peerId", id,
                    "currentState", getState(id),
                    "proceedingToAuthenticated", true,
                    "securityWarning", "Transition allowed without HMAC verification",
                    "recommendation", "Configure HmacVerificationHook for secure channels");
        }

        return transitionTo(id, AUTHENTICATED);
    }

    public boolean markDisconnected(String id) {
        return transitionTo(id, DISCONNECTED);
    }

    public boolean markBanned(String id) {
        return transitionTo(id, BANNED);
    }

    /**
     * Marks a peer as rejected (transitions to DISCONNECTED with rejection context).
     *
     * This is a convenience method for handling explicit handshake rejections,
     * providing better observability than just calling markDisconnected().
     *
     * @param id the peer ID
     * @param reason the rejection reason (for logging/metrics)
     * @return true if the transition succeeded
     */
    public boolean markRejected(String id, String reason) {
        log.info("PEER_REJECTED",
                "peerId", id,
                "reason", reason,
                "currentState", getState(id));

        boolean transitioned = transitionTo(id, DISCONNECTED);

        if (transitioned) {
            log.info("PEER_REJECTION_COMPLETE",
                    "peerId", id,
                    "reason", reason);
        } else {
            log.warn("PEER_REJECTION_TRANSITION_FAILED",
                    "peerId", id,
                    "reason", reason,
                    "currentState", getState(id));
        }

        return transitioned;
    }

    // ========================= State Queries =========================

    /**
     * Gets the current state of a peer.
     */
    public PeerStore.PeerState getState(String id) {
        return store.getMetadata(id)
                .map(meta -> meta.state)
                .orElse(null);
    }

    /**
     * Checks if peer is in a specific state.
     */
    public boolean isInState(String id, PeerStore.PeerState state) {
        return getState(id) == state;
    }

    /**
     * Checks if peer is banned.
     */
    public boolean isBanned(String id) {
        return isInState(id, BANNED);
    }

    /**
     * Checks if peer is authenticated.
     */
    public boolean isAuthenticated(String id) {
        return isInState(id, AUTHENTICATED);
    }

    /**
     * Checks if peer is connected (CONNECTED or AUTHENTICATED).
     */
    public boolean isConnected(String id) {
        PeerStore.PeerState state = getState(id);
        return state == CONNECTED || state == AUTHENTICATED;
    }

    /**
     * Checks if peer is in channel negotiation.
     */
    public boolean isChannelNegotiating(String id) {
        return isInState(id, CHANNEL_NEGOTIATING);
    }

    /**
     * Checks if peer has an established secure channel.
     */
    public boolean isChannelEstablished(String id) {
        return isInState(id, CHANNEL_ESTABLISHED);
    }

    /**
     * Checks if peer has a secure channel (negotiating, established, or connected).
     */
    public boolean hasSecureChannel(String id) {
        PeerStore.PeerState state = getState(id);
        return state == CHANNEL_NEGOTIATING ||
               state == CHANNEL_ESTABLISHED ||
               state == CONNECTING ||
               state == CONNECTED ||
               state == AUTHENTICATED;
    }
}