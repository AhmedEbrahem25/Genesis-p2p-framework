package com.genesis.p2p.security.channel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 1 initiator message for secure channel establishment.
 *
 * Contains the initiator's ephemeral public key signed with their identity key,
 * enabling the responder to:
 * 1. Verify the initiator's identity (signature verification)
 * 2. Perform ECDH key exchange (using ephemeral public key)
 * 3. Establish a secure session before any handshake messages
 *
 * Message format:
 * - nodeId: Initiator's identity (peer ID)
 * - ephemeralPublicKey: Base64-encoded X.509 EC public key for ECDH
 * - identityPublicKey: Base64-encoded identity public key for signature verification
 * - timestamp: Message creation time (for freshness validation)
 * - signature: ECDSA signature over (ephemeralPubKey || nodeId || timestamp)
 * - protocolVersion: Protocol version for compatibility negotiation
 * - correlationId: Unique ID to correlate request/response
 *
 * Security properties:
 * - Signed ephemeral key prevents MITM key substitution
 * - Timestamp prevents replay attacks (reject if >30s old)
 * - Identity public key allows verification without prior knowledge
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class KeyExchangeInit {

    public static final String MESSAGE_TYPE = "KEY_EXCHANGE_INIT";
    private static final int MAX_TIMESTAMP_AGE_SECONDS = 30;

    private final String nodeId;
    private final String ephemeralPublicKey;
    private final String identityPublicKey;
    private final long timestamp;
    private final String signature;
    private final String protocolVersion;
    private final String correlationId;

    private KeyExchangeInit(Builder builder) {
        this.nodeId = Objects.requireNonNull(builder.nodeId, "nodeId is required");
        this.ephemeralPublicKey = Objects.requireNonNull(builder.ephemeralPublicKey, "ephemeralPublicKey is required");
        this.identityPublicKey = Objects.requireNonNull(builder.identityPublicKey, "identityPublicKey is required");
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
        this.signature = Objects.requireNonNull(builder.signature, "signature is required");
        this.protocolVersion = builder.protocolVersion != null ? builder.protocolVersion : "1.0";
        this.correlationId = builder.correlationId != null ? builder.correlationId : UUID.randomUUID().toString();
    }

    // ========================= Getters =========================

    public String getNodeId() {
        return nodeId;
    }

    public String getEphemeralPublicKey() {
        return ephemeralPublicKey;
    }

    public byte[] getEphemeralPublicKeyBytes() {
        return Base64.getDecoder().decode(ephemeralPublicKey);
    }

    public String getIdentityPublicKey() {
        return identityPublicKey;
    }

    public byte[] getIdentityPublicKeyBytes() {
        return Base64.getDecoder().decode(identityPublicKey);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSignature() {
        return signature;
    }

    public byte[] getSignatureBytes() {
        return Base64.getDecoder().decode(signature);
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    // ========================= Validation =========================

    /**
     * Checks if the message timestamp is fresh (within allowed window).
     *
     * @return true if timestamp is within MAX_TIMESTAMP_AGE_SECONDS of current time
     */
    public boolean isTimestampFresh() {
        long now = System.currentTimeMillis();
        long ageSeconds = (now - timestamp) / 1000;
        return ageSeconds >= 0 && ageSeconds <= MAX_TIMESTAMP_AGE_SECONDS;
    }

    /**
     * Gets the data that was signed.
     * The signature covers: ephemeralPubKey || nodeId || timestamp
     *
     * @return byte array of signed data
     */
    public byte[] getSignedData() {
        byte[] ephemeralBytes = getEphemeralPublicKeyBytes();
        byte[] nodeIdBytes = nodeId.getBytes(StandardCharsets.UTF_8);
        byte[] timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array();

        ByteBuffer buffer = ByteBuffer.allocate(ephemeralBytes.length + nodeIdBytes.length + timestampBytes.length);
        buffer.put(ephemeralBytes);
        buffer.put(nodeIdBytes);
        buffer.put(timestampBytes);

        return buffer.array();
    }

    // ========================= Builder =========================

    public static class Builder {
        private String nodeId;
        private String ephemeralPublicKey;
        private String identityPublicKey;
        private long timestamp;
        private String signature;
        private String protocolVersion;
        private String correlationId;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder ephemeralPublicKey(String ephemeralPublicKey) {
            this.ephemeralPublicKey = ephemeralPublicKey;
            return this;
        }

        public Builder ephemeralPublicKey(byte[] ephemeralPublicKey) {
            this.ephemeralPublicKey = Base64.getEncoder().encodeToString(ephemeralPublicKey);
            return this;
        }

        public Builder identityPublicKey(String identityPublicKey) {
            this.identityPublicKey = identityPublicKey;
            return this;
        }

        public Builder identityPublicKey(byte[] identityPublicKey) {
            this.identityPublicKey = Base64.getEncoder().encodeToString(identityPublicKey);
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        public Builder signature(byte[] signature) {
            this.signature = Base64.getEncoder().encodeToString(signature);
            return this;
        }

        public Builder protocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public KeyExchangeInit build() {
            return new KeyExchangeInit(this);
        }
    }

    // ========================= Factory Methods =========================

    /**
     * Creates a KeyExchangeInit message with proper signing.
     *
     * @param nodeId the local node's ID
     * @param ephemeralKeyPair the ephemeral keypair for this connection
     * @param identityPublicKey the node's identity public key
     * @param signer function to sign data with identity private key
     * @param protocolVersion the protocol version
     * @return signed KeyExchangeInit message
     */
    public static KeyExchangeInit create(
            String nodeId,
            EphemeralKeyPair ephemeralKeyPair,
            byte[] identityPublicKey,
            SignerFunction signer,
            String protocolVersion) {

        long timestamp = System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();

        byte[] ephemeralBytes = ephemeralKeyPair.getPublicKeyBytes();
        byte[] nodeIdBytes = nodeId.getBytes(StandardCharsets.UTF_8);
        byte[] timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array();

        // Create signed data: ephemeralPubKey || nodeId || timestamp
        ByteBuffer dataToSign = ByteBuffer.allocate(ephemeralBytes.length + nodeIdBytes.length + timestampBytes.length);
        dataToSign.put(ephemeralBytes);
        dataToSign.put(nodeIdBytes);
        dataToSign.put(timestampBytes);

        byte[] signature = signer.sign(dataToSign.array());

        return new Builder()
                .nodeId(nodeId)
                .ephemeralPublicKey(ephemeralBytes)
                .identityPublicKey(identityPublicKey)
                .timestamp(timestamp)
                .signature(signature)
                .protocolVersion(protocolVersion)
                .correlationId(correlationId)
                .build();
    }

    /**
     * Creates a KeyExchangeInit message with a pre-specified correlationId.
     * Used for retry scenarios where we need to keep the same correlationId
     * to maintain idempotency with the receiving peer.
     *
     * @param nodeId the local node's ID
     * @param ephemeralKeyPair the ephemeral keypair for this connection
     * @param identityPublicKey the node's identity public key
     * @param signer function to sign data with identity private key
     * @param protocolVersion the protocol version
     * @param correlationId the correlationId to use (from original request)
     * @return signed KeyExchangeInit message with specified correlationId
     */
    public static KeyExchangeInit createWithCorrelation(
            String nodeId,
            EphemeralKeyPair ephemeralKeyPair,
            byte[] identityPublicKey,
            SignerFunction signer,
            String protocolVersion,
            String correlationId) {

        long timestamp = System.currentTimeMillis();

        byte[] ephemeralBytes = ephemeralKeyPair.getPublicKeyBytes();
        byte[] nodeIdBytes = nodeId.getBytes(StandardCharsets.UTF_8);
        byte[] timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array();

        // Create signed data: ephemeralPubKey || nodeId || timestamp
        ByteBuffer dataToSign = ByteBuffer.allocate(ephemeralBytes.length + nodeIdBytes.length + timestampBytes.length);
        dataToSign.put(ephemeralBytes);
        dataToSign.put(nodeIdBytes);
        dataToSign.put(timestampBytes);

        byte[] signature = signer.sign(dataToSign.array());

        return new Builder()
                .nodeId(nodeId)
                .ephemeralPublicKey(ephemeralBytes)
                .identityPublicKey(identityPublicKey)
                .timestamp(timestamp)
                .signature(signature)
                .protocolVersion(protocolVersion)
                .correlationId(correlationId)  // Use provided correlationId
                .build();
    }

    /**
     * Functional interface for signing data.
     */
    @FunctionalInterface
    public interface SignerFunction {
        byte[] sign(byte[] data);
    }

    // ========================= Serialization =========================

    /**
     * Converts this KeyExchangeInit to a JSON-serializable map.
     *
     * @return map representation suitable for JSON serialization
     */
    public java.util.Map<String, Object> toJson() {
        java.util.Map<String, Object> json = new java.util.LinkedHashMap<>();
        json.put("nodeId", nodeId);
        json.put("ephemeralPublicKey", ephemeralPublicKey);
        json.put("identityPublicKey", identityPublicKey);
        json.put("timestamp", timestamp);
        json.put("signature", signature);
        json.put("protocolVersion", protocolVersion);
        json.put("correlationId", correlationId);
        return json;
    }

    // ========================= Object Methods =========================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KeyExchangeInit that = (KeyExchangeInit) o;
        return timestamp == that.timestamp &&
                Objects.equals(nodeId, that.nodeId) &&
                Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, timestamp, correlationId);
    }

    @Override
    public String toString() {
        return "KeyExchangeInit{" +
                "nodeId='" + nodeId + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", timestamp=" + timestamp +
                ", protocolVersion='" + protocolVersion + '\'' +
                ", ephemeralKeyLength=" + (ephemeralPublicKey != null ? ephemeralPublicKey.length() : 0) +
                '}';
    }
}
