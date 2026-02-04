package com.genesis.p2p.security.cert;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

/**
 * Self-Signed Certificate Generator for P2P nodes.
 *
 * Generates certificates without requiring a Certificate Authority.
 * Each node creates its own certificate signed with its private key.
 *
 * Features:
 * - RSA/EC key pair generation
 * - SHA-256 with RSA/ECDSA signatures
 * - Configurable validity period
 * - Custom attributes support
 *
 * Use Cases:
 * - Initial node bootstrap
 * - Development/testing
 * - Decentralized trust models
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class SelfSignedCertificateGenerator {

    private static final NodeLogger log = NodeLogger.getLogger(SelfSignedCertificateGenerator.class);

    private static final String DEFAULT_ALGORITHM = "RSA";
    private static final int DEFAULT_KEY_SIZE = 2048;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final long DEFAULT_VALIDITY_DAYS = 365;

    /**
     * Generates a self-signed certificate with RSA 2048-bit key.
     *
     * @param nodeId the node identifier
     * @return certificate with key pair
     * @throws CertificateException if generation fails
     */
    public static CertificateWithKey generateSelfSigned(String nodeId)
            throws CertificateException {
        return generateSelfSigned(nodeId, DEFAULT_VALIDITY_DAYS, Map.of());
    }

    /**
     * Generates a self-signed certificate with custom validity and attributes.
     *
     * @param nodeId the node identifier
     * @param validityDays certificate validity in days
     * @param attributes custom certificate attributes
     * @return certificate with key pair
     * @throws CertificateException if generation fails
     */
    public static CertificateWithKey generateSelfSigned(String nodeId,
                                                        long validityDays,
                                                        Map<String, String> attributes)
            throws CertificateException {

        log.info("Generating self-signed certificate",
                "nodeId", nodeId,
                "validityDays", validityDays);

        try {
            // Generate key pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(DEFAULT_ALGORITHM);
            keyGen.initialize(DEFAULT_KEY_SIZE, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();

            log.debug("Key pair generated",
                    "algorithm", DEFAULT_ALGORITHM,
                    "keySize", DEFAULT_KEY_SIZE);

            // Build certificate
            Instant now = Instant.now();
            Instant notBefore = now;
            Instant notAfter = now.plus(validityDays, ChronoUnit.DAYS);

            Certificate.Builder certBuilder = new Certificate.Builder()
                    .nodeId(nodeId)
                    .publicKey(keyPair.getPublic())
                    .notBefore(notBefore)
                    .notAfter(notAfter)
                    .signedBy(nodeId)
                    .attributes(attributes);

            // Add standard attributes
            certBuilder.addAttribute("keyAlgorithm", DEFAULT_ALGORITHM);
            certBuilder.addAttribute("keySize", String.valueOf(DEFAULT_KEY_SIZE));
            certBuilder.addAttribute("signatureAlgorithm", SIGNATURE_ALGORITHM);

            // Get bytes to sign
            Certificate tempCert = certBuilder
                    .signature(new byte[0]) // Temporary
                    .build();
            byte[] dataToSign = tempCert.getSignableBytes();

            // Sign certificate
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(keyPair.getPrivate());
            signer.update(dataToSign);
            byte[] signature = signer.sign();

            // Build final certificate with signature
            Certificate certificate = certBuilder
                    .signature(signature)
                    .build();

            log.info("Self-signed certificate generated",
                    "nodeId", nodeId,
                    "notBefore", notBefore,
                    "notAfter", notAfter,
                    "fingerprint", getFingerprint(certificate));

            return new CertificateWithKey(certificate, keyPair);

        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            log.error("Failed to generate self-signed certificate", e, "nodeId", nodeId);
            throw new CertificateException("Certificate generation failed", e);
        }
    }

    /**
     * Generates certificate with EC (Elliptic Curve) keys.
     *
     * EC keys are smaller and faster than RSA.
     * Recommended for resource-constrained environments.
     *
     * @param nodeId the node identifier
     * @param validityDays certificate validity in days
     * @return certificate with EC key pair
     * @throws CertificateException if generation fails
     */
    public static CertificateWithKey generateSelfSignedEC(String nodeId, long validityDays)
            throws CertificateException {

        log.info("Generating self-signed EC certificate",
                "nodeId", nodeId,
                "validityDays", validityDays);

        try {
            // Generate EC key pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(256, new SecureRandom()); // 256-bit EC ≈ 3072-bit RSA
            KeyPair keyPair = keyGen.generateKeyPair();

            log.debug("EC key pair generated", "keySize", 256);

            // Build certificate
            Instant now = Instant.now();
            Certificate.Builder certBuilder = new Certificate.Builder()
                    .nodeId(nodeId)
                    .publicKey(keyPair.getPublic())
                    .notBefore(now)
                    .notAfter(now.plus(validityDays, ChronoUnit.DAYS))
                    .signedBy(nodeId)
                    .addAttribute("keyAlgorithm", "EC")
                    .addAttribute("keySize", "256")
                    .addAttribute("signatureAlgorithm", "SHA256withECDSA");

            // Get bytes to sign
            Certificate tempCert = certBuilder
                    .signature(new byte[0])
                    .build();
            byte[] dataToSign = tempCert.getSignableBytes();

            // Sign with ECDSA
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(dataToSign);
            byte[] signature = signer.sign();

            // Build final certificate
            Certificate certificate = certBuilder
                    .signature(signature)
                    .build();

            log.info("Self-signed EC certificate generated", "nodeId", nodeId);

            return new CertificateWithKey(certificate, keyPair);

        } catch (Exception e) {
            log.error("Failed to generate EC certificate", e, "nodeId", nodeId);
            throw new CertificateException("EC certificate generation failed", e);
        }
    }

    /**
     * Renews a certificate with a new validity period.
     *
     * @param oldCert the certificate to renew
     * @param keyPair the key pair (must match certificate)
     * @param validityDays new validity in days
     * @return renewed certificate with same key pair
     * @throws CertificateException if renewal fails
     */
    public static CertificateWithKey renewCertificate(Certificate oldCert,
                                                     KeyPair keyPair,
                                                     long validityDays)
            throws CertificateException {

        log.info("Renewing certificate",
                "nodeId", oldCert.getNodeId(),
                "validityDays", validityDays);

        try {
            // Build renewed certificate with new validity
            Instant now = Instant.now();
            Certificate.Builder certBuilder = new Certificate.Builder()
                    .nodeId(oldCert.getNodeId())
                    .publicKey(keyPair.getPublic())
                    .notBefore(now)
                    .notAfter(now.plus(validityDays, ChronoUnit.DAYS))
                    .signedBy(oldCert.getNodeId())
                    .attributes(oldCert.getAttributes());

            // Re-sign certificate
            Certificate tempCert = certBuilder
                    .signature(new byte[0])
                    .build();
            byte[] dataToSign = tempCert.getSignableBytes();

            String sigAlg = oldCert.getAttribute("signatureAlgorithm");
            if (sigAlg == null) {
                sigAlg = SIGNATURE_ALGORITHM;
            }

            Signature signer = Signature.getInstance(sigAlg);
            signer.initSign(keyPair.getPrivate());
            signer.update(dataToSign);
            byte[] signature = signer.sign();

            Certificate certificate = certBuilder
                    .signature(signature)
                    .build();

            log.info("Certificate renewed", "nodeId", oldCert.getNodeId());

            return new CertificateWithKey(certificate, keyPair);

        } catch (Exception e) {
            log.error("Failed to renew certificate", e);
            throw new CertificateException("Certificate renewal failed", e);
        }
    }

    /**
     * Gets certificate fingerprint (SHA-256 hash of public key).
     */
    private static String getFingerprint(Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(cert.getPublicKey().getEncoded());
            return Base64.getEncoder().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    // ==================== Data Classes ====================

    /**
     * Certificate with its private key.
     */
    public static class CertificateWithKey {
        private final Certificate certificate;
        private final KeyPair keyPair;

        public CertificateWithKey(Certificate certificate, KeyPair keyPair) {
            this.certificate = certificate;
            this.keyPair = keyPair;
        }

        public Certificate getCertificate() { return certificate; }
        public KeyPair getKeyPair() { return keyPair; }
        public PrivateKey getPrivateKey() { return keyPair.getPrivate(); }
        public PublicKey getPublicKey() { return keyPair.getPublic(); }

        @Override
        public String toString() {
            return String.format("CertificateWithKey[nodeId=%s, algorithm=%s]",
                    certificate.getNodeId(),
                    keyPair.getPublic().getAlgorithm());
        }
    }

    /**
     * Certificate generation exception.
     */
    public static class CertificateException extends Exception {
        public CertificateException(String message) {
            super(message);
        }

        public CertificateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

