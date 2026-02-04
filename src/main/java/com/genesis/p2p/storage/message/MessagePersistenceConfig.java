package com.genesis.p2p.storage.message;

/**
 * Configuration for message persistence.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public record MessagePersistenceConfig(
        boolean enableCache,
        int maxCacheSize,
        int retentionDays,
        boolean enableCompression,
        boolean syncWrites
) {
    public static MessagePersistenceConfig defaults() {
        return new MessagePersistenceConfig(
                true,      // enableCache
                10000,     // maxCacheSize
                30,        // retentionDays
                false,     // enableCompression
                true       // syncWrites (for durability)
        );
    }

    public static MessagePersistenceConfig highPerformance() {
        return new MessagePersistenceConfig(
                true,      // enableCache
                50000,     // maxCacheSize
                7,         // retentionDays
                true,      // enableCompression
                false      // syncWrites (faster but less durable)
        );
    }

    public static MessagePersistenceConfig highDurability() {
        return new MessagePersistenceConfig(
                true,      // enableCache
                5000,      // maxCacheSize
                90,        // retentionDays
                false,     // enableCompression
                true       // syncWrites (maximum durability)
        );
    }
}

