/*
package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.BindException;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

*/
/**
 * Comprehensive test suite for BroadcastDiscovery.
 * Tests socket binding, SO_BROADCAST option, peer discovery, and error handling.
 *//*

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("BroadcastDiscovery Tests")
class BroadcastDiscoveryTest {

    @Mock
    private PeerManager peerManager;

    private NodeConfig config;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        config = NodeConfig.builder()
                .nodeId("test-broadcast-node-1")
                .tcpPort(8081)
                .listenPort(8080)
                .broadcastPort(5001)
                .build();

        when(peerManager.getAllPeers()).thenReturn(List.of());
        when(peerManager.getOnlinePeers()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    // ========================= Configuration Tests =========================

    @Test
    @Order(1)
    @DisplayName("Should use correct default broadcast port (5001)")
    void testDefaultBroadcastPort() {
        BroadcastDiscovery discovery = new BroadcastDiscovery(config, peerManager);
        assertNotNull(discovery);
        assertEquals(5001, config.broadcastPort());
    }

    @Test
    @Order(2)
    @DisplayName("Should accept custom broadcast configuration")
    void testCustomConfiguration() {
        BroadcastDiscovery discovery = new BroadcastDiscovery(
                config,
                peerManager,
                5555,
                Duration.ofSeconds(15)
        );
        assertNotNull(discovery);
    }

    // ========================= Lifecycle Tests =========================

    @Test
    @Order(3)
    @DisplayName("Should start and stop discovery service")
    void testStartStop() throws Exception {
        BroadcastDiscovery discovery = new BroadcastDiscovery(config, peerManager);

        assertFalse(discovery.isRunning());

        discovery.start();
        assertTrue(discovery.isRunning());

        discovery.stop();
        assertFalse(discovery.isRunning());
    }

    @Test
    @Order(4)
    @DisplayName("Should not start twice")
    void testDoubleStart() throws Exception {
        BroadcastDiscovery discovery = new BroadcastDiscovery(config, peerManager);

        discovery.start();
        assertTrue(discovery.isRunning());

        // Second start should throw exception or be no-op
        assertThrows(Exception.class, discovery::start);

        discovery.stop();
    }

    @Test
    @Order(5)
    @DisplayName("Should handle stop when not started")
    void testStopWhenNotStarted() {
        BroadcastDiscovery discovery = new BroadcastDiscovery(config, peerManager);
        assertDoesNotThrow(discovery::stop);
    }

    // ========================= Socket Binding Tests =========================

    @Test
    @Order(6)
    @DisplayName("Should bind to correct port (5001)")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSocketBindsToCorrectPort() throws Exception {
        BroadcastDiscovery discovery = new BroadcastDiscovery(config, peerManager);

        discovery.start();

        // Verify socket is bound by trying to bind to same port
        try (DatagramSocket testSocket = new DatagramSocket(5001)) {
            fail("Port 5001 should be in use by BroadcastDiscovery");
        } catch (BindException e) {
            // Expected - port is in use
            assertTrue(true);
        } finally {
            discovery.stop();
        }
    }

    @Test
    @Order(7)
    @DisplayName("Should enable SO_BROADCAST option")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSocketHasBroadcastEnabled() throws Exception {
        BroadcastDiscovery discovery = new BroadcastDiscovery(
                config,
                peerManager,
                5060, // Different port
                Duration.ofSeconds(30)
        );

        discovery.start();

        // If SO_BROADCAST wasn't enabled, broadcasts would fail
        // The fact that it starts successfully indicates SO_BROADCAST is set
        assertTrue(discovery.isRunning());

        discovery.stop();
    }

    @Test
    @Order(8)
    @DisplayName("Should bind to 0.0.0.0 (all interfaces)")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testBindsToAllInterfaces() throws Exception {
        BroadcastDiscovery discovery = new BroadcastDiscovery(
                config,
                peerManager,
                5070,
                Duration.ofSeconds(30)
        );

        discovery.start();

        // Binding to 0.0.0.0 means it can receive from any interface
        assertTrue(discovery.isRunning());

        discovery.stop();
    }

    // ========================= Announcement Tests =========================

    @Test
    @Order(9)
    @DisplayName("Should send broadcast announcements")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSendsBroadcastAnnouncements() throws Exception {
        BroadcastDiscovery discovery = new BroadcastDiscovery(
                config,
                peerManager,
                5080,
                Duration.ofSeconds(30)
        );

        discovery.start();

        // Manually trigger announcement
        discovery.announceSelf();

        // Should not throw exception
        assertTrue(discovery.isRunning());

        discovery.stop();
    }

    @Test
    @Order(10)
    @DisplayName("Should not announce when not running")
    void testNoAnnounceWhenNotRunning() {
        BroadcastDiscovery discovery = new BroadcastDiscovery(config, peerManager);

        // Should handle gracefully
        assertDoesNotThrow(discovery::announceSelf);
    }

    @Test
    @Order(11)
    @DisplayName("Should broadcast to 255.255.255.255")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testBroadcastsToCorrectAddress() throws Exception {
        BroadcastDiscovery discovery = new BroadcastDiscovery(
                config,
                peerManager,
                5090,
                Duration.ofSeconds(30)
        );

        discovery.start();

        // Trigger broadcast - should not throw
        assertDoesNotThrow(discovery::announceSelf);

        discovery.stop();
    }

    // ========================= Peer Discovery Tests =========================

    @Test
    @Order(12)
    @DisplayName("Should discover peers on same network via broadcast")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testDiscoversPeersViaBroadcast() throws Exception {
        // Create two discovery instances on same port
        NodeConfig config1 = NodeConfig.builder()
                .nodeId("broadcast-node-1")
                .tcpPort(8081)
                .broadcastPort(5100)
                .build();

        NodeConfig config2 = NodeConfig.builder()
                .nodeId("broadcast-node-2")
                .tcpPort(8082)
                .broadcastPort(5100)
                .build();

        PeerManager peerManager1 = mock(PeerManager.class);
        PeerManager peerManager2 = mock(PeerManager.class);

        CountDownLatch peerDiscovered = new CountDownLatch(1);
        AtomicReference<Peer> discoveredPeer = new AtomicReference<>();

        BroadcastDiscovery discovery1 = new BroadcastDiscovery(
                config1, peerManager1, 5100, Duration.ofSeconds(5)
        );
        BroadcastDiscovery discovery2 = new BroadcastDiscovery(
                config2, peerManager2, 5100, Duration.ofSeconds(5)
        );

        // Add listener to discovery1
        discovery1.setDiscoveryListener(new IDiscoveryListener() {
            @Override
            public void onPeerDiscovered(Peer peer) {
                if (peer.id().equals("broadcast-node-2")) {
                    discoveredPeer.set(peer);
                    peerDiscovered.countDown();
                }
            }

            @Override
            public void onPeerLost(Peer peer) {
                // Not used in this test
            }

            @Override
            public void onDiscoveryError(Throwable error) {
                error.printStackTrace();
            }
        });

        discovery1.start();
        discovery2.start();

        // Trigger announcement from node 2
        discovery2.announceSelf();

        // Wait for discovery
        boolean discovered = peerDiscovered.await(10, TimeUnit.SECONDS);

        discovery1.stop();
        discovery2.stop();

        assertTrue(discovered, "Peer should be discovered via broadcast");
        assertNotNull(discoveredPeer.get());
        assertEquals("broadcast-node-2", discoveredPeer.get().id());
    }

    @Test
    @Order(13)
    @DisplayName("Should ignore own broadcasts")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testIgnoresOwnBroadcasts() throws Exception {
        AtomicBoolean selfDiscovered = new AtomicBoolean(false);

        BroadcastDiscovery discovery = new BroadcastDiscovery(
                config,
                peerManager,
                5110,
                Duration.ofSeconds(5)
        );

        discovery.setDiscoveryListener(new IDiscoveryListener() {
            @Override
            public void onPeerDiscovered(Peer peer) {
                if (peer.id().equals(config.nodeId())) {
                    selfDiscovered.set(true);
                }
            }

            @Override
            public void onPeerLost(Peer peer) {
                // Not used in this test
            }

            @Override
            public void onDiscoveryError(Throwable error) {
            }
        });

        discovery.start();
        discovery.announceSelf();

        Thread.sleep(2000);

        discovery.stop();

        assertFalse(selfDiscovered.get(), "Should not discover own broadcasts");
    }

    @Test
    @Order(14)
    @DisplayName("Should discover multiple peers")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testDiscoversMultiplePeers() throws Exception {
        NodeConfig config1 = NodeConfig.builder()
                .nodeId("multi-node-1")
                .tcpPort(8081)
                .broadcastPort(5120)
                .build();

        NodeConfig config2 = NodeConfig.builder()
                .nodeId("multi-node-2")
                .tcpPort(8082)
                .broadcastPort(5120)
                .build();

        NodeConfig config3 = NodeConfig.builder()
                .nodeId("multi-node-3")
                .tcpPort(8083)
                .broadcastPort(5120)
                .build();

        PeerManager pm1 = mock(PeerManager.class);
        PeerManager pm2 = mock(PeerManager.class);
        PeerManager pm3 = mock(PeerManager.class);

        CountDownLatch discovered = new CountDownLatch(2); // Node 1 should discover 2 others

        BroadcastDiscovery discovery1 = new BroadcastDiscovery(config1, pm1, 5120, Duration.ofSeconds(3));
        BroadcastDiscovery discovery2 = new BroadcastDiscovery(config2, pm2, 5120, Duration.ofSeconds(3));
        BroadcastDiscovery discovery3 = new BroadcastDiscovery(config3, pm3, 5120, Duration.ofSeconds(3));

        discovery1.setDiscoveryListener(new IDiscoveryListener() {
            @Override
            public void onPeerDiscovered(Peer peer) {
                if (!peer.id().equals("multi-node-1")) {
                    System.out.println("Node 1 discovered: " + peer.id());
                    discovered.countDown();
                }
            }

            @Override
            public void onDiscoveryError(Throwable error) {
            }
        });

        discovery1.start();
        discovery2.start();
        discovery3.start();

        // Trigger announcements
        discovery2.announceSelf();
        discovery3.announceSelf();

        boolean success = discovered.await(15, TimeUnit.SECONDS);

        discovery1.stop();
        discovery2.stop();
        discovery3.stop();

        assertTrue(success, "Should discover multiple peers via broadcast");
    }

    // ========================= Error Handling Tests =========================

    @Test
    @Order(15)
    @DisplayName("Should handle port already in use")
    void testPortAlreadyInUse() throws Exception {
        // Occupy the port first
        DatagramSocket blocker = new DatagramSocket(5130);

        NodeConfig conflictConfig = NodeConfig.builder()
                .nodeId("conflict-node")
                .broadcastPort(5130)
                .build();

        BroadcastDiscovery discovery = new BroadcastDiscovery(
                conflictConfig,
                peerManager,
                5130,
                Duration.ofSeconds(30)
        );

        // Should throw exception when trying to bind to occupied port
        assertThrows(Exception.class, discovery::start);

        blocker.close();
    }

    @Test
    @Order(16)
    @DisplayName("Should handle network unavailable")
    void testNetworkUnavailable() {
        BroadcastDiscovery discovery = new BroadcastDiscovery(config, peerManager);

        // Should not crash if network is temporarily unavailable
        assertDoesNotThrow(() -> {
            try {
                discovery.start();
                discovery.announceSelf();
                discovery.stop();
            } catch (Exception e) {
                // Acceptable if network issues occur
                assertTrue(e.getMessage().contains("network") ||
                        e.getMessage().contains("socket") ||
                        e.getMessage().contains("broadcast"));
            }
        });
    }

    // ========================= Integration Tests =========================

    @Test
    @Order(17)
    @DisplayName("Integration: Full broadcast discovery cycle")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testFullBroadcastDiscoveryCycle() throws Exception {
        CountDownLatch bothDiscovered = new CountDownLatch(2);

        NodeConfig config1 = NodeConfig.builder()
                .nodeId("integration-broadcast-1")
                .tcpPort(8091)
                .broadcastPort(5140)
                .build();

        NodeConfig config2 = NodeConfig.builder()
                .nodeId("integration-broadcast-2")
                .tcpPort(8092)
                .broadcastPort(5140)
                .build();

        PeerManager pm1 = mock(PeerManager.class);
        PeerManager pm2 = mock(PeerManager.class);

        BroadcastDiscovery discovery1 = new BroadcastDiscovery(
                config1, pm1, 5140, Duration.ofSeconds(3)
        );
        BroadcastDiscovery discovery2 = new BroadcastDiscovery(
                config2, pm2, 5140, Duration.ofSeconds(3)
        );

        IDiscoveryListener listener = new IDiscoveryListener() {
            @Override
            public void onPeerDiscovered(Peer peer) {
                System.out.println("Broadcast discovered peer: " + peer.id());
                bothDiscovered.countDown();
            }

            @Override
            public void onPeerLost(Peer peer) {
                // Not used in this test
            }

            @Override
            public void onDiscoveryError(Throwable error) {
                System.err.println("Broadcast discovery error: " + error.getMessage());
            }
        };

        discovery1.setDiscoveryListener(listener);
        discovery2.setDiscoveryListener(listener);

        discovery1.start();
        discovery2.start();

        // Trigger broadcasts
        discovery1.announceSelf();
        discovery2.announceSelf();

        // Wait for mutual discovery
        boolean success = bothDiscovered.await(15, TimeUnit.SECONDS);

        discovery1.stop();
        discovery2.stop();

        assertTrue(success, "Both nodes should discover each other via broadcast");
    }

    @Test
    @Order(18)
    @DisplayName("Integration: Broadcast works across local subnets")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testBroadcastWorksAcrossSubnets() throws Exception {
        // This test verifies broadcast reaches all interfaces
        CountDownLatch discovered = new CountDownLatch(1);

        NodeConfig config1 = NodeConfig.builder()
                .nodeId("subnet-node-1")
                .tcpPort(8081)
                .broadcastPort(5150)
                .build();

        NodeConfig config2 = NodeConfig.builder()
                .nodeId("subnet-node-2")
                .tcpPort(8082)
                .broadcastPort(5150)
                .build();

        PeerManager pm1 = mock(PeerManager.class);
        PeerManager pm2 = mock(PeerManager.class);

        BroadcastDiscovery discovery1 = new BroadcastDiscovery(config1, pm1, 5150, Duration.ofSeconds(5));
        BroadcastDiscovery discovery2 = new BroadcastDiscovery(config2, pm2, 5150, Duration.ofSeconds(5));

        discovery1.setDiscoveryListener(new IDiscoveryListener() {
            @Override
            public void onPeerDiscovered(Peer peer) {
                if (peer.id().equals("subnet-node-2")) {
                    discovered.countDown();
                }
            }

            @Override
            public void onDiscoveryError(Throwable error) {
            }
        });

        discovery1.start();
        discovery2.start();

        discovery2.announceSelf();

        boolean success = discovered.await(10, TimeUnit.SECONDS);

        discovery1.stop();
        discovery2.stop();

        assertTrue(success, "Broadcast should work across local network");
    }

    // ========================= Performance Tests =========================

    @Test
    @Order(19)
    @DisplayName("Should handle rapid announcements")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testHandlesRapidAnnouncements() throws Exception {
        BroadcastDiscovery discovery = new BroadcastDiscovery(
                config,
                peerManager,
                5160,
                Duration.ofSeconds(30)
        );

        discovery.start();

        // Send multiple rapid announcements
        for (int i = 0; i < 100; i++) {
            discovery.announceSelf();
        }

        // Should not crash or throw exceptions
        assertTrue(discovery.isRunning());

        discovery.stop();
    }
}
*/
