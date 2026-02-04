package com.genesis.p2p.core.handlers;

import java.time.Duration;
import java.time.Instant;

/**
 * Statistics for a message processor.
 */
public record ProcessorStats(
        String messageType,
        long processedCount,
        long failedCount,
        int priority,
        boolean async,
        Duration timeout,
        Instant lastProcessed
) {
    public double getSuccessRate() {
        long total = processedCount + failedCount;
        if (total == 0) return 0.0;
        return (double) processedCount / total * 100.0;
    }
}