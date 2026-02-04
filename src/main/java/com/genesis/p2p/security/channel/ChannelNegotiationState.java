package com.genesis.p2p.security.channel;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the state of an ongoing secure channel negotiation.
 *
 * Each channel negotiation goes through these states:
 * 1. INIT_SENT (initiator): Sent KEY_EXCHANGE_INIT, waiting for COMPLETE
 * 2. INIT_RECEIVED (responder): Received INIT, preparing COMPLETE response
 * 3. COMPLETE_SENT (responder): Sent KEY_EXCHANGE_COMPLETE, channel ready
 * 4. ESTABLISHED (both): Both sides have derived shared secret, ready for handshake
 * 5. FAILED (both): Negotiation failed (timeout, invalid signature, etc.)
 *
 * Security features:
 * - Timeout enforcement (default 10 seconds)
 * - Replay prevention via correlationId tracking
 * - Ephemeral key lifecycle management
 *
 * SECURITY FIX (v2.6): Thread-safe compound state updates using AtomicReference.
 * Previously, volatile fields were used without atomic guards for multi-step
 * state operations, which could lead to inconsistent state if multiple threads
 * called markFailed() and cleanup() simultaneously.
 *
 * Thread-safety: State transitions are now thread-safe via AtomicReference.
 *
 * @author Genesis P2P Framework
 * @version 2.6
 */
public class ChannelNegotiationState {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofSeconds(2);

    /**
     * Negotiation state machine states.
     */
    public enum State {
        /** Initiator has sent KEY_EXCHANGE_INIT, waiting for COMPLETE */
        INIT_SENT,

        /** Responder has received INIT, preparing COMPLETE response */
        INIT_RECEIVED,

        /** Responder has sent KEY_EXCHANGE_COMPLETE */
        COMPLETE_SENT,

        /** Both sides have established the secure channel */
        ESTABLISHED,

        /** Negotiation failed */
        FAILED
    }

    /**
     * Role in the negotiation.
     */
    public enum Role {
        /** Started the negotiation (sent INIT first) */
        INITIATOR,

        /** Responded to the negotiation (received INIT first) */
        RESPONDER
    }

    /**
     * Failure reasons for diagnostics.
     */
    public enum FailureReason {
        NONE,
        TIMEOUT,
        INVALID_SIGNATURE,
        STALE_TIMESTAMP,
        PEER_REJECTED,
        PEER_BLACKLISTED,
        KEY_DERIVATION_FAILED,
        INTERNAL_ERROR,
        MAX_RETRIES_EXCEEDED,
        SIMULTANEOUS_INIT_TIMEOUT
    }

    private final String peerId;
    private final String correlationId;
    private final Role role;
    private final Instant startedAt;
    private final Duration timeout;

    private EphemeralKeyPair localEphemeralKeys;
    private byte[] peerEphemeralPublicKey;
    private byte[] peerIdentityPublicKey;
    private byte[] derivedSharedSecret;
    private String sessionId;

    // SECURITY FIX (v2.6): Use AtomicReference for thread-safe compound state updates
    // This replaces the previous volatile fields which were not atomic for multi-field updates
    private final AtomicReference<StateSnapshot> stateRef;

    /**
     * Immutable snapshot of negotiation state.
     * SECURITY FIX (v2.6): Ensures atomic state transitions.
     */
    public record StateSnapshot(State state, FailureReason failureReason, String failureMessage) {
        public StateSnapshot(State state) {
            this(state, FailureReason.NONE, null);
        }
    }

    // Retry tracking for simultaneous INIT and lost COMPLETE recovery (v2.4)
    private volatile int retryCount = 0;
    private volatile Instant lastRetriedAt;
    private volatile Instant simultaneousInitDetectedAt;
    private final int maxRetries;
    private final Duration retryInterval;

    /**
     * Creates a new negotiation state.
     *
     * @param peerId the peer we're negotiating with
     * @param correlationId unique ID for this negotiation
     * @param role our role in the negotiation
     * @param ephemeralKeys our ephemeral keypair for this negotiation
     */
    public ChannelNegotiationState(String peerId, String correlationId, Role role, EphemeralKeyPair ephemeralKeys) {
        this(peerId, correlationId, role, ephemeralKeys, DEFAULT_TIMEOUT, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_INTERVAL);
    }

