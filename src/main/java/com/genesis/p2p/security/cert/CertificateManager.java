package com.genesis.p2p.security.cert;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Certificate Manager for P2P network.
 *
 * Manages certificates for:
 * - Local node (own certificate + key)
 * - Remote peers (their certificates)
 * - Trust relationships
 * - Certificate lifecycle (creation, renewal, revocation)
 *
 * Features:
 * - Certificate storage and retrieval
 * - Trust management (add/remove trusted certs)
 * - Automatic renewal
 * - Revocation list
 * - Certificate validation
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class CertificateManager {

    private static final NodeLogger log = NodeLogger.getLogger(CertificateManager.class);

    // Local certificate and key
    private Certificate localCertificate;
    private KeyPair localKeyPair;

    // Remote peer certificates
    private final Map<String, Certificate> peerCertificates;

    // Trusted certificates (roots)
    private final Set<String> trustedNodeIds;

    // Revoked certificates
    private final Set<String> revokedNodeIds;

    // SECURITY FIX (v2.6): Track revocation timestamps for expiration
    private final Map<String, Instant> revocationTimestamps;

    // SECURITY FIX (v2.6): Configurable revocation retention period
    private static final Duration DEFAULT_REVOCATION_RETENTION = Duration.ofDays(365);
    private volatile Duration revocationRetention = DEFAULT_REVOCATION_RETENTION;

    // Validator
    private final CertificateValidator validator;

    // Auto-renewal settings
    private boolean autoRenewEnabled = true;
    private long renewBeforeDays = 30;

    /**
     * Creates a certificate manager.
     */
    public CertificateManager() {
        this.peerCertificates = new ConcurrentHashMap<>();
        this.trustedNodeIds = ConcurrentHashMap.newKeySet();
        this.revokedNodeIds = ConcurrentHashMap.newKeySet();
        this.revocationTimestamps = new ConcurrentHashMap<>();
        this.validator = new CertificateValidator();

        log.info("CertificateManager initialized");
    }

    // ==================== Local Certificate ====================

    /**
     * Initializes local certificate (generates self-signed).
     *
     * @param nodeId the local node identifier
     * @param validityDays certificate validity in days
     * @throws SelfSignedCertificateGenerator.CertificateException if generation fails
     */
    public void initializeLocalCertificate(String nodeId, long validityDays)
            throws SelfSignedCertificateGenerator.CertificateException {

        log.info("Initializing local certificate",
                "nodeId", nodeId,
                "validityDays", validityDays);

        SelfSignedCertificateGenerator.CertificateWithKey certWithKey =
                SelfSignedCertificateGenerator.generateSelfSigned(nodeId, validityDays, Map.of());

        this.localCertificate = certWithKey.getCertificate();
        this.localKeyPair = certWithKey.getKeyPair();

        // Trust own certificate
        trustedNodeIds.add(nodeId);

        log.info("Local certificate initialized",
                "nodeId", nodeId,
                "notBefore", localCertificate.getNotBefore(),
                "notAfter", localCertificate.getNotAfter());
    }

    /**
     * Sets local certificate (already generated).
     */
    public void setLocalCertificate(Certificate certificate, KeyPair keyPair) {
        this.localCertificate = certificate;
        this.localKeyPair = keyPair;

        log.info("Local certificate set", "nodeId", certificate.getNodeId());
    }

    /**
     * Gets local certificate.
     */
    public Certificate getLocalCertificate() {
        return localCertificate;
    }

    /**
     * Gets local key pair.
     */
    public KeyPair getLocalKeyPair() {
        return localKeyPair;
    }

    /**
     * Checks if local certificate needs renewal.
     */
    public boolean needsRenewal() {
        if (localCertificate == null) {
            return false;
        }

        long daysRemaining = localCertificate.getRemainingValiditySeconds() / 86400;
        return daysRemaining <= renewBeforeDays;
    }

    /**
     * Renews local certificate.
     */
    public void renewLocalCertificate(long validityDays)
            throws SelfSignedCertificateGenerator.CertificateException {

        if (localCertificate == null || localKeyPair == null) {
            throw new IllegalStateException("No local certificate to renew");
        }

        log.info("Renewing local certificate",
                "nodeId", localCertificate.getNodeId());

        SelfSignedCertificateGenerator.CertificateWithKey renewed =
                SelfSignedCertificateGenerator.renewCertificate(
                        localCertificate, localKeyPair, validityDays);

        this.localCertificate = renewed.getCertificate();

        log.info("Local certificate renewed",
                "nodeId", localCertificate.getNodeId());
    }

    // ==================== Peer Certificates ====================

    /**
     * Adds a peer certificate.
     *
     * @param certificate the peer's certificate
     * @return true if added successfully
     */
    public boolean addPeerCertificate(Certificate certificate) {
        String nodeId = certificate.getNodeId();

        // Check if revoked
        if (revokedNodeIds.contains(nodeId)) {
            log.warn("Rejecting revoked certificate", "nodeId", nodeId);
            return false;
        }

        // Validate certificate
        CertificateValidator.ValidationResult result = validator.validateBasic(certificate);
        if (!result.isValid()) {
            log.warn("Rejecting invalid certificate",
                    "nodeId", nodeId,
                    "errors", result.getErrorSummary());
            return false;
        }

        peerCertificates.put(nodeId, certificate);

        log.info("Peer certificate added", "nodeId", nodeId);
        return true;
    }

    /**
     * Gets a peer's certificate.
     */
    public Optional<Certificate> getPeerCertificate(String nodeId) {
        return Optional.ofNullable(peerCertificates.get(nodeId));
    }

    /**
     * Removes a peer certificate.
     */
    public void removePeerCertificate(String nodeId) {
        peerCertificates.remove(nodeId);
        log.info("Peer certificate removed", "nodeId", nodeId);
    }

    /**
     * Gets all peer certificates.
     */
    public Collection<Certificate> getAllPeerCertificates() {
        return new ArrayList<>(peerCertificates.values());
    }

    // ==================== Trust Management ====================

    /**
     * Adds a node to trusted list.
     */
    public void trustNode(String nodeId) {
        trustedNodeIds.add(nodeId);
        log.info("Node added to trusted list", "nodeId", nodeId);
    }

    /**
     * Removes a node from trusted list.
     */
    public void untrustNode(String nodeId) {
        trustedNodeIds.remove(nodeId);
        log.info("Node removed from trusted list", "nodeId", nodeId);
    }

    /**
     * Checks if a node is trusted.
     */
    public boolean isTrusted(String nodeId) {
        return trustedNodeIds.contains(nodeId);
    }

    /**
     * Gets all trusted node IDs.
     */
    public Set<String> getTrustedNodes() {
        return new HashSet<>(trustedNodeIds);
    }

    /**
     * Gets all trusted certificates.
     */
    public List<Certificate> getTrustedCertificates() {
        return peerCertificates.values().stream()
                .filter(cert -> trustedNodeIds.contains(cert.getNodeId()))
                .collect(Collectors.toList());
    }

    // ==================== Revocation ====================

    /**
     * Revokes a certificate.
     *
     * SECURITY FIX (v2.6): Now tracks revocation timestamp for expiration management.
     */
    public void revokeCertificate(String nodeId) {
        revokedNodeIds.add(nodeId);
        peerCertificates.remove(nodeId);
        trustedNodeIds.remove(nodeId);

        // SECURITY FIX (v2.6): Track revocation timestamp
        revocationTimestamps.put(nodeId, Instant.now());

        log.warn("Certificate revoked", "nodeId", nodeId);
    }

    /**
     * Checks if a certificate is revoked.
     */
    public boolean isRevoked(String nodeId) {
        return revokedNodeIds.contains(nodeId);
    }

    /**
     * Gets all revoked node IDs.
     */
    public Set<String> getRevokedNodes() {
        return new HashSet<>(revokedNodeIds);
    }

    /**
     * Sets the revocation retention period.
     *
     * SECURITY FIX (v2.6): Configures how long revocation entries are kept.
     * Shorter retention saves memory but risks accepting previously revoked certs.
     *
     * @param retention the retention duration (minimum 30 days)
     */
    public void setRevocationRetention(Duration retention) {
        if (retention == null || retention.compareTo(Duration.ofDays(30)) < 0) {
            throw new IllegalArgumentException(
                    "Revocation retention must be at least 30 days for security");
        }
        this.revocationRetention = retention;
        log.info("Revocation retention configured", "retention", retention);
    }

    /**
     * Cleans up expired revocation entries.
     *
     * SECURITY FIX (v2.6): Removes revocation entries older than the retention period.
     * This prevents the revocation list from growing indefinitely.
     *
     * CAUTION: Revoked certificates that are re-presented after cleanup will be
     * accepted unless they've also expired. Configure retention carefully.
     *
     * @return number of expired revocations removed
     */
    public int cleanupExpiredRevocations() {
        int removed = 0;
        Instant expirationThreshold = Instant.now().minus(revocationRetention);

        Iterator<Map.Entry<String, Instant>> iterator = revocationTimestamps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Instant> entry = iterator.next();
            if (entry.getValue().isBefore(expirationThreshold)) {
                String nodeId = entry.getKey();
                revokedNodeIds.remove(nodeId);
                iterator.remove();
                removed++;

                log.debug("Expired revocation removed",
                        "nodeId", nodeId,
                        "revokedAt", entry.getValue());
            }
        }

        if (removed > 0) {
            log.info("Expired revocations cleaned up",
                    "removed", removed,
                    "remaining", revokedNodeIds.size());
        }

        return removed;
    }

    /**
     * Gets the revocation timestamp for a node.
     *
     * @param nodeId the node to check
     * @return revocation instant or null if not revoked
     */
    public Instant getRevocationTime(String nodeId) {
        return revocationTimestamps.get(nodeId);
    }

    // ==================== Validation ====================

    /**
     * Validates a peer certificate.
     */
    public CertificateValidator.ValidationResult validatePeerCertificate(Certificate certificate) {
        return validator.validateWithTrust(certificate, getTrustedCertificates());
    }

    /**
     * Validates certificate with full chain validation.
     */
    public CertificateValidator.ValidationResult validateWithChain(Certificate certificate) {
        List<Certificate> allCerts = new ArrayList<>(peerCertificates.values());
        if (localCertificate != null) {
            allCerts.add(localCertificate);
        }

        return validator.validateChain(certificate, allCerts, getTrustedCertificates());
    }

    // ==================== Lifecycle ====================

    /**
     * Checks and renews certificates automatically.
     */
    public void checkAutoRenewal() {
        if (!autoRenewEnabled || localCertificate == null) {
            return;
        }

        if (needsRenewal()) {
            try {
                log.info("Auto-renewing local certificate");
                renewLocalCertificate(365);
            } catch (Exception e) {
                log.error("Auto-renewal failed", e);
            }
        }
    }

    /**
     * Cleans up expired peer certificates.
     */
    public int cleanupExpiredCertificates() {
        int removed = 0;

        Iterator<Map.Entry<String, Certificate>> it = peerCertificates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Certificate> entry = it.next();
            if (entry.getValue().isExpired()) {
                it.remove();
                removed++;
                log.debug("Removed expired certificate", "nodeId", entry.getKey());
            }
        }

        if (removed > 0) {
            log.info("Expired certificates cleaned up", "count", removed);
        }

        return removed;
    }

    /**
     * Gets certificate statistics.
     */
    public CertificateStats getStats() {
        int total = peerCertificates.size();
        int trusted = (int) peerCertificates.values().stream()
                .filter(cert -> trustedNodeIds.contains(cert.getNodeId()))
                .count();
        int expired = (int) peerCertificates.values().stream()
                .filter(Certificate::isExpired)
                .count();

        return new CertificateStats(
                localCertificate != null ? 1 : 0,
                total,
                trusted,
                expired,
                revokedNodeIds.size()
        );
    }

    // ==================== Configuration ====================

    public void setAutoRenewEnabled(boolean enabled) {
        this.autoRenewEnabled = enabled;
    }

    public void setRenewBeforeDays(long days) {
        this.renewBeforeDays = days;
    }

    // ==================== Statistics ====================

    public static class CertificateStats {
        private final int localCertificates;
        private final int peerCertificates;
        private final int trustedCertificates;
        private final int expiredCertificates;
        private final int revokedCertificates;

        public CertificateStats(int localCertificates, int peerCertificates,
                               int trustedCertificates, int expiredCertificates,
                               int revokedCertificates) {
            this.localCertificates = localCertificates;
            this.peerCertificates = peerCertificates;
            this.trustedCertificates = trustedCertificates;
            this.expiredCertificates = expiredCertificates;
            this.revokedCertificates = revokedCertificates;
        }

        public int getLocalCertificates() { return localCertificates; }
        public int getPeerCertificates() { return peerCertificates; }
        public int getTrustedCertificates() { return trustedCertificates; }
        public int getExpiredCertificates() { return expiredCertificates; }
        public int getRevokedCertificates() { return revokedCertificates; }

        @Override
        public String toString() {
            return String.format("CertificateStats[local=%d, peers=%d, trusted=%d, expired=%d, revoked=%d]",
                    localCertificates, peerCertificates, trustedCertificates,
                    expiredCertificates, revokedCertificates);
        }
    }
}

