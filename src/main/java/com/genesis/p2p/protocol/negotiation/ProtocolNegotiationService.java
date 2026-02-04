package com.genesis.p2p.protocol.negotiation;

import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.observability.logging.NodeLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol Negotiation Service.
 *
 * Handles version negotiation between peers to ensure compatibility.
 * Uses semantic versioning with backward compatibility checks.
 *
 * Negotiation Process:
 * 1. Peer A sends supported versions [2.1, 2.0, 1.5]
 * 2. Peer B compares with its versions [2.0, 1.9, 1.5]
 * 3. Select highest common compatible version (2.0)
 * 4. Both peers use negotiated version for communication
 *
 * Features:
 * - Version compatibility checking
 * - Capability negotiation (compression, encryption, features)
 * - Fallback to older versions
 * - Per-peer version tracking
 * - Feature flag support
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ProtocolNegotiationService {

    private static final NodeLogger log = NodeLogger.getLogger(ProtocolNegotiationService.class);

    private final ProtocolVersion localVersion;
    private final List<ProtocolVersion> supportedVersions;
    private final Map<String, String> localCapabilities;
    private final Map<String, NegotiationResult> peerNegotiations;

    /**
     * Creates a protocol negotiation service.
     *
     * @param localVersion the current protocol version
     * @param supportedVersions list of supported versions (in descending order)
     */
    public ProtocolNegotiationService(ProtocolVersion localVersion,
                                     List<ProtocolVersion> supportedVersions) {
        this.localVersion = localVersion;
        this.supportedVersions = new ArrayList<>(supportedVersions);
        this.localCapabilities = new ConcurrentHashMap<>();
        this.peerNegotiations = new ConcurrentHashMap<>();

        // Sort versions in descending order (newest first)
        this.supportedVersions.sort(Collections.reverseOrder());

        log.info("ProtocolNegotiationService initialized",
                "localVersion", localVersion,
                "supportedVersions", supportedVersions.size());
    }

    /**
     * Creates a service with default supported versions.
     */
    public ProtocolNegotiationService() {
        this(ProtocolVersion.current(), getDefaultSupportedVersions());
    }

    /**
     * Registers a local capability.
     *
     * @param name capability name (e.g., "compression", "encryption")
     * @param value capability value (e.g., "gzip,lz4", "aes-gcm")
     */
    public void registerCapability(String name, String value) {
        localCapabilities.put(name, value);
        log.debug("Capability registered", "name", name, "value", value);
    }

    /**
     * Negotiates protocol version with a peer.
     *
     * @param peerId the peer identifier
     * @param peerVersions versions supported by the peer
     * @param peerCapabilities capabilities offered by the peer
     * @return negotiation result
     */
    public NegotiationResult negotiate(String peerId,
                                      List<ProtocolVersion> peerVersions,
                                      Map<String, String> peerCapabilities) {
        log.info("Starting protocol negotiation",
                "peerId", peerId,
                "peerVersions", peerVersions.size());

        // Find highest compatible version
        Optional<ProtocolVersion> negotiatedVersion = findCompatibleVersion(peerVersions);

        if (negotiatedVersion.isEmpty()) {
            log.warn("No compatible version found",
                    "peerId", peerId,
                    "localVersions", supportedVersions,
                    "peerVersions", peerVersions);

            NegotiationResult result = NegotiationResult.failure(
                    peerId,
                    "No compatible protocol version found"
            );
            peerNegotiations.put(peerId, result);
            return result;
        }

        // Negotiate capabilities
        Map<String, String> negotiatedCapabilities = negotiateCapabilities(peerCapabilities);

        // Create successful result
        NegotiationResult result = NegotiationResult.success(
                peerId,
                negotiatedVersion.get(),
                negotiatedCapabilities
        );

        peerNegotiations.put(peerId, result);

        log.info("Protocol negotiation succeeded",
                "peerId", peerId,
                "version", negotiatedVersion.get(),
                "capabilities", negotiatedCapabilities.size());

        return result;
    }

    /**
     * Finds the highest compatible version between local and peer.
     *
     * @param peerVersions versions supported by peer
     * @return optional negotiated version
     */
    private Optional<ProtocolVersion> findCompatibleVersion(List<ProtocolVersion> peerVersions) {
        // Try each local version from newest to oldest
        for (ProtocolVersion localVer : supportedVersions) {
            // Check if peer supports this version or a compatible one
            for (ProtocolVersion peerVer : peerVersions) {
                if (localVer.isCompatibleWith(peerVer)) {
                    // Use the lower version for safety
                    return Optional.of(
                            localVer.compareTo(peerVer) <= 0 ? localVer : peerVer
                    );
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Negotiates capabilities between local and peer.
     *
     * @param peerCapabilities peer's capabilities
     * @return negotiated capabilities (intersection)
     */
    private Map<String, String> negotiateCapabilities(Map<String, String> peerCapabilities) {
        Map<String, String> negotiated = new HashMap<>();

        for (Map.Entry<String, String> local : localCapabilities.entrySet()) {
            String capName = local.getKey();
            String localValue = local.getValue();
            String peerValue = peerCapabilities.get(capName);

            if (peerValue != null) {
                // Find common values (comma-separated lists)
                Set<String> localSet = new HashSet<>(Arrays.asList(localValue.split(",")));
                Set<String> peerSet = new HashSet<>(Arrays.asList(peerValue.split(",")));

                localSet.retainAll(peerSet); // Intersection

                if (!localSet.isEmpty()) {
                    // Select the first common value (can be improved with priority)
                    String negotiatedValue = localSet.iterator().next();
                    negotiated.put(capName, negotiatedValue);

                    log.debug("Capability negotiated",
                            "name", capName,
                            "value", negotiatedValue);
                }
            }
        }

        return negotiated;
    }

    /**
     * Gets the negotiated version for a peer.
     *
     * @param peerId the peer identifier
     * @return optional negotiation result
     */
    public Optional<NegotiationResult> getNegotiation(String peerId) {
        return Optional.ofNullable(peerNegotiations.get(peerId));
    }

    /**
     * Checks if a peer has successfully negotiated.
     *
     * @param peerId the peer identifier
     * @return true if negotiated successfully
     */
    public boolean isNegotiated(String peerId) {
        NegotiationResult result = peerNegotiations.get(peerId);
        return result != null && result.isSuccess();
    }

    /**
     * Gets the negotiated version for a peer.
     *
     * @param peerId the peer identifier
     * @return optional protocol version
     */
    public Optional<ProtocolVersion> getNegotiatedVersion(String peerId) {
        NegotiationResult result = peerNegotiations.get(peerId);
        return result != null && result.isSuccess() ?
                Optional.of(result.getVersion()) : Optional.empty();
    }

    /**
     * Clears negotiation for a peer (on disconnect).
     *
     * @param peerId the peer identifier
     */
    public void clearNegotiation(String peerId) {
        peerNegotiations.remove(peerId);
        log.debug("Negotiation cleared", "peerId", peerId);
    }

    /**
     * Gets all local capabilities.
     *
     * @return map of capabilities
     */
    public Map<String, String> getLocalCapabilities() {
        return new HashMap<>(localCapabilities);
    }

    /**
     * Gets local version.
     *
     * @return current protocol version
     */
    public ProtocolVersion getLocalVersion() {
        return localVersion;
    }

    /**
     * Gets all supported versions.
     *
     * @return list of supported versions
     */
    public List<ProtocolVersion> getSupportedVersions() {
        return new ArrayList<>(supportedVersions);
    }

    /**
     * Gets negotiation statistics.
     *
     * @return statistics
     */
    public NegotiationStats getStats() {
        int total = peerNegotiations.size();
        int successful = (int) peerNegotiations.values().stream()
                .filter(NegotiationResult::isSuccess)
                .count();
        int failed = total - successful;

        return new NegotiationStats(total, successful, failed);
    }

    /**
     * Gets default supported versions (current + older versions).
     */
    private static List<ProtocolVersion> getDefaultSupportedVersions() {
        List<ProtocolVersion> versions = new ArrayList<>();

        // Current version
        versions.add(ProtocolVersion.current());

        // Add commonly supported older versions
        // This should be configured based on actual version history
        versions.add(new ProtocolVersion(1, 9, 0));
        versions.add(new ProtocolVersion(1, 5, 0));

        return versions;
    }

    // ==================== Result Classes ====================

    /**
     * Result of protocol negotiation.
     */
    public static class NegotiationResult {
        private final String peerId;
        private final boolean success;
        private final ProtocolVersion version;
        private final Map<String, String> capabilities;
        private final String errorMessage;

        private NegotiationResult(String peerId, boolean success, ProtocolVersion version,
                                 Map<String, String> capabilities, String errorMessage) {
            this.peerId = peerId;
            this.success = success;
            this.version = version;
            this.capabilities = capabilities != null ?
                    Collections.unmodifiableMap(capabilities) : Collections.emptyMap();
            this.errorMessage = errorMessage;
        }

        public static NegotiationResult success(String peerId, ProtocolVersion version,
                                               Map<String, String> capabilities) {
            return new NegotiationResult(peerId, true, version, capabilities, null);
        }

        public static NegotiationResult failure(String peerId, String errorMessage) {
            return new NegotiationResult(peerId, false, null, null, errorMessage);
        }

        public String getPeerId() { return peerId; }
        public boolean isSuccess() { return success; }
        public ProtocolVersion getVersion() { return version; }
        public Map<String, String> getCapabilities() { return capabilities; }
        public String getErrorMessage() { return errorMessage; }

        /**
         * Gets a negotiated capability value.
         */
        public Optional<String> getCapability(String name) {
            return Optional.ofNullable(capabilities.get(name));
        }

        /**
         * Checks if a capability is available.
         */
        public boolean hasCapability(String name) {
            return capabilities.containsKey(name);
        }

        @Override
        public String toString() {
            if (success) {
                return String.format("NegotiationResult[success, peer=%s, version=%s, capabilities=%d]",
                        peerId, version, capabilities.size());
            } else {
                return String.format("NegotiationResult[failed, peer=%s, error=%s]",
                        peerId, errorMessage);
            }
        }
    }

    /**
     * Negotiation statistics.
     */
    public static class NegotiationStats {
        private final int total;
        private final int successful;
        private final int failed;

        public NegotiationStats(int total, int successful, int failed) {
            this.total = total;
            this.successful = successful;
            this.failed = failed;
        }

        public int getTotal() { return total; }
        public int getSuccessful() { return successful; }
        public int getFailed() { return failed; }
        public double getSuccessRate() {
            return total > 0 ? (double) successful / total : 0.0;
        }

        @Override
        public String toString() {
            return String.format("NegotiationStats[total=%d, successful=%d, failed=%d, rate=%.2f%%]",
                    total, successful, failed, getSuccessRate() * 100);
        }
    }
}

