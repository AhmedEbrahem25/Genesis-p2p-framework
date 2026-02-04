package com.genesis.p2p.security.hmac;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import com.genesis.p2p.security.SecurityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HmacService Tests")
class HmacServiceTest extends BaseUnitTest {

    private byte[] generateKey(int size) {
        byte[] key = new byte[size];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    @DisplayName("Should compute HMAC")
    void testComputeHmac() {
        byte[] data = "test data".getBytes();
        byte[] key = generateKey(32);

        byte[] hmac = HmacService.computeHmac(data, key);

        assertNotNull(hmac);
        assertEquals(32, hmac.length); // SHA-256 produces 32 bytes
    }

    @Test
    @DisplayName("Should produce consistent HMAC for same inputs")
    void testConsistentHmac() {
        byte[] data = "test data".getBytes();
        byte[] key = generateKey(32);

        byte[] hmac1 = HmacService.computeHmac(data, key);
        byte[] hmac2 = HmacService.computeHmac(data, key);

        assertArrayEquals(hmac1, hmac2);
    }

    @Test
    @DisplayName("Should produce different HMAC for different data")
    void testDifferentData() {
        byte[] data1 = "test data 1".getBytes();
        byte[] data2 = "test data 2".getBytes();
        byte[] key = generateKey(32);

        byte[] hmac1 = HmacService.computeHmac(data1, key);
        byte[] hmac2 = HmacService.computeHmac(data2, key);

        assertFalse(java.util.Arrays.equals(hmac1, hmac2));
    }

    @Test
    @DisplayName("Should produce different HMAC for different keys")
    void testDifferentKeys() {
        byte[] data = "test data".getBytes();
        byte[] key1 = generateKey(32);
        byte[] key2 = generateKey(32);

        byte[] hmac1 = HmacService.computeHmac(data, key1);
        byte[] hmac2 = HmacService.computeHmac(data, key2);

        assertFalse(java.util.Arrays.equals(hmac1, hmac2));
    }

    @Test
    @DisplayName("Should verify valid HMAC")
    void testVerifyHmac() {
        byte[] data = "test data".getBytes();
        byte[] key = generateKey(32);

        byte[] hmac = HmacService.computeHmac(data, key);
        boolean valid = HmacService.verifyHmac(data, key, hmac);

        assertTrue(valid);
    }

    @Test
    @DisplayName("Should reject invalid HMAC")
    void testRejectInvalidHmac() {
        byte[] data = "test data".getBytes();
        byte[] key = generateKey(32);

        byte[] hmac = HmacService.computeHmac(data, key);
        // Corrupt the HMAC
        hmac[0] ^= 0xFF;

        boolean valid = HmacService.verifyHmac(data, key, hmac);

        assertFalse(valid);
    }

    @Test
    @DisplayName("Should reject HMAC for modified data")
    void testRejectModifiedData() {
        byte[] data = "test data".getBytes();
        byte[] key = generateKey(32);

        byte[] hmac = HmacService.computeHmac(data, key);

        byte[] modifiedData = "modified data".getBytes();
        boolean valid = HmacService.verifyHmac(modifiedData, key, hmac);

        assertFalse(valid);
    }

    @Test
    @DisplayName("Should throw on null data")
    void testNullData() {
        byte[] key = generateKey(32);
        assertThrows(SecurityException.class, () -> HmacService.computeHmac(null, key));
    }

    @Test
    @DisplayName("Should throw on empty data")
    void testEmptyData() {
        byte[] key = generateKey(32);
        assertThrows(SecurityException.class, () -> HmacService.computeHmac(new byte[0], key));
    }

    @Test
    @DisplayName("Should throw on null key")
    void testNullKey() {
        byte[] data = "test".getBytes();
        assertThrows(SecurityException.class, () -> HmacService.computeHmac(data, null));
    }

    @Test
    @DisplayName("Should throw on short key")
    void testShortKey() {
        byte[] data = "test".getBytes();
        byte[] shortKey = new byte[8]; // Too short
        assertThrows(SecurityException.class, () -> HmacService.computeHmac(data, shortKey));
    }

    @Test
    @DisplayName("Should handle large data")
    void testLargeData() {
        byte[] data = new byte[1024 * 1024]; // 1MB
        new SecureRandom().nextBytes(data);
        byte[] key = generateKey(32);

        byte[] hmac = HmacService.computeHmac(data, key);

        assertNotNull(hmac);
        assertTrue(HmacService.verifyHmac(data, key, hmac));
    }
}

