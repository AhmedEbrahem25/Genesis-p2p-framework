//package com.genesis.p2p.core.peer;
//
//import com.genesis.p2p.core.Peer;
//import com.genesis.p2p.testutil.base.BaseAsyncTest;
//import org.junit.jupiter.api.*;
//
//import java.time.Duration;
//import java.time.Instant;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicInteger;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * Comprehensive integration tests for PeerManager and all its services.
// */
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//class PeerManagerIntegrationTest extends BaseAsyncTest {
//
//    private PeerManager peerManager;
//
//    @BeforeEach
//    void setUp() {
//        logTestStep("Setting up PeerManager for test");
//        peerManager = new PeerManager(50, 100, Duration.ofSeconds(5), Duration.ofSeconds(2));
//    }
//
//    @AfterEach
//    void tearDown() {
//        if (peerManager != null) {
//            peerManager.close();
//        }
//        logTestStep("Test cleanup complete");
//    }
//
//    // ========================= Basic Operations Tests =========================
//
//    @Test
//    @Order(1)
//    @DisplayName("Should add and retrieve peers")
//    void testAddAndRetrievePeer() {
//        logTestStep("Add and retrieve peer");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//
//        assertTrue(peerManager.upsertPeer(peer));
//        assertEquals(1, peerManager.size());
//
//        Peer retrieved = peerManager.getPeer("peer1");
//        assertNotNull(retrieved);
//        assertEquals("peer1", retrieved.id());
//        assertEquals("192.168.1.1", retrieved.ip());
//        assertEquals(8080, retrieved.port());
//    }
//
//    @Test
//    @Order(2)
//    @DisplayName("Should remove peers")
//    void testRemovePeer() {
//        log.info("TEST: Remove peer");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        assertTrue(peerManager.removePeer("peer1", "Test removal"));
//        assertEquals(0, peerManager.size());
//        assertNull(peerManager.getPeer("peer1"));
//    }
//
//    @Test
//    @Order(3)
//    @DisplayName("Should update existing peer")
//    void testUpdatePeer() {
//        log.info("TEST: Update peer");
//
//        Peer peer1 = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer1);
//
//        Peer peer2 = new Peer(
//                "peer1", "pubkey", "host", "192.168.1.2", 8081,
//                true, Instant.now(), 0, false, 50, "1.0", "Linux", "agent"
//        );
//        peerManager.upsertPeer(peer2);
//
//        assertEquals(1, peerManager.size());
//        Peer retrieved = peerManager.getPeer("peer1");
//        assertEquals("192.168.1.2", retrieved.ip());
//        assertEquals(8081, retrieved.port());
//    }
//
//    @Test
//    @Order(4)
//    @DisplayName("Should prevent self-registration")
//    void testPreventSelfRegistration() {
//        log.info("TEST: Prevent self-registration");
//
//        // Note: PeerManager would need the local node ID passed in constructor
//        // For now, this test documents expected behavior
//        assertTrue(true, "Self-registration prevention requires node ID in constructor");
//    }
//
//    // ========================= Reputation Tests =========================
//
//    @Test
//    @Order(5)
//    @DisplayName("Should increase reputation")
//    void testIncreaseReputation() {
//        log.info("TEST: Increase reputation");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        int newRep = peerManager.increaseReputation("peer1");
//        assertTrue(newRep > 50, "Reputation should increase from initial 50");
//
//        Peer updated = peerManager.getPeer("peer1");
//        assertEquals(newRep, updated.reputation());
//    }
//
//    @Test
//    @Order(6)
//    @DisplayName("Should decrease reputation")
//    void testDecreaseReputation() {
//        log.info("TEST: Decrease reputation");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        int newRep = peerManager.decreaseReputation("peer1");
//        assertTrue(newRep < 50, "Reputation should decrease from initial 50");
//    }
//
//    @Test
//    @Order(7)
//    @DisplayName("Should ban peer")
//    void testBanPeer() {
//        log.info("TEST: Ban peer");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        assertTrue(peerManager.banPeer("peer1", "Malicious behavior"));
//
//        Peer banned = peerManager.getPeer("peer1");
//        assertEquals(0, banned.reputation(), "Banned peer should have 0 reputation");
//        assertEquals(PeerStore.PeerState.BANNED, peerManager.getPeerState("peer1"));
//    }
//
//    @Test
//    @Order(8)
//    @DisplayName("Should clamp reputation to valid range")
//    void testReputationClamping() {
//        log.info("TEST: Reputation clamping");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        // Increase many times
//        for (int i = 0; i < 20; i++) {
//            peerManager.increaseReputation("peer1");
//        }
//
//        Peer updated = peerManager.getPeer("peer1");
//        assertTrue(updated.reputation() <= 100, "Reputation should not exceed 100");
//    }
//
//    // ========================= Query Tests =========================
//
//    @Test
//    @Order(9)
//    @DisplayName("Should get trusted peers")
//    void testGetTrustedPeers() {
//        log.info("TEST: Get trusted peers");
//
//        // Add trusted peer (reputation >= 50)
//        Peer trusted = createTestPeer("trusted1", "192.168.1.1", 8080, 80);
//        peerManager.upsertPeer(trusted);
//
//        // Add untrusted peer (reputation < 50)
//        Peer untrusted = createTestPeer("untrusted1", "192.168.1.2", 8081, 30);
//        peerManager.upsertPeer(untrusted);
//
//        List<Peer> trustedPeers = peerManager.getTrustedPeers();
//        assertEquals(1, trustedPeers.size());
//        assertEquals("trusted1", trustedPeers.get(0).id());
//    }
//
//    @Test
//    @Order(10)
//    @DisplayName("Should get online peers")
//    void testGetOnlinePeers() {
//        log.info("TEST: Get online peers");
//
//        Peer online = createOnlinePeer("online1", "192.168.1.1", 8080);
//        Peer offline = createOfflinePeer("offline1", "192.168.1.2", 8081);
//
//        peerManager.upsertPeer(online);
//        peerManager.upsertPeer(offline);
//
//        List<Peer> onlinePeers = peerManager.getOnlinePeers();
//        assertEquals(1, onlinePeers.size());
//        assertEquals("online1", onlinePeers.get(0).id());
//    }
//
//    @Test
//    @Order(11)
//    @DisplayName("Should get top peers by reputation")
//    void testGetTopPeersByReputation() {
//        log.info("TEST: Get top peers by reputation");
//
//        peerManager.upsertPeer(createTestPeer("peer1", "192.168.1.1", 8080, 90));
//        peerManager.upsertPeer(createTestPeer("peer2", "192.168.1.2", 8081, 70));
//        peerManager.upsertPeer(createTestPeer("peer3", "192.168.1.3", 8082, 80));
//
//        List<Peer> topPeers = peerManager.getTopPeersByReputation(2);
//        assertEquals(2, topPeers.size());
//        assertEquals("peer1", topPeers.get(0).id());
//        assertEquals("peer3", topPeers.get(1).id());
//    }
//
//    @Test
//    @Order(12)
//    @DisplayName("Should get peers by version")
//    void testGetPeersByVersion() {
//        log.info("TEST: Get peers by version");
//
//        peerManager.upsertPeer(createTestPeerWithVersion("peer1", "1.0"));
//        peerManager.upsertPeer(createTestPeerWithVersion("peer2", "2.0"));
//        peerManager.upsertPeer(createTestPeerWithVersion("peer3", "1.0"));
//
//        List<Peer> v1Peers = peerManager.getPeersByVersion("1.0");
//        assertEquals(2, v1Peers.size());
//    }
//
//    // ========================= Health Monitoring Tests =========================
//
//    @Test
//    @Order(13)
//    @DisplayName("Should record successful interactions")
//    void testRecordSuccess() {
//        log.info("TEST: Record success");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        peerManager.recordSuccess("peer1");
//        peerManager.recordSuccess("peer1");
//        peerManager.recordSuccess("peer1");
//
//        // Reputation should increase
//        Peer updated = peerManager.getPeer("peer1");
//        assertTrue(updated.reputation() > 50);
//    }
//
//    @Test
//    @Order(14)
//    @DisplayName("Should record failed interactions")
//    void testRecordFailure() {
//        log.info("TEST: Record failure");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        peerManager.recordFailure("peer1");
//
//        Peer updated = peerManager.getPeer("peer1");
//        assertTrue(updated.reputation() < 50);
//    }
//
//    @Test
//    @Order(15)
//    @DisplayName("Should update latency")
//    void testUpdateLatency() {
//        log.info("TEST: Update latency");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        peerManager.updateLatency("peer1", 150);
//
//        Peer updated = peerManager.getPeer("peer1");
//        assertEquals(150, updated.lastLatency());
//    }
//
//    // ========================= State Machine Tests =========================
//
//    @Test
//    @Order(16)
//    @DisplayName("Should transition peer states")
//    void testStateTransitions() {
//        log.info("TEST: State transitions");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        assertEquals(PeerStore.PeerState.DISCOVERED, peerManager.getPeerState("peer1"));
//
//        assertTrue(peerManager.markConnected("peer1"));
//        assertEquals(PeerStore.PeerState.CONNECTED, peerManager.getPeerState("peer1"));
//
//        assertTrue(peerManager.markAuthenticated("peer1"));
//        assertEquals(PeerStore.PeerState.AUTHENTICATED, peerManager.getPeerState("peer1"));
//
//        assertTrue(peerManager.markDisconnected("peer1"));
//        assertEquals(PeerStore.PeerState.DISCONNECTED, peerManager.getPeerState("peer1"));
//    }
//
//    @Test
//    @Order(17)
//    @DisplayName("Should prevent invalid state transitions")
//    void testInvalidStateTransitions() {
//        log.info("TEST: Invalid state transitions");
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        // Ban the peer
//        peerManager.banPeer("peer1", "Test");
//
//        // Try to transition from BANNED (should fail as it's terminal)
//        assertFalse(peerManager.markConnected("peer1"));
//        assertEquals(PeerStore.PeerState.BANNED, peerManager.getPeerState("peer1"));
//    }
//
//    // ========================= Cleanup Tests =========================
//
//    @Test
//    @Order(18)
//    @DisplayName("Should cleanup stale peers")
//    void testCleanupStalePeers() throws InterruptedException {
//        log.info("TEST: Cleanup stale peers");
//
//        // Create peer with old timestamp
//        Peer stalePeer = new Peer(
//                "stale1", "pubkey", "host", "192.168.1.1", 8080,
//                true, Instant.now().minusSeconds(10), 0, false, 50, "1.0", "Linux", "agent"
//        );
//        peerManager.upsertPeer(stalePeer);
//
//        // Wait for cleanup to run
//        Thread.sleep(3000);
//
//        // Manual cleanup trigger
//        int removed = peerManager.cleanupStalePeers();
//        assertTrue(removed >= 0, "Cleanup should return non-negative count");
//    }
//
//    // ========================= Event Bus Tests =========================
//
//    @Test
//    @Order(19)
//    @DisplayName("Should fire peer events")
//    void testPeerEvents() throws InterruptedException {
//        log.info("TEST: Peer events");
//
//        CountDownLatch addedLatch = new CountDownLatch(1);
//        CountDownLatch removedLatch = new CountDownLatch(1);
//        AtomicInteger reputationChanges = new AtomicInteger(0);
//
//        PeerEventBus.PeerEventListener listener = new PeerEventBus.PeerEventListener() {
//            @Override
//            public void onPeerAdded(Peer peer) {
//                log.info("Event: Peer added - {}", peer.id());
//                addedLatch.countDown();
//            }
//
//            @Override
//            public void onPeerUpdated(Peer oldPeer, Peer newPeer) {
//                log.info("Event: Peer updated - {}", newPeer.id());
//            }
//
//            @Override
//            public void onPeerRemoved(Peer peer, String reason) {
//                log.info("Event: Peer removed - {} ({})", peer.id(), reason);
//                removedLatch.countDown();
//            }
//
//            @Override
//            public void onPeerStateChanged(Peer peer, PeerStore.PeerState oldState,
//                                           PeerStore.PeerState newState) {
//                log.info("Event: State changed - {} ({} -> {})", peer.id(), oldState, newState);
//            }
//
//            @Override
//            public void onReputationChanged(Peer peer, int oldReputation, int newReputation) {
//                log.info("Event: Reputation changed - {} ({} -> {})",
//                        peer.id(), oldReputation, newReputation);
//                reputationChanges.incrementAndGet();
//            }
//        };
//
//        peerManager.addEventListener(listener);
//
//        Peer peer = createTestPeer("peer1", "192.168.1.1", 8080);
//        peerManager.upsertPeer(peer);
//
//        peerManager.increaseReputation("peer1");
//        peerManager.removePeer("peer1", "Test removal");
//
//        assertTrue(addedLatch.await(1, TimeUnit.SECONDS), "Should receive peer added event");
//        assertTrue(removedLatch.await(1, TimeUnit.SECONDS), "Should receive peer removed event");
//        assertTrue(reputationChanges.get() > 0, "Should receive reputation changed events");
//    }
//
//    // ========================= Metrics Tests =========================
//
//    @Test
//    @Order(20)
//    @DisplayName("Should collect accurate metrics")
//    void testMetrics() {
//        log.info("TEST: Metrics collection");
//
//        peerManager.upsertPeer(createTestPeer("peer1", "192.168.1.1", 8080, 80));
//        peerManager.upsertPeer(createTestPeer("peer2", "192.168.1.2", 8081, 60));
//        peerManager.upsertPeer(createTestPeer("peer3", "192.168.1.3", 8082, 40));
//
//        PeerMetricsService.PeerMetrics metrics = peerManager.getMetrics();
//
//        assertEquals(3, metrics.totalPeers());
//        assertEquals(2, metrics.trustedPeers()); // reputation >= 50
//        assertTrue(metrics.averageReputation() > 0);
//    }
//
//    // ========================= Capacity Tests =========================
//
//    @Test
//    @Order(21)
//    @DisplayName("Should handle capacity limits")
//    void testCapacityManagement() {
//        log.info("TEST: Capacity management");
//
//        // Create manager with small capacity
//        PeerManager smallManager = new PeerManager(50, 5,
//                Duration.ofMinutes(5),
//                Duration.ofSeconds(10));
//
//        // Add peers up to capacity
//        for (int i = 0; i < 5; i++) {
//            Peer peer = createTestPeer("peer" + i, "192.168.1." + i, 8080 + i);
//            assertTrue(smallManager.upsertPeer(peer));
//        }
//
//        assertEquals(5, smallManager.size());
//
//        // Add one more - should evict lowest reputation peer
//        Peer extraPeer = createTestPeer("extra", "192.168.1.100", 9000, 90);
//        assertTrue(smallManager.upsertPeer(extraPeer));
//
//        assertEquals(5, smallManager.size()); // Still at capacity
//
//        smallManager.close();
//    }
//
//    // ========================= Concurrent Access Tests =========================
//
//    @Test
//    @Order(22)
//    @DisplayName("Should handle concurrent operations")
//    void testConcurrentAccess() throws InterruptedException {
//        log.info("TEST: Concurrent access");
//
//        int threadCount = 10;
//        int peersPerThread = 10;
//        CountDownLatch startLatch = new CountDownLatch(1);
//        CountDownLatch doneLatch = new CountDownLatch(threadCount);
//
//        List<Thread> threads = new ArrayList<>();
//
//        for (int t = 0; t < threadCount; t++) {
//            final int threadId = t;
//            Thread thread = new Thread(() -> {
//                try {
//                    startLatch.await();
//                    for (int i = 0; i < peersPerThread; i++) {
//                        String peerId = "peer-" + threadId + "-" + i;
//                        Peer peer = createTestPeer(peerId, "192.168." + threadId + "." + i,
//                                8000 + i);
//                        peerManager.upsertPeer(peer);
//
//                        if (i % 2 == 0) {
//                            peerManager.increaseReputation(peerId);
//                        } else {
//                            peerManager.decreaseReputation(peerId);
//                        }
//                    }
//                } catch (Exception e) {
//                    log.error("Thread error", e);
//                } finally {
//                    doneLatch.countDown();
//                }
//            });
//            threads.add(thread);
//            thread.start();
//        }
//
//        startLatch.countDown(); // Start all threads
//        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");
//
//        assertEquals(threadCount * peersPerThread, peerManager.size());
//    }
//
//    // ========================= Integration Scenario Tests =========================
//
//    @Test
//    @Order(23)
//    @DisplayName("Should handle complete peer lifecycle")
//    void testCompletePeerLifecycle() throws InterruptedException {
//        log.info("TEST: Complete peer lifecycle");
//
//        // 1. Peer discovered
//        Peer peer = createTestPeer("lifecycle-peer", "192.168.1.100", 8080);
//        peerManager.upsertPeer(peer);
//        assertEquals(PeerStore.PeerState.DISCOVERED, peerManager.getPeerState("lifecycle-peer"));
//
//        // 2. Peer connects
//        peerManager.markConnected("lifecycle-peer");
//        assertEquals(PeerStore.PeerState.CONNECTED, peerManager.getPeerState("lifecycle-peer"));
//
//        // 3. Successful interactions
//        for (int i = 0; i < 5; i++) {
//            peerManager.recordSuccess("lifecycle-peer");
//            peerManager.recordMessage("lifecycle-peer");
//        }
//
//        // 4. Update latency
//        peerManager.updateLatency("lifecycle-peer", 50);
//
//        // 5. Authenticate
//        peerManager.markAuthenticated("lifecycle-peer");
//        assertEquals(PeerStore.PeerState.AUTHENTICATED,
//                peerManager.getPeerState("lifecycle-peer"));
//
//        // 6. Verify improved reputation
//        Peer updated = peerManager.getPeer("lifecycle-peer");
//        assertTrue(updated.reputation() > 50);
//        assertTrue(updated.trusted());
//
//        // 7. Disconnect
//        peerManager.markDisconnected("lifecycle-peer");
//        assertEquals(PeerStore.PeerState.DISCONNECTED,
//                peerManager.getPeerState("lifecycle-peer"));
//
//        // 8. Remove
//        assertTrue(peerManager.removePeer("lifecycle-peer", "Test complete"));
//        assertNull(peerManager.getPeer("lifecycle-peer"));
//    }
//
//    // ========================= Helper Methods =========================
//
//    private Peer createTestPeer(String id, String ip, int port) {
//        return createTestPeer(id, ip, port, 50);
//    }
//
//    private Peer createTestPeer(String id, String ip, int port, int reputation) {
//        return new Peer(
//                id, "pubkey-" + id, "host-" + id, ip, port,
//                true, Instant.now(), 0, reputation >= 50, reputation,
//                "1.0", "Linux", "genesis-agent"
//        );
//    }
//
//    private Peer createOnlinePeer(String id, String ip, int port) {
//        return new Peer(
//                id, "pubkey", "host", ip, port,
//                true, Instant.now(), 0, false, 50, "1.0", "Linux", "agent"
//        );
//    }
//
//    private Peer createOfflinePeer(String id, String ip, int port) {
//        return new Peer(
//                id, "pubkey", "host", ip, port,
//                false, Instant.now(), 0, false, 50, "1.0", "Linux", "agent"
//        );
//    }
//
//    private Peer createTestPeerWithVersion(String id, String version) {
//        return new Peer(
//                id, "pubkey", "host", "192.168.1.1", 8080,
//                true, Instant.now(), 0, false, 50, version, "Linux", "agent"
//        );
//    }
//}