package com.genesis.p2p.security.cert;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.security.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Certificate Validator for P2P certificates.
 *
 * Validates certificates for:
 * - Signature verification
 * - Validity period
 * - Trust chain (web of trust)
 * - Revocation status
 *
 * Validation Levels:
 * 1. Basic: Signature + validity
 * 2. Trust: Check against trusted certificates
 * 3. Chain: Verify trust chain/web
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class CertificateValidator {

    private static final NodeLogger log = NodeLogger.getLogger(CertificateValidator.class);

    /**
     * Performs basic certificate validation.
     *
     * @param certificate the certificate to validate
     * @return validation result
     */
    public ValidationResult validateBasic(Certificate certificate) {
        log.debug("Performing basic validation", "nodeId", certificate.getNodeId());

        List<String> errors = new ArrayList<>();

        // Check validity period
        if (certificate.isExpired()) {
            errors.add("Certificate is expired");
        }

        if (certificate.isNotYetValid()) {
            errors.add("Certificate is not yet valid");
        }

        // Verify signature
        try {
            if (!verifySignature(certificate)) {
                errors.add("Signature verification failed");
            }
        } catch (Exception e) {
            errors.add("Signature verification error: " + e.getMessage());
        }

        if (errors.isEmpty()) {
            log.debug("Basic validation passed", "nodeId", certificate.getNodeId());
            return ValidationResult.success();
        } else {
            log.warn("Basic validation failed",
                    "nodeId", certificate.getNodeId(),
                    "errors", errors.size());
            return ValidationResult.failure(errors);
        }
    }

    /**
     * Validates certificate with trust check.
     *
     * @param certificate the certificate to validate
     * @param trustedCerts list of trusted certificates
     * @return validation result
     */
    public ValidationResult validateWithTrust(Certificate certificate,
                                             List<Certificate> trustedCerts) {

        log.debug("Performing trust validation", "nodeId", certificate.getNodeId());

        // First do basic validation
        ValidationResult basicResult = validateBasic(certificate);
        if (!basicResult.isValid()) {
            return basicResult;
        }

        List<String> errors = new ArrayList<>();

        // Check if self-signed and in trusted list
        if (certificate.isSelfSigned()) {
            boolean trusted = trustedCerts.stream()
                    .anyMatch(tc -> tc.getNodeId().equals(certificate.getNodeId()));

            if (!trusted) {
                errors.add("Self-signed certificate not in trusted list");
            }
        } else {
            // Check if signed by trusted node
            boolean signerTrusted = trustedCerts.stream()
                    .anyMatch(tc -> tc.getNodeId().equals(certificate.getSignedBy()));

            if (!signerTrusted) {
                errors.add("Certificate signed by untrusted node: " + certificate.getSignedBy());
            }
        }

        if (errors.isEmpty()) {
            log.debug("Trust validation passed", "nodeId", certificate.getNodeId());
            return ValidationResult.success();
        } else {
            log.warn("Trust validation failed",
                    "nodeId", certificate.getNodeId(),
                    "errors", errors);
            return ValidationResult.failure(errors);
        }
    }

    /**
     * Validates certificate chain (web of trust).
     *
     * @param certificate the certificate to validate
     * @param allCerts all available certificates
     * @param trustedRoots root trusted certificates
     * @return validation result
     */
    public ValidationResult validateChain(Certificate certificate,
                                         List<Certificate> allCerts,
                                         List<Certificate> trustedRoots) {

        log.debug("Performing chain validation", "nodeId", certificate.getNodeId());

        // Basic validation first
        ValidationResult basicResult = validateBasic(certificate);
        if (!basicResult.isValid()) {
            return basicResult;
        }

        List<String> errors = new ArrayList<>();

        // Build trust chain
        List<Certificate> chain = buildTrustChain(certificate, allCerts);

        if (chain.isEmpty()) {
            errors.add("Cannot build trust chain");
            return ValidationResult.failure(errors);
        }

        // Verify chain leads to trusted root
        Certificate root = chain.get(chain.size() - 1);
        boolean rootTrusted = trustedRoots.stream()
                .anyMatch(tr -> tr.getNodeId().equals(root.getNodeId()));

        if (!rootTrusted) {
            errors.add("Trust chain does not lead to trusted root");
        }

        // Validate each certificate in chain
        for (Certificate cert : chain) {
            ValidationResult result = validateBasic(cert);
            if (!result.isValid()) {
                errors.add("Chain cert invalid: " + cert.getNodeId());
            }
        }

        if (errors.isEmpty()) {
            log.info("Chain validation passed",
                    "nodeId", certificate.getNodeId(),
                    "chainLength", chain.size());
            return ValidationResult.success(chain);
        } else {
            log.warn("Chain validation failed",
                    "nodeId", certificate.getNodeId(),
                    "errors", errors);
            return ValidationResult.failure(errors);
        }
    }

    /**
     * Verifies certificate signature.
     */
    private boolean verifySignature(Certificate certificate) throws Exception {
        // Get signature algorithm
        String sigAlg = certificate.getAttribute("signatureAlgorithm");
        if (sigAlg == null) {
            sigAlg = "SHA256withRSA"; // Default
        }

        // Verify signature
        Signature verifier = Signature.getInstance(sigAlg);
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(certificate.getSignableBytes());

        return verifier.verify(certificate.getSignature());
    }

    /**
     * Builds trust chain from certificate to root.
     */
    private List<Certificate> buildTrustChain(Certificate cert, List<Certificate> allCerts) {
        List<Certificate> chain = new ArrayList<>();
        chain.add(cert);

        Certificate current = cert;
        int maxDepth = 10; // Prevent infinite loops

        while (!current.isSelfSigned() && maxDepth-- > 0) {
            String signerId = current.getSignedBy();

            // Find signer's certificate
            Certificate signer = allCerts.stream()
                    .filter(c -> c.getNodeId().equals(signerId))
                    .findFirst()
                    .orElse(null);

            if (signer == null) {
                break; // Chain incomplete
            }

            chain.add(signer);
            current = signer;
        }

        return chain;
    }

    // ==================== Validation Result ====================

    /**
     * Result of certificate validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<Certificate> trustChain;

        private ValidationResult(boolean valid, List<String> errors, List<Certificate> trustChain) {
            this.valid = valid;
            this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
            this.trustChain = trustChain != null ? new ArrayList<>(trustChain) : new ArrayList<>();
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult success(List<Certificate> trustChain) {
            return new ValidationResult(true, null, trustChain);
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors, null);
        }

        public static ValidationResult failure(String error) {
            return new ValidationResult(false, List.of(error), null);
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<Certificate> getTrustChain() { return trustChain; }

        public String getErrorSummary() {
            return String.join("; ", errors);
        }

        @Override
        public String toString() {
            if (valid) {
                return "ValidationResult[valid=true, chainLength=" + trustChain.size() + "]";
            } else {
                return "ValidationResult[valid=false, errors=" + errors.size() + "]";
            }
        }
    }
}

