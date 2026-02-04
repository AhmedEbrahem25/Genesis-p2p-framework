package com.genesis.p2p.transport.tcp;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Classification of TCP errors for appropriate handling and metrics.
 *
 * Each classification has:
 * - Severity level for logging
 * - Suggested recovery action
 * - Whether the error is retryable
 *
 * This enables granular error handling without masking critical failures
 * or increasing log noise for expected scenarios.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public enum TcpErrorClassification {

    /**
     * Network instability - transient issues that may resolve with retry.
     * Examples: Connection reset, timeout, temporary network failure, connection refused
     */
    NETWORK_INSTABILITY(Severity.WARNING, RecoveryAction.RETRY_WITH_BACKOFF, true),

    /**
     * Peer misbehavior - protocol violation, should disconnect and optionally report.
     * Examples: Invalid frame length, malformed data, unexpected protocol messages
     */
    PEER_MISBEHAVIOR(Severity.WARNING, RecoveryAction.DISCONNECT_AND_REPORT, false),

    /**
     * Internal fault - our bug, needs investigation.
     * Examples: Null pointer, illegal state, unexpected exception types
     */
    INTERNAL_FAULT(Severity.ERROR, RecoveryAction.LOG_AND_DISCONNECT, false),

    /**
     * Expected disconnect - graceful closure, no action needed.
     * Examples: EOF during normal operation, socket closed by us during shutdown
     */
    EXPECTED_DISCONNECT(Severity.DEBUG, RecoveryAction.NONE, false);

    /**
     * Severity levels for logging classified errors.
     */
    public enum Severity {
        DEBUG,   // Expected behavior, no action needed
        INFO,    // Informational, normal operations
        WARNING, // Potential issue, may need attention
        ERROR    // Critical issue, needs investigation
    }

    /**
     * Recovery actions for each error classification.
     */
    public enum RecoveryAction {
        /** No action needed - error is expected/normal */
        NONE,
        /** Retry with exponential backoff - transient error */
        RETRY_WITH_BACKOFF,
        /** Disconnect and report to reputation system - peer issue */
        DISCONNECT_AND_REPORT,
        /** Log error for investigation and disconnect - our bug */
        LOG_AND_DISCONNECT
    }

    private final Severity severity;
    private final RecoveryAction recoveryAction;
    private final boolean retryable;

    TcpErrorClassification(Severity severity, RecoveryAction recoveryAction, boolean retryable) {
        this.severity = severity;
        this.recoveryAction = recoveryAction;
        this.retryable = retryable;
    }

    /**
     * Gets the severity level for logging.
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * Gets the recommended recovery action.
     */
    public RecoveryAction getRecoveryAction() {
        return recoveryAction;
    }

    /**
     * Returns true if the error is transient and retry may succeed.
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Gets the metric name suffix for this classification.
     */
    public String getMetricSuffix() {
        return name().toLowerCase();
    }

    /**
     * Classifies an exception into the appropriate error category.
     *
     * Classification rules:
     * - EOFException: Always EXPECTED_DISCONNECT (peer closed connection)
     * - SocketTimeoutException: NETWORK_INSTABILITY (transient, may retry)
     * - ConnectException: NETWORK_INSTABILITY (connection failed)
     * - SocketException during shutdown: EXPECTED_DISCONNECT
     * - SocketException with connection reset: NETWORK_INSTABILITY or EXPECTED based on session
     * - IOException with "invalid frame": PEER_MISBEHAVIOR
     * - NullPointerException/IllegalStateException: INTERNAL_FAULT
     * - Unknown exceptions: INTERNAL_FAULT (needs investigation)
     *
     * @param e the exception to classify
     * @param connectionClosedByUs true if we intentionally closed the connection
     * @param sessionActive true if an authenticated session was active
     * @return the appropriate error classification
     */
    public static TcpErrorClassification classify(Exception e,
                                                   boolean connectionClosedByUs,
                                                   boolean sessionActive) {
        // If we closed the connection, any error is expected
        if (connectionClosedByUs) {
            return EXPECTED_DISCONNECT;
        }

        // EOF = peer closed the connection
        if (e instanceof EOFException) {
            // EOF is always expected - peer chose to disconnect
            return EXPECTED_DISCONNECT;
        }

        // Timeout = network issue, may retry
        if (e instanceof SocketTimeoutException) {
            return NETWORK_INSTABILITY;
        }

        // Connection refused/failed = network issue
        if (e instanceof ConnectException) {
            return NETWORK_INSTABILITY;
        }

        // Socket exceptions require message analysis
        if (e instanceof SocketException) {
            return classifySocketException((SocketException) e, sessionActive);
        }

        // General IO exceptions
        if (e instanceof IOException) {
            return classifyIOException((IOException) e);
        }

        // Programming errors = internal fault
        if (e instanceof NullPointerException ||
            e instanceof IllegalStateException ||
            e instanceof IllegalArgumentException ||
            e instanceof IndexOutOfBoundsException) {
            return INTERNAL_FAULT;
        }

        // Unknown exception type = needs investigation
        return INTERNAL_FAULT;
    }

    /**
     * Classifies SocketException based on message content.
     */
    private static TcpErrorClassification classifySocketException(SocketException e,
                                                                   boolean sessionActive) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Socket closed by us during shutdown
        if (message.contains("socket closed")) {
            return EXPECTED_DISCONNECT;
        }

        // Connection reset by peer
        if (message.contains("connection reset")) {
            // During active session, reset is unexpected but not necessarily our fault
            // Before session, it's network instability
            return sessionActive ? EXPECTED_DISCONNECT : NETWORK_INSTABILITY;
        }

        // Broken pipe = connection was closed
        if (message.contains("broken pipe")) {
            return sessionActive ? EXPECTED_DISCONNECT : NETWORK_INSTABILITY;
        }

        // Network unreachable/no route = network issue
        if (message.contains("network is unreachable") ||
            message.contains("no route to host") ||
            message.contains("host is down")) {
            return NETWORK_INSTABILITY;
        }

        // Connection refused = network issue
        if (message.contains("connection refused")) {
            return NETWORK_INSTABILITY;
        }

        // Address already in use = configuration issue (internal)
        if (message.contains("address already in use")) {
            return INTERNAL_FAULT;
        }

        // Permission denied = configuration issue (internal)
        if (message.contains("permission denied")) {
            return INTERNAL_FAULT;
        }

        // Default for unknown socket exceptions = network issue
        return NETWORK_INSTABILITY;
    }

    /**
     * Classifies IOException based on message content.
     */
    private static TcpErrorClassification classifyIOException(IOException e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Protocol violations = peer misbehavior
        if (message.contains("invalid frame") ||
            message.contains("frame too large") ||
            message.contains("protocol error") ||
            message.contains("unexpected data") ||
            message.contains("malformed")) {
            return PEER_MISBEHAVIOR;
        }

        // Stream closed = expected
        if (message.contains("stream closed") ||
            message.contains("closed") && message.contains("stream")) {
            return EXPECTED_DISCONNECT;
        }

        // Default for unknown IO exceptions = network issue
        return NETWORK_INSTABILITY;
    }

    /**
     * Returns a human-readable description of this classification.
     */
    public String getDescription() {
        return switch (this) {
            case NETWORK_INSTABILITY -> "Transient network issue - may retry";
            case PEER_MISBEHAVIOR -> "Protocol violation by peer";
            case INTERNAL_FAULT -> "Internal error - needs investigation";
            case EXPECTED_DISCONNECT -> "Normal connection closure";
        };
    }

    @Override
    public String toString() {
        return String.format("TcpErrorClassification[%s, severity=%s, action=%s, retryable=%s]",
                name(), severity, recoveryAction, retryable);
    }
}
