package com.genesis.p2p;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for NodeConfig.
 * Tests Builder pattern, validation, and utility methods.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("NodeConfig Tests")
class NodeConfigTest extends BaseUnitTest {

    // ========================= Builder Pattern Tests =========================

    @Test
    @Order(1)
    @DisplayName("Should create config with default values using builder")
    void testBuilderDefaultValues() {
        NodeConfig config = NodeConfig.builder().build();

        assertNotNull(config.nodeId());
        assertTrue(config.nodeId().startsWith("node-"));
        assertEquals(NodeConfig.DEFAULT_LISTEN_PORT, config.listenPort());
        assertEquals(NodeConfig.DEFAULT_TCP_PORT, config.tcpPort());
        assertEquals(NodeConfig.DEFAULT_MULTICAST_GROUP, config.multicastGroup());
        assertEquals(NodeConfig.DEFAULT_MULTICAST_PORT, config.multicastPort());
        assertEquals(NodeConfig.DEFAULT_BROADCAST_PORT, config.broadcastPort());
        assertEquals("", config.preSharedKeyHex());
    }

    @Test
    @Order(2)
    @DisplayName("Should create config with custom values using builder")
    void testBuilderCustomValues() {
        NodeConfig config = NodeConfig.builder()
                .nodeId("test-node-1")
                .listenPort(9000)
                .tcpPort(9001)
                .multicastGroup("239.255.1.1")
                .multicastPort(6000)
                .broadcastPort(6001)
                .preSharedKey("abc123")
                .build();

        assertEquals("test-node-1", config.nodeId());
        assertEquals(9000, config.listenPort());
        assertEquals(9001, config.tcpPort());
        assertEquals("239.255.1.1", config.multicastGroup());
        assertEquals(6000, config.multicastPort());
        assertEquals(6001, config.broadcastPort());
        assertEquals("abc123", config.preSharedKeyHex());
    }

    @Test
    @Order(3)
    @DisplayName("Should create builder from existing config")
    void testBuilderFromExistingConfig() {
        NodeConfig original = NodeConfig.builder()
                .nodeId("original-node")
                .tcpPort(9999)
                .build();

        NodeConfig modified = NodeConfig.builder(original)
                .tcpPort(8888)
                .build();

        assertEquals("original-node", modified.nodeId());
        assertEquals(8888, modified.tcpPort());
        assertEquals(original.listenPort(), modified.listenPort());
    }

    @Test
    @Order(4)
    @DisplayName("Should set both ports using ports() method")
    void testBuilderPortsMethod() {
        NodeConfig config = NodeConfig.builder()
                .ports(7000, 7001)
                .build();

        assertEquals(7000, config.listenPort());
        assertEquals(7001, config.tcpPort());
    }

    @Test
    @Order(5)
    @DisplayName("Should set discovery ports using discoveryPorts() method")
    void testBuilderDiscoveryPortsMethod() {
        NodeConfig config = NodeConfig.builder()
                .discoveryPorts(5500, 5501)
                .build();

        assertEquals(5500, config.multicastPort());
        assertEquals(5501, config.broadcastPort());
    }

    // ========================= Factory Method Tests =========================

    @Test
    @Order(10)
    @DisplayName("Should create config with defaults() factory method")
    void testDefaultsFactoryMethod() {
        NodeConfig config = NodeConfig.defaults();

        assertNotNull(config);
        assertNotNull(config.nodeId());
        assertEquals(NodeConfig.DEFAULT_TCP_PORT, config.tcpPort());
    }

    @Test
    @Order(11)
    @DisplayName("Should create config with withNodeId() factory method")
    void testWithNodeIdFactoryMethod() {
        NodeConfig config = NodeConfig.withNodeId("my-custom-node");

        assertEquals("my-custom-node", config.nodeId());
        assertEquals(NodeConfig.DEFAULT_TCP_PORT, config.tcpPort());
    }

    @Test
    @Order(12)
    @DisplayName("Should create testing config with forTesting() factory method")
    void testForTestingFactoryMethod() {
        NodeConfig config = NodeConfig.forTesting();

        assertNotNull(config);
        assertTrue(config.nodeId().startsWith("test-"));
        assertEquals(0, config.listenPort()); // Random port
        assertEquals(0, config.tcpPort()); // Random port
    }

    // ========================= Validation Tests =========================

    @Test
    @Order(20)
    @DisplayName("Should reject null node ID")
    void testNullNodeIdValidation() {
        assertThrows(IllegalArgumentException.class, () -> {
            NodeConfig.builder().nodeId(null).build();
        });
    }

    @Test
    @Order(21)
    @DisplayName("Should reject blank node ID")
    void testBlankNodeIdValidation() {
        assertThrows(IllegalArgumentException.class, () -> {
            NodeConfig.builder().nodeId("   ").build();
        });
    }

    @ParameterizedTest
    @Order(22)
    @ValueSource(ints = {-1, -100, 65536, 100000})
    @DisplayName("Should reject invalid listen port")
    void testInvalidListenPortValidation(int port) {
        assertThrows(IllegalArgumentException.class, () -> {
            NodeConfig.builder().listenPort(port).build();
        });
    }

