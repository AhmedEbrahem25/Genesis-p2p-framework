package com.genesis.p2p.protocol.handshake;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit handshake rejection message.
 *
 * Sent when a handshake request cannot be fulfilled, providing clear
 * rejection semantics with a specific reason and correlation ID for tracking.
 *
 * This is sent as a HANDSHAKE_REJECT message type, separate from HANDSHAKE_RESPONSE,
 * to eliminate silent failures and enable immediate state transitions on the
 * receiving side.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class HandshakeReject {

    /**
     * Specific reasons for handshake rejection.
     * Each reason maps to different handling on the receiving side.
     */
    public enum RejectionReason {
        /** Incompatible protocol version */
        PROTOCOL_MISMATCH("Protocol version incompatible"),

        /** Authentication/security check failed */
        AUTH_FAILED("Authentication failed"),

        /** Too many handshake attempts from this peer */
        RATE_LIMITED("Rate limit exceeded"),

        /** Peer is on the blacklist */
        BLACKLISTED("Peer is blacklisted"),

        /** Request validation failed (missing fields, invalid format) */
        VALIDATION_FAILED("Request validation failed"),

        /** Security session establishment failed (ECDH error) */
        SESSION_ERROR("Security session establishment failed"),

        /** Internal server error (our fault) */
        INTERNAL_ERROR("Internal error"),

        /** Connection limit reached (resource exhaustion) */
        RESOURCE_EXHAUSTED("Resource limit reached");

        private final String defaultMessage;

        RejectionReason(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String getDefaultMessage() {
            return defaultMessage;
        }
    }

    private final String correlationId;
    private final RejectionReason reason;
    private final String message;
    private final long timestamp;
    private final String nodeId;
    private final Map<String, String> metadata;

    private HandshakeReject(Builder builder) {
        this.correlationId = builder.correlationId;
        this.reason = builder.reason;
        this.message = builder.message;
        this.timestamp = builder.timestamp;
        this.nodeId = builder.nodeId;
        this.metadata = builder.metadata;
    }

    /**
     * Gets the correlation ID linking this rejection to the original request.
     */
    public String getCorrelationId() {
        return correlationId;
    }

    /**
     * Gets the specific rejection reason.
     */
    public RejectionReason getReason() {
        return reason;
    }

    /**
     * Gets the human-readable rejection message.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Gets the timestamp when the rejection was created.
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the ID of the rejecting node.
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Gets additional metadata about the rejection.
     */
    public Map<String, String> getMetadata() {
        return metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    /**
     * Checks if this rejection is due to a transient condition that may be retried.
     */
    public boolean isRetryable() {
        return reason == RejectionReason.RATE_LIMITED ||
               reason == RejectionReason.RESOURCE_EXHAUSTED ||
               reason == RejectionReason.INTERNAL_ERROR;
    }

    /**
     * Checks if this rejection is due to a permanent condition (no retry).
     */
    public boolean isPermanent() {
        return reason == RejectionReason.BLACKLISTED ||
               reason == RejectionReason.PROTOCOL_MISMATCH;
    }

    @Override
    public String toString() {
        return String.format("HandshakeReject[reason=%s, correlationId=%s, nodeId=%s, message=%s]",
                reason, correlationId, nodeId, message);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HandshakeReject that = (HandshakeReject) o;
        return timestamp == that.timestamp &&
               Objects.equals(correlationId, that.correlationId) &&
               reason == that.reason &&
               Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(correlationId, reason, timestamp, nodeId);
    }

    // ========================= Builder =========================

    /**
     * Builder for HandshakeReject.
     */
    public static class Builder {
        private String correlationId;
        private RejectionReason reason;
        private String message;
        private long timestamp = System.currentTimeMillis();
        private String nodeId;
        private Map<String, String> metadata = new HashMap<>();

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder reason(RejectionReason reason) {
            this.reason = reason;
            if (this.message == null && reason != null) {
                this.message = reason.getDefaultMessage();
            }
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder addMetadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public HandshakeReject build() {
            Objects.requireNonNull(reason, "Rejection reason is required");
            Objects.requireNonNull(nodeId, "Node ID is required");
            if (message == null) {
                message = reason.getDefaultMessage();
            }
            return new HandshakeReject(this);
        }
    }

    // ========================= Factory Methods =========================

    /**
     * Creates a rejection for rate limiting.
     */
    public static HandshakeReject rateLimited(String nodeId, String correlationId) {
        return new Builder()
                .nodeId(nodeId)
                .correlationId(correlationId)
                .reason(RejectionReason.RATE_LIMITED)
                .build();
    }

    /**
     * Creates a rejection for protocol mismatch.
     */
    public static HandshakeReject protocolMismatch(String nodeId, String correlationId,
                                                    String expectedVersion) {
        return new Builder()
                .nodeId(nodeId)
                .correlationId(correlationId)
                .reason(RejectionReason.PROTOCOL_MISMATCH)
                .message("Protocol version incompatible. Expected: " + expectedVersion)
                .addMetadata("expectedVersion", expectedVersion)
                .build();
    }

    /**
     * Creates a rejection for validation failure.
     */
    public static HandshakeReject validationFailed(String nodeId, String correlationId,
                                                    String validationError) {
        return new Builder()
                .nodeId(nodeId)
                .correlationId(correlationId)
                .reason(RejectionReason.VALIDATION_FAILED)
                .message(validationError)
                .build();
    }

    /**
     * Creates a rejection for session establishment error.
     */
    public static HandshakeReject sessionError(String nodeId, String correlationId,
                                                String errorDetail) {
        return new Builder()
                .nodeId(nodeId)
                .correlationId(correlationId)
                .reason(RejectionReason.SESSION_ERROR)
                .message("Security session establishment failed: " + errorDetail)
                .build();
    }

    /**
     * Creates a rejection for blacklisted peer.
     */
    public static HandshakeReject blacklisted(String nodeId, String correlationId) {
        return new Builder()
                .nodeId(nodeId)
                .correlationId(correlationId)
                .reason(RejectionReason.BLACKLISTED)
                .build();
    }

    /**
     * Creates a rejection for authentication failure.
     */
    public static HandshakeReject authFailed(String nodeId, String correlationId,
                                              String reason) {
        return new Builder()
                .nodeId(nodeId)
                .correlationId(correlationId)
                .reason(RejectionReason.AUTH_FAILED)
                .message(reason)
                .build();
    }

    /**
     * Creates a rejection for resource exhaustion.
     */
    public static HandshakeReject resourceExhausted(String nodeId, String correlationId) {
        return new Builder()
                .nodeId(nodeId)
                .correlationId(correlationId)
                .reason(RejectionReason.RESOURCE_EXHAUSTED)
                .build();
    }

    /**
     * Creates a rejection for internal error.
     */
    public static HandshakeReject internalError(String nodeId, String correlationId,
                                                 String errorDetail) {
        return new Builder()
                .nodeId(nodeId)
                .correlationId(correlationId)
                .reason(RejectionReason.INTERNAL_ERROR)
                .message("Internal error: " + errorDetail)
                .build();
    }
}