    /**
     * Creates a new negotiation state with custom timeout.
     *
     * @param peerId the peer we're negotiating with
     * @param correlationId unique ID for this negotiation
     * @param role our role in the negotiation
     * @param ephemeralKeys our ephemeral keypair for this negotiation
     * @param timeout timeout duration for the negotiation
     */
    public ChannelNegotiationState(String peerId, String correlationId, Role role,
                                   EphemeralKeyPair ephemeralKeys, Duration timeout) {
        this(peerId, correlationId, role, ephemeralKeys, timeout, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_INTERVAL);
    }

    /**
     * Creates a new negotiation state with full configuration.
     *
     * @param peerId the peer we're negotiating with
     * @param correlationId unique ID for this negotiation
     * @param role our role in the negotiation
     * @param ephemeralKeys our ephemeral keypair for this negotiation
     * @param timeout timeout duration for the negotiation
     * @param maxRetries maximum retry attempts for lost messages
     * @param retryInterval interval between retry attempts
     */
    public ChannelNegotiationState(String peerId, String correlationId, Role role,
                                   EphemeralKeyPair ephemeralKeys, Duration timeout,
                                   int maxRetries, Duration retryInterval) {
        this.peerId = Objects.requireNonNull(peerId, "peerId is required");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId is required");
        this.role = Objects.requireNonNull(role, "role is required");
        this.localEphemeralKeys = Objects.requireNonNull(ephemeralKeys, "ephemeralKeys is required");
        this.startedAt = Instant.now();
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.retryInterval = retryInterval != null ? retryInterval : DEFAULT_RETRY_INTERVAL;

        // SECURITY FIX (v2.6): Initial state depends on role, using AtomicReference
        State initialState = (role == Role.INITIATOR) ? State.INIT_SENT : State.INIT_RECEIVED;
        this.stateRef = new AtomicReference<>(new StateSnapshot(initialState));
    }

    // ========================= State Transitions =========================

    /**
     * Transitions to COMPLETE_SENT state (responder only).
     *
     * SECURITY FIX (v2.6): Uses compare-and-swap for thread-safe transition.
     *
     * @throws IllegalStateException if transition is invalid
     */
    public void markCompleteSent() {
        if (role != Role.RESPONDER) {
            throw new IllegalStateException("Only responder can transition to COMPLETE_SENT");
        }

        // SECURITY FIX (v2.6): Atomic compare-and-swap transition
        StateSnapshot current;
        StateSnapshot next;
        do {
            current = stateRef.get();
            if (current.state() != State.INIT_RECEIVED) {
                throw new IllegalStateException("Can only transition to COMPLETE_SENT from INIT_RECEIVED, current: " + current.state());
            }
            next = new StateSnapshot(State.COMPLETE_SENT);
        } while (!stateRef.compareAndSet(current, next));
    }

    /**
     * Transitions to ESTABLISHED state.
     *
     * SECURITY FIX (v2.6): Uses compare-and-swap for thread-safe transition.
     *
     * @param sessionId the session ID for the established channel
     * @throws IllegalStateException if transition is invalid
     */
    public void markEstablished(String sessionId) {
        // SECURITY FIX (v2.6): Atomic compare-and-swap transition
        StateSnapshot current;
        StateSnapshot next;
        do {
            current = stateRef.get();
            if (current.state() == State.FAILED) {
                throw new IllegalStateException("Cannot establish from FAILED state");
            }
            if (derivedSharedSecret == null) {
                throw new IllegalStateException("Cannot establish without derived shared secret");
            }
            next = new StateSnapshot(State.ESTABLISHED);
        } while (!stateRef.compareAndSet(current, next));

        this.sessionId = sessionId;
    }

    /**
     * Transitions to FAILED state.
     *
     * SECURITY FIX (v2.6): Uses atomic update for thread-safe compound state change.
     *
     * @param reason the failure reason
     * @param message optional message with details
     * @return true if state was changed to FAILED, false if already in terminal state
     */
    public boolean markFailed(FailureReason reason, String message) {
        // SECURITY FIX (v2.6): Atomic update to prevent race between markFailed and cleanup
        StateSnapshot current;
        StateSnapshot next;
        do {
            current = stateRef.get();
            if (current.state() == State.FAILED || current.state() == State.ESTABLISHED) {
                // Already in terminal state - don't override
                return false;
            }
            next = new StateSnapshot(State.FAILED, reason, message);
        } while (!stateRef.compareAndSet(current, next));

        // Clean up ephemeral keys on failure (only if we actually transitioned)
        if (localEphemeralKeys != null) {
            localEphemeralKeys.close();
        }
        return true;
    }

