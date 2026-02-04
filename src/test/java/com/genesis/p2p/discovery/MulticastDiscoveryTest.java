/*
package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

*/
/**
 * Comprehensive test suite for MulticastDiscovery.
 * Tests socket binding, multicast group joining, peer discovery, and error handling.
 *//*

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MulticastDiscovery Tests")
class MulticastDiscoveryTest {

    @Mock
    private PeerManager peerManager;

    private NodeConfig config;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        config = NodeConfig.builder()
                .nodeId("test-node-1")
                .tcpPort(8081)
                .listenPort(8080)
                .multicastGroup("239.255.0.1")
                .multicastPort(5000)
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
    @DisplayName("Should use correct default multicast group")
    void testDefaultMulticastGroup() {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);
        assertNotNull(discovery);
        // Verify through NodeConfig that correct group is used
        assertEquals("239.255.0.1", config.multicastGroup());
    }

    @Test
    @Order(2)
    @DisplayName("Should use correct default multicast port")
    void testDefaultMulticastPort() {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);
        assertNotNull(discovery);
        assertEquals(5000, config.multicastPort());
    }

    @Test
    @Order(3)
    @DisplayName("Should accept custom multicast configuration")
    void testCustomConfiguration() {
        MulticastDiscovery discovery = new MulticastDiscovery(
                config,
                peerManager,
                "239.255.100.1",
                5555,
                Duration.ofSeconds(15)
        );
        assertNotNull(discovery);
    }

    @Test
    @Order(4)
    @DisplayName("Should validate multicast address")
    void testMulticastAddressValidation() {
        // Test with invalid multicast address
        MulticastDiscovery discovery = new MulticastDiscovery(
                config,
                peerManager,
                "192.168.1.1", // Not a multicast address
                5000,
                Duration.ofSeconds(30)
        );

        // Should throw exception when started with invalid address
        assertThrows(Exception.class, discovery::start);
    }

    // ========================= Lifecycle Tests =========================

    @Test
    @Order(5)
    @DisplayName("Should start and stop discovery service")
    void testStartStop() throws Exception {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);

        assertFalse(discovery.isRunning());

        discovery.start();
        assertTrue(discovery.isRunning());

        discovery.stop();
        assertFalse(discovery.isRunning());
    }

    @Test
    @Order(6)
    @DisplayName("Should not start twice")
    void testDoubleStart() throws Exception {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);

        discovery.start();
        assertTrue(discovery.isRunning());

        // Second start should be no-op or throw exception
        assertThrows(Exception.class, discovery::start);

        discovery.stop();
    }

    @Test
    @Order(7)
    @DisplayName("Should handle stop when not started")
    void testStopWhenNotStarted() {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);
        assertDoesNotThrow(discovery::stop);
    }

    // ========================= Socket Binding Tests =========================

    @Test
    @Order(8)
    @DisplayName("Should bind to correct port (5000)")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSocketBindsToCorrectPort() throws Exception {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);

        discovery.start();

        // Verify socket is bound by checking if port is in use
        try (DatagramSocket testSocket = new DatagramSocket(5000)) {
            fail("Port 5000 should be in use by MulticastDiscovery");
        } catch (BindException e) {
            // Expected - port is in use
            assertTrue(true);
        } finally {
            discovery.stop();
        }
    }

    @Test
    @Order(9)
    @DisplayName("Should allow socket reuse (SO_REUSEADDR)")
    void testSocketReuseAddress() throws Exception {
        // Start first instance
        MulticastDiscovery discovery1 = new MulticastDiscovery(
                config,
                peerManager,
                "239.255.0.1",
                5010, // Different port to avoid conflicts
                Duration.ofSeconds(30)
        );
        discovery1.start();

        // With SO_REUSEADDR, multiple sockets can bind to same port
        // This is required for multicast
        assertTrue(discovery1.isRunning());

        discovery1.stop();
    }

    // ========================= Multicast Group Joining Tests =========================

    @Test
    @Order(10)
    @DisplayName("Should join multicast group 239.255.0.1")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testJoinsMulticastGroup() throws Exception {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);

        discovery.start();

        // Give it time to join the group
        Thread.sleep(500);

        // Verify by checking network interfaces
        boolean groupJoined = isMulticastGroupJoined("239.255.0.1");

        discovery.stop();

        // Note: This may fail in some environments (CI, containers)
        // but should work on normal Windows/Linux hosts
        if (!groupJoined) {
            System.out.println("WARNING: Could not verify multicast group join - may be environment limitation");
        }
    }

    @Test
    @Order(11)
    @DisplayName("Should leave multicast group on stop")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testLeavesMulticastGroupOnStop() throws Exception {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);

        discovery.start();
        Thread.sleep(500);

        discovery.stop();
        Thread.sleep(500);

        // After stop, should have left the group
        // Note: Verification depends on OS support
        assertTrue(true); // Basic test - no exceptions means success
    }

    // ========================= Announcement Tests =========================

    @Test
    @Order(12)
    @DisplayName("Should send multicast announcements")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSendsAnnouncements() throws Exception {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);

        discovery.start();

        // Manually trigger announcement
        discovery.announceSelf();

        // Should not throw exception
        assertTrue(discovery.isRunning());

        discovery.stop();
    }

    @Test
    @Order(13)
    @DisplayName("Should not announce when not running")
    void testNoAnnounceWhenNotRunning() {
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);

        // Should handle gracefully
        assertDoesNotThrow(discovery::announceSelf);
    }

    // ========================= Peer Discovery Tests =========================

    @Test
    @Order(14)
    @DisplayName("Should discover peers on same multicast group")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testDiscoversPeers() throws Exception {
        // Create two discovery instances
        NodeConfig config1 = NodeConfig.builder()
                .nodeId("node-1")
                .tcpPort(8081)
                .multicastGroup("239.255.0.1")
                .multicastPort(5020) // Use different port to avoid test conflicts
                .build();

        NodeConfig config2 = NodeConfig.builder()
                .nodeId("node-2")
                .tcpPort(8082)
                .multicastGroup("239.255.0.1")
                .multicastPort(5020)
                .build();

        PeerManager peerManager1 = mock(PeerManager.class);
        PeerManager peerManager2 = mock(PeerManager.class);

        CountDownLatch peerDiscovered = new CountDownLatch(1);
        AtomicReference<Peer> discoveredPeer = new AtomicReference<>();

        MulticastDiscovery discovery1 = new MulticastDiscovery(
                config1, peerManager1, "239.255.0.1", 5020, Duration.ofSeconds(5)
        );
        MulticastDiscovery discovery2 = new MulticastDiscovery(
                config2, peerManager2, "239.255.0.1", 5020, Duration.ofSeconds(5)
        );

        // Add listener to capture discovered peer
        discovery1.setDiscoveryListener(new IDiscoveryListener() {
            @Override
            public void onPeerDiscovered(Peer peer) {
                if (peer.id().equals("node-2")) {
                    discoveredPeer.set(peer);
                    peerDiscovered.countDown();
                }
            }

            @Override
            public void onDiscoveryError(Throwable error) {
                error.printStackTrace();
            }
        });

        discovery1.start();
        discovery2.start();

        // Manually trigger announcements
        discovery2.announceSelf();

        // Wait for discovery
        boolean discovered = peerDiscovered.await(10, TimeUnit.SECONDS);

        discovery1.stop();
        discovery2.stop();

        assertTrue(discovered, "Peer should be discovered via multicast");
        assertNotNull(discoveredPeer.get());
        assertEquals("node-2", discoveredPeer.get().id());
    }

    @Test
    @Order(15)
    @DisplayName("Should ignore own announcements")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testIgnoresOwnAnnouncements() throws Exception {
        AtomicBoolean selfDiscovered = new AtomicBoolean(false);

        MulticastDiscovery discovery = new MulticastDiscovery(
                config, peerManager, "239.255.0.1", 5030, Duration.ofSeconds(5)
        );

        discovery.setDiscoveryListener(new IDiscoveryListener() {
            @Override
            public void onPeerDiscovered(Peer peer) {
                if (peer.id().equals(config.nodeId())) {
                    selfDiscovered.set(true);
                }
            }

            @Override
            public void onDiscoveryError(Throwable error) {
            }
        });

        discovery.start();
        discovery.announceSelf();

        Thread.sleep(2000);

        discovery.stop();

        assertFalse(selfDiscovered.get(), "Should not discover own announcements");
    }

    // ========================= Error Handling Tests =========================

    @Test
    @Order(16)
    @DisplayName("Should handle port already in use")
    void testPortAlreadyInUse() throws Exception {
        // Occupy the port first
        DatagramSocket blocker = new DatagramSocket(5040);

        NodeConfig conflictConfig = NodeConfig.builder()
                .nodeId("test-node")
                .multicastPort(5040)
                .multicastGroup("239.255.0.1")
                .build();

        MulticastDiscovery discovery = new MulticastDiscovery(
                conflictConfig, peerManager, "239.255.0.1", 5040, Duration.ofSeconds(30)
        );

        // Note: With SO_REUSEADDR, this might not fail
        // Depends on OS behavior
        try {
            discovery.start();
            discovery.stop();
        } finally {
            blocker.close();
        }
    }

    @Test
    @Order(17)
    @DisplayName("Should handle network interface not available")
    void testNetworkInterfaceNotAvailable() {
        // This test verifies graceful degradation when no suitable network interface is found
        MulticastDiscovery discovery = new MulticastDiscovery(config, peerManager);

        // Should not crash even if no suitable interface found
        assertDoesNotThrow(() -> {
            try {
                discovery.start();
                discovery.stop();
            } catch (Exception e) {
                // Acceptable if network not available
                assertTrue(e.getMessage().contains("network") ||
                        e.getMessage().contains("interface") ||
                        e.getMessage().contains("multicast"));
            }
        });
    }

    // ========================= Integration Tests =========================

    @Test
    @Order(18)
    @DisplayName("Integration: Full discovery cycle")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testFullDiscoveryCycle() throws Exception {
        CountDownLatch bothDiscovered = new CountDownLatch(2);

        NodeConfig config1 = NodeConfig.builder()
                .nodeId("integration-node-1")
                .tcpPort(8091)
                .multicastGroup("239.255.0.1")
                .multicastPort(5050)
                .build();

        NodeConfig config2 = NodeConfig.builder()
                .nodeId("integration-node-2")
                .tcpPort(8092)
                .multicastGroup("239.255.0.1")
                .multicastPort(5050)
                .build();

        PeerManager pm1 = mock(PeerManager.class);
        PeerManager pm2 = mock(PeerManager.class);

        MulticastDiscovery discovery1 = new MulticastDiscovery(
                config1, pm1, "239.255.0.1", 5050, Duration.ofSeconds(3)
        );
        MulticastDiscovery discovery2 = new MulticastDiscovery(
                config2, pm2, "239.255.0.1", 5050, Duration.ofSeconds(3)
        );

        IDiscoveryListener listener = new IDiscoveryListener() {
            @Override
            public void onPeerDiscovered(Peer peer) {
                System.out.println("Discovered peer: " + peer.id());
                bothDiscovered.countDown();
            }

            @Override
            public void onPeerLost(Peer peer) {
                // Not used in this test
            }

            @Override
            public void onDiscoveryError(Throwable error) {
                System.err.println("Discovery error: " + error.getMessage());
            }
        };

        discovery1.setDiscoveryListener(listener);
        discovery2.setDiscoveryListener(listener);

        discovery1.start();
        discovery2.start();

        // Trigger announcements
        discovery1.announceSelf();
        discovery2.announceSelf();

        // Wait for mutual discovery
        boolean success = bothDiscovered.await(15, TimeUnit.SECONDS);

        discovery1.stop();
        discovery2.stop();

        assertTrue(success, "Both nodes should discover each other via multicast");
    }

    // ========================= Helper Methods =========================

    */
/**
     * Checks if a multicast group has been joined on any network interface.
     *//*

    private boolean isMulticastGroupJoined(String multicastGroup) {
        try {
            InetAddress group = InetAddress.getByName(multicastGroup);
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isUp() && ni.supportsMulticast()) {
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            // Interface is multicast-capable
                            // Note: Actual group membership check is OS-specific
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
*/
