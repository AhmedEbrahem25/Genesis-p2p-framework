package com.genesis.p2p.security.channel;

/**
 * Result of KEY_EXCHANGE_COMPLETE processing.
 *
 * Returns both success status AND the resolved peerId to ensure
 * the caller uses the correct ID for state machine transitions.
 *
 * This fixes the critical bug where KeyExchangeCompleteProcessor used
 * message.from() for markChannelEstablished() instead of the peerId
 * that was resolved via correlationId fallback in the negotiator.
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class KeyExchangeResult {

    private final boolean success;
    private final String resolvedPeerId;
    private final String sessionId;
    private final FailureReason failureReason;
    private final String failureMessage;

    public enum FailureReason {
        NONE,
        NO_PENDING_STATE,
        CORRELATION_MISMATCH,
        PEER_REJECTED,
        STALE_TIMESTAMP,
        INVALID_EPHEMERAL_KEY,
        INVALID_SIGNATURE,
        IDENTITY_MISMATCH,
        KEY_DERIVATION_FAILED,
        INTERNAL_ERROR
    }

    private KeyExchangeResult(boolean success, String resolvedPeerId, String sessionId,
                              FailureReason failureReason, String failureMessage) {
        this.success = success;
        this.resolvedPeerId = resolvedPeerId;
        this.sessionId = sessionId;
        this.failureReason = failureReason;
        this.failureMessage = failureMessage;
    }

    /**
     * Creates a successful result.
     *
     * @param resolvedPeerId the peerId as stored in PeerStore (may differ from message.from())
     * @param sessionId the established session ID
     * @return success result
     */
    public static KeyExchangeResult success(String resolvedPeerId, String sessionId) {
        return new KeyExchangeResult(true, resolvedPeerId, sessionId, FailureReason.NONE, null);
    }

    /**
     * Creates a failure result.
     *
     * @param resolvedPeerId the resolved peerId (if known)
     * @param reason the failure reason
     * @param message additional failure context
     * @return failure result
     */
    public static KeyExchangeResult failure(String resolvedPeerId, FailureReason reason, String message) {
        return new KeyExchangeResult(false, resolvedPeerId, null, reason, message);
    }

    public boolean isSuccess() {
        return success;
    }

    /**
     * Gets the resolved peer ID.
     *
     * CRITICAL: Always use this for PeerManager state transitions,
     * NOT message.from() which may differ due to discovery ID format.
     *
     * @return the peer ID as stored in PeerStore
     */
    public String getResolvedPeerId() {
        return resolvedPeerId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    @Override
    public String toString() {
        if (success) {
            return "KeyExchangeResult{success=true, resolvedPeerId='" + resolvedPeerId +
                   "', sessionId='" + sessionId + "'}";
        } else {
            return "KeyExchangeResult{success=false, resolvedPeerId='" + resolvedPeerId +
                   "', reason=" + failureReason + ", message='" + failureMessage + "'}";
        }
    }
}
