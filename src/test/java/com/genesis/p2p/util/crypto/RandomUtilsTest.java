package com.genesis.p2p.util.crypto;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RandomUtils Tests")
class RandomUtilsTest extends BaseUnitTest {

    @Test
    @DisplayName("Should generate secure random bytes")
    void testSecureBytes() {
        byte[] bytes = RandomUtils.secureBytes(16);
        assertNotNull(bytes);
        assertEquals(16, bytes.length);
    }

    @Test
    @DisplayName("Should generate different secure random bytes")
    void testSecureBytesDifferent() {
        byte[] bytes1 = RandomUtils.secureBytes(16);
        byte[] bytes2 = RandomUtils.secureBytes(16);
        assertFalse(java.util.Arrays.equals(bytes1, bytes2));
    }

    @Test
    @DisplayName("Should generate secure random int")
    void testSecureInt() {
        int value = RandomUtils.secureInt();
        // Just verify it returns without exception
        assertNotNull(value);
    }

    @Test
    @DisplayName("Should generate secure random int with bound")
    void testSecureIntWithBound() {
        int value = RandomUtils.secureInt(100);
        assertTrue(value >= 0 && value < 100);
    }

    @Test
    @DisplayName("Should generate secure random int in range")
    void testSecureIntInRange() {
        int value = RandomUtils.secureInt(10, 20);
        assertTrue(value >= 10 && value <= 20);
    }

    @Test
    @DisplayName("Should generate secure random long")
    void testSecureLong() {
        long value = RandomUtils.secureLong();
        // Just verify it returns without exception
        assertNotNull(value);
    }

    @Test
    @DisplayName("Should generate secure random long with bound")
    void testSecureLongWithBound() {
        long value = RandomUtils.secureLong(100);
        assertTrue(value >= 0 && value < 100);
    }

    @Test
    @DisplayName("Should generate secure random boolean")
    void testSecureBoolean() {
        boolean value = RandomUtils.secureBoolean();
        // Just verify it returns without exception
        assertTrue(value || !value);
    }

    @Test
    @DisplayName("Should generate secure random double")
    void testSecureDouble() {
        double value = RandomUtils.secureDouble();
        assertTrue(value >= 0.0 && value < 1.0);
    }

    @Test
    @DisplayName("Should throw on invalid byte length")
    void testSecureBytesInvalidLength() {
        assertThrows(IllegalArgumentException.class, () -> RandomUtils.secureBytes(0));
        assertThrows(IllegalArgumentException.class, () -> RandomUtils.secureBytes(-1));
    }
}