    // ========================= Key Management =========================

    /**
     * Stores the peer's ephemeral public key.
     *
     * @param peerEphemeralPublicKey X.509 encoded ephemeral public key
     */
    public void setPeerEphemeralPublicKey(byte[] peerEphemeralPublicKey) {
        this.peerEphemeralPublicKey = peerEphemeralPublicKey;
    }

    /**
     * Stores the peer's identity public key.
     *
     * @param peerIdentityPublicKey X.509 encoded identity public key
     */
    public void setPeerIdentityPublicKey(byte[] peerIdentityPublicKey) {
        this.peerIdentityPublicKey = peerIdentityPublicKey;
    }

    /**
     * Stores the derived shared secret.
     *
     * @param sharedSecret the ECDH shared secret
     */
    public void setDerivedSharedSecret(byte[] sharedSecret) {
        this.derivedSharedSecret = sharedSecret;
    }

    /**
     * Gets the local ephemeral keypair.
     *
     * @return the ephemeral keypair, or null if destroyed
     */
    public EphemeralKeyPair getLocalEphemeralKeys() {
        return localEphemeralKeys;
    }

    /**
     * Gets the peer's ephemeral public key.
     *
     * @return X.509 encoded ephemeral public key, or null if not set
     */
    public byte[] getPeerEphemeralPublicKey() {
        return peerEphemeralPublicKey;
    }

    /**
     * Gets the peer's identity public key.
     *
     * @return X.509 encoded identity public key, or null if not set
     */
    public byte[] getPeerIdentityPublicKey() {
        return peerIdentityPublicKey;
    }

    /**
     * Gets the derived shared secret.
     *
     * @return the ECDH shared secret, or null if not derived
     */
    public byte[] getDerivedSharedSecret() {
        return derivedSharedSecret;
    }

    // ========================= State Queries =========================

