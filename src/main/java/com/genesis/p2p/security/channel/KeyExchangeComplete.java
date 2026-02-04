package com.genesis.p2p.security.channel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Phase 1 responder message completing secure channel establishment.
 *
 * Sent in response to {@link KeyExchangeInit}, contains the responder's
 * ephemeral public key signed with their identity key. After both parties
 * exchange these messages, they can derive a shared secret for encrypted
 * communication.
 *
 * Message format:
 * - nodeId: Responder's identity (peer ID)
 * - ephemeralPublicKey: Base64-encoded X.509 EC public key for ECDH
 * - identityPublicKey: Base64-encoded identity public key for signature verification
 * - timestamp: Message creation time (for freshness validation)
 * - signature: ECDSA signature over (ephemeralPubKey || nodeId || timestamp || correlationId)
 * - correlationId: Links this response to the original KeyExchangeInit
 * - accepted: Whether the channel negotiation was accepted
 * - rejectionReason: Reason for rejection if not accepted
 *
 * Security properties:
 * - Signed ephemeral key prevents MITM key substitution
 * - correlationId binding prevents response substitution attacks
 * - Timestamp prevents replay attacks
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class KeyExchangeComplete {

    public static final String MESSAGE_TYPE = "KEY_EXCHANGE_COMPLETE";
    private static final int MAX_TIMESTAMP_AGE_SECONDS = 30;

    private final String nodeId;
    private final String ephemeralPublicKey;
    private final String identityPublicKey;
    private final long timestamp;
    private final String signature;
    private final String correlationId;
    private final boolean accepted;
    private final String rejectionReason;

    private KeyExchangeComplete(Builder builder) {
        this.nodeId = Objects.requireNonNull(builder.nodeId, "nodeId is required");
        this.correlationId = Objects.requireNonNull(builder.correlationId, "correlationId is required");
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
        this.accepted = builder.accepted;

        if (accepted) {
            this.ephemeralPublicKey = Objects.requireNonNull(builder.ephemeralPublicKey,
                    "ephemeralPublicKey is required when accepted");
            this.identityPublicKey = Objects.requireNonNull(builder.identityPublicKey,
                    "identityPublicKey is required when accepted");
            this.signature = Objects.requireNonNull(builder.signature, "signature is required when accepted");
            this.rejectionReason = null;
        } else {
            this.ephemeralPublicKey = null;
            this.identityPublicKey = null;
            this.signature = null;
            this.rejectionReason = builder.rejectionReason != null ? builder.rejectionReason : "Unknown";
        }
    }

    // ========================= Getters =========================

    public String getNodeId() {
        return nodeId;
    }

    public String getEphemeralPublicKey() {
        return ephemeralPublicKey;
    }

    public byte[] getEphemeralPublicKeyBytes() {
        return ephemeralPublicKey != null ? Base64.getDecoder().decode(ephemeralPublicKey) : null;
    }

    public String getIdentityPublicKey() {
        return identityPublicKey;
    }

    public byte[] getIdentityPublicKeyBytes() {
        return identityPublicKey != null ? Base64.getDecoder().decode(identityPublicKey) : null;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSignature() {
        return signature;
    }

    public byte[] getSignatureBytes() {
        return signature != null ? Base64.getDecoder().decode(signature) : null;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getRejectionReason() {
        return rejectionReason;
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
     * The signature covers: ephemeralPubKey || nodeId || timestamp || correlationId
     *
     * @return byte array of signed data, or null if not accepted
     */
    public byte[] getSignedData() {
        if (!accepted || ephemeralPublicKey == null) {
            return null;
        }

        byte[] ephemeralBytes = getEphemeralPublicKeyBytes();
        byte[] nodeIdBytes = nodeId.getBytes(StandardCharsets.UTF_8);
        byte[] timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array();
        byte[] correlationBytes = correlationId.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(
                ephemeralBytes.length + nodeIdBytes.length + timestampBytes.length + correlationBytes.length);
        buffer.put(ephemeralBytes);
        buffer.put(nodeIdBytes);
        buffer.put(timestampBytes);
        buffer.put(correlationBytes);

        return buffer.array();
    }

    // ========================= Builder =========================

    public static class Builder {
        private String nodeId;
        private String ephemeralPublicKey;
        private String identityPublicKey;
        private long timestamp;
        private String signature;
        private String correlationId;
        private boolean accepted = true;
        private String rejectionReason;

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

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder accepted(boolean accepted) {
            this.accepted = accepted;
            return this;
        }

        public Builder rejectionReason(String rejectionReason) {
            this.rejectionReason = rejectionReason;
            return this;
        }

        public KeyExchangeComplete build() {
            return new KeyExchangeComplete(this);
        }
    }

    // ========================= Factory Methods =========================

    /**
     * Creates an accepted KeyExchangeComplete message with proper signing.
     *
     * @param nodeId the local node's ID
     * @param ephemeralKeyPair the ephemeral keypair for this connection
     * @param identityPublicKey the node's identity public key
     * @param signer function to sign data with identity private key
     * @param correlationId the correlationId from the KeyExchangeInit
     * @return signed KeyExchangeComplete message
     */
    public static KeyExchangeComplete createAccepted(
            String nodeId,
            EphemeralKeyPair ephemeralKeyPair,
            byte[] identityPublicKey,
            KeyExchangeInit.SignerFunction signer,
            String correlationId) {

        long timestamp = System.currentTimeMillis();

        byte[] ephemeralBytes = ephemeralKeyPair.getPublicKeyBytes();
        byte[] nodeIdBytes = nodeId.getBytes(StandardCharsets.UTF_8);
        byte[] timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array();
        byte[] correlationBytes = correlationId.getBytes(StandardCharsets.UTF_8);

        // Create signed data: ephemeralPubKey || nodeId || timestamp || correlationId
        ByteBuffer dataToSign = ByteBuffer.allocate(
                ephemeralBytes.length + nodeIdBytes.length + timestampBytes.length + correlationBytes.length);
        dataToSign.put(ephemeralBytes);
        dataToSign.put(nodeIdBytes);
        dataToSign.put(timestampBytes);
        dataToSign.put(correlationBytes);

        byte[] signature = signer.sign(dataToSign.array());

        return new Builder()
                .nodeId(nodeId)
                .ephemeralPublicKey(ephemeralBytes)
                .identityPublicKey(identityPublicKey)
                .timestamp(timestamp)
                .signature(signature)
                .correlationId(correlationId)
                .accepted(true)
                .build();
    }

    /**
     * Creates a rejection KeyExchangeComplete message.
     *
     * @param nodeId the local node's ID
     * @param correlationId the correlationId from the KeyExchangeInit
     * @param reason the rejection reason
     * @return rejection KeyExchangeComplete message
     */
    public static KeyExchangeComplete createRejection(String nodeId, String correlationId, String reason) {
        return new Builder()
                .nodeId(nodeId)
                .correlationId(correlationId)
                .timestamp(System.currentTimeMillis())
                .accepted(false)
                .rejectionReason(reason)
                .build();
    }

    /**
     * Creates rejection for rate limiting.
     */
    public static KeyExchangeComplete rateLimited(String nodeId, String correlationId) {
        return createRejection(nodeId, correlationId, "RATE_LIMITED");
    }

    /**
     * Creates rejection for invalid signature.
     */
    public static KeyExchangeComplete invalidSignature(String nodeId, String correlationId) {
        return createRejection(nodeId, correlationId, "INVALID_SIGNATURE");
    }

    /**
     * Creates rejection for blacklisted peer.
     */
    public static KeyExchangeComplete blacklisted(String nodeId, String correlationId) {
        return createRejection(nodeId, correlationId, "BLACKLISTED");
    }

    /**
     * Creates rejection for expired timestamp.
     */
    public static KeyExchangeComplete timestampExpired(String nodeId, String correlationId) {
        return createRejection(nodeId, correlationId, "TIMESTAMP_EXPIRED");
    }

    /**
     * Creates rejection for resource exhaustion.
     */
    public static KeyExchangeComplete resourceExhausted(String nodeId, String correlationId) {
        return createRejection(nodeId, correlationId, "RESOURCE_EXHAUSTED");
    }

    // ========================= Object Methods =========================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KeyExchangeComplete that = (KeyExchangeComplete) o;
        return timestamp == that.timestamp &&
                accepted == that.accepted &&
                Objects.equals(nodeId, that.nodeId) &&
                Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, timestamp, correlationId, accepted);
    }

    @Override
    public String toString() {
        if (accepted) {
            return "KeyExchangeComplete{" +
                    "nodeId='" + nodeId + '\'' +
                    ", correlationId='" + correlationId + '\'' +
                    ", timestamp=" + timestamp +
                    ", accepted=true" +
                    ", ephemeralKeyLength=" + (ephemeralPublicKey != null ? ephemeralPublicKey.length() : 0) +
                    '}';
        } else {
            return "KeyExchangeComplete{" +
                    "nodeId='" + nodeId + '\'' +
                    ", correlationId='" + correlationId + '\'' +
                    ", timestamp=" + timestamp +
                    ", accepted=false" +
                    ", rejectionReason='" + rejectionReason + '\'' +
                    '}';
        }
    }
}
