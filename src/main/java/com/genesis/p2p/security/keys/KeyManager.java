
package com.genesis.p2p.security.keys;

import com.genesis.p2p.security.api.IKeyManager;
import com.genesis.p2p.observability.logging.NodeLogger;

import javax.crypto.KeyAgreement;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Iterator;

/**
 * Key manager implementation.
 *
 * Manages:
 * - Identity keypair (long-term key for signing)
 * - Peer public keys (for ECDH)
 * - Shared secrets (derived from ECDH)
 * - Identity public keys (for signature verification during channel negotiation)
 *
 * The identity keypair (myKeyPair) serves dual purpose:
 * 1. ECDH key exchange with peers
 * 2. Signing ephemeral keys during secure channel negotiation
 *
 * Thread-safe: All operations use ConcurrentHashMap for thread safety.
 */
public class KeyManager implements IKeyManager {

    private static final NodeLogger log = NodeLogger.getLogger(KeyManager.class);

    private static final String EC_ALGORITHM = "EC";
    private static final String CURVE_NAME = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    // SECURITY FIX (v2.6): Default TTL for shared secrets (1 hour)
    // Shared secrets should be cleaned up after use to limit exposure window
    private static final Duration DEFAULT_SHARED_SECRET_TTL = Duration.ofHours(1);

    private final KeyPair myKeyPair;
    private final ConcurrentHashMap<String, byte[]> publicKeys;
    private final ConcurrentHashMap<String, byte[]> sharedSecrets;
    private final ConcurrentHashMap<String, byte[]> identityPublicKeys;

    // SECURITY FIX (v2.6): Track creation time of shared secrets for TTL cleanup
    private final ConcurrentHashMap<String, Instant> sharedSecretTimestamps;
    private volatile Duration sharedSecretTtl = DEFAULT_SHARED_SECRET_TTL;

    public KeyManager() throws Exception {
        this.myKeyPair = generateKeyPair();
        this.publicKeys = new ConcurrentHashMap<>();
        this.sharedSecrets = new ConcurrentHashMap<>();
        this.identityPublicKeys = new ConcurrentHashMap<>();
        this.sharedSecretTimestamps = new ConcurrentHashMap<>();
        log.info("KeyManager initialized with identity keypair");
    }

    /**
     * Creates a KeyManager with a pre-existing keypair.
     * Used to share the identity keypair with other security components.
     *
     * @param keyPair the keypair to use for identity operations
     */
    public KeyManager(KeyPair keyPair) {
        this.myKeyPair = keyPair;
        this.publicKeys = new ConcurrentHashMap<>();
        this.sharedSecrets = new ConcurrentHashMap<>();
        this.identityPublicKeys = new ConcurrentHashMap<>();
        this.sharedSecretTimestamps = new ConcurrentHashMap<>();
        log.info("KeyManager initialized with provided keypair");
    }

