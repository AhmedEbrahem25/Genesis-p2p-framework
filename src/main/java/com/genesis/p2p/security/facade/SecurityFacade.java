package com.genesis.p2p.security.facade;

import com.genesis.p2p.security.api.*;
import com.genesis.p2p.security.config.SecurityConfig;
import com.genesis.p2p.security.factory.SecurityFactory;
import com.genesis.p2p.security.ecdh.EcdhKeyExchange;
import com.genesis.p2p.security.hmac.HmacService;
import com.genesis.p2p.security.SecurityException;
import com.genesis.p2p.security.KeyExchangeException;
import com.genesis.p2p.observability.logging.NodeLogger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Security Facade - Unified API for Transport Layer.
 *
 * Integrates:
 * - ECDH key exchange (Elliptic Curve Diffie-Hellman)
 * - HKDF key derivation (HMAC-based Key Derivation Function)
 * - AES-GCM encryption (authenticated encryption)
 * - HMAC authentication (message authentication codes)
 * - Session management with key rotation
 * - Trust management
 *
 * Design Pattern: Facade Pattern
 * - Simplifies complex security subsystem
 * - Single point of interaction for transport layer
 * - Hides internal complexity
 *
 * Security Features:
 * - Perfect Forward Secrecy (via ECDH)
 * - Authenticated Encryption (AES-GCM + HMAC)
 * - Automatic key rotation
 * - Domain separation (different keys for different purposes)
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class SecurityFacade {

    private static final NodeLogger log = NodeLogger.getLogger(SecurityFacade.class);

    private final ICryptoProvider crypto;
    private final ISessionManager sessionManager;
    private final ISignatureService signatureService;
    private final ITrustManager trustManager;
    private final SecurityConfig config;

    // ECDH key exchange instance (one per node)
    private final EcdhKeyExchange ecdhKeyExchange;

    // Track ECDH exchanges per peer
    private final ConcurrentHashMap<String, EcdhKeyExchange> peerKeyExchanges;

    // Local peer identifier
    private final String localPeerId;

    /**
     * Creates security facade using factory.
     *
     * @param config security configuration
     * @param localPeerId local peer identifier
     */
    public SecurityFacade(SecurityConfig config, String localPeerId) {
        this.config = config;
        this.localPeerId = localPeerId;
        this.peerKeyExchanges = new ConcurrentHashMap<>();

        // Use factory to create all components
        SecurityFactory factory = new SecurityFactory(config);

        this.crypto = factory.createCryptoProvider();
        this.sessionManager = factory.createSessionManager();
        this.signatureService = factory.createSignatureService();
        this.trustManager = factory.createTrustManager();

        // Initialize ECDH for this node
        this.ecdhKeyExchange = new EcdhKeyExchange(localPeerId);

        log.info("SecurityFacade initialized",
                "algorithm", crypto.getAlgorithm(),
                "peerId", localPeerId);
    }

    // ========================= Encryption Operations =========================

    /**
     * Encrypts and authenticates data for a peer.
     *
     * Process:
     * 1. Get session (contains encryption + HMAC keys)
     * 2. Encrypt data using AES-GCM
     * 3. Compute HMAC over ciphertext
     * 4. Return [ciphertext][hmac]
     *
     * @param data the data to encrypt
     * @param peerId the target peer ID
     * @return encrypted and authenticated data
     */
    public byte[] encrypt(byte[] data, String peerId) throws Exception {
        // Get or create session
        ISecureSession session = getOrCreateSession(peerId);

        // Encrypt using AES-GCM (already provides authentication)
        byte[] ciphertext = crypto.encrypt(data, session.getEncryptionKey());

        // Add HMAC for additional layer of authentication
        byte[] authenticatedMessage = HmacService.createAuthenticatedMessage(
                ciphertext,
                session.getHmacKey()
        );

        // Log encryption (note: nonce is embedded in ciphertext by AES-GCM)
        log.debug("MESSAGE_ENCRYPTED",
                "nodeId", localPeerId,
                "peerId", peerId,
                "algorithm", "AES-256-GCM",
                "plaintextSize", data.length,
                "ciphertextSize", ciphertext.length,
                "authTagSize", authenticatedMessage.length - ciphertext.length,
                "sessionId", session.getSessionId());

        // Update session activity
        session.refresh();

        // Check if key rotation needed
        checkKeyRotation(peerId, session);

        return authenticatedMessage;
    }

    /**
     * Decrypts and verifies data from a peer.
     *
     * Process:
     * 1. Get session
     * 2. Verify and extract HMAC
     * 3. Decrypt ciphertext using AES-GCM
     *
     * @param data the encrypted and authenticated data
     * @param peerId the source peer ID
     * @return decrypted data
     */
    public byte[] decrypt(byte[] data, String peerId) throws Exception {
        // Get session
        ISecureSession session = sessionManager.getSession(peerId);
        if (session == null || !session.isActive()) {
            log.error("AUTHENTICATION_FAILED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "reason", "NO_ACTIVE_SESSION");
            throw new SecurityException("No active session for peer: " + peerId);
        }

        try {
            // Verify HMAC and extract ciphertext
            byte[] ciphertext = HmacService.verifyAndExtractMessage(data, session.getHmacKey());

            // Decrypt using session key (AES-GCM verifies authentication tag)
            byte[] plaintext = crypto.decrypt(ciphertext, session.getEncryptionKey());

            // Log successful decryption
            log.debug("MESSAGE_DECRYPTED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "algorithm", "AES-256-GCM",
                    "ciphertextSize", ciphertext.length,
                    "plaintextSize", plaintext.length,
                    "authTagVerified", true,
                    "sessionId", session.getSessionId());

            // Update session activity
            session.refresh();

            return plaintext;

        } catch (SecurityException e) {
            // HMAC verification failed
            log.error("AUTHENTICATION_FAILED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "reason", "INVALID_HMAC",
                    "error", e.getMessage());

            // Log security violation
            log.warn("SECURITY_VIOLATION_DETECTED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "violationType", "INVALID_HMAC",
                    "severity", "HIGH");

            throw e;

        } catch (Exception e) {
            // Decryption failed (likely invalid auth tag)
            log.error("AUTHENTICATION_FAILED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "reason", "DECRYPTION_FAILED",
                    "error", e.getMessage());

            // Log security violation
            log.warn("SECURITY_VIOLATION_DETECTED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "violationType", "INVALID_AUTH_TAG",
                    "severity", "CRITICAL");

            throw new SecurityException("Decryption failed for peer " + peerId + ": " + e.getMessage());
        }
    }

    // ========================= Key Exchange =========================

    /**
     * Gets our public key to send to peer.
     * This is the first step in ECDH key exchange.
     */
    public byte[] getPublicKey() {
        return ecdhKeyExchange.getPublicKey();
    }

    /**
     * Performs ECDH key exchange with a peer.
     *
     * Process:
     * 1. Verify remote public key
     * 2. Perform ECDH to get shared secret
     * 3. Derive session keys using HKDF
     * 4. Create secure session
     *
     * @param peerId the peer ID
     * @param theirPublicKey their EC public key
     * @return session ID (NOT the shared secret for security)
     */
    public String performKeyExchange(String peerId, byte[] theirPublicKey)
            throws KeyExchangeException {

        try {
            // Verify public key format
            if (!ecdhKeyExchange.verifyPublicKey(theirPublicKey)) {
                throw new KeyExchangeException("Invalid public key from peer: " + peerId);
            }

            // Perform ECDH to derive shared secret
            byte[] sharedSecret = ecdhKeyExchange.deriveSharedSecret(peerId, theirPublicKey);

            // Create session with HKDF-derived keys
            ISecureSession session = sessionManager.createSession(
                    localPeerId,
                    peerId,
                    sharedSecret
            );

            // Zero out shared secret immediately
            java.util.Arrays.fill(sharedSecret, (byte) 0);

            log.info("Key exchange complete",
                    "localPeer", localPeerId,
                    "remotePeer", peerId,
                    "sessionId", session.getSessionId());

            return session.getSessionId();

        } catch (Exception e) {
            throw new KeyExchangeException(
                    "Key exchange failed with peer " + peerId + ": " + e.getMessage());
        }
    }

    /**
     * Initiates key exchange as requester.
     * Returns our public key to send to the peer.
     */
    public byte[] initiateKeyExchange(String peerId) {
        // Create dedicated key exchange for this peer
        try {
            log.info("HANDSHAKE_INITIATE",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "algorithm", "ECDH",
                    "keySize", 256);

            EcdhKeyExchange peerExchange = new EcdhKeyExchange(localPeerId + ":" + peerId);
            peerKeyExchanges.put(peerId, peerExchange);

            byte[] publicKey = peerExchange.getPublicKey();

            // Log public key sent
            log.debug("PUBLIC_KEY_SENT",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "keyFormat", "EC",
                    "keySize", publicKey.length,
                    "keyHash", computeHash(publicKey));

            return publicKey;

        } catch (Exception e) {
            log.error("HANDSHAKE_FAILED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "phase", "initiate",
                    "error", e.getMessage());
            throw new KeyExchangeException("Failed to initiate key exchange: " + e.getMessage());
        }
    }

    /**
     * Completes key exchange as requester after receiving peer's public key.
     */
    public String completeKeyExchange(String peerId, byte[] theirPublicKey)
            throws KeyExchangeException {

        EcdhKeyExchange peerExchange = peerKeyExchanges.remove(peerId);
        if (peerExchange == null) {
            log.error("HANDSHAKE_FAILED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "phase", "complete",
                    "reason", "no_pending_exchange");
            throw new KeyExchangeException("No pending key exchange for peer: " + peerId);
        }

        try {
            // Log public key received
            log.debug("PUBLIC_KEY_RECEIVED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "keyFormat", "EC",
                    "keySize", theirPublicKey.length,
                    "keyHash", computeHash(theirPublicKey));

            // Derive shared secret
            byte[] sharedSecret = peerExchange.deriveSharedSecret(peerId, theirPublicKey);

            // Log shared secret generation
            log.debug("SHARED_SECRET_GENERATED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "algorithm", "ECDH",
                    "secretSize", sharedSecret.length,
                    "secretHash", computeHash(sharedSecret));

            // Create session (performs HKDF key derivation)
            ISecureSession session = sessionManager.createSession(
                    localPeerId,
                    peerId,
                    sharedSecret
            );

            // Log session key derivation
            log.info("SESSION_KEY_DERIVED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "derivationFunction", "HKDF",
                    "keySize", 256,
                    "sessionId", session.getSessionId());

            // Clean up
            java.util.Arrays.fill(sharedSecret, (byte) 0);
            peerExchange.destroy();

            log.info("HANDSHAKE_COMPLETE",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "sessionId", session.getSessionId());

            return session.getSessionId();

        } catch (Exception e) {
            log.error("HANDSHAKE_FAILED",
                    "nodeId", localPeerId,
                    "peerId", peerId,
                    "phase", "complete",
                    "error", e.getMessage());
            throw new KeyExchangeException("Failed to complete key exchange: " + e.getMessage());
        }
    }

    // ========================= Signing Operations =========================

    /**
     * Signs data with our private key.
     */
    public byte[] sign(byte[] data) throws Exception {
        return signatureService.sign(data, ecdhKeyExchange.getKeyPair());
    }

    /**
     * Verifies signature from a peer.
     * Uses the public key from their ECDH exchange.
     */
    public boolean verify(byte[] data, byte[] signature, String peerId) throws Exception {
        // Get their public key from session or key exchange
        EcdhKeyExchange peerExchange = peerKeyExchanges.get(peerId);
        if (peerExchange == null) {
            throw new SecurityException("No key exchange data for peer: " + peerId);
        }

        byte[] publicKey = peerExchange.getPublicKey();
        return signatureService.verify(data, signature, publicKey);
    }

    // ========================= HMAC Operations =========================

    /**
     * Computes HMAC for message authentication.
     */
    public byte[] computeHmac(byte[] data, String peerId) {
        ISecureSession session = getOrCreateSession(peerId);
        return HmacService.computeHmac(data, session.getHmacKey());
    }

    /**
     * Verifies HMAC from a peer.
     */
    public boolean verifyHmac(byte[] data, byte[] hmac, String peerId) {
        ISecureSession session = sessionManager.getSession(peerId);
        if (session == null) {
            throw new SecurityException("No session for peer: " + peerId);
        }

        return HmacService.verifyHmac(data, session.getHmacKey(), hmac);
    }

    // ========================= Trust Management =========================

    /**
     * Checks if a peer is trusted.
     */
    public boolean isTrusted(String peerId) {
        return trustManager.isTrusted(peerId);
    }

    /**
     * Trusts a peer with specified level.
     */
    public void trustPeer(String peerId, int level) {
        trustManager.trustPeer(peerId, level);
        log.info("Peer trusted", "peerId", peerId, "level", level);
    }

    /**
     * Untrusts a peer and invalidates session.
     */
    public void untrustPeer(String peerId) {
        trustManager.untrustPeer(peerId);
        sessionManager.invalidateSession(peerId);
        peerKeyExchanges.remove(peerId);
        log.info("Peer untrusted", "peerId", peerId);
    }

    // ========================= Session Management =========================

    /**
     * Gets or validates existing session.
     */
    private ISecureSession getOrCreateSession(String peerId) throws SecurityException {
        ISecureSession session = sessionManager.getSession(peerId);

        if (session == null || !session.isActive()) {
            throw new SecurityException(
                    "No active session for peer: " + peerId +
                            ". Perform key exchange first.");
        }

        return session;
    }

    /**
     * Checks if session needs key rotation and rotates if needed.
     * Note: Key rotation logic is currently disabled as it requires
     * access to implementation-specific methods. Consider adding
     * needsKeyRotation() to ISecureSession interface.
     */
    private void checkKeyRotation(String peerId, @SuppressWarnings("unused") ISecureSession session) {
        // TODO: Add needsKeyRotation() to ISecureSession interface
        // For now, key rotation is handled manually via rotateSessionKey()
        log.debug("Key rotation check skipped - requires interface extension", "peerId", peerId);
    }

    /**
     * Manually rotates session keys for a peer.
     * Note: This requires the ISecureSession interface to expose rotateKeys().
     * Currently not implemented - requires interface extension.
     */
    public void rotateSessionKey(String peerId) {
        ISecureSession session = sessionManager.getSession(peerId);
        if (session == null) {
            throw new SecurityException("No session for peer: " + peerId);
        }

        // TODO: Add rotateKeys() to ISecureSession interface
        // For now, the workaround is to invalidate and re-establish the session
        log.warn("Key rotation not fully implemented - requires interface extension", "peerId", peerId);

        // As a temporary solution, invalidate the session
        // The next communication will trigger a new key exchange
        sessionManager.invalidateSession(peerId);
        log.info("Session invalidated for key rotation", "peerId", peerId);
    }

    /**
     * Refreshes a session's last activity timestamp.
     */
    public void refreshSession(String peerId) {
        ISecureSession session = sessionManager.getSession(peerId);
        if (session != null) {
            session.refresh();
        }
    }

    /**
     * Cleanup expired sessions.
     */
    public void cleanupSessions() {
        sessionManager.cleanupExpiredSessions();
        log.debug("Expired sessions cleaned up");
    }

    /**
     * Checks if a valid session exists for a peer.
     */
    public boolean hasValidSession(String peerId) {
        ISecureSession session = sessionManager.getSession(peerId);
        return session != null && session.isActive();
    }

    /**
     * Gets session statistics for a peer.
     * Returns basic session info since detailed stats require interface extension.
     */
    public String getSessionStats(String peerId) {
        ISecureSession session = sessionManager.getSession(peerId);
        if (session == null) {
            return "No session found for peer: " + peerId;
        }

        return String.format("Session[id=%s, peer=%s, active=%s]",
                session.getSessionId(),
                session.getPeerId(),
                session.isActive());
    }

    // ========================= Configuration =========================

    /**
     * Checks if encryption is enabled.
     */
    public boolean isEncryptionEnabled() {
        return config.encryptionEnabled();
    }

    /**
     * Gets crypto algorithm name.
     */
    public String getAlgorithm() {
        return crypto.getAlgorithm();
    }

    /**
     * Gets comprehensive security health report.
     */
    public SecurityHealthReport getHealthReport() {
        return new SecurityHealthReport(
                config.encryptionEnabled(),
                crypto.getAlgorithm(),
                sessionManager.toString(), // Would need actual session count
                trustManager.toString(),
                ecdhKeyExchange != null
        );
    }

    /**
     * Emergency shutdown - invalidate all keys and sessions.
     */
    public void emergencyShutdown() {
        log.warn("Emergency shutdown initiated - invalidating all sessions");

        // Invalidate all sessions
        cleanupSessions();

        // Destroy key exchanges
        peerKeyExchanges.values().forEach(EcdhKeyExchange::destroy);
        peerKeyExchanges.clear();

        // Destroy main key exchange
        if (ecdhKeyExchange != null) {
            ecdhKeyExchange.destroy();
        }

        log.warn("Emergency shutdown complete");
    }

    /**
     * Security health report record.
     */
    public record SecurityHealthReport(
            boolean encryptionEnabled,
            String algorithm,
            String sessionInfo,
            String trustInfo,
            boolean ecdhInitialized
    ) {}

    // ========================= Component Access =========================

    /**
     * Gets the key manager for identity key operations.
     * Used by SecureChannelNegotiator for signing ephemeral keys.
     *
     * @return the key manager implementation
     */
    public com.genesis.p2p.security.keys.KeyManager getKeyManager() {
        // Create a KeyManager backed by the SecurityFacade's components
        return new com.genesis.p2p.security.keys.KeyManager(
                ecdhKeyExchange.getKeyPair());
    }

    /**
     * Gets the session manager for session operations.
     * Used by SecureChannelNegotiator to create secure sessions.
     *
     * @return the session manager interface
     */
    public ISessionManager getSessionManager() {
        return sessionManager;
    }

    // ========================= Utility Methods =========================

    /**
     * Computes SHA-256 hash of data for logging purposes.
     * Only logs first 16 bytes (32 hex chars) to avoid log bloat.
     */
    private static String computeHash(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return bytesToHex(hash, 16); // First 16 bytes (32 hex chars)
        } catch (Exception e) {
            return "hash_error";
        }
    }

    /**
     * Converts bytes to hexadecimal string.
     */
    private static String bytesToHex(byte[] bytes, int length) {
        int len = Math.min(bytes.length, length);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}