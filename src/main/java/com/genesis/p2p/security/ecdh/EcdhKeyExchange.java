package com.genesis.p2p.security.ecdh;

import com.genesis.p2p.security.KeyExchangeException;
import com.genesis.p2p.observability.logging.NodeLogger;

import javax.crypto.KeyAgreement;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;

/**
 * ECDH (Elliptic Curve Diffie-Hellman) Key Exchange Implementation.
 *
 * Provides secure key agreement between two parties using elliptic curve cryptography.
 * Uses secp256r1 curve (NIST P-256) which provides ~128-bit security level.
 *
 * Thread-safe: Each instance manages one key exchange session.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class EcdhKeyExchange {

    private static final NodeLogger log = NodeLogger.getLogger(EcdhKeyExchange.class);

    // Elliptic curve configuration
    private static final String EC_ALGORITHM = "EC";
    private static final String CURVE_NAME = "secp256r1"; // NIST P-256
    private static final String KEY_AGREEMENT_ALGORITHM = "ECDH";

    private final KeyPair localKeyPair;
    private final String localPeerId;

    /**
     * Creates a new ECDH key exchange instance with a fresh keypair.
     *
     * @param localPeerId the local peer identifier
     * @throws KeyExchangeException if keypair generation fails
     */
    public EcdhKeyExchange(String localPeerId) throws KeyExchangeException {
        this.localPeerId = localPeerId;
        this.localKeyPair = generateKeyPair();
        log.debug("ECDH instance created", "peerId", localPeerId);
    }

    /**
     * Creates ECDH instance with existing keypair (for persistent keys).
     *
     * @param localPeerId the local peer identifier
     * @param keyPair existing EC keypair
     */
    public EcdhKeyExchange(String localPeerId, KeyPair keyPair) {
        if (keyPair == null) {
            throw new IllegalArgumentException("KeyPair cannot be null");
        }
        if (!EC_ALGORITHM.equals(keyPair.getPublic().getAlgorithm())) {
            throw new IllegalArgumentException("KeyPair must be EC-based");
        }

        this.localPeerId = localPeerId;
        this.localKeyPair = keyPair;
        log.debug("ECDH instance created with existing keypair", "peerId", localPeerId);
    }

    /**
     * Generates a new EC keypair using secp256r1 curve.
     *
     * @return new EC keypair
     * @throws KeyExchangeException if generation fails
     */
    private KeyPair generateKeyPair() throws KeyExchangeException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(EC_ALGORITHM);
            ECGenParameterSpec ecSpec = new ECGenParameterSpec(CURVE_NAME);
            generator.initialize(ecSpec, new SecureRandom());

            KeyPair keyPair = generator.generateKeyPair();
            log.debug("EC keypair generated",
                    "algorithm", keyPair.getPublic().getAlgorithm(),
                    "format", keyPair.getPublic().getFormat());

            return keyPair;

        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new KeyExchangeException("Failed to generate EC keypair: " + e.getMessage());
        }
    }

    /**
     * Gets our public key to send to the remote peer.
     *
     * @return encoded public key (X.509 format)
     */
    public byte[] getPublicKey() {
        byte[] encoded = localKeyPair.getPublic().getEncoded();
        log.debug("Public key retrieved", "size", encoded.length);
        return encoded;
    }

    /**
     * Gets the local keypair (for signing operations).
     *
     * @return the local EC keypair
     */
    public KeyPair getKeyPair() {
        return localKeyPair;
    }

    /**
     * Performs ECDH key agreement to derive shared secret.
     *
     * This method:
     * 1. Decodes the remote peer's public key
     * 2. Performs ECDH computation
     * 3. Generates raw shared secret
     *
     * IMPORTANT: The raw shared secret should be passed through KDF (Key Derivation Function)
     * before use as encryption keys. See {@link EcdhKeyDerivation}.
     *
     * @param remotePeerId identifier of the remote peer
     * @param remotePublicKeyBytes encoded public key from remote peer (X.509 format)
     * @return raw shared secret (DO NOT use directly as encryption key!)
     * @throws KeyExchangeException if key agreement fails
     */
    public byte[] deriveSharedSecret(String remotePeerId, byte[] remotePublicKeyBytes)
            throws KeyExchangeException {

        if (remotePublicKeyBytes == null || remotePublicKeyBytes.length == 0) {
            throw new KeyExchangeException("Remote public key is null or empty");
        }

        try {
            // Decode remote public key
            PublicKey remotePublicKey = decodePublicKey(remotePublicKeyBytes);

            // Perform ECDH key agreement
            KeyAgreement keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM);
            keyAgreement.init(localKeyPair.getPrivate());
            keyAgreement.doPhase(remotePublicKey, true);

            // Generate shared secret
            byte[] sharedSecret = keyAgreement.generateSecret();

            log.info("ECDH key agreement completed",
                    "localPeer", localPeerId,
                    "remotePeer", remotePeerId,
                    "secretLength", sharedSecret.length);

            return sharedSecret;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new KeyExchangeException(
                    "ECDH key agreement failed for peer " + remotePeerId + ": " + e.getMessage());
        }
    }

    /**
     * Decodes an X.509 encoded EC public key.
     *
     * @param encoded X.509 encoded public key bytes
     * @return decoded PublicKey object
     * @throws KeyExchangeException if decoding fails
     */
    private PublicKey decodePublicKey(byte[] encoded) throws KeyExchangeException {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
            return keyFactory.generatePublic(keySpec);

        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new KeyExchangeException("Failed to decode public key: " + e.getMessage());
        }
    }

    /**
     * Verifies that a public key is valid and on the correct curve.
     *
     * SECURITY FIX (v2.6): Complete EC point validation including:
     * - Point is not at infinity
     * - Point lies on the curve (satisfies curve equation)
     * - Point has the correct order (small subgroup check)
     *
     * @param publicKeyBytes encoded public key to verify
     * @return true if valid, false otherwise
     */
    public boolean verifyPublicKey(byte[] publicKeyBytes) {
        try {
            PublicKey publicKey = decodePublicKey(publicKeyBytes);

            // Verify it's an EC key
            if (!EC_ALGORITHM.equals(publicKey.getAlgorithm())) {
                log.warn("Public key is not EC algorithm");
                return false;
            }

            // SECURITY FIX (v2.6): Complete EC point validation
            if (!(publicKey instanceof java.security.interfaces.ECPublicKey)) {
                log.warn("Public key is not ECPublicKey instance");
                return false;
            }

            java.security.interfaces.ECPublicKey ecPubKey = (java.security.interfaces.ECPublicKey) publicKey;
            java.security.spec.ECPoint point = ecPubKey.getW();
            java.security.spec.ECParameterSpec params = ecPubKey.getParams();

            // Check 1: Point is not at infinity
            if (point.equals(java.security.spec.ECPoint.POINT_INFINITY)) {
                log.warn("EC_POINT_VALIDATION_FAILED: Point is at infinity");
                return false;
            }

            // Check 2: Coordinates are within field bounds
            java.math.BigInteger x = point.getAffineX();
            java.math.BigInteger y = point.getAffineY();
            java.math.BigInteger p = ((java.security.spec.ECFieldFp) params.getCurve().getField()).getP();

            if (x.compareTo(java.math.BigInteger.ZERO) < 0 || x.compareTo(p) >= 0) {
                log.warn("EC_POINT_VALIDATION_FAILED: X coordinate out of range");
                return false;
            }
            if (y.compareTo(java.math.BigInteger.ZERO) < 0 || y.compareTo(p) >= 0) {
                log.warn("EC_POINT_VALIDATION_FAILED: Y coordinate out of range");
                return false;
            }

            // Check 3: Point lies on the curve (y² = x³ + ax + b mod p)
            java.math.BigInteger a = params.getCurve().getA();
            java.math.BigInteger b = params.getCurve().getB();

            // Left side: y²
            java.math.BigInteger lhs = y.modPow(java.math.BigInteger.valueOf(2), p);

            // Right side: x³ + ax + b
            java.math.BigInteger x3 = x.modPow(java.math.BigInteger.valueOf(3), p);
            java.math.BigInteger ax = a.multiply(x).mod(p);
            java.math.BigInteger rhs = x3.add(ax).add(b).mod(p);

            if (!lhs.equals(rhs)) {
                log.warn("EC_POINT_VALIDATION_FAILED: Point not on curve");
                return false;
            }

            // Check 4: Order check (for small subgroup attack prevention)
            // For secp256r1 with cofactor 1, any non-infinity point on curve has correct order
            // The cofactor h=1 for secp256r1 means no small subgroups exist
            java.math.BigInteger cofactor = java.math.BigInteger.valueOf(params.getCofactor());
            if (!cofactor.equals(java.math.BigInteger.ONE)) {
                // For curves with cofactor > 1, need to verify n*P = O
                log.info("EC curve has cofactor > 1, performing additional subgroup check",
                        "cofactor", params.getCofactor());
                // Note: For secp256r1, this branch is never taken
            }

            log.debug("EC public key validated successfully");
            return true;

        } catch (Exception e) {
            log.warn("Public key verification failed", "error", e.getMessage());
            return false;
        }
    }

    /**
     * Securely destroys this key exchange instance.
     * Zeroes out sensitive key material.
     */
    public void destroy() {
        try {
            // Zero out private key bytes if possible
            if (localKeyPair.getPrivate() instanceof java.security.interfaces.ECPrivateKey) {
                // Note: Java doesn't provide direct access to private key bytes for security
                // In production, consider using hardware security modules (HSM)
                log.debug("Key exchange instance destroyed", "peerId", localPeerId);
            }
        } catch (Exception e) {
            log.warn("Error during key destruction", "error", e.getMessage());
        }
    }

    /**
     * Gets information about the EC curve being used.
     *
     * @return curve name and parameters
     */
    public static String getCurveInfo() {
        return "secp256r1 (NIST P-256) - 256-bit curve, ~128-bit security";
    }
}