package com.genesis.p2p.testutil.builders;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.testutil.TestConstants;

import java.util.UUID;

/**
 * Builder for creating test NodeConfig objects with sensible defaults.
 * <p>
 * Design Pattern: Builder Pattern
 * Simplifies test configuration creation and improves test readability.
 * <p>
 * Features:
 * - Fluent API for easy customization
 * - Sensible defaults optimized for testing (e.g., port 0 for random assignment)
 * - Convenience factory methods for common test scenarios
 * - Automatic generation of unique node IDs
 * <p>
 * Usage:
 * <pre>
 * // Simple config with defaults (random ports)
 * NodeConfig config = TestNodeConfigBuilder.aNodeConfig().build();
 *
 * // Customized config
 * NodeConfig config = TestNodeConfigBuilder.aNodeConfig()
 *     .withNodeId("test-node-1")
 *     .withTcpPort(8081)
 *     .withPreSharedKey("test-key-hex")
 *     .build();
 *
 * // Production-like config
 * NodeConfig config = TestNodeConfigBuilder.aProductionConfig().build();
 * </pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class TestNodeConfigBuilder {

    // Default values - use port 0 for random assignment to avoid port conflicts in tests
    private String nodeId = TestConstants.TEST_NODE_ID_PREFIX + generateUniqueId();
    private int listenPort = TestConstants.TEST_LISTEN_BASE_PORT; // 0 = random
    private int tcpPort = TestConstants.TEST_TCP_BASE_PORT; // 0 = random
    private String multicastGroup = TestConstants.TEST_MULTICAST_GROUP;
    private int multicastPort = TestConstants.TEST_MULTICAST_PORT;
    private int broadcastPort = TestConstants.TEST_BROADCAST_PORT;
    private String preSharedKeyHex = "";

    /**
     * Creates a new builder with default test values.
     * Uses port 0 for automatic port assignment to avoid conflicts.
     *
     * @return a new TestNodeConfigBuilder instance
     */
    public static TestNodeConfigBuilder aNodeConfig() {
        return new TestNodeConfigBuilder();
    }

    /**
     * Creates a builder pre-configured with production-like settings.
     * Uses actual port numbers instead of random assignment.
     *
     * @return a TestNodeConfigBuilder configured for production-like testing
     */
    public static TestNodeConfigBuilder aProductionConfig() {
        return new TestNodeConfigBuilder()
            .withListenPort(8080)
            .withTcpPort(8081)
            .withMulticastPort(5000)
            .withBroadcastPort(5001);
    }

    /**
     * Creates a builder pre-configured for a secure node.
     * Includes a test pre-shared key.
     *
     * @return a TestNodeConfigBuilder configured with security
     */
    public static TestNodeConfigBuilder aSecureNodeConfig() {
        return new TestNodeConfigBuilder()
            .withPreSharedKey("0123456789ABCDEF0123456789ABCDEF"); // 32 hex chars = 16 bytes
    }

    /**
     * Creates a builder pre-configured for a specific node in a cluster.
     * Useful for multi-node tests where you need distinct configurations.
     *
     * @param nodeIndex the index of the node in the cluster (0-based)
     * @return a TestNodeConfigBuilder configured for the specified node
     */
    public static TestNodeConfigBuilder aClusterNode(int nodeIndex) {
        return new TestNodeConfigBuilder()
            .withNodeId(TestConstants.TEST_NODE_ID_PREFIX + nodeIndex)
            .withListenPort(8080 + (nodeIndex * 2))
            .withTcpPort(8081 + (nodeIndex * 2));
    }

    /**
     * Sets the node ID.
     *
     * @param nodeId the node ID
     * @return this builder
     */
    public TestNodeConfigBuilder withNodeId(String nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /**
     * Sets the listen port.
     *
     * @param listenPort the listen port (use 0 for random assignment)
     * @return this builder
     */
    public TestNodeConfigBuilder withListenPort(int listenPort) {
        this.listenPort = listenPort;
        return this;
    }

    /**
     * Sets the TCP port.
     *
     * @param tcpPort the TCP port (use 0 for random assignment)
     * @return this builder
     */
    public TestNodeConfigBuilder withTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
        return this;
    }

    /**
     * Sets the multicast group address.
     *
     * @param multicastGroup the multicast group IP address
     * @return this builder
     */
    public TestNodeConfigBuilder withMulticastGroup(String multicastGroup) {
        this.multicastGroup = multicastGroup;
        return this;
    }

    /**
     * Sets the multicast port.
     *
     * @param multicastPort the multicast port
     * @return this builder
     */
    public TestNodeConfigBuilder withMulticastPort(int multicastPort) {
        this.multicastPort = multicastPort;
        return this;
    }

    /**
     * Sets the broadcast port.
     *
     * @param broadcastPort the broadcast port
     * @return this builder
     */
    public TestNodeConfigBuilder withBroadcastPort(int broadcastPort) {
        this.broadcastPort = broadcastPort;
        return this;
    }

    /**
     * Sets the pre-shared key (hex encoded).
     *
     * @param preSharedKeyHex the pre-shared key in hexadecimal format
     * @return this builder
     */
    public TestNodeConfigBuilder withPreSharedKey(String preSharedKeyHex) {
        this.preSharedKeyHex = preSharedKeyHex;
        return this;
    }

    /**
     * Builds the NodeConfig instance using the NodeConfig.builder() API.
     *
     * @return a new NodeConfig with the configured values
     */
    public NodeConfig build() {
        return NodeConfig.builder()
            .nodeId(nodeId)
            .listenPort(listenPort)
            .tcpPort(tcpPort)
            .multicastGroup(multicastGroup)
            .multicastPort(multicastPort)
            .broadcastPort(broadcastPort)
            .preSharedKey(preSharedKeyHex)
            .build();
    }

    /**
     * Generates a unique short ID for testing.
     *
     * @return a unique 8-character ID
     */
    private static String generateUniqueId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
