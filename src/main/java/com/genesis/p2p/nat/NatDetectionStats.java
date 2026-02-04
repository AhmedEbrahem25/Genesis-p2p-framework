package com.genesis.p2p.nat;

import java.time.Instant;

/**
 * Statistics for NAT detection operations.
 */
public record NatDetectionStats(
        int totalDetections,
        int successfulDetections,
        int failedDetections,
        NatType lastDetectedType,
        Instant lastDetectionTime,
        long averageDetectionTimeMs,
        int stunRequestsSent,
        int stunResponsesReceived
) {

    /**
     * Creates empty statistics.
     */
    public static NatDetectionStats empty() {
        return new NatDetectionStats(
                0, 0, 0,
                NatType.UNKNOWN,
                null,
                0, 0, 0
        );
    }

    /**
     * Calculates success rate percentage.
     */
    public double getSuccessRate() {
        return totalDetections == 0 ? 0.0 :
                (double) successfulDetections / totalDetections * 100.0;
    }

    /**
     * Checks if statistics indicate healthy operation.
     */
    public boolean isHealthy() {
        return getSuccessRate() >= 80.0 &&
                lastDetectedType != NatType.UNKNOWN;
    }

    /**
     * Returns formatted summary.
     */
    public String getSummary() {
        return String.format(
                "NAT Stats: %d/%d successful (%.1f%%), avg %dms, type=%s",
                successfulDetections, totalDetections,
                getSuccessRate(), averageDetectionTimeMs,
                lastDetectedType
        );
    }
}