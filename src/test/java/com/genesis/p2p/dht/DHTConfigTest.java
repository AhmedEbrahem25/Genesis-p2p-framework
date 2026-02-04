package com.genesis.p2p.dht;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DHTConfig Tests")
class DHTConfigTest extends BaseUnitTest {

    @Test
    @DisplayName("Should create default config")
    void testDefaults() {
        DHTConfig config = DHTConfig.defaults();

        assertNotNull(config);
        assertEquals(20, config.kBucketSize());
        assertEquals(3, config.alpha());
        assertEquals(5, config.replicationFactor());
    }

    @Test
    @DisplayName("Should have valid operation timeout")
    void testOperationTimeout() {
        DHTConfig config = DHTConfig.defaults();

        assertNotNull(config.operationTimeout());
        assertTrue(config.operationTimeout().toMillis() > 0);
    }

    @Test
    @DisplayName("Should have valid bucket refresh interval")
    void testBucketRefreshInterval() {
        DHTConfig config = DHTConfig.defaults();

        assertNotNull(config.bucketRefreshInterval());
        assertTrue(config.bucketRefreshInterval().toMillis() > 0);
    }

    @Test
    @DisplayName("Should have valid republish interval")
    void testRepublishInterval() {
        DHTConfig config = DHTConfig.defaults();

        assertNotNull(config.republishInterval());
        assertTrue(config.republishInterval().toMillis() > 0);
    }

    @Test
    @DisplayName("Should have valid value expiration")
    void testValueExpiration() {
        DHTConfig config = DHTConfig.defaults();

        assertNotNull(config.valueExpiration());
        assertTrue(config.valueExpiration().toHours() > 0);
    }

    @Test
    @DisplayName("Should have valid max stored values")
    void testMaxStoredValues() {
        DHTConfig config = DHTConfig.defaults();

        assertTrue(config.maxStoredValues() > 0);
    }

    @Test
    @DisplayName("Should create custom config")
    void testCustomConfig() {
        DHTConfig config = new DHTConfig(
            10, 5, 3,
            Duration.ofSeconds(10),
            Duration.ofMinutes(30),
            Duration.ofMinutes(45),
            Duration.ofHours(12),
            5000
        );

        assertEquals(10, config.kBucketSize());
        assertEquals(5, config.alpha());
        assertEquals(3, config.replicationFactor());
        assertEquals(Duration.ofSeconds(10), config.operationTimeout());
        assertEquals(Duration.ofMinutes(30), config.bucketRefreshInterval());
        assertEquals(Duration.ofMinutes(45), config.republishInterval());
        assertEquals(Duration.ofHours(12), config.valueExpiration());
        assertEquals(5000, config.maxStoredValues());
    }

    @Test
    @DisplayName("Should apply defaults for invalid values")
    void testDefaultsForInvalidValues() {
        DHTConfig config = new DHTConfig(0, 0, 0, null, null, null, null, 0);

        assertEquals(20, config.kBucketSize());
        assertEquals(3, config.alpha());
        assertEquals(5, config.replicationFactor());
        assertNotNull(config.operationTimeout());
        assertNotNull(config.bucketRefreshInterval());
        assertNotNull(config.republishInterval());
        assertNotNull(config.valueExpiration());
        assertEquals(10000, config.maxStoredValues());
    }
}

