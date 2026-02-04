package com.genesis.p2p.security.channel;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.security.cert.Certificate;
import com.genesis.p2p.security.cert.CertificateManager;
import com.genesis.p2p.util.common.Time;

import javax.crypto.KeyAgreement;
import java.security.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Secure Channel Initializer (similar to TLS handshake for P2P).
 *
 * Establishes encrypted communication channels between peers using:
 * - ECDH (Elliptic Curve Diffie-Hellman) key exchange
 * - Certificate-based authentication
 * - Perfect Forward Secrecy (PFS)
 * - Session key derivation
 *
 * Handshake Flow:
 * 1. Peer A sends: Certificate + ECDH public key
 * 2. Peer B validates certificate
 * 3. Peer B sends: Certificate + ECDH public key
 * 4. Both derive shared secret using ECDH
 * 5. Derive session keys from shared secret
 * 6. Start encrypted communication
 *
 * Features:
 * - Perfect Forward Secrecy (ephemeral keys)
 * - Certificate authentication
 * - Replay protection (via nonces)
 * - Session key rotation
 *
 * NEW in v2.3: Atomic Post-Exchange Trigger
 * - Callback invoked immediately after successful key exchange
 * - Enables immediate HandshakeRequest dispatch over secure channel
 * - Eliminates timing window that causes TCP_ERROR_CLASSIFIED deadlocks
 *
 * @author Genesis P2P Framework
 * @version 2.3
 */
public class SecureChannelInitializer {

    private static final NodeLogger log = NodeLogger.getLogger(SecureChannelInitializer.class);

    private static final String KEY_AGREEMENT_ALGORITHM = "ECDH";
    private static final String KEY_DERIVATION_ALGORITHM = "SHA-256";
    private static final int SESSION_KEY_SIZE = 32; // 256 bits

    // SECURITY FIX (v2.6): TTL-based cleanup for abandoned pending channels
    private static final long PENDING_CHANNEL_TTL_MS = 30_000; // 30 seconds
    private static final long CLEANUP_INTERVAL_MS = 10_000; // Run cleanup every 10 seconds

    private final CertificateManager certificateManager;
    private final Map<String, SecureChannel> activeChannels;

    // SECURITY FIX (v2.6): Cleanup scheduler and metrics
    private final ScheduledExecutorService cleanupScheduler;
    private final AtomicLong expiredPendingsRemoved = new AtomicLong(0);

    // POST-EXCHANGE TRIGGER (v2.3): Atomic callback after successful key exchange
    private volatile PostExchangeTrigger postExchangeTrigger;

    /**
     * Post-Exchange Trigger Callback Interface.
     *
     * Invoked atomically after successful secure channel establishment
     * to immediately dispatch a HandshakeRequest over the newly created channel.
     *
     * This eliminates the timing window where peers have a secure channel but
     * haven't initiated the application handshake, preventing TCP_ERROR_CLASSIFIED
     * deadlocks in simultaneous open scenarios.
     */
    @FunctionalInterface
    public interface PostExchangeTrigger {
        /**
         * Triggered when secure channel establishment completes successfully.
         *
         * @param remotePeerId the peer with whom the channel was established
         * @param sessionId the established session ID
         */
        void onChannelEstablished(String remotePeerId, String sessionId);
    }

