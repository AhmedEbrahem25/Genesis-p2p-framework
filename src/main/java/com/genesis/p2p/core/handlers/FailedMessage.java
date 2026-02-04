package com.genesis.p2p.core.handlers;

import com.genesis.p2p.core.Message;
import java.time.Instant;

/**
 * Represents a failed message in the dead letter queue.
 */
public record FailedMessage(
        Message message,
        String reason,
        Throwable exception,
        Instant failedAt,
        int retryCount
) {}