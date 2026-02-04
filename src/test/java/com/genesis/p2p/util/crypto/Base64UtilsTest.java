package com.genesis.p2p.util.crypto;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Base64Utils Tests")
class Base64UtilsTest extends BaseUnitTest {

    @Test
    @DisplayName("Should encode bytes to base64")
    void testEncode() {
        byte[] bytes = "Hello, World!".getBytes();
        String encoded = Base64Utils.encode(bytes);
        assertNotNull(encoded);
        assertTrue(encoded.length() > 0);
        assertEquals("SGVsbG8sIFdvcmxkIQ==", encoded);
    }

    @Test
    @DisplayName("Should decode base64 to bytes")
    void testDecode() {
        byte[] original = "Hello, World!".getBytes();
        String encoded = Base64Utils.encode(original);
        byte[] decoded = Base64Utils.decode(encoded);
        assertArrayEquals(original, decoded);
    }

    @Test
    @DisplayName("Should handle null input")
    void testNullInput() {
        assertNull(Base64Utils.encode((byte[]) null));
        assertNull(Base64Utils.decode(null));
    }

    @Test
    @DisplayName("Should handle empty bytes")
    void testEmptyBytes() {
        byte[] bytes = {};
        String encoded = Base64Utils.encode(bytes);
        assertNotNull(encoded);
        assertEquals("", encoded);
    }

    @Test
    @DisplayName("Should encode string to base64")
    void testEncodeString() {
        String str = "Hello, World!";
        String encoded = Base64Utils.encode(str);
        assertNotNull(encoded);
        assertEquals("SGVsbG8sIFdvcmxkIQ==", encoded);
    }

    @Test
    @DisplayName("Should roundtrip correctly")
    void testRoundtrip() {
        byte[] original = new byte[256];
        for (int i = 0; i < 256; i++) {
            original[i] = (byte) i;
        }
        String encoded = Base64Utils.encode(original);
        byte[] decoded = Base64Utils.decode(encoded);
        assertArrayEquals(original, decoded);
    }
}
