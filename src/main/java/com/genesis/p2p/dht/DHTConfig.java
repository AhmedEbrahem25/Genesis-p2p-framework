package com.genesis.p2p.dht;

import java.time.Duration;

public record DHTConfig(
        int kBucketSize, int alpha, int replicationFactor,
        Duration operationTimeout, Duration bucketRefreshInterval,
        Duration republishInterval, Duration valueExpiration, int maxStoredValues
) {
    public DHTConfig {
        if (kBucketSize <= 0) kBucketSize = 20;
        if (alpha <= 0) alpha = 3;
        if (replicationFactor <= 0) replicationFactor = 5;
        if (operationTimeout == null) operationTimeout = Duration.ofSeconds(5);
        if (bucketRefreshInterval == null) bucketRefreshInterval = Duration.ofHours(1);
        if (republishInterval == null) republishInterval = Duration.ofHours(1);
        if (valueExpiration == null) valueExpiration = Duration.ofHours(24);
        if (maxStoredValues <= 0) maxStoredValues = 10000;
    }

    public static DHTConfig defaults() {
        return new DHTConfig(20, 3, 5, Duration.ofSeconds(5),
                Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(24), 10000);
    }
}

