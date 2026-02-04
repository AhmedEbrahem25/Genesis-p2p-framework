package com.genesis.p2p.protocol.handshake;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.util.common.Time;

import java.util.ArrayList;
import java.util.List;

/**
 * Validator for handshake requests and responses.
 *
 * Validates:
 * - Protocol version compatibility
 * - Timestamp validity (not expired, not from future)
 * - Node ID format
 * - Required fields presence
 * - Capability requirements
 * - Trust status (if SecurityFacade provided)
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class HandshakeValidator {

    private static final NodeLogger log = NodeLogger.getLogger(HandshakeValidator.class);
    private static final long MAX_TIMESTAMP_SKEW = 60000; // 60 seconds
    private static final long MAX_HANDSHAKE_AGE = 30000;  // 30 seconds
    private static final int MIN_NODE_ID_LENGTH = 8;
    private static final int MAX_NODE_ID_LENGTH = 256;

    private final ProtocolVersion currentVersion;
    private final ProtocolVersion minimumVersion;
    private final List<String> requiredCapabilities;
    private final SecurityFacade security;

    /**
     * Creates a validator with default version requirements.
     */
    public HandshakeValidator() {
        this(ProtocolVersion.current(), ProtocolVersion.minimum(), null);
    }

    /**
     * Creates a validator with custom version requirements.
     */
    public HandshakeValidator(ProtocolVersion currentVersion, ProtocolVersion minimumVersion) {
        this(currentVersion, minimumVersion, null);
    }

    /**
     * Creates a validator with security integration.
     */
    public HandshakeValidator(ProtocolVersion currentVersion, ProtocolVersion minimumVersion,
                            SecurityFacade security) {
        this.currentVersion = currentVersion;
        this.minimumVersion = minimumVersion;
        this.requiredCapabilities = new ArrayList<>();
        this.security = security;
    }

    /**
     * Adds a required capability.
     */
    public HandshakeValidator requireCapability(String capability) {
        this.requiredCapabilities.add(capability);
        return this;
    }

    /**
     * Validates a handshake request.
     */
    public ValidationResult validateRequest(HandshakeRequest request) {
        // Check null
        if (request == null) {
            return ValidationResult.failure("Request is null");
        }

        // Validate node ID
        String nodeId = request.getNodeId();
        if (nodeId == null || nodeId.isEmpty()) {
            return ValidationResult.failure("Node ID is missing");
        }
        if (nodeId.length() < MIN_NODE_ID_LENGTH || nodeId.length() > MAX_NODE_ID_LENGTH) {
            return ValidationResult.failure(
                    String.format("Node ID length must be between %d and %d characters",
                            MIN_NODE_ID_LENGTH, MAX_NODE_ID_LENGTH));
        }

        // Validate protocol version
        ProtocolVersion requestVersion = request.getProtocolVersion();
        if (requestVersion == null) {
            return ValidationResult.failure("Protocol version is missing");
        }

        if (!requestVersion.isCompatibleWith(currentVersion)) {
            return ValidationResult.failure(
                    String.format("Protocol version %s is not compatible with %s",
                            requestVersion, currentVersion));
        }

        if (requestVersion.compareTo(minimumVersion) < 0) {
            return ValidationResult.failure(
                    String.format("Protocol version %s is below minimum %s",
                            requestVersion, minimumVersion));
        }

        // Validate timestamp
        long now = Time.currentMillis();
        long timestamp = request.getTimestamp();

        if (timestamp > now + MAX_TIMESTAMP_SKEW) {
            return ValidationResult.failure("Timestamp is from the future");
        }

        if (now - timestamp > MAX_HANDSHAKE_AGE) {
            return ValidationResult.failure("Handshake request has expired");
        }

        // Validate required capabilities
        for (String required : requiredCapabilities) {
            if (!request.hasCapability(required)) {
                return ValidationResult.failure(
                        String.format("Missing required capability: %s", required));
            }
        }

        // Security validation if SecurityFacade available
        if (security != null) {
            // Check if node is trusted
            try {
                boolean isTrusted = security.isTrusted(nodeId);
                if (!isTrusted) {
                    log.info("Handshake from untrusted node", "nodeId", nodeId);
                    // Allow untrusted nodes but log it
                    // In strict mode, could reject here
                }
            } catch (Exception e) {
                log.warn("Failed to check trust status", e, "nodeId", nodeId);
            }

            // Note: Signature verification would require HandshakeRequest to have signature field
            // For now, we rely on the public key exchange and trust model
            log.debug("Handshake validated with security checks", "nodeId", nodeId);
        }

        return ValidationResult.success();
    }


    /**
     * Validates a handshake response.
     */
    public ValidationResult validateResponse(HandshakeResponse response) {
        // Check null
        if (response == null) {
            return ValidationResult.failure("Response is null");
        }

        // For rejected responses, only basic validation
        if (response.isRejected()) {
            if (response.getReason() == null || response.getReason().isEmpty()) {
                return ValidationResult.failure("Rejection reason is required");
            }
            return ValidationResult.success();
        }

        // For accepted responses, validate all fields
        String nodeId = response.getNodeId();
        if (nodeId == null || nodeId.isEmpty()) {
            return ValidationResult.failure("Node ID is missing in accepted response");
        }

        if (nodeId.length() < MIN_NODE_ID_LENGTH || nodeId.length() > MAX_NODE_ID_LENGTH) {
            return ValidationResult.failure(
                    String.format("Node ID length must be between %d and %d characters",
                            MIN_NODE_ID_LENGTH, MAX_NODE_ID_LENGTH));
        }

        // Validate protocol version
        ProtocolVersion responseVersion = response.getProtocolVersion();
        if (responseVersion == null) {
            return ValidationResult.failure("Protocol version is missing in accepted response");
        }

        if (!responseVersion.isCompatibleWith(currentVersion)) {
            return ValidationResult.failure(
                    String.format("Protocol version %s is not compatible with %s",
                            responseVersion, currentVersion));
        }

        // Validate timestamp
        long now = Time.currentMillis();
        long timestamp = response.getTimestamp();

        if (timestamp > now + MAX_TIMESTAMP_SKEW) {
            return ValidationResult.failure("Response timestamp is from the future");
        }

        return ValidationResult.success();
    }

    /**
     * Validates a request-response pair.
     */
    public ValidationResult validatePair(HandshakeRequest request, HandshakeResponse response) {
        // Validate request
        ValidationResult requestResult = validateRequest(request);
        if (!requestResult.isValid()) {
            return requestResult;
        }

        // Validate response
        ValidationResult responseResult = validateResponse(response);
        if (!responseResult.isValid()) {
            return responseResult;
        }

        // Validate compatibility between request and response
        if (response.isAccepted()) {
            ProtocolVersion requestVersion = request.getProtocolVersion();
            ProtocolVersion responseVersion = response.getProtocolVersion();

            if (!requestVersion.isCompatibleWith(responseVersion)) {
                return ValidationResult.failure(
                        String.format("Request version %s not compatible with response version %s",
                                requestVersion, responseVersion));
            }
        }

        return ValidationResult.success();
    }

    // ==================== Validation Result ====================

    /**
     * Result of handshake validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            if (valid) {
                return "ValidationResult[valid=true]";
            } else {
                return String.format("ValidationResult[valid=false, error=%s]", errorMessage);
            }
        }
    }
}