    @ParameterizedTest
    @Order(23)
    @ValueSource(ints = {-1, -100, 65536, 100000})
    @DisplayName("Should reject invalid TCP port")
    void testInvalidTcpPortValidation(int port) {
        assertThrows(IllegalArgumentException.class, () -> {
            NodeConfig.builder().tcpPort(port).build();
        });
    }

    @ParameterizedTest
    @Order(24)
    @ValueSource(ints = {0, 1, 1024, 65535})
    @DisplayName("Should accept valid port numbers")
    void testValidPortNumbers(int port) {
        NodeConfig config = NodeConfig.builder()
                .tcpPort(port)
                .listenPort(port)
                .build();

        assertEquals(port, config.tcpPort());
        assertEquals(port, config.listenPort());
    }

    @Test
    @Order(25)
    @DisplayName("Should default null multicast group to default value")
    void testNullMulticastGroupDefaulting() {
        NodeConfig config = NodeConfig.builder()
                .multicastGroup(null)
                .build();

        assertEquals(NodeConfig.DEFAULT_MULTICAST_GROUP, config.multicastGroup());
    }

    @Test
    @Order(26)
    @DisplayName("Should default null preSharedKeyHex to empty string")
    void testNullPreSharedKeyDefaulting() {
        NodeConfig config = new NodeConfig(
                "test-node", 8080, 8081,
                NodeConfig.DEFAULT_MULTICAST_GROUP, 5000, 5001,
                null,
                false,
                "./data"
        );

        assertEquals("", config.preSharedKeyHex());
    }

    // ========================= Derived Properties Tests =========================

    @Test
    @Order(30)
    @DisplayName("Should return protocol version")
    void testProtocolVersion() {
        NodeConfig config = NodeConfig.defaults();

        assertEquals(NodeConfig.PROTOCOL_VERSION, config.protocolVersion());
        assertEquals("2.0", config.protocolVersion());
    }

    @Test
    @Order(31)
    @DisplayName("Should detect security enabled when key present")
    void testSecurityEnabledWithKey() {
        NodeConfig config = NodeConfig.builder()
                .preSharedKey("abc123")
                .build();

        assertTrue(config.isSecurityEnabled());
    }

    @Test
    @Order(32)
    @DisplayName("Should detect security disabled when no key")
    void testSecurityDisabledWithoutKey() {
        NodeConfig config = NodeConfig.builder()
                .preSharedKey("")
                .build();

        assertFalse(config.isSecurityEnabled());
    }

    @Test
    @Order(33)
    @DisplayName("Should return correct multicast address")
    void testMulticastAddress() {
        NodeConfig config = NodeConfig.builder()
                .multicastGroup("239.255.0.1")
                .multicastPort(5000)
                .build();

        assertEquals("239.255.0.1:5000", config.getMulticastAddress());
    }

    @Test
    @Order(34)
    @DisplayName("Should return correct TCP bind address")
    void testTcpBindAddress() {
        NodeConfig config = NodeConfig.builder()
                .tcpPort(8081)
                .build();

        assertEquals("0.0.0.0:8081", config.getTcpBindAddress());
    }

    @Test
    @Order(35)
    @DisplayName("Should return correct UDP bind address")
    void testUdpBindAddress() {
        NodeConfig config = NodeConfig.builder()
                .listenPort(8080)
                .build();

        assertEquals("0.0.0.0:8080", config.getUdpBindAddress());
    }

    // ========================= Copy Methods Tests =========================

    @Test
    @Order(40)
    @DisplayName("Should copy config with new node ID")
    void testCopyWithNodeId() {
        NodeConfig original = NodeConfig.builder()
                .nodeId("original")
                .tcpPort(9000)
                .build();

        NodeConfig copy = original.copyWithNodeId("new-id");

        assertEquals("new-id", copy.nodeId());
        assertEquals(original.tcpPort(), copy.tcpPort());
        assertEquals(original.listenPort(), copy.listenPort());
    }

    @Test
    @Order(41)
    @DisplayName("Should copy config with new ports")
    void testCopyWithPorts() {
        NodeConfig original = NodeConfig.builder()
                .nodeId("test")
                .listenPort(8080)
                .tcpPort(8081)
                .build();

        NodeConfig copy = original.withPorts(9090, 9091);

        assertEquals(original.nodeId(), copy.nodeId());
        assertEquals(9090, copy.listenPort());
        assertEquals(9091, copy.tcpPort());
    }

    // ========================= Serialization Tests =========================

