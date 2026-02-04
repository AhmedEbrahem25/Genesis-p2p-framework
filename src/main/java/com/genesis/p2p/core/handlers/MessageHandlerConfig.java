package com.genesis.p2p.core.handlers;

import java.time.Duration;

/**
 * Configuration for MessageHandler.
 */
public record MessageHandlerConfig(
        int threadPoolSize,
        int queueCapacity,
        Duration processingTimeout,
        int maxRetries,
        Duration dedupWindow,
        int globalRateLimit,
        int perPeerRateLimit,
        int dlqCapacity,
        boolean enableCircuitBreaker,
        boolean enableDeduplication
) {
    public static MessageHandlerConfig defaults() {
        return new MessageHandlerConfig(
                10,                          // 10 threads
                10000,                       // 10k queue
                Duration.ofSeconds(30),      // 30s timeout
                3,                           // 3 retries
                Duration.ofMinutes(5),       // 5min dedup
                1000,                        // 1k msgs/sec global
                100,                         // 100 msgs/sec per peer
                1000,                        // 1k DLQ capacity
                true,                        // enable circuit breaker
                true                         // enable deduplication
        );
    }
}