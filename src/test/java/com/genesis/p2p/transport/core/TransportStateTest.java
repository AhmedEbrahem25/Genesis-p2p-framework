package com.genesis.p2p.transport.core;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransportState Tests")
class TransportStateTest extends BaseUnitTest {

    @Test
    @DisplayName("Should have all states")
    void testAllStates() {
        TransportState[] states = TransportState.values();
        assertTrue(states.length >= 4);
    }

    @Test
    @DisplayName("Should have CREATED state")
    void testCreatedState() {
        assertNotNull(TransportState.valueOf("CREATED"));
    }

    @Test
    @DisplayName("Should have STARTING state")
    void testStartingState() {
        assertNotNull(TransportState.valueOf("STARTING"));
    }

    @Test
    @DisplayName("Should have RUNNING state")
    void testRunningState() {
        assertNotNull(TransportState.valueOf("RUNNING"));
    }

    @Test
    @DisplayName("Should have STOPPED state")
    void testStoppedState() {
        assertNotNull(TransportState.valueOf("STOPPED"));
    }
}

