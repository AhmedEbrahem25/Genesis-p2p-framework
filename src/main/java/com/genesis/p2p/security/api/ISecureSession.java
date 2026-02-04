package com.genesis.p2p.security.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Secure session interface.
 *
 * Defines the contract for secure communication sessions between peers.
 * Sessions maintain encryption and HMAC keys derived via ECDH + HKDF.
 *
 * @author Genesis P2P Framework
 * @version 2.1 - Added key rotation support
 */
public interface ISecureSession {

    // ==================== Core Methods ====================

    /** Gets unique session identifier */
    String getSessionId();

    /** Gets remote peer identifier */
    String getPeerId();

    /** Gets AES-GCM encryption key */
    byte[] getEncryptionKey();

    /** Gets HMAC authentication key */
    byte[] getHmacKey();

    /** Checks if session is active */
    boolean isActive();

    /** Refreshes session activity timestamp and increments message count */
    void refresh();

    /** Invalidates session and securely zeros keys */
    void invalidate();

    // ==================== Key Rotation Methods ====================

    /**
     * Checks if session needs key rotation.
     * Rotation is recommended after:
     * - 1 hour of session lifetime (time-based)
     * - 10,000 messages (count-based)
     * - 90% of max message count reached (security limit)
     *
     * @return true if rotation is recommended
     */
    default boolean needsKeyRotation() {
        return false;
    }

    /**
     * Gets the age of this session since creation.
     *
     * @return duration since session was created
     */
    default Duration getAge() {
        return Duration.ZERO;
    }

    /**
     * Gets total message count processed by this session.
     *
     * @return number of messages encrypted/decrypted
     */
    default long getMessageCount() {
        return 0;
    }

    /**
     * Gets session creation timestamp.
     *
     * @return creation instant
     */
    default Instant getCreatedAt() {
        return Instant.now();
    }

    /**
     * Gets last activity timestamp.
     *
     * @return last activity instant
     */
    default Instant getLastActivity() {
        return Instant.now();
    }

    /**
     * Checks if session is approaching security limits.
     *
     * @return true if near message count limit
     */
    default boolean isNearingMessageLimit() {
        return false;
    }
}
