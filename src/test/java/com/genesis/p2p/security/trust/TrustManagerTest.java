package com.genesis.p2p.security.trust;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TrustManager Tests")
class TrustManagerTest extends BaseUnitTest {

    private TrustManager trustManager;

    @BeforeEach
    void setUp() {
        trustManager = new TrustManager(50, 60);
    }

    @Test
    @DisplayName("Should create with default level")
    void testCreate() {
        TrustManager tm = new TrustManager(50);
        assertNotNull(tm);
    }

    @Test
    @DisplayName("Should create with custom threshold")
    void testCreateWithThreshold() {
        TrustManager tm = new TrustManager(50, 75);
        assertNotNull(tm);
    }

    @Test
    @DisplayName("Should set trust level for peer")
    void testTrustPeer() {
        trustManager.trustPeer("peer-1", 80);
        assertEquals(80, trustManager.getTrustLevel("peer-1"));
    }

    @Test
    @DisplayName("Should untrust peer")
    void testUntrustPeer() {
        trustManager.trustPeer("peer-1", 80);
        trustManager.untrustPeer("peer-1");
        assertEquals(0, trustManager.getTrustLevel("peer-1"));
    }

    @Test
    @DisplayName("Should return default level for unknown peer")
    void testUnknownPeer() {
        assertEquals(50, trustManager.getTrustLevel("unknown-peer"));
    }

    @Test
    @DisplayName("Should check if peer is trusted")
    void testIsTrusted() {
        trustManager.trustPeer("trusted-peer", 80);
        trustManager.trustPeer("untrusted-peer", 30);

        assertTrue(trustManager.isTrusted("trusted-peer"));
        assertFalse(trustManager.isTrusted("untrusted-peer"));
    }

    @Test
    @DisplayName("Should clamp trust level to valid range")
    void testClampTrustLevel() {
        trustManager.trustPeer("peer-high", 150);
        trustManager.trustPeer("peer-low", -50);

        assertEquals(100, trustManager.getTrustLevel("peer-high"));
        assertEquals(0, trustManager.getTrustLevel("peer-low"));
    }

    @Test
    @DisplayName("Should update trust level for existing peer")
    void testUpdateTrustLevel() {
        trustManager.trustPeer("peer-1", 50);
        assertEquals(50, trustManager.getTrustLevel("peer-1"));

        trustManager.trustPeer("peer-1", 80);
        assertEquals(80, trustManager.getTrustLevel("peer-1"));
    }

    @Test
    @DisplayName("Should handle multiple peers")
    void testMultiplePeers() {
        trustManager.trustPeer("peer-1", 60);
        trustManager.trustPeer("peer-2", 70);
        trustManager.trustPeer("peer-3", 80);

        assertEquals(60, trustManager.getTrustLevel("peer-1"));
        assertEquals(70, trustManager.getTrustLevel("peer-2"));
        assertEquals(80, trustManager.getTrustLevel("peer-3"));
    }

    @Test
    @DisplayName("Should be thread-safe")
    void testThreadSafety() throws InterruptedException {
        int threads = 10;
        Thread[] workers = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            workers[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    trustManager.trustPeer("peer-" + threadId, 50 + j % 50);
                }
            });
            workers[i].start();
        }

        for (Thread worker : workers) {
            worker.join();
        }

        // All peers should have valid trust levels
        for (int i = 0; i < threads; i++) {
            int level = trustManager.getTrustLevel("peer-" + i);
            assertTrue(level >= 0 && level <= 100);
        }
    }
}