    /**
     * Creates a secure channel initializer.
     *
     * SECURITY FIX (v2.6): Starts background cleanup task for expired pending channels.
     */
    public SecureChannelInitializer(CertificateManager certificateManager) {
        this.certificateManager = certificateManager;
        this.activeChannels = new ConcurrentHashMap<>();

        // SECURITY FIX (v2.6): Start cleanup scheduler for expired pending channels
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SecureChannelInitializer-Cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupScheduler.scheduleAtFixedRate(
                this::cleanupExpiredPendings,
                CLEANUP_INTERVAL_MS,
                CLEANUP_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        log.info("SecureChannelInitializer created",
                "pendingTtlMs", PENDING_CHANNEL_TTL_MS,
                "cleanupIntervalMs", CLEANUP_INTERVAL_MS);
    }

    /**
     * Sets the post-exchange trigger for atomic handshake dispatch.
     *
     * CRITICAL: This callback is invoked IMMEDIATELY after successful channel
     * establishment to dispatch the HandshakeRequest, eliminating the timing
     * window that causes deadlocks in simultaneous open scenarios.
     *
     * @param trigger the callback to invoke on successful channel establishment
     */
    public void setPostExchangeTrigger(PostExchangeTrigger trigger) {
        this.postExchangeTrigger = trigger;
        log.info("PostExchangeTrigger configured for SecureChannelInitializer",
                "triggerClass", trigger != null ? trigger.getClass().getSimpleName() : "null");
    }

    /**
     * Initiates secure channel establishment (client side).
     *
     * @param remotePeerId the remote peer identifier
     * @return channel initiation data to send to peer
     * @throws SecureChannelException if initiation fails
     */
    public ChannelInitiation initiateChannel(String remotePeerId)
            throws SecureChannelException {

        log.info("Initiating secure channel", "remotePeer", remotePeerId);

        try {
            // Get local certificate
            Certificate localCert = certificateManager.getLocalCertificate();
            if (localCert == null) {
                throw new SecureChannelException("No local certificate available");
            }

            // Generate ephemeral ECDH key pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(256, new SecureRandom());
            KeyPair ephemeralKeyPair = keyGen.generateKeyPair();

            // Create nonce for replay protection
            byte[] nonce = new byte[32];
            new SecureRandom().nextBytes(nonce);

            // Store ephemeral key for later
            String sessionId = generateSessionId(localCert.getNodeId(), remotePeerId);
            PendingChannel pending = new PendingChannel(
                    sessionId,
                    ephemeralKeyPair,
                    nonce,
                    Time.currentMillis()
            );
            activePendings.put(sessionId, pending);

            log.debug("Channel initiation created",
                    "sessionId", sessionId,
                    "nonce", Base64.getEncoder().encodeToString(nonce).substring(0, 8));

            return new ChannelInitiation(
                    localCert,
                    ephemeralKeyPair.getPublic(),
                    nonce
            );

        } catch (Exception e) {
            log.error("Failed to initiate channel", e, "remotePeer", remotePeerId);
            throw new SecureChannelException("Channel initiation failed", e);
        }
    }

    /**
     * Accepts channel initiation (server side).
     *
     * @param initiation the initiation received from peer
     * @return channel response to send back
     * @throws SecureChannelException if acceptance fails
     */
    public ChannelResponse acceptChannel(ChannelInitiation initiation)
            throws SecureChannelException {

        String remotePeerId = initiation.getCertificate().getNodeId();
        log.info("Accepting secure channel", "remotePeer", remotePeerId);

        try {
            // Validate remote certificate
            var validationResult = certificateManager.validatePeerCertificate(
                    initiation.getCertificate());

            if (!validationResult.isValid()) {
                throw new SecureChannelException(
                        "Certificate validation failed: " + validationResult.getErrorSummary());
            }

            // Generate ephemeral ECDH key pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(256, new SecureRandom());
            KeyPair ephemeralKeyPair = keyGen.generateKeyPair();

            // Perform ECDH key agreement
            KeyAgreement keyAgree = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM);
            keyAgree.init(ephemeralKeyPair.getPrivate());
            keyAgree.doPhase(initiation.getEphemeralPublicKey(), true);
            byte[] sharedSecret = keyAgree.generateSecret();

            // Derive session key
            byte[] sessionKey = deriveSessionKey(
                    sharedSecret,
                    initiation.getNonce()
            );

            // Create secure channel
            String sessionId = generateSessionId(
                    certificateManager.getLocalCertificate().getNodeId(),
                    remotePeerId
            );

            SecureChannel channel = new SecureChannel(
                    sessionId,
                    remotePeerId,
                    sessionKey,
                    initiation.getNonce()
            );

            activeChannels.put(sessionId, channel);

            log.info("Secure channel established",
                    "remotePeer", remotePeerId,
                    "sessionId", sessionId);

            // ═════════════════════════════════════════════════════════════════════════
            // ATOMIC POST-EXCHANGE TRIGGER: Immediate HandshakeRequest Dispatch
            // ═════════════════════════════════════════════════════════════════════════
            // Invoke callback IMMEDIATELY after successful channel establishment
            // to eliminate timing window that causes TCP_ERROR_CLASSIFIED deadlocks
            // in simultaneous open scenarios.
            if (postExchangeTrigger != null) {
                try {
                    log.debug("INVOKING_POST_EXCHANGE_TRIGGER",
                            "remotePeer", remotePeerId,
                            "sessionId", sessionId,
                            "phase", "ACCEPT_CHANNEL");

                    postExchangeTrigger.onChannelEstablished(remotePeerId, sessionId);

                    log.info("POST_EXCHANGE_TRIGGER_SUCCESS",
                            "remotePeer", remotePeerId,
                            "sessionId", sessionId,
                            "handshakeDispatched", true);

                } catch (Exception triggerEx) {
                    // Log but don't fail channel establishment - handshake can be retried
                    log.error("POST_EXCHANGE_TRIGGER_FAILED",
                            "remotePeer", remotePeerId,
                            "sessionId", sessionId,
                            "error", triggerEx.getMessage(),
                            "stackTrace", triggerEx);
                    // Don't throw - channel is still established
                }
            } else {
                log.debug("POST_EXCHANGE_TRIGGER_NOT_CONFIGURED",
                        "remotePeer", remotePeerId,
                        "sessionId", sessionId,
                        "hint", "Manual handshake initiation required");
            }

            // Create response
            Certificate localCert = certificateManager.getLocalCertificate();
            return new ChannelResponse(
                    localCert,
                    ephemeralKeyPair.getPublic(),
                    sessionId
            );

        } catch (Exception e) {
            log.error("Failed to accept channel", e, "remotePeer", remotePeerId);
            throw new SecureChannelException("Channel acceptance failed", e);
        }
    }

