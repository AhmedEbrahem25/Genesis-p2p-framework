package com.genesis.p2p.security.api;

/**
 * Session manager interface with enhanced functionality.
 *
 * Manages secure sessions including:
 * - Session creation from shared secrets
 * - Session retrieval and validation
 * - Session expiration and cleanup
 * - Key rotation for long-lived sessions
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public interface ISessionManager {

    /**
     * Creates a new secure session from an ECDH shared secret.
     *
     * The session manager will use HKDF to derive:
     * - Encryption key (for AES-GCM)
     * - HMAC key (for message authentication)
     * - Session ID (for identification)
     *
     * @param localPeerId local peer identifier
     * @param remotePeerId remote peer identifier
     * @param sharedSecret raw ECDH shared secret (will be zeroed after use)
     * @return new secure session with derived keys
     * @throws Exception if session creation fails
     */
    ISecureSession createSession(String localPeerId, String remotePeerId, byte[] sharedSecret)
            throws Exception;

    /**
     * Retrieves an existing session for a peer.
     *
     * Returns null if:
     * - No session exists for the peer
     * - Session has expired
     * - Session has been invalidated
     *
     * @param peerId the peer identifier
     * @return active session or null
     */
    ISecureSession getSession(String peerId);

    /**
     * Invalidates and removes a session.
     *
     * This will:
     * - Mark the session as inactive
     * - Zero out cryptographic keys
     * - Remove from session store
     *
     * Safe to call even if session doesn't exist.
     *
     * @param peerId the peer identifier
     */
    void invalidateSession(String peerId);

    /**
     * Removes all expired sessions.
     *
     * Should be called periodically to clean up resources.
     * Sessions are considered expired based on the configured timeout
     * and their last activity timestamp.
     */
    void cleanupExpiredSessions();

    // ==================== Key Rotation Methods ====================

    /**
     * Rotates keys for an existing session.
     *
     * Creates a new session with fresh keys derived from the current
     * session's keys using HKDF. The old session is invalidated.
     *
     * @param peerId the peer identifier
     * @return true if rotation successful, false if no session exists
     */
    default boolean rotateSession(String peerId) {
        // Default: not supported
        return false;
    }

    /**
     * Gets all active peer IDs with sessions.
     *
     * @return iterable of peer IDs with active sessions
     */
    default Iterable<String> getActivePeerIds() {
        return java.util.Collections.emptyList();
    }

    /**
     * Checks if a session exists for a peer.
     *
     * @param peerId the peer identifier
     * @return true if active session exists
     */
    default boolean hasSession(String peerId) {
        return getSession(peerId) != null;
    }
}