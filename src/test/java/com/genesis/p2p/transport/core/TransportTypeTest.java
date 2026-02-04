package com.genesis.p2p.transport.core;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransportType Tests")
class TransportTypeTest extends BaseUnitTest {

    @Test
    @DisplayName("Should have all transport types")
    void testAllTypes() {
        TransportType[] types = TransportType.values();
        assertTrue(types.length >= 3);
    }

    @Test
    @DisplayName("Should have TCP type")
    void testTcpType() {
        assertNotNull(TransportType.valueOf("TCP"));
    }

    @Test
    @DisplayName("Should have UDP type")
    void testUdpType() {
        assertNotNull(TransportType.valueOf("UDP"));
    }

    @Test
    @DisplayName("Should have WebSocket type")
    void testWebSocketType() {
        assertNotNull(TransportType.valueOf("WEBSOCKET"));
    }
}

