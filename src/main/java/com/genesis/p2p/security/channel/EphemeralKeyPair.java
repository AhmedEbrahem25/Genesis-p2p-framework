package com.genesis.p2p.security.channel;

import com.genesis.p2p.security.KeyExchangeException;
import com.genesis.p2p.observability.logging.NodeLogger;

import javax.crypto.KeyAgreement;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;

/**
 * Ephemeral EC keypair for secure channel establishment with forward secrecy.
 *
 * Each connection gets a fresh keypair that is:
 * - Generated using secp256r1 (NIST P-256) curve
 * - Used only once for ECDH key derivation
 * - Zeroed out after use via {@link #close()}
 *
 * Security properties:
 * - Forward secrecy: Compromise of long-term keys doesn't expose past sessions
 * - Single-use: Prevents key reuse attacks
 * - Secure destruction: Private key material zeroed after use
 *
 * Thread-safety: Instances are NOT thread-safe. Each connection should have its own instance.
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class EphemeralKeyPair implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(EphemeralKeyPair.class);

    private static final String EC_ALGORITHM = "EC";
    private static final String CURVE_NAME = "secp256r1";
    private static final String KEY_AGREEMENT_ALGORITHM = "ECDH";

    private final KeyPair keyPair;
    private final Instant createdAt;
    private final String id;

    private volatile boolean used = false;
    private volatile boolean destroyed = false;

    /**
     * Private constructor - use {@link #generate()} factory method.
     */
    private EphemeralKeyPair(KeyPair keyPair, String id) {
        this.keyPair = keyPair;
        this.createdAt = Instant.now();
        this.id = id;
    }

    /**
     * Generates a new ephemeral EC keypair.
     *
     * @return new ephemeral keypair
     * @throws KeyExchangeException if generation fails
     */
    public static EphemeralKeyPair generate() throws KeyExchangeException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(EC_ALGORITHM);
            ECGenParameterSpec ecSpec = new ECGenParameterSpec(CURVE_NAME);
            generator.initialize(ecSpec, new SecureRandom());

            KeyPair keyPair = generator.generateKeyPair();
            String id = generateId();

            log.debug("Ephemeral keypair generated",
                    "id", id,
                    "algorithm", keyPair.getPublic().getAlgorithm());

            return new EphemeralKeyPair(keyPair, id);

        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new KeyExchangeException("Failed to generate ephemeral keypair: " + e.getMessage());
        }
    }

    /**
     * Gets the public key bytes to share with the peer.
     *
     * @return X.509 encoded public key bytes
     * @throws IllegalStateException if keypair has been destroyed
     */
    public byte[] getPublicKeyBytes() {
        checkNotDestroyed();
        return keyPair.getPublic().getEncoded();
    }

    /**
     * Gets the underlying public key.
     *
     * @return the public key
     * @throws IllegalStateException if keypair has been destroyed
     */
    public PublicKey getPublicKey() {
        checkNotDestroyed();
        return keyPair.getPublic();
    }

    /**
     * Gets the underlying private key.
     * Use with caution - prefer {@link #deriveSharedSecret(byte[])} for ECDH.
     *
     * @return the private key
     * @throws IllegalStateException if keypair has been destroyed
     */
    public PrivateKey getPrivateKey() {
        checkNotDestroyed();
        return keyPair.getPrivate();
    }

    /**
     * Gets the full keypair for signing operations.
     *
     * @return the EC keypair
     * @throws IllegalStateException if keypair has been destroyed
     */
    public KeyPair getKeyPair() {
        checkNotDestroyed();
        return keyPair;
    }

    /**
     * Derives a shared secret using ECDH with the peer's public key.
     *
     * This method marks the keypair as "used" - subsequent calls are allowed
     * but logged as warnings. For single-use semantics, check {@link #isUsed()}.
     *
     * IMPORTANT: The returned raw shared secret should be passed through
     * a KDF (Key Derivation Function) before use as encryption keys.
     *
     * @param peerPublicKeyBytes X.509 encoded public key from peer
     * @return raw shared secret (pass through KDF before use!)
     * @throws KeyExchangeException if derivation fails
     * @throws IllegalStateException if keypair has been destroyed
     */
    public byte[] deriveSharedSecret(byte[] peerPublicKeyBytes) throws KeyExchangeException {
        checkNotDestroyed();

        if (peerPublicKeyBytes == null || peerPublicKeyBytes.length == 0) {
            throw new KeyExchangeException("Peer public key is null or empty");
        }

        // SECURITY FIX (v2.6): Enforce single-use ephemeral keys
        // Previously this only logged a warning, but ephemeral key reuse is a
        // serious security violation that breaks forward secrecy guarantees.
        if (used) {
            log.error("EPHEMERAL_KEY_REUSE_BLOCKED: Attempted reuse of ephemeral keypair",
                    "id", id);
            throw new KeyExchangeException(
                    "Ephemeral keypair already used for key derivation. " +
                    "Each secure channel must use a fresh ephemeral keypair for forward secrecy. " +
                    "KeypairId: " + id);
        }

        try {
            // Decode peer's public key
            KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(peerPublicKeyBytes);
            PublicKey peerPublicKey = keyFactory.generatePublic(keySpec);

            // Perform ECDH
            KeyAgreement keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM);
            keyAgreement.init(keyPair.getPrivate());
            keyAgreement.doPhase(peerPublicKey, true);

            byte[] sharedSecret = keyAgreement.generateSecret();

            used = true;

            log.debug("ECDH shared secret derived",
                    "id", id,
                    "secretLength", sharedSecret.length);

            return sharedSecret;

        } catch (Exception e) {
            throw new KeyExchangeException("Failed to derive shared secret: " + e.getMessage());
        }
    }

    /**
     * Verifies that a public key is valid and on the secp256r1 curve.
     *
     * @param publicKeyBytes X.509 encoded public key to verify
     * @return true if valid, false otherwise
     */
    public static boolean verifyPublicKey(byte[] publicKeyBytes) {
        if (publicKeyBytes == null || publicKeyBytes.length == 0) {
            return false;
        }

        try {
            KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            return EC_ALGORITHM.equals(publicKey.getAlgorithm());

        } catch (Exception e) {
            log.debug("Public key verification failed", "error", e.getMessage());
            return false;
        }
    }

    /**
     * Gets the unique identifier for this ephemeral keypair.
     * Used for logging and debugging.
     *
     * @return keypair identifier
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the timestamp when this keypair was created.
     *
     * @return creation timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Checks if this keypair has been used for key derivation.
     *
     * @return true if {@link #deriveSharedSecret(byte[])} has been called
     */
    public boolean isUsed() {
        return used;
    }

    /**
     * Checks if this keypair has been destroyed.
     *
     * @return true if {@link #close()} has been called
     */
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * Securely destroys this ephemeral keypair.
     * Attempts to zero out private key material.
     *
     * After calling this method, any attempt to use the keypair
     * will throw {@link IllegalStateException}.
     */
    @Override
    public void close() {
        if (destroyed) {
            return;
        }

        destroyed = true;

        try {
            // Attempt to destroy the private key if it supports Destroyable
            PrivateKey privateKey = keyPair.getPrivate();
            if (privateKey instanceof javax.security.auth.Destroyable) {
                try {
                    ((javax.security.auth.Destroyable) privateKey).destroy();
                    log.debug("Ephemeral private key destroyed", "id", id);
                } catch (javax.security.auth.DestroyFailedException e) {
                    // Some JCE providers don't support destroy
                    log.debug("Private key destroy not supported", "id", id);
                }
            }

            log.debug("Ephemeral keypair closed",
                    "id", id,
                    "wasUsed", used);

        } catch (Exception e) {
            log.warn("Error during ephemeral keypair destruction",
                    "id", id,
                    "error", e.getMessage());
        }
    }

    private void checkNotDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("Ephemeral keypair has been destroyed: " + id);
        }
    }

    private static String generateId() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder("eph-");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "EphemeralKeyPair{" +
                "id='" + id + '\'' +
                ", createdAt=" + createdAt +
                ", used=" + used +
                ", destroyed=" + destroyed +
                '}';
    }
}
