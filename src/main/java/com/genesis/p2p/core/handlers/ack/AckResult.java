package com.genesis.p2p.core.handlers.ack;

import java.time.Instant;

/**
 * Result of an acknowledgment operation.
 */
public record AckResult(
        String messageId, String peerId, Status status, long rttMs,
        int attempts, String errorMessage, Instant timestamp
) {
    public enum Status {
        ACKNOWLEDGED, REJECTED, TIMEOUT, MAX_RETRIES_EXCEEDED, SEND_FAILED, CANCELLED, ERROR
    }

    public static AckResult acknowledged(String messageId, String peerId, long rttMs, int attempts) {
        return new AckResult(messageId, peerId, Status.ACKNOWLEDGED, rttMs, attempts, null, Instant.now());
    }

    public static AckResult rejected(String messageId, String peerId, String reason, int attempts) {
        return new AckResult(messageId, peerId, Status.REJECTED, 0, attempts, reason, Instant.now());
    }

    public static AckResult timeout(String messageId, String peerId, int attempts) {
        return new AckResult(messageId, peerId, Status.TIMEOUT, 0, attempts, "Timeout", Instant.now());
    }

    public static AckResult maxRetriesExceeded(String messageId, String peerId, int attempts) {
        return new AckResult(messageId, peerId, Status.MAX_RETRIES_EXCEEDED, 0, attempts, "Max retries", Instant.now());
    }

    public static AckResult sendFailed(String messageId, String peerId, String error) {
        return new AckResult(messageId, peerId, Status.SEND_FAILED, 0, 1, error, Instant.now());
    }

    public static AckResult cancelled(String messageId, String peerId, int attempts) {
        return new AckResult(messageId, peerId, Status.CANCELLED, 0, attempts, "Cancelled", Instant.now());
    }

    public static AckResult error(String messageId, String peerId, String error, int attempts) {
        return new AckResult(messageId, peerId, Status.ERROR, 0, attempts, error, Instant.now());
    }

    public boolean isSuccess() { return status == Status.ACKNOWLEDGED; }
    public boolean isFailed() { return status != Status.ACKNOWLEDGED; }
    public boolean isRejected() { return status == Status.REJECTED; }
    public boolean isTimeout() { return status == Status.TIMEOUT || status == Status.MAX_RETRIES_EXCEEDED; }
}