    @Override
    public KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(EC_ALGORITHM);
        ECGenParameterSpec ecSpec = new ECGenParameterSpec(CURVE_NAME);
        generator.initialize(ecSpec, new SecureRandom());
        return generator.generateKeyPair();
    }

    @Override
    public byte[] getPublicKey(String peerId) throws Exception {
        if ("self".equals(peerId)) {
            return myKeyPair.getPublic().getEncoded();
        }
        return publicKeys.get(peerId);
    }

    /**
     * Stores a peer's public key for ECDH key exchange.
     *
     * SECURITY FIX (v2.6): Reject public key changes to prevent MITM attacks.
     * If a peer's public key changes after initial establishment, this is a
     * strong indicator of an attack and the new key is rejected.
     *
     * @param peerId the peer identifier
     * @param publicKey the peer's public key bytes
     * @throws SecurityException if the key would replace an existing different key
     */
    @Override
    public void storePublicKey(String peerId, byte[] publicKey) {
        if (peerId == null || publicKey == null) {
            log.warn("Cannot store null public key", "peerId", peerId);
            return;
        }

        // SECURITY FIX (v2.6): Use putIfAbsent to atomically check and set
        byte[] existingKey = publicKeys.putIfAbsent(peerId, publicKey);

        if (existingKey != null && !java.util.Arrays.equals(existingKey, publicKey)) {
            // CRITICAL: Public key changed - possible MITM attack
            log.error("PUBLIC_KEY_CHANGE_REJECTED: Possible MITM attack detected",
                    "peerId", peerId,
                    "existingKeyLength", existingKey.length,
                    "newKeyLength", publicKey.length,
                    "action", "REJECTED");

            throw new SecurityException(
                    "Public key change detected for peer " + peerId +
                    ". Existing key cannot be replaced. This may indicate a MITM attack.");
        }

        if (existingKey == null) {
            log.debug("Stored public key for peer", "peerId", peerId);
        }
    }

    @Override
    public byte[] deriveSharedSecret(String peerId, byte[] theirPublicKey) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(theirPublicKey);
        PublicKey publicKey = keyFactory.generatePublic(keySpec);

        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(myKeyPair.getPrivate());
        keyAgreement.doPhase(publicKey, true);

        byte[] secret = keyAgreement.generateSecret();
        sharedSecrets.put(peerId, secret);

        // SECURITY FIX (v2.6): Track timestamp for TTL-based cleanup
        sharedSecretTimestamps.put(peerId, Instant.now());

        return secret;
    }

    @Override
    public byte[] getSharedSecret(String peerId) {
        return sharedSecrets.get(peerId);
    }

    // ========================= Identity Key Operations =========================

    @Override
    public byte[] getIdentityPublicKey() throws Exception {
        return myKeyPair.getPublic().getEncoded();
    }

    @Override
    public byte[] signWithIdentityKey(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data to sign cannot be null or empty");
        }

        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(myKeyPair.getPrivate());
        signature.update(data);

        byte[] sig = signature.sign();
        log.debug("Data signed with identity key", "dataLength", data.length, "signatureLength", sig.length);
        return sig;
    }

    @Override
    public boolean verifyIdentitySignature(byte[] data, byte[] signatureBytes, byte[] identityPublicKey) throws Exception {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }
        if (signatureBytes == null || signatureBytes.length == 0) {
            throw new IllegalArgumentException("Signature cannot be null or empty");
        }
        if (identityPublicKey == null || identityPublicKey.length == 0) {
            throw new IllegalArgumentException("Identity public key cannot be null or empty");
        }

        try {
            // Decode the public key
            KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(identityPublicKey);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            // Verify signature
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(data);

            boolean valid = signature.verify(signatureBytes);
            log.debug("Identity signature verification",
                    "valid", valid,
                    "dataLength", data.length,
                    "signatureLength", signatureBytes.length);

            return valid;

        } catch (InvalidKeySpecException e) {
            log.warn("Invalid identity public key format", "error", e.getMessage());
            throw new Exception("Invalid identity public key: " + e.getMessage(), e);
        } catch (SignatureException e) {
            log.warn("Signature verification error", "error", e.getMessage());
            return false;
        }
    }

    /**
     * Stores a peer's identity public key for signature verification.
     *
     * SECURITY FIX (v2.6): Reject identity key changes to prevent MITM attacks.
     * The identity key is used to verify signatures during secure channel negotiation.
     * If this key changes, an attacker may be impersonating the peer.
     *
     * @param peerId the peer identifier
     * @param identityPublicKey the peer's identity public key bytes
     * @throws SecurityException if the key would replace an existing different key
     */
    @Override
    public void storeIdentityPublicKey(String peerId, byte[] identityPublicKey) {
        if (peerId == null || identityPublicKey == null) {
            log.warn("Cannot store null identity public key", "peerId", peerId);
            return;
        }

        // SECURITY FIX (v2.6): Use putIfAbsent to atomically check and set
        byte[] existingKey = identityPublicKeys.putIfAbsent(peerId, identityPublicKey);

        if (existingKey != null && !java.util.Arrays.equals(existingKey, identityPublicKey)) {
            // CRITICAL: Identity key changed - possible MITM attack
            log.error("IDENTITY_KEY_CHANGE_REJECTED: Possible MITM attack detected",
                    "peerId", peerId,
                    "existingKeyLength", existingKey.length,
                    "newKeyLength", identityPublicKey.length,
                    "action", "REJECTED");

            throw new SecurityException(
                    "Identity public key change detected for peer " + peerId +
                    ". Existing key cannot be replaced. This may indicate a MITM attack.");
        }

        if (existingKey == null) {
            log.debug("Stored identity public key for peer", "peerId", peerId);
        }
    }

    @Override
    public byte[] getIdentityPublicKey(String peerId) {
        return identityPublicKeys.get(peerId);
    }

    /**
     * Gets the underlying identity keypair.
     * Use with caution - prefer the sign/verify methods.
     *
     * @return the identity keypair
     */
    public KeyPair getIdentityKeyPair() {
        return myKeyPair;
    }

    /**
     * Clears all stored keys and secrets.
     * Use for testing or secure cleanup.
     */
    public void clear() {
        publicKeys.clear();
        // Securely zero out shared secrets before clearing
        for (byte[] secret : sharedSecrets.values()) {
            java.util.Arrays.fill(secret, (byte) 0);
        }
        sharedSecrets.clear();
        sharedSecretTimestamps.clear();
        identityPublicKeys.clear();
        log.info("KeyManager cleared all stored keys");
    }

    // ========================= TTL-based Cleanup (v2.6) =========================

    /**
     * Sets the TTL for shared secrets.
     *
     * SECURITY FIX (v2.6): Allows configuring how long shared secrets are retained.
     *
     * @param ttl the time-to-live duration for shared secrets
     */
    public void setSharedSecretTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("TTL must be a positive duration");
        }
        this.sharedSecretTtl = ttl;
        log.info("Shared secret TTL configured", "ttl", ttl);
    }

    /**
     * Cleans up expired shared secrets based on TTL.
     *
     * SECURITY FIX (v2.6): Shared secrets should not be retained indefinitely.
     * This method should be called periodically (e.g., every 5 minutes) to remove
     * expired secrets and limit the exposure window if memory is compromised.
     *
     * @return number of secrets cleaned up
     */
    public int cleanupExpiredSharedSecrets() {
        int removed = 0;
        Instant expirationThreshold = Instant.now().minus(sharedSecretTtl);

        Iterator<Map.Entry<String, Instant>> iterator = sharedSecretTimestamps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Instant> entry = iterator.next();
            if (entry.getValue().isBefore(expirationThreshold)) {
                String peerId = entry.getKey();

                // Securely zero out the shared secret before removal
                byte[] secret = sharedSecrets.remove(peerId);
                if (secret != null) {
                    java.util.Arrays.fill(secret, (byte) 0);
                }

                iterator.remove();
                removed++;

                log.debug("Expired shared secret cleaned up",
                        "peerId", peerId,
                        "age", Duration.between(entry.getValue(), Instant.now()));
            }
        }

        if (removed > 0) {
            log.info("Expired shared secrets cleaned up",
                    "count", removed,
                    "remaining", sharedSecrets.size());
        }

        return removed;
    }

    /**
     * Removes a specific shared secret and securely zeros it.
     *
     * SECURITY FIX (v2.6): Call this when a session ends to immediately
     * remove the shared secret rather than waiting for TTL expiration.
     *
     * @param peerId the peer whose shared secret to remove
     */
    public void removeSharedSecret(String peerId) {
        byte[] secret = sharedSecrets.remove(peerId);
        if (secret != null) {
            java.util.Arrays.fill(secret, (byte) 0);
            sharedSecretTimestamps.remove(peerId);
            log.debug("Shared secret removed for peer", "peerId", peerId);
        }
    }

    /**
     * Gets the count of stored shared secrets.
     * Useful for monitoring and debugging.
     *
     * @return number of shared secrets currently stored
     */
    public int getSharedSecretCount() {
        return sharedSecrets.size();
    }
}
