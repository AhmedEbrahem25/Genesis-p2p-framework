package com.genesis.p2p.util.common;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HexUtils Tests")
class HexUtilsTest extends BaseUnitTest {

    @Test
    @DisplayName("Should encode bytes to hex")
    void testToHex() {
        byte[] bytes = {0x0A, 0x0B, 0x0C};
        String hex = HexUtils.toHex(bytes);
        assertNotNull(hex);
        assertEquals("0a0b0c", hex);
    }

    @Test
    @DisplayName("Should decode hex to bytes")
    void testFromHex() {
        String hex = "0a0b0c";
        byte[] bytes = HexUtils.fromHex(hex);
        assertNotNull(bytes);
        assertEquals(3, bytes.length);
        assertEquals(0x0A, bytes[0]);
        assertEquals(0x0B, bytes[1]);
        assertEquals(0x0C, bytes[2]);
    }

    @Test
    @DisplayName("Should handle uppercase hex")
    void testUppercaseHex() {
        String hex = "0A0B0C";
        byte[] bytes = HexUtils.fromHex(hex);
        assertNotNull(bytes);
        assertEquals(3, bytes.length);
    }

    @Test
    @DisplayName("Should handle mixed case hex")
    void testMixedCaseHex() {
        String hex = "0A0b0C";
        byte[] bytes = HexUtils.fromHex(hex);
        assertNotNull(bytes);
        assertEquals(3, bytes.length);
    }

    @Test
    @DisplayName("Should return null for null input")
    void testNullInput() {
        assertNull(HexUtils.toHex(null));
        assertNull(HexUtils.fromHex(null));
    }

    @Test
    @DisplayName("Should handle empty hex string")
    void testEmptyHex() {
        byte[] bytes = HexUtils.fromHex("");
        assertNotNull(bytes);
        assertEquals(0, bytes.length);
    }

    @Test
    @DisplayName("Should handle empty byte array")
    void testEmptyBytes() {
        String hex = HexUtils.toHex(new byte[0]);
        assertEquals("", hex);
    }

    @Test
    @DisplayName("Should roundtrip correctly")
    void testRoundtrip() {
        byte[] original = {0x01, 0x23, 0x45, 0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF};
        String hex = HexUtils.toHex(original);
        byte[] decoded = HexUtils.fromHex(hex);
        assertArrayEquals(original, decoded);
    }

    @Test
    @DisplayName("Should throw on odd length hex")
    void testOddLengthHex() {
        assertThrows(IllegalArgumentException.class, () -> HexUtils.fromHex("123"));
    }

    @Test
    @DisplayName("Should throw on invalid hex characters")
    void testInvalidHexChars() {
        assertThrows(IllegalArgumentException.class, () -> HexUtils.fromHex("ZZZZ"));
    }
}
