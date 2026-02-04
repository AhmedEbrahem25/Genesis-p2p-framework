package com.genesis.p2p.security.session;

import com.genesis.p2p.security.api.ISecureSession;
import com.genesis.p2p.security.ecdh.EcdhKeyDerivation;
import com.genesis.p2p.security.ecdh.EcdhKeyDerivation.DerivedKeys;

import java.time.Duration;
import java.time.Instant;

/**
 * Secure session implementation using HKDF for key derivation.
 *
 * Each session contains:
 * - Unique session ID (derived from shared secret)
 * - Encryption key (for AES-GCM)
 * - HMAC key (for message authentication)
 * - Session metadata (creation time, last activity)
 *
 * Keys are cryptographically independent (compromise of one doesn't affect others).
 *
 * SECURITY FIX (v2.6): Added message count overflow protection.
 * Sessions must be rotated before reaching MAX_MESSAGE_COUNT to prevent
 * counter wrap-around which could lead to nonce reuse.
 *
 * @author Genesis P2P Framework
 * @version 2.6
 */
class SecureSession implements ISecureSession {
    // SECURITY FIX (v2.6): Maximum messages per session before mandatory rotation
    // Set to 2^62 to have safety margin before 2^64 counter wrap
    private static final long MAX_MESSAGE_COUNT = 1L << 62; // ~4.6 * 10^18
    private final String sessionId;
    private final String peerId;
    private final byte[] encryptionKey;
    private final byte[] hmacKey;
    private final Instant createdAt;
    private volatile Instant lastActivity;
    private volatile boolean active;
    private volatile long messageCount;
    private volatile long rotationCounter;

    /**
     * Creates a new secure session using HKDF key derivation.
     *
     * @param localPeerId local peer identifier
     * @param remotePeerId remote peer identifier
     * @param sharedSecret raw ECDH shared secret
     * @throws Exception if key derivation fails
     */
    SecureSession(String localPeerId, String remotePeerId, byte[] sharedSecret) throws Exception {
        this.peerId = remotePeerId;
        this.createdAt = Instant.now();
        this.lastActivity = Instant.now();
        this.active = true;
        this.messageCount = 0;
        this.rotationCounter = 0;

        // Derive all session keys using HKDF
        DerivedKeys keys = EcdhKeyDerivation.deriveSessionKeys(
                sharedSecret,
                localPeerId,
                remotePeerId,
                null // No salt for initial derivation
        );

        this.sessionId = keys.getSessionIdHex();
        this.encryptionKey = keys.getEncryptionKey();
        this.hmacKey = keys.getHmacKey();
    }

    /**
     * Creates session with explicit keys (for key rotation).
     */
    SecureSession(String peerId, String sessionId, byte[] encryptionKey, byte[] hmacKey) {
        this.peerId = peerId;
        this.sessionId = sessionId;
        this.encryptionKey = encryptionKey;
        this.hmacKey = hmacKey;
        this.createdAt = Instant.now();
        this.lastActivity = Instant.now();
        this.active = true;
        this.messageCount = 0;
        this.rotationCounter = 0;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public String getPeerId() {
        return peerId;
    }

    @Override
    public byte[] getEncryptionKey() {
        return encryptionKey;
    }

    @Override
    public byte[] getHmacKey() {
        return hmacKey;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    /**
     * Refreshes session activity and increments message count.
     *
     * SECURITY FIX (v2.6): Checks for message count overflow and throws
     * exception if limit exceeded. Caller should rotate keys before this happens.
     *
     * @throws IllegalStateException if message count would exceed MAX_MESSAGE_COUNT
     */
    @Override
    public void refresh() {
        // SECURITY FIX (v2.6): Check for overflow before incrementing
        if (messageCount >= MAX_MESSAGE_COUNT) {
            throw new IllegalStateException(
                    "Session message count limit exceeded (" + MAX_MESSAGE_COUNT +
                    "). Key rotation required to prevent nonce reuse.");
        }
        lastActivity = Instant.now();
        messageCount++;
    }

    /**
     * Checks if session is approaching message count limit.
     * SECURITY FIX (v2.6): Returns true when 90% of limit is reached.
     *
     * @return true if session should be rotated soon
     */
    public boolean isNearingMessageLimit() {
        return messageCount >= (MAX_MESSAGE_COUNT * 9 / 10);
    }

    @Override
    public void invalidate() {
        active = false;

        // Securely zero out keys
        java.util.Arrays.fill(encryptionKey, (byte) 0);
        java.util.Arrays.fill(hmacKey, (byte) 0);
    }

    /**
     * Checks if session has expired based on timeout.
     */
    public boolean isExpired(Duration timeout) {
        return Duration.between(lastActivity, Instant.now()).compareTo(timeout) > 0;
    }

    /**
     * Gets the age of this session.
     */
    public Duration getAge() {
        return Duration.between(createdAt, Instant.now());
    }

    /**
     * Gets the time since last activity.
     */
    public Duration getIdleTime() {
        return Duration.between(lastActivity, Instant.now());
    }

    /**
     * Gets total message count for this session.
     */
    public long getMessageCount() {
        return messageCount;
    }

    /**
     * Gets session creation time.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Gets last activity time.
     */
    public Instant getLastActivity() {
        return lastActivity;
    }

    /**
     * Checks if session needs key rotation.
     * Rotation recommended after:
     * - 1 hour of session lifetime
     * - 10,000 messages
     */
    public boolean needsKeyRotation() {
        Duration age = getAge();
        return age.toHours() >= 1 || messageCount >= 10000;
    }

    /**
     * Rotates session keys using HKDF.
     *
     * @return new session with rotated keys
     */
    public SecureSession rotateKeys() throws Exception {
        rotationCounter++;

        // Derive new keys from current keys
        byte[] newEncKey = EcdhKeyDerivation.deriveRotatedKey(
                encryptionKey,
                rotationCounter,
                EcdhKeyDerivation.PURPOSE_ENCRYPTION
        );

        byte[] newHmacKey = EcdhKeyDerivation.deriveRotatedKey(
                hmacKey,
                rotationCounter,
                EcdhKeyDerivation.PURPOSE_HMAC
        );

        // Create new session with rotated keys
        SecureSession newSession = new SecureSession(
                peerId,
                sessionId + "-r" + rotationCounter,
                newEncKey,
                newHmacKey
        );

        newSession.rotationCounter = this.rotationCounter;

        return newSession;
    }

    /**
     * Gets session statistics for monitoring.
     */
    public SessionStats getStats() {
        return new SessionStats(
                sessionId,
                peerId,
                active,
                createdAt,
                lastActivity,
                messageCount,
                rotationCounter,
                getAge(),
                getIdleTime()
        );
    }

    @Override
    public String toString() {
        return String.format("SecureSession[id=%s, peer=%s, active=%s, messages=%d, age=%s]",
                sessionId.substring(0, 8) + "...",
                peerId,
                active,
                messageCount,
                getAge()
        );
    }

    /**
     * Session statistics record.
     */
    public record SessionStats(
            String sessionId,
            String peerId,
            boolean active,
            Instant createdAt,
            Instant lastActivity,
            long messageCount,
            long rotationCounter,
            Duration age,
            Duration idleTime
    ) {}
}