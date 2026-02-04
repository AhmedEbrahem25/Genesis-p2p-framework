package com.genesis.p2p.protocol.handshake;

import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.util.common.Time;

import java.util.HashMap;
import java.util.Map;

/**
 * Handshake request for establishing P2P connection.
 *
 * Sent when a peer wants to establish a connection with another peer.
 * Contains peer information, capabilities, and authentication data.
 *
 * Handshake Flow:
 * 1. Initiator sends HandshakeRequest
 * 2. Responder validates and sends HandshakeResponse
 * 3. Connection is established if both parties agree
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class HandshakeRequest {

    private final String nodeId;
    private final ProtocolVersion protocolVersion;
    private final String publicKey;
    private final long timestamp;
    private final Map<String, String> capabilities;
    private final Map<String, Object> metadata;
    private final byte[] challengeNonce;

    private HandshakeRequest(Builder builder) {
        this.nodeId = builder.nodeId;
        this.protocolVersion = builder.protocolVersion;
        this.publicKey = builder.publicKey;
        this.timestamp = builder.timestamp;
        this.capabilities = Map.copyOf(builder.capabilities);
        this.metadata = Map.copyOf(builder.metadata);
        this.challengeNonce = builder.challengeNonce;
    }

    // ==================== Getters ====================

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

    public byte[] getChallengeNonce() {
        return challengeNonce;
    }

    /**
     * Checks if this request has a specific capability.
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

    /**
     * Checks if handshake is expired (older than 30 seconds).
     */
    public boolean isExpired() {
        long now = Time.currentMillis();
        return (now - timestamp) > 30000; // 30 seconds
    }

    // ==================== Builder ====================

    public static class Builder {
        private String nodeId;
        private ProtocolVersion protocolVersion = ProtocolVersion.current();
        private String publicKey;
        private long timestamp = Time.currentMillis();
        private Map<String, String> capabilities = new HashMap<>();
        private Map<String, Object> metadata = new HashMap<>();
        private byte[] challengeNonce;

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

        public Builder challengeNonce(byte[] nonce) {
            this.challengeNonce = nonce;
            return this;
        }

        public HandshakeRequest build() {
            if (nodeId == null || nodeId.isEmpty()) {
                throw new IllegalStateException("Node ID is required");
            }
            if (protocolVersion == null) {
                throw new IllegalStateException("Protocol version is required");
            }
            return new HandshakeRequest(this);
        }
    }

    @Override
    public String toString() {
        return String.format("HandshakeRequest[nodeId=%s, version=%s, capabilities=%d, timestamp=%d]",
                nodeId, protocolVersion, capabilities.size(), timestamp);
    }
}

