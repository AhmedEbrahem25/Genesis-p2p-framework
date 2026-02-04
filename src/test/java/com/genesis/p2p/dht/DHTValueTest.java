package com.genesis.p2p.dht;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DHTValue Tests")
class DHTValueTest extends BaseUnitTest {

    @Test
    @DisplayName("Should create DHTValue with all fields")
    void testCreate() {
        NodeId key = NodeId.random();
        byte[] data = "test data".getBytes();
        NodeId publisherId = NodeId.random();
        Instant storedAt = Instant.now();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1));

        DHTValue value = new DHTValue(key, data, publisherId, storedAt, expiresAt, 0);

        assertNotNull(value);
        assertEquals(key, value.key());
        assertArrayEquals(data, value.data());
        assertEquals(publisherId, value.publisherId());
        assertEquals(storedAt, value.storedAt());
        assertEquals(expiresAt, value.expiresAt());
        assertEquals(0, value.republishCount());
    }

    @Test
    @DisplayName("Should detect expired value")
    void testIsExpired() {
        NodeId key = NodeId.random();
        byte[] data = "test".getBytes();
        NodeId publisherId = NodeId.random();
        Instant storedAt = Instant.now().minus(Duration.ofHours(2));
        Instant expiresAt = Instant.now().minus(Duration.ofHours(1)); // Already expired

        DHTValue value = new DHTValue(key, data, publisherId, storedAt, expiresAt, 0);

        assertTrue(value.isExpired());
    }

    @Test
    @DisplayName("Should detect non-expired value")
    void testIsNotExpired() {
        NodeId key = NodeId.random();
        byte[] data = "test".getBytes();
        NodeId publisherId = NodeId.random();
        Instant storedAt = Instant.now();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1)); // Not expired

        DHTValue value = new DHTValue(key, data, publisherId, storedAt, expiresAt, 0);

        assertFalse(value.isExpired());
    }

    @Test
    @DisplayName("Should track republish count")
    void testRepublishCount() {
        NodeId key = NodeId.random();
        byte[] data = "test".getBytes();
        NodeId publisherId = NodeId.random();
        Instant storedAt = Instant.now();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1));

        DHTValue value = new DHTValue(key, data, publisherId, storedAt, expiresAt, 5);

        assertEquals(5, value.republishCount());
    }

    @Test
    @DisplayName("Should handle empty data")
    void testEmptyData() {
        NodeId key = NodeId.random();
        byte[] data = new byte[0];
        NodeId publisherId = NodeId.random();
        Instant storedAt = Instant.now();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1));

        DHTValue value = new DHTValue(key, data, publisherId, storedAt, expiresAt, 0);

        assertNotNull(value.data());
        assertEquals(0, value.data().length);
    }

    @Test
    @DisplayName("Should handle large data")
    void testLargeData() {
        NodeId key = NodeId.random();
        byte[] data = new byte[1024 * 1024]; // 1MB
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        NodeId publisherId = NodeId.random();
        Instant storedAt = Instant.now();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1));

        DHTValue value = new DHTValue(key, data, publisherId, storedAt, expiresAt, 0);

        assertArrayEquals(data, value.data());
    }
}

