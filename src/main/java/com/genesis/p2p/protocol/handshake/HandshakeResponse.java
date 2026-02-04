package com.genesis.p2p.protocol.handshake;

import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.util.common.Time;

import java.util.HashMap;
import java.util.Map;

/**
 * Handshake response for accepting or rejecting P2P connection.
 *
 * Sent in response to a HandshakeRequest.
 * Contains acceptance status, responder capabilities, and authentication data.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class HandshakeResponse {

    /**
     * Status of the handshake response.
     */
    public enum Status {
        ACCEPTED,           // Connection accepted
        REJECTED,           // Connection rejected
        PROTOCOL_MISMATCH,  // Incompatible protocol version
        AUTH_FAILED,        // Authentication failed
        RATE_LIMITED,       // Too many requests
        BLACKLISTED         // Peer is blacklisted
    }

    private final Status status;
    private final String nodeId;
    private final ProtocolVersion protocolVersion;
    private final String publicKey;
    private final long timestamp;
    private final Map<String, String> capabilities;
    private final Map<String, Object> metadata;
    private final byte[] challengeResponse;
    private final String reason;

    private HandshakeResponse(Builder builder) {
        this.status = builder.status;
        this.nodeId = builder.nodeId;
        this.protocolVersion = builder.protocolVersion;
        this.publicKey = builder.publicKey;
        this.timestamp = builder.timestamp;
        this.capabilities = Map.copyOf(builder.capabilities);
        this.metadata = Map.copyOf(builder.metadata);
        this.challengeResponse = builder.challengeResponse;
        this.reason = builder.reason;
    }

    // ==================== Getters ====================

    public Status getStatus() {
        return status;
    }

    public String getNodeId() {
        return nodeId;
    }

    public ProtocolVersion getProtocolVersion() {
        return protocolVersion;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Map<String, String> getCapabilities() {
        return capabilities;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public byte[] getChallengeResponse() {
        return challengeResponse;
    }

    public String getReason() {
        return reason;
    }

    /**
     * Checks if the handshake was accepted.
     */
    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }

    /**
     * Checks if the handshake was rejected.
     */
    public boolean isRejected() {
        return status != Status.ACCEPTED;
    }

    /**
     * Checks if this response has a specific capability.
     */
    public boolean hasCapability(String capability) {
        return capabilities.containsKey(capability);
    }

    /**
     * Gets a capability value.
     */
    public String getCapability(String capability) {
        return capabilities.get(capability);
    }

    // ==================== Factory Methods ====================

    /**
     * Creates an accepted handshake response.
     */
    public static HandshakeResponse accepted(String nodeId, ProtocolVersion version) {
        return new Builder()
                .status(Status.ACCEPTED)
                .nodeId(nodeId)
                .protocolVersion(version)
                .build();
    }

    /**
     * Creates a rejected handshake response.
     */
    public static HandshakeResponse rejected(String reason) {
        return new Builder()
                .status(Status.REJECTED)
                .reason(reason)
                .build();
    }

    /**
     * Creates a protocol mismatch response.
     */
    public static HandshakeResponse protocolMismatch(ProtocolVersion supportedVersion) {
        return new Builder()
                .status(Status.PROTOCOL_MISMATCH)
                .protocolVersion(supportedVersion)
                .reason("Protocol version not compatible")
                .build();
    }

    /**
     * Creates an authentication failed response.
     */
    public static HandshakeResponse authFailed() {
        return new Builder()
                .status(Status.AUTH_FAILED)
                .reason("Authentication failed")
                .build();
    }

    /**
     * Creates a rate limited response.
     */
    public static HandshakeResponse rateLimited() {
        return new Builder()
                .status(Status.RATE_LIMITED)
                .reason("Too many handshake attempts")
                .build();
    }

    // ==================== Builder ====================

    public static class Builder {
        private Status status = Status.REJECTED;
        private String nodeId;
        private ProtocolVersion protocolVersion = ProtocolVersion.current();
        private String publicKey;
        private long timestamp = Time.currentMillis();
        private Map<String, String> capabilities = new HashMap<>();
        private Map<String, Object> metadata = new HashMap<>();
        private byte[] challengeResponse;
        private String reason;

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder protocolVersion(ProtocolVersion version) {
            this.protocolVersion = version;
            return this;
        }

        public Builder publicKey(String publicKey) {
            this.publicKey = publicKey;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder addCapability(String name, String value) {
            this.capabilities.put(name, value);
            return this;
        }

        public Builder capabilities(Map<String, String> capabilities) {
            this.capabilities.putAll(capabilities);
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }

        public Builder challengeResponse(byte[] response) {
            this.challengeResponse = response;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public HandshakeResponse build() {
            return new HandshakeResponse(this);
        }
    }

    @Override
    public String toString() {
        return String.format("HandshakeResponse[status=%s, nodeId=%s, version=%s, reason=%s]",
                status, nodeId, protocolVersion, reason);
    }
}

