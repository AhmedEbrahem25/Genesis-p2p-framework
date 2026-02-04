package com.genesis.p2p.security.cert;

import java.io.Serializable;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * P2P Certificate for node identity and trust.
 *
 * Lightweight certificate system for P2P networks.
 * Unlike X.509, optimized for decentralized trust without CAs.
 *
 * Certificate Structure:
 * - Node ID (unique identifier)
 * - Public Key (for verification)
 * - Validity Period (notBefore, notAfter)
 * - Attributes (version, capabilities, metadata)
 * - Signature (self-signed or signed by trusted peer)
 *
 * Trust Models Supported:
 * 1. Self-Signed: Each node creates its own certificate
 * 2. Web of Trust: Peers sign each other's certificates
 * 3. Distributed CA: Designated trust nodes sign certificates
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class Certificate implements Serializable {
    private static final long serialVersionUID = 1L;

    // Identity
    private final String nodeId;
    private final PublicKey publicKey;

    // Validity
    private final Instant notBefore;
    private final Instant notAfter;

    // Attributes
    private final String version;
    private final Map<String, String> attributes;

    // Signature
    private final byte[] signature;
    private final String signedBy; // Node ID of signer (self for self-signed)

    // Metadata
    private final Instant createdAt;

    private Certificate(Builder builder) {
        this.nodeId = builder.nodeId;
        this.publicKey = builder.publicKey;
        this.notBefore = builder.notBefore;
        this.notAfter = builder.notAfter;
        this.version = builder.version;
        this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
        this.signature = builder.signature;
        this.signedBy = builder.signedBy;
        this.createdAt = builder.createdAt;
    }

    // ==================== Getters ====================

    public String getNodeId() { return nodeId; }
    public PublicKey getPublicKey() { return publicKey; }
    public Instant getNotBefore() { return notBefore; }
    public Instant getNotAfter() { return notAfter; }
    public String getVersion() { return version; }
    public Map<String, String> getAttributes() { return attributes; }
    public byte[] getSignature() { return signature; }
    public String getSignedBy() { return signedBy; }
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Gets a certificate attribute.
     */
    public String getAttribute(String name) {
        return attributes.get(name);
    }

    /**
     * Checks if certificate has an attribute.
     */
    public boolean hasAttribute(String name) {
        return attributes.containsKey(name);
    }

    // ==================== Validation ====================

    /**
     * Checks if certificate is currently valid (time-based).
     */
    public boolean isValid() {
        return isValid(Instant.now());
    }

    /**
     * Checks if certificate is valid at a specific time.
     */
    public boolean isValid(Instant at) {
        return !at.isBefore(notBefore) && !at.isAfter(notAfter);
    }

    /**
     * Checks if certificate is expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(notAfter);
    }

    /**
     * Checks if certificate is not yet valid.
     */
    public boolean isNotYetValid() {
        return Instant.now().isBefore(notBefore);
    }

    /**
     * Checks if certificate is self-signed.
     */
    public boolean isSelfSigned() {
        return nodeId.equals(signedBy);
    }

    /**
     * Gets remaining validity in seconds.
     */
    public long getRemainingValiditySeconds() {
        return notAfter.getEpochSecond() - Instant.now().getEpochSecond();
    }

    /**
     * Gets bytes to be signed (for signature generation/verification).
     */
    public byte[] getSignableBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append(nodeId);
        sb.append(publicKey.getEncoded().length);
        sb.append(notBefore.toEpochMilli());
        sb.append(notAfter.toEpochMilli());
        sb.append(version);

        // Add sorted attributes for consistency
        attributes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append(e.getValue()));

        return sb.toString().getBytes();
    }

    // ==================== Builder ====================

    public static class Builder {
        private String nodeId;
        private PublicKey publicKey;
        private Instant notBefore;
        private Instant notAfter;
        private String version = "2.0";
        private Map<String, String> attributes = new HashMap<>();
        private byte[] signature;
        private String signedBy;
        private Instant createdAt = Instant.now();

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder publicKey(PublicKey publicKey) {
            this.publicKey = publicKey;
            return this;
        }

        public Builder notBefore(Instant notBefore) {
            this.notBefore = notBefore;
            return this;
        }

        public Builder notAfter(Instant notAfter) {
            this.notAfter = notAfter;
            return this;
        }

        public Builder validFor(long durationSeconds) {
            this.notBefore = Instant.now();
            this.notAfter = notBefore.plusSeconds(durationSeconds);
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder addAttribute(String name, String value) {
            this.attributes.put(name, value);
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes.putAll(attributes);
            return this;
        }

        public Builder signature(byte[] signature) {
            this.signature = signature;
            return this;
        }

        public Builder signedBy(String signedBy) {
            this.signedBy = signedBy;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Certificate build() {
            Objects.requireNonNull(nodeId, "Node ID is required");
            Objects.requireNonNull(publicKey, "Public key is required");
            Objects.requireNonNull(notBefore, "notBefore is required");
            Objects.requireNonNull(notAfter, "notAfter is required");
            Objects.requireNonNull(signature, "Signature is required");
            Objects.requireNonNull(signedBy, "signedBy is required");

            if (notAfter.isBefore(notBefore)) {
                throw new IllegalArgumentException("notAfter must be after notBefore");
            }

            return new Certificate(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Certificate that = (Certificate) o;
        return nodeId.equals(that.nodeId) &&
               publicKey.equals(that.publicKey) &&
               signedBy.equals(that.signedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, publicKey, signedBy);
    }

    @Override
    public String toString() {
        return String.format("Certificate[node=%s, valid=%s-%s, signer=%s, selfSigned=%s]",
                nodeId,
                notBefore,
                notAfter,
                signedBy,
                isSelfSigned());
    }
}