    @Test
    @Order(50)
    @DisplayName("Should convert to map correctly")
    void testToMap() {
        NodeConfig config = NodeConfig.builder()
                .nodeId("map-test")
                .listenPort(8080)
                .tcpPort(8081)
                .multicastGroup("239.255.0.1")
                .multicastPort(5000)
                .broadcastPort(5001)
                .preSharedKey("secret")
                .build();

        Map<String, Object> map = config.toMap();

        assertEquals("map-test", map.get("nodeId"));
        assertEquals(8080, map.get("listenPort"));
        assertEquals(8081, map.get("tcpPort"));
        assertEquals("239.255.0.1", map.get("multicastGroup"));
        assertEquals(5000, map.get("multicastPort"));
        assertEquals(5001, map.get("broadcastPort"));
        assertEquals("secret", map.get("preSharedKeyHex"));
        assertEquals("2.0", map.get("protocolVersion"));
    }

    @Test
    @Order(51)
    @DisplayName("Should create from map correctly")
    void testFromMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("nodeId", "from-map-node");
        map.put("listenPort", 7000);
        map.put("tcpPort", 7001);
        map.put("multicastGroup", "239.255.1.1");
        map.put("multicastPort", 6000);
        map.put("broadcastPort", 6001);
        map.put("preSharedKeyHex", "key123");

        NodeConfig config = NodeConfig.fromMap(map);

        assertEquals("from-map-node", config.nodeId());
        assertEquals(7000, config.listenPort());
        assertEquals(7001, config.tcpPort());
        assertEquals("239.255.1.1", config.multicastGroup());
        assertEquals(6000, config.multicastPort());
        assertEquals(6001, config.broadcastPort());
        assertEquals("key123", config.preSharedKeyHex());
    }

    @Test
    @Order(52)
    @DisplayName("Should use defaults for missing map values")
    void testFromMapWithMissingValues() {
        Map<String, Object> map = new HashMap<>();
        map.put("nodeId", "partial-node");

        NodeConfig config = NodeConfig.fromMap(map);

        assertEquals("partial-node", config.nodeId());
        assertEquals(NodeConfig.DEFAULT_LISTEN_PORT, config.listenPort());
        assertEquals(NodeConfig.DEFAULT_TCP_PORT, config.tcpPort());
    }

    @Test
    @Order(53)
    @DisplayName("Should roundtrip through map")
    void testMapRoundtrip() {
        NodeConfig original = NodeConfig.builder()
                .nodeId("roundtrip-test")
                .tcpPort(9999)
                .preSharedKey("secret123")
                .build();

        Map<String, Object> map = original.toMap();
        NodeConfig restored = NodeConfig.fromMap(map);

        assertEquals(original.nodeId(), restored.nodeId());
        assertEquals(original.tcpPort(), restored.tcpPort());
        assertEquals(original.listenPort(), restored.listenPort());
        assertEquals(original.preSharedKeyHex(), restored.preSharedKeyHex());
    }

    // ========================= Record Methods Tests =========================

    @Test
    @Order(60)
    @DisplayName("Should have correct toString representation")
    void testToString() {
        NodeConfig config = NodeConfig.builder()
                .nodeId("test-node")
                .tcpPort(8081)
                .listenPort(8080)
                .multicastGroup("239.255.0.1")
                .multicastPort(5000)
                .build();

        String str = config.toString();

        assertTrue(str.contains("test-node"));
        assertTrue(str.contains("8081") || str.contains("tcp=8081"));
    }

    @Test
    @Order(61)
    @DisplayName("Should implement equals correctly")
    void testEquals() {
        NodeConfig config1 = NodeConfig.builder()
                .nodeId("same-node")
                .tcpPort(8081)
                .build();

        NodeConfig config2 = NodeConfig.builder()
                .nodeId("same-node")
                .tcpPort(8081)
                .build();

        NodeConfig config3 = NodeConfig.builder()
                .nodeId("different-node")
                .tcpPort(8081)
                .build();

        assertEquals(config1, config2);
        assertNotEquals(config1, config3);
    }

    @Test
    @Order(62)
    @DisplayName("Should implement hashCode correctly")
    void testHashCode() {
        NodeConfig config1 = NodeConfig.builder()
                .nodeId("hash-node")
                .tcpPort(8081)
                .build();

        NodeConfig config2 = NodeConfig.builder()
                .nodeId("hash-node")
                .tcpPort(8081)
                .build();

        assertEquals(config1.hashCode(), config2.hashCode());
    }

    // ========================= Edge Cases =========================

    @Test
    @Order(70)
    @DisplayName("Should handle empty node ID with default")
    void testEmptyNodeIdWithDefault() {
        assertThrows(IllegalArgumentException.class, () -> {
            NodeConfig.builder().nodeId("").build();
        });
    }

    @Test
    @Order(71)
    @DisplayName("Should handle port 0 (random port)")
    void testPortZero() {
        NodeConfig config = NodeConfig.builder()
                .listenPort(0)
                .tcpPort(0)
                .build();

        assertEquals(0, config.listenPort());
        assertEquals(0, config.tcpPort());
    }

    @Test
    @Order(72)
    @DisplayName("Should handle max port 65535")
    void testMaxPort() {
        NodeConfig config = NodeConfig.builder()
                .listenPort(65535)
                .tcpPort(65535)
                .build();

        assertEquals(65535, config.listenPort());
        assertEquals(65535, config.tcpPort());
    }
}
