package com.genesis.p2p.integration;

import com.genesis.p2p.testutil.base.BaseIntegrationTest;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.protocol.compression.GzipCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@DisplayName("Protocol Integration Tests")
class ProtocolIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should negotiate compatible protocol versions")
    void testVersionNegotiation() {
        ProtocolVersion local = new ProtocolVersion(2, 1, 0);
        ProtocolVersion remote = new ProtocolVersion(2, 0, 0);

        // Same major version = compatible
        assertTrue(local.isCompatibleWith(remote));
        assertTrue(remote.isCompatibleWith(local));
    }

    @Test
    @DisplayName("Should reject incompatible protocol versions")
    void testIncompatibleVersions() {
        ProtocolVersion v1 = new ProtocolVersion(1, 5, 0);
        ProtocolVersion v2 = new ProtocolVersion(2, 0, 0);

        // Different major version = incompatible
        assertFalse(v1.isCompatibleWith(v2));
        assertFalse(v2.isCompatibleWith(v1));
    }

    @Test
    @DisplayName("Should compress and decompress message payload")
    void testPayloadCompression() throws IOException {
        GzipCodec codec = new GzipCodec();

        String jsonPayload = "{\"type\":\"HELLO\",\"from\":\"node-1\",\"to\":\"node-2\",\"data\":{\"key\":\"value\",\"timestamp\":1234567890}}";
        byte[] original = jsonPayload.getBytes();

        byte[] compressed = codec.compress(original);
        byte[] decompressed = codec.decompress(compressed);

        assertArrayEquals(original, decompressed);
        assertEquals(jsonPayload, new String(decompressed));
    }

    @Test
    @DisplayName("Should achieve compression ratio for repetitive data")
    void testCompressionEfficiency() throws IOException {
        GzipCodec codec = new GzipCodec();

        // Create repetitive JSON-like data
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("{\"id\":").append(i).append(",\"name\":\"item\",\"value\":12345},");
        }
        byte[] original = sb.toString().getBytes();

        byte[] compressed = codec.compress(original);

        assertTrue(compressed.length < original.length,
            "Compressed size (" + compressed.length + ") should be less than original (" + original.length + ")");
    }

    @Test
    @DisplayName("Should parse protocol version from string")
    void testVersionParsing() {
        ProtocolVersion v1 = ProtocolVersion.parse("2.1.0");
        assertEquals(2, v1.getMajor());
        assertEquals(1, v1.getMinor());
        assertEquals(0, v1.getPatch());

        ProtocolVersion v2 = ProtocolVersion.parse("1.5");
        assertEquals(1, v2.getMajor());
        assertEquals(5, v2.getMinor());
        assertEquals(0, v2.getPatch());
    }

    @Test
    @DisplayName("Should handle current and minimum versions")
    void testCurrentAndMinimumVersions() {
        ProtocolVersion current = ProtocolVersion.current();
        ProtocolVersion minimum = ProtocolVersion.minimum();

        assertNotNull(current);
        assertNotNull(minimum);
        assertTrue(current.compareTo(minimum) >= 0);
    }

    @Test
    @DisplayName("Should compare protocol versions correctly")
    void testVersionComparison() {
        ProtocolVersion v100 = new ProtocolVersion(1, 0, 0);
        ProtocolVersion v110 = new ProtocolVersion(1, 1, 0);
        ProtocolVersion v111 = new ProtocolVersion(1, 1, 1);
        ProtocolVersion v200 = new ProtocolVersion(2, 0, 0);

        assertTrue(v100.compareTo(v110) < 0);
        assertTrue(v110.compareTo(v111) < 0);
        assertTrue(v111.compareTo(v200) < 0);
        assertEquals(0, v100.compareTo(new ProtocolVersion(1, 0, 0)));
    }

    @Test
    @DisplayName("Should handle compression with different levels")
    void testCompressionLevels() throws IOException {
        byte[] data = new byte[10000];
        java.util.Arrays.fill(data, (byte) 'A');

        GzipCodec fastCodec = new GzipCodec(1);
        GzipCodec bestCodec = new GzipCodec(9);

        byte[] fastCompressed = fastCodec.compress(data);
        byte[] bestCompressed = bestCodec.compress(data);

        // Both should decompress correctly
        assertArrayEquals(data, fastCodec.decompress(fastCompressed));
        assertArrayEquals(data, bestCodec.decompress(bestCompressed));

        // Best compression should typically produce smaller output
        // (though not guaranteed for all inputs)
        assertTrue(bestCompressed.length <= fastCompressed.length);
    }
}

