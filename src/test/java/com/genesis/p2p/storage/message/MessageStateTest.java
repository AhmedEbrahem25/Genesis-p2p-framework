package com.genesis.p2p.storage.message;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MessageState Tests")
class MessageStateTest extends BaseUnitTest {

    @Test
    @DisplayName("Should have all message states")
    void testAllStates() {
        MessageState[] states = MessageState.values();
        assertTrue(states.length > 0);
    }

    @Test
    @DisplayName("Should have PENDING state")
    void testPendingState() {
        assertNotNull(MessageState.valueOf("PENDING"));
    }

    @Test
    @DisplayName("Should have SENT state")
    void testSentState() {
        assertNotNull(MessageState.valueOf("SENT"));
    }

    @Test
    @DisplayName("Should have DELIVERED state")
    void testDeliveredState() {
        assertNotNull(MessageState.valueOf("DELIVERED"));
    }

    @Test
    @DisplayName("Should have FAILED state")
    void testFailedState() {
        assertNotNull(MessageState.valueOf("FAILED"));
    }
}

