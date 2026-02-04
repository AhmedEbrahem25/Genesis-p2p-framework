package com.genesis.p2p.protocol;

/**
 * Base exception for protocol-related errors.
 *
 * Thrown when protocol operations fail:
 * - Encoding/decoding errors
 * - Validation failures
 * - Version incompatibility
 * - Malformed messages
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ProtocolException extends Exception {

    /**
     * Creates a protocol exception with a message.
     *
     * @param message error description
     */
    public ProtocolException(String message) {
        super(message);
    }

    /**
     * Creates a protocol exception with a message and cause.
     *
     * @param message error description
     * @param cause underlying cause
     */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a protocol exception with a cause.
     *
     * @param cause underlying cause
     */
    public ProtocolException(Throwable cause) {
        super(cause);
    }

    // ==================== Specific Exception Types ====================

    /**
     * Thrown when message encoding fails.
     */
    public static class EncodingException extends ProtocolException {
        public EncodingException(String message) {
            super(message);
        }

        public EncodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown when message decoding fails.
     */
    public static class DecodingException extends ProtocolException {
        public DecodingException(String message) {
            super(message);
        }

        public DecodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown when message validation fails.
     */
    public static class ValidationException extends ProtocolException {
        private final String ruleName;

        public ValidationException(String message) {
            this(message, null);
        }

        public ValidationException(String message, String ruleName) {
            super(message);
            this.ruleName = ruleName;
        }

        public String getRuleName() {
            return ruleName;
        }
    }

    /**
     * Thrown when protocol versions are incompatible.
     */
    public static class VersionIncompatibleException extends ProtocolException {
        private final String localVersion;
        private final String remoteVersion;

        public VersionIncompatibleException(String localVersion, String remoteVersion) {
            super(String.format("Version incompatibility: local=%s, remote=%s",
                    localVersion, remoteVersion));
            this.localVersion = localVersion;
            this.remoteVersion = remoteVersion;
        }

        public String getLocalVersion() {
            return localVersion;
        }

        public String getRemoteVersion() {
            return remoteVersion;
        }
    }

    /**
     * Thrown when message format is invalid.
     */
    public static class MalformedMessageException extends ProtocolException {
        public MalformedMessageException(String message) {
            super(message);
        }

        public MalformedMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

