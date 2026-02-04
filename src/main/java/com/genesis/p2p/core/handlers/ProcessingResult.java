package com.genesis.p2p.core.handlers;

import java.time.Duration;

/**
 * Result of message processing.
 */
public record ProcessingResult(
        boolean success,
        String messageId,
        Duration processingTime,
        String error
) {
    public static ProcessingResult success(String messageId, Duration time) {
        return new ProcessingResult(true, messageId, time, null);
    }

    public static ProcessingResult failure(String messageId, Duration time, String error) {
        return new ProcessingResult(false, messageId, time, error);
    }
}