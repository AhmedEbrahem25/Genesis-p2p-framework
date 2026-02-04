package com.genesis.p2p.transport.core;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransportConfig Tests")
class TransportConfigTest extends BaseUnitTest {

    @Test
    @DisplayName("Should create default config")
    void testDefaults() {
        TransportConfig config = TransportConfig.defaults();

        assertNotNull(config);
        assertEquals(8080, config.port());
        assertEquals("0.0.0.0", config.bindAddress());
    }

    @Test
    @DisplayName("Should have valid buffer sizes")
    void testBufferSizes() {
        TransportConfig config = TransportConfig.defaults();

        assertTrue(config.sendBufferSize() > 0);
        assertTrue(config.receiveBufferSize() > 0);
    }

    @Test
    @DisplayName("Should have valid connection timeout")
    void testConnectionTimeout() {
        TransportConfig config = TransportConfig.defaults();

        assertTrue(config.connectionTimeout() > 0);
    }

    @Test
    @DisplayName("Should have encryption enabled by default")
    void testEncryptionEnabled() {
        TransportConfig config = TransportConfig.defaults();

        assertTrue(config.enableEncryption());
    }

    @Test
    @DisplayName("Should have compression disabled by default")
    void testCompressionDisabled() {
        TransportConfig config = TransportConfig.defaults();

        assertFalse(config.enableCompression());
    }

    @Test
    @DisplayName("Should create custom config")
    void testCustomConfig() {
        TransportConfig config = new TransportConfig(
            9999,           // port
            "127.0.0.1",   // bind address
            32768,         // send buffer
            32768,         // receive buffer
            3000,          // timeout
            false,         // no encryption
            true           // compression
        );

        assertEquals(9999, config.port());
        assertEquals("127.0.0.1", config.bindAddress());
        assertEquals(32768, config.sendBufferSize());
        assertEquals(32768, config.receiveBufferSize());
        assertEquals(3000, config.connectionTimeout());
        assertFalse(config.enableEncryption());
        assertTrue(config.enableCompression());
    }

    @Test
    @DisplayName("Should support record equality")
    void testEquality() {
        TransportConfig config1 = TransportConfig.defaults();
        TransportConfig config2 = TransportConfig.defaults();

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
    }
}