    public String getPeerId() {
        return peerId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Role getRole() {
        return role;
    }

    /**
     * Gets the current state.
     * SECURITY FIX (v2.6): Thread-safe read from AtomicReference.
     */
    public State getState() {
        return stateRef.get().state();
    }

    /**
     * Gets the complete state snapshot.
     * SECURITY FIX (v2.6): Returns immutable snapshot for consistent multi-field reads.
     */
    public StateSnapshot getStateSnapshot() {
        return stateRef.get();
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Gets the failure reason.
     * SECURITY FIX (v2.6): Thread-safe read from AtomicReference.
     */
    public FailureReason getFailureReason() {
        return stateRef.get().failureReason();
    }

    /**
     * Gets the failure message.
     * SECURITY FIX (v2.6): Thread-safe read from AtomicReference.
     */
    public String getFailureMessage() {
        return stateRef.get().failureMessage();
    }

    /**
     * Checks if the negotiation has expired.
     *
     * @return true if started more than timeout duration ago
     */
    public boolean isExpired() {
        return Duration.between(startedAt, Instant.now()).compareTo(timeout) > 0;
    }

    /**
     * Checks if the negotiation is complete (established or failed).
     * SECURITY FIX (v2.6): Thread-safe read from AtomicReference.
     *
     * @return true if in terminal state
     */
    public boolean isTerminal() {
        State current = stateRef.get().state();
        return current == State.ESTABLISHED || current == State.FAILED;
    }

    /**
     * Checks if we're the initiator.
     *
     * @return true if we initiated this negotiation
     */
    public boolean isInitiator() {
        return role == Role.INITIATOR;
    }

    /**
     * Checks if we're the responder.
     *
     * @return true if we're responding to this negotiation
     */
    public boolean isResponder() {
        return role == Role.RESPONDER;
    }

    /**
     * Checks if the channel is established.
     * SECURITY FIX (v2.6): Thread-safe read from AtomicReference.
     *
     * @return true if state is ESTABLISHED
     */
    public boolean isEstablished() {
        return stateRef.get().state() == State.ESTABLISHED;
    }

    /**
     * Checks if the negotiation failed.
     * SECURITY FIX (v2.6): Thread-safe read from AtomicReference.
     *
     * @return true if state is FAILED
     */
    public boolean isFailed() {
        return stateRef.get().state() == State.FAILED;
    }

    /**
     * Gets the age of this negotiation.
     *
     * @return duration since startedAt
     */
    public Duration getAge() {
        return Duration.between(startedAt, Instant.now());
    }

    // ========================= Retry Management (v2.4) =========================

    /**
     * Records that a simultaneous INIT was detected.
     * The winner (lower nodeId) calls this to track when they need to wait
     * for the peer's COMPLETE response.
     */
    public void markSimultaneousInitDetected() {
        this.simultaneousInitDetectedAt = Instant.now();
    }

    /**
     * Checks if a simultaneous INIT was detected.
     * SECURITY FIX (v2.6): Thread-safe read from AtomicReference.
     *
     * @return true if we won a simultaneous INIT duel and are waiting
     */
    public boolean isWaitingAfterSimultaneousInit() {
        return simultaneousInitDetectedAt != null && stateRef.get().state() == State.INIT_SENT;
    }

    /**
     * Records that a retry was attempted.
     */
    public void recordRetryAttempt() {
        this.retryCount++;
        this.lastRetriedAt = Instant.now();
    }

    /**
     * Gets the current retry count.
     *
     * @return number of retry attempts made
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * Gets the maximum allowed retries.
     *
     * @return max retries
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Gets the retry interval.
     *
     * @return duration between retries
     */
    public Duration getRetryInterval() {
        return retryInterval;
    }

    /**
     * Checks if retry is allowed (hasn't exceeded max retries).
     *
     * @return true if more retries are allowed
     */
    public boolean canRetry() {
        return retryCount < maxRetries && !isTerminal();
    }

    /**
     * Checks if enough time has passed since last retry (or initial send) for a new retry.
     *
     * @return true if retry interval has elapsed
     */
    public boolean isRetryDue() {
        if (isTerminal()) return false;

        Instant lastAttempt = lastRetriedAt != null ? lastRetriedAt : startedAt;
        Duration sinceLastAttempt = Duration.between(lastAttempt, Instant.now());
        return sinceLastAttempt.compareTo(retryInterval) >= 0;
    }

    /**
     * Checks if this negotiation needs a retry (due and allowed).
     * SECURITY FIX (v2.6): Thread-safe read from AtomicReference.
     * Should be called periodically to check for stuck negotiations.
     *
     * @return true if a retry should be attempted
     */
    public boolean needsRetry() {
        return stateRef.get().state() == State.INIT_SENT && canRetry() && isRetryDue();
    }

    /**
     * Gets the instant when simultaneous init was detected.
     *
     * @return the detection timestamp, or null if not applicable
     */
    public Instant getSimultaneousInitDetectedAt() {
        return simultaneousInitDetectedAt;
    }

    // ========================= Cleanup =========================

    /**
     * Cleans up resources associated with this negotiation.
     * Should be called when the negotiation is complete or abandoned.
     */
    public void cleanup() {
        if (localEphemeralKeys != null && !localEphemeralKeys.isDestroyed()) {
            localEphemeralKeys.close();
        }

        // Clear sensitive data
        if (derivedSharedSecret != null) {
            java.util.Arrays.fill(derivedSharedSecret, (byte) 0);
            derivedSharedSecret = null;
        }
    }

    // ========================= Object Methods =========================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChannelNegotiationState that = (ChannelNegotiationState) o;
        return Objects.equals(peerId, that.peerId) &&
                Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(peerId, correlationId);
    }

    @Override
    public String toString() {
        StateSnapshot snapshot = stateRef.get();
        StringBuilder sb = new StringBuilder("ChannelNegotiationState{");
        sb.append("peerId='").append(peerId).append('\'');
        sb.append(", correlationId='").append(correlationId).append('\'');
        sb.append(", role=").append(role);
        sb.append(", state=").append(snapshot.state());
        sb.append(", age=").append(getAge().toMillis()).append("ms");

        if (snapshot.state() == State.FAILED) {
            sb.append(", failureReason=").append(snapshot.failureReason());
            if (snapshot.failureMessage() != null) {
                sb.append(", failureMessage='").append(snapshot.failureMessage()).append('\'');
            }
        }

        if (sessionId != null) {
            sb.append(", sessionId='").append(sessionId).append('\'');
        }

        sb.append('}');
        return sb.toString();
    }
}
