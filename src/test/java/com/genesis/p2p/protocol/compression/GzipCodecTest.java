package com.genesis.p2p.protocol.compression;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GzipCodec Tests")
class GzipCodecTest extends BaseUnitTest {

    private GzipCodec codec;

    @BeforeEach
    void setUp() {
        codec = new GzipCodec();
    }

    @Test
    @DisplayName("Should create codec with default compression level")
    void testDefaultConstructor() {
        GzipCodec defaultCodec = new GzipCodec();
        assertNotNull(defaultCodec);
    }

    @Test
    @DisplayName("Should create codec with custom compression level")
    void testCustomLevel() {
        GzipCodec fastCodec = new GzipCodec(1);
        GzipCodec bestCodec = new GzipCodec(9);

        assertNotNull(fastCodec);
        assertNotNull(bestCodec);
    }

    @Test
    @DisplayName("Should throw on invalid compression level")
    void testInvalidLevel() {
        assertThrows(IllegalArgumentException.class, () -> new GzipCodec(0));
        assertThrows(IllegalArgumentException.class, () -> new GzipCodec(10));
    }

    @Test
    @DisplayName("Should compress data")
    void testCompress() throws IOException {
        byte[] original = "Hello, World! This is a test message for compression.".getBytes();

        byte[] compressed = codec.compress(original);

        assertNotNull(compressed);
        assertTrue(compressed.length > 0);
    }

    @Test
    @DisplayName("Should decompress data")
    void testDecompress() throws IOException {
        byte[] original = "Hello, World! This is a test message for compression.".getBytes();

        byte[] compressed = codec.compress(original);
        byte[] decompressed = codec.decompress(compressed);

        assertArrayEquals(original, decompressed);
    }

    @Test
    @DisplayName("Should compress and decompress large data")
    void testLargeData() throws IOException {
        // Create 1MB of repetitive data (compresses well)
        byte[] original = new byte[1024 * 1024];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 256);
        }

        byte[] compressed = codec.compress(original);
        byte[] decompressed = codec.decompress(compressed);

        assertArrayEquals(original, decompressed);
    }

    @Test
    @DisplayName("Should handle empty data")
    void testEmptyData() throws IOException {
        byte[] empty = new byte[0];

        byte[] compressed = codec.compress(empty);
        byte[] decompressed = codec.decompress(compressed);

        assertEquals(0, decompressed.length);
    }

    @Test
    @DisplayName("Should handle null data")
    void testNullData() throws IOException {
        byte[] compressed = codec.compress(null);
        assertEquals(0, compressed.length);
    }

    @Test
    @DisplayName("Should achieve compression for repetitive data")
    void testCompressionRatio() throws IOException {
        // Create repetitive data that should compress well
        byte[] original = new byte[10000];
        Arrays.fill(original, (byte) 'A');

        byte[] compressed = codec.compress(original);

        assertTrue(compressed.length < original.length,
            "Compressed size should be less than original for repetitive data");
    }

    @Test
    @DisplayName("Should return codec name")
    void testGetName() {
        assertEquals("gzip", codec.getName());
    }

    @Test
    @DisplayName("Should compress multiple times consistently")
    void testMultipleCompressions() throws IOException {
        byte[] data = "Test data for compression".getBytes();

        byte[] compressed1 = codec.compress(data);
        byte[] compressed2 = codec.compress(data);

        // Both should decompress to original
        assertArrayEquals(data, codec.decompress(compressed1));
        assertArrayEquals(data, codec.decompress(compressed2));
    }

    @Test
    @DisplayName("Should handle binary data")
    void testBinaryData() throws IOException {
        byte[] original = new byte[1000];
        new java.security.SecureRandom().nextBytes(original);

        byte[] compressed = codec.compress(original);
        byte[] decompressed = codec.decompress(compressed);

        assertArrayEquals(original, decompressed);
    }
}

