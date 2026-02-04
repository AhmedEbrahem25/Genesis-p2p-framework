//package com.genesis.p2p.testutil.factories;
//
//import com.genesis.p2p.application.NodeConfig;
//import com.genesis.p2p.core.Message;
//import com.genesis.p2p.core.Peer;
//import com.genesis.p2p.core.peer.PeerManager;
//import com.genesis.p2p.testutil.TestConstants;
//import com.genesis.p2p.testutil.builders.TestMessageBuilder;
//import com.genesis.p2p.testutil.builders.TestNodeConfigBuilder;
//import com.genesis.p2p.testutil.builders.TestPeerBuilder;
//
//import java.time.Duration;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.stream.Collectors;
//import java.util.stream.IntStream;
//
///**
// * Factory for common test data creation.
// * <p>
// * Design Pattern: Factory Pattern
// * Centralizes test data generation to ensure consistency across tests.
// * <p>
// * Benefits:
// * - Single source of truth for test data creation
// * - Reduces duplication across test classes
// * - Makes it easy to change test data defaults globally
// * - Improves test readability
// * <p>
// * Usage:
// * <pre>
// * // Create a list of test peers
// * List<Peer> peers = TestDataFactory.createPeerList(10);
// *
// * // Create a test node config
// * NodeConfig config = TestDataFactory.createTestNodeConfig();
// *
// * // Create a peer manager for testing
// * PeerManager manager = TestDataFactory.createTestPeerManager();
// * </pre>
// *
// * @author Genesis P2P Framework
// * @version 2.0
// */
//public final class TestDataFactory {
//
//    // ==================== Peer Factory Methods ====================
//
//    /**
//     * Creates a single test peer with default values.
//     *
//     * @return a test Peer
//     */
//    public static Peer createTestPeer() {
//        return TestPeerBuilder.aPeer().build();
//    }
//
//    /**
//     * Creates a test peer with a specific ID.
//     *
//     * @param id the peer ID
//     * @return a test Peer with the specified ID
//     */
//    public static Peer createTestPeer(String id) {
//        return TestPeerBuilder.aPeer()
//            .withId(id)
//            .build();
//    }
//
//    /**
//     * Creates a test peer with specific ID and IP.
//     *
//     * @param id the peer ID
//     * @param ip the IP address
//     * @param port the port number
//     * @return a test Peer with the specified values
//     */
//    public static Peer createTestPeer(String id, String ip, int port) {
//        return TestPeerBuilder.aPeer()
//            .withId(id)
//            .withIp(ip)
//            .withPort(port)
//            .build();
//    }
//
//    /**
//     * Creates a list of test peers with sequential IDs and IPs.
//     *
//     * @param count number of peers to create
//     * @return list of test Peers
//     */
//    public static List<Peer> createPeerList(int count) {
//        return IntStream.range(0, count)
//            .mapToObj(i -> TestPeerBuilder.aPeer()
//                .withId(TestConstants.TEST_PEER_ID_PREFIX + i)
//                .withIp(TestConstants.TEST_PEER_IP_PREFIX + (i + 1))
//                .withPort(8080 + i)
//                .build())
//            .collect(Collectors.toList());
//    }
//
//    /**
//     * Creates a list of trusted peers.
//     *
//     * @param count number of peers to create
//     * @return list of trusted test Peers
//     */
//    public static List<Peer> createTrustedPeerList(int count) {
//        return IntStream.range(0, count)
//            .mapToObj(i -> TestPeerBuilder.aTrustedPeer()
//                .withId(TestConstants.TEST_PEER_ID_PREFIX + "trusted-" + i)
//                .withIp(TestConstants.TEST_PEER_IP_PREFIX + (i + 1))
//                .build())
//            .collect(Collectors.toList());
//    }
//
//    /**
//     * Creates a mix of online and offline peers.
//     *
//     * @param onlineCount number of online peers
//     * @param offlineCount number of offline peers
//     * @return list of mixed status peers
//     */
//    public static List<Peer> createMixedStatusPeerList(int onlineCount, int offlineCount) {
//        List<Peer> peers = new ArrayList<>();
//
//        // Add online peers
//        for (int i = 0; i < onlineCount; i++) {
//            peers.add(TestPeerBuilder.aPeer()
//                .withId(TestConstants.TEST_PEER_ID_PREFIX + "online-" + i)
//                .withOnline(true)
//                .build());
//        }
//
//        // Add offline peers
//        for (int i = 0; i < offlineCount; i++) {
//            peers.add(TestPeerBuilder.anOfflinePeer()
//                .withId(TestConstants.TEST_PEER_ID_PREFIX + "offline-" + i)
//                .build());
//        }
//
//        return peers;
//    }
//
//    // ==================== NodeConfig Factory Methods ====================
//
//    /**
//     * Creates a test NodeConfig with random ports.
//     *
//     * @return a test NodeConfig
//     */
//    public static NodeConfig createTestNodeConfig() {
//        return TestNodeConfigBuilder.aNodeConfig().build();
//    }
//
//    /**
//     * Creates a test NodeConfig with a specific node ID.
//     *
//     * @param nodeId the node ID
//     * @return a test NodeConfig with the specified ID
//     */
//    public static NodeConfig createTestNodeConfig(String nodeId) {
//        return TestNodeConfigBuilder.aNodeConfig()
//            .withNodeId(nodeId)
//            .build();
//    }
//
//    /**
//     * Creates a production-like NodeConfig for testing.
//     *
//     * @return a production-style NodeConfig
//     */
//    public static NodeConfig createProductionNodeConfig() {
//        return TestNodeConfigBuilder.aProductionConfig().build();
//    }
//
//    /**
//     * Creates a secure NodeConfig with encryption enabled.
//     *
//     * @return a secure NodeConfig
//     */
//    public static NodeConfig createSecureNodeConfig() {
//        return TestNodeConfigBuilder.aSecureNodeConfig().build();
//    }
//
//    /**
//     * Creates a list of NodeConfigs for cluster testing.
//     *
//     * @param nodeCount number of nodes in the cluster
//     * @return list of NodeConfigs with distinct ports
//     */
//    public static List<NodeConfig> createClusterNodeConfigs(int nodeCount) {
//        return IntStream.range(0, nodeCount)
//            .mapToObj(TestNodeConfigBuilder::aClusterNode)
//            .map(TestNodeConfigBuilder::build)
//            .collect(Collectors.toList());
//    }
//
//    // ==================== Message Factory Methods ====================
//
//    /**
//     * Creates a test Message with default values.
//     *
//     * @return a test Message
//     */
//    public static Message createTestMessage() {
//        return TestMessageBuilder.aMessage().build();
//    }
//
//    /**
//     * Creates a test Message with specific type and content.
//     *
//     * @param type the message type
//     * @param content the message content
//     * @return a test Message
//     */
//    public static Message createTestMessage(String type, String content) {
//        return TestMessageBuilder.aMessage()
//            .withType(type)
//            .withContent(content)
//            .build();
//    }
//
//    /**
//     * Creates a test Message from one node to another.
//     *
//     * @param from sender node ID
//     * @param to recipient node ID
//     * @param type message type
//     * @return a test Message
//     */
//    public static Message createTestMessage(String from, String to, String type) {
//        return TestMessageBuilder.aMessage()
//            .withFrom(from)
//            .withTo(to)
//            .withType(type)
//            .build();
//    }
//
//    /**
//     * Creates a PING message for testing.
//     *
//     * @return a PING Message
//     */
//    public static Message createPingMessage() {
//        return TestMessageBuilder.aPingMessage().build();
//    }
//
//    /**
//     * Creates a PONG message for testing.
//     *
//     * @return a PONG Message
//     */
//    public static Message createPongMessage() {
//        return TestMessageBuilder.aPongMessage().build();
//    }
//
//    /**
//     * Creates a HELLO message for testing.
//     *
//     * @return a HELLO Message
//     */
//    public static Message createHelloMessage() {
//        return TestMessageBuilder.aHelloMessage().build();
//    }
//
//    /**
//     * Creates a WELCOME message for testing.
//     *
//     * @return a WELCOME Message
//     */
//    public static Message createWelcomeMessage() {
//        return TestMessageBuilder.aWelcomeMessage().build();
//    }
//
//    /**
//     * Creates a list of test messages.
//     *
//     * @param count number of messages to create
//     * @return list of test Messages
//     */
//    public static List<Message> createMessageList(int count) {
//        return IntStream.range(0, count)
//            .mapToObj(i -> createTestMessage("TEST", "{\"index\":" + i + "}"))
//            .collect(Collectors.toList());
//    }
//
//    // ==================== Component Factory Methods ====================
//
//    /**
//     * Creates a test PeerManager with default settings.
//     *
//     * @return a test PeerManager
//     */
//    public static PeerManager createTestPeerManager() {
//        return new PeerManager(
//            50,  // capacity
//            100, // maxConnections
//            Duration.ofSeconds(5), // timeout
//            Duration.ofSeconds(2)  // cleanupInterval
//        );
//    }
//
//    /**
//     * Creates a PeerManager with specific capacity.
//     *
//     * @param capacity maximum number of peers
//     * @return a test PeerManager with specified capacity
//     */
//    public static PeerManager createTestPeerManager(int capacity) {
//        return new PeerManager(
//            capacity,
//            capacity * 2,
//            Duration.ofSeconds(5),
//            Duration.ofSeconds(2)
//        );
//    }
//
//    /**
//     * Creates a PeerManager with custom settings.
//     *
//     * @param capacity maximum number of peers
//     * @param maxConnections maximum connections
//     * @param timeout peer timeout duration
//     * @return a test PeerManager with specified settings
//     */
//    public static PeerManager createTestPeerManager(int capacity, int maxConnections, Duration timeout) {
//        return new PeerManager(capacity, maxConnections, timeout, Duration.ofSeconds(2));
//    }
//
//    /**
//     * Private constructor to prevent instantiation
//     */
//    private TestDataFactory() {
//        throw new AssertionError("Utility class - do not instantiate");
//    }
//}
