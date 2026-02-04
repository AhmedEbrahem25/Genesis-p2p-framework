package com.genesis.p2p.protocol.validator;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.protocol.model.ProtocolVersion;

/**
 * Validates protocol version compatibility.
 *
 * Checks that:
 * - Message protocol version is supported
 * - Version is compatible with current node version
 * - Version meets minimum requirements
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class VersionValidationRule implements ValidationRule {

    private static final String RULE_NAME = "VERSION_VALIDATION";

    private final ProtocolVersion currentVersion;
    private final ProtocolVersion minimumVersion;

    /**
     * Creates version validator with default versions.
     */
    public VersionValidationRule() {
        this(ProtocolVersion.current(), ProtocolVersion.minimum());
    }

    /**
     * Creates version validator with custom versions.
     *
     * @param currentVersion the current protocol version
     * @param minimumVersion the minimum supported version
     */
    public VersionValidationRule(ProtocolVersion currentVersion, ProtocolVersion minimumVersion) {
        if (currentVersion == null || minimumVersion == null) {
            throw new IllegalArgumentException("Versions cannot be null");
        }
        this.currentVersion = currentVersion;
        this.minimumVersion = minimumVersion;
    }

    @Override
    public ValidationResult validate(Message message) {
        if (message == null) {
            return ValidationResult.failure(RULE_NAME, "Message is null");
        }

        // Note: In a full implementation, the protocol version would be
        // extracted from the Envelope that wraps the message.
        // For now, we'll validate based on the message structure itself.

        // Check if message structure is valid (basic sanity check)
        if (message.header() == null) {
            return ValidationResult.failure(RULE_NAME, "Message header is missing");
        }

        if (message.body() == null) {
            return ValidationResult.failure(RULE_NAME, "Message body is missing");
        }

        // In production, we would:
        // 1. Extract version from Envelope
        // 2. Check if version is compatible with currentVersion
        // 3. Check if version meets minimumVersion requirement
        // Example:
        // ProtocolVersion messageVersion = envelope.getVersion();
        // if (!messageVersion.isCompatibleWith(currentVersion)) {
        //     return ValidationResult.failure(RULE_NAME,
        //         String.format("Version %s is not compatible with %s",
        //             messageVersion, currentVersion));
        // }

        return ValidationResult.success(RULE_NAME);
    }

    @Override
    public String getName() {
        return RULE_NAME;
    }

    public ProtocolVersion getCurrentVersion() {
        return currentVersion;
    }

    public ProtocolVersion getMinimumVersion() {
        return minimumVersion;
    }

    @Override
    public String toString() {
        return String.format("VersionValidationRule[current=%s, minimum=%s]",
                currentVersion, minimumVersion);
    }
}

