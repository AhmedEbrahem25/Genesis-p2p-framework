package com.genesis.p2p.transport.core;

import java.time.Duration;
import java.time.Instant;

/**
 * Transport statistics.
 */
public record TransportStats(
        long bytesSent,
        long bytesReceived,
        long messagesSent,
        long messagesReceived,
        long sendErrors,
        long receiveErrors,
        Instant startedAt
) {
    public double getUptime() {
        return Duration.between(startedAt, Instant.now()).toSeconds();
    }

    public double getThroughput() {
        double uptime = getUptime();
        return uptime > 0 ? (bytesSent + bytesReceived) / uptime : 0;
    }
}
