package com.genesis.p2p.storage.message;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MessageDirection Tests")
class MessageDirectionTest extends BaseUnitTest {

    @Test
    @DisplayName("Should have all directions")
    void testAllDirections() {
        MessageDirection[] directions = MessageDirection.values();
        assertTrue(directions.length >= 2);
    }

    @Test
    @DisplayName("Should have INBOUND direction")
    void testInboundDirection() {
        assertNotNull(MessageDirection.valueOf("INBOUND"));
    }

    @Test
    @DisplayName("Should have OUTBOUND direction")
    void testOutboundDirection() {
        assertNotNull(MessageDirection.valueOf("OUTBOUND"));
    }
}