    /**
     * Completes channel establishment (client side after receiving response).
     *
     * @param response the response from peer
     * @return established secure channel
     * @throws SecureChannelException if completion fails
     */
    public SecureChannel completeChannel(ChannelResponse response)
            throws SecureChannelException {

        String remotePeerId = response.getCertificate().getNodeId();
        log.info("Completing secure channel", "remotePeer", remotePeerId);

        try {
            // Find pending channel
            String sessionId = response.getSessionId();
            PendingChannel pending = activePendings.remove(sessionId);

            if (pending == null) {
                throw new SecureChannelException("No pending channel found: " + sessionId);
            }

            // Validate remote certificate
            var validationResult = certificateManager.validatePeerCertificate(
                    response.getCertificate());

            if (!validationResult.isValid()) {
                throw new SecureChannelException(
                        "Certificate validation failed: " + validationResult.getErrorSummary());
            }

            // Perform ECDH key agreement
            KeyAgreement keyAgree = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM);
            keyAgree.init(pending.ephemeralKeyPair.getPrivate());
            keyAgree.doPhase(response.getEphemeralPublicKey(), true);
            byte[] sharedSecret = keyAgree.generateSecret();

            // Derive session key (same as server)
            byte[] sessionKey = deriveSessionKey(sharedSecret, pending.nonce);

            // Create secure channel
            SecureChannel channel = new SecureChannel(
                    sessionId,
                    remotePeerId,
                    sessionKey,
                    pending.nonce
            );

            activeChannels.put(sessionId, channel);

            log.info("Secure channel completed",
                    "remotePeer", remotePeerId,
                    "sessionId", sessionId);

            // ═════════════════════════════════════════════════════════════════════════
            // ATOMIC POST-EXCHANGE TRIGGER: Immediate HandshakeRequest Dispatch
            // ═════════════════════════════════════════════════════════════════════════
            // Invoke callback IMMEDIATELY after successful channel completion
            // to eliminate timing window that causes TCP_ERROR_CLASSIFIED deadlocks
            // in simultaneous open scenarios.
            if (postExchangeTrigger != null) {
                try {
                    log.debug("INVOKING_POST_EXCHANGE_TRIGGER",
                            "remotePeer", remotePeerId,
                            "sessionId", sessionId,
                            "phase", "COMPLETE_CHANNEL");

                    postExchangeTrigger.onChannelEstablished(remotePeerId, sessionId);

                    log.info("POST_EXCHANGE_TRIGGER_SUCCESS",
                            "remotePeer", remotePeerId,
                            "sessionId", sessionId,
                            "handshakeDispatched", true);

                } catch (Exception triggerEx) {
                    // Log but don't fail channel completion - handshake can be retried
                    log.error("POST_EXCHANGE_TRIGGER_FAILED",
                            "remotePeer", remotePeerId,
                            "sessionId", sessionId,
                            "error", triggerEx.getMessage(),
                            "stackTrace", triggerEx);
                    // Don't throw - channel is still completed
                }
            } else {
                log.debug("POST_EXCHANGE_TRIGGER_NOT_CONFIGURED",
                        "remotePeer", remotePeerId,
                        "sessionId", sessionId,
                        "hint", "Manual handshake initiation required");
            }

            return channel;

        } catch (Exception e) {
            log.error("Failed to complete channel", e, "remotePeer", remotePeerId);
            throw new SecureChannelException("Channel completion failed", e);
        }
    }

    /**
     * Gets an active secure channel.
     */
    public SecureChannel getChannel(String sessionId) {
        return activeChannels.get(sessionId);
    }

    /**
     * Closes a secure channel.
     */
    public void closeChannel(String sessionId) {
        SecureChannel channel = activeChannels.remove(sessionId);
        if (channel != null) {
            log.info("Secure channel closed", "sessionId", sessionId);
        }
    }

    /**
     * Cleans up expired pending channels.
     *
     * SECURITY FIX (v2.6): Removes pending channels that haven't completed
     * within the TTL to prevent memory leaks and resource exhaustion.
     */
    private void cleanupExpiredPendings() {
        try {
            long now = Time.currentMillis();
            int removed = 0;

            var iterator = activePendings.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                PendingChannel pending = entry.getValue();

                long age = now - pending.timestamp;
                if (age > PENDING_CHANNEL_TTL_MS) {
                    iterator.remove();
                    removed++;
                    expiredPendingsRemoved.incrementAndGet();

                    log.warn("EXPIRED_PENDING_CHANNEL_REMOVED",
                            "sessionId", pending.sessionId,
                            "ageMs", age,
                            "ttlMs", PENDING_CHANNEL_TTL_MS);
                }
            }

            if (removed > 0) {
                log.info("Cleaned up expired pending channels",
                        "removed", removed,
                        "remaining", activePendings.size(),
                        "totalExpiredRemoved", expiredPendingsRemoved.get());
            }

        } catch (Exception e) {
            log.error("Error cleaning up expired pending channels", e);
        }
    }

    /**
     * Shuts down the cleanup scheduler.
     * Call this when the initializer is no longer needed.
     */
    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("SecureChannelInitializer shutdown complete",
                "totalExpiredRemoved", expiredPendingsRemoved.get());
    }

    /**
     * Gets the count of pending channels currently being tracked.
     */
    public int getPendingCount() {
        return activePendings.size();
    }

    /**
     * Gets the total number of expired pending channels removed.
     */
    public long getExpiredPendingsRemoved() {
        return expiredPendingsRemoved.get();
    }

    /**
     * Derives session key from shared secret and nonce.
     */
    private byte[] deriveSessionKey(byte[] sharedSecret, byte[] nonce)
            throws NoSuchAlgorithmException {

        MessageDigest digest = MessageDigest.getInstance(KEY_DERIVATION_ALGORITHM);
        digest.update(sharedSecret);
        digest.update(nonce);
        byte[] hash = digest.digest();

        // Take first SESSION_KEY_SIZE bytes
        return Arrays.copyOf(hash, SESSION_KEY_SIZE);
    }

    /**
     * Generates session ID.
     */
    private String generateSessionId(String localId, String remoteId) {
        // Ensure consistent ordering
        String combined = localId.compareTo(remoteId) < 0 ?
                localId + "-" + remoteId : remoteId + "-" + localId;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes());
            return Base64.getEncoder().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return combined.substring(0, Math.min(16, combined.length()));
        }
    }

    // Pending channels (awaiting completion)
    private final Map<String, PendingChannel> activePendings = new ConcurrentHashMap<>();

    private static class PendingChannel {
        final String sessionId;
        final KeyPair ephemeralKeyPair;
        final byte[] nonce;
        final long timestamp;

        PendingChannel(String sessionId, KeyPair ephemeralKeyPair,
                      byte[] nonce, long timestamp) {
            this.sessionId = sessionId;
            this.ephemeralKeyPair = ephemeralKeyPair;
            this.nonce = nonce;
            this.timestamp = timestamp;
        }
    }

    /**
     * Channel initiation data.
     */
    public static class ChannelInitiation {
        private final Certificate certificate;
        private final PublicKey ephemeralPublicKey;
        private final byte[] nonce;

        public ChannelInitiation(Certificate certificate, PublicKey ephemeralPublicKey, byte[] nonce) {
            this.certificate = certificate;
            this.ephemeralPublicKey = ephemeralPublicKey;
            this.nonce = nonce;
        }

        public Certificate getCertificate() { return certificate; }
        public PublicKey getEphemeralPublicKey() { return ephemeralPublicKey; }
        public byte[] getNonce() { return nonce; }
    }

    /**
     * Channel response data.
     */
    public static class ChannelResponse {
        private final Certificate certificate;
        private final PublicKey ephemeralPublicKey;
        private final String sessionId;

        public ChannelResponse(Certificate certificate, PublicKey ephemeralPublicKey, String sessionId) {
            this.certificate = certificate;
            this.ephemeralPublicKey = ephemeralPublicKey;
            this.sessionId = sessionId;
        }

        public Certificate getCertificate() { return certificate; }
        public PublicKey getEphemeralPublicKey() { return ephemeralPublicKey; }
        public String getSessionId() { return sessionId; }
    }

    /**
     * Secure channel exception.
     */
    public static class SecureChannelException extends Exception {
        public SecureChannelException(String message) {
            super(message);
        }

        public SecureChannelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

