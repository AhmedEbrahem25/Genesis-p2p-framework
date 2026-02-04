// ========================= TraceIdGenerator.java =========================
package com.genesis.p2p.observability.tracing;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates unique trace IDs.
 */
public class TraceIdGenerator {

    private static final AtomicLong counter = new AtomicLong(0);
    private static final String nodePrefix = UUID.randomUUID().toString().substring(0, 8);

    /**
     * Generates a short trace ID.
     */
    public static String generate() {
        return String.format("%s-%016x", nodePrefix, counter.incrementAndGet());
    }

    /**
     * Generates a full UUID trace ID.
     */
    public static String generateFull() {
        return UUID.randomUUID().toString();
    }
}