package com.genesis.p2p.integration;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.dht.DHTConfig;
import com.genesis.p2p.dht.DHTValue;
import com.genesis.p2p.dht.NodeId;
import com.genesis.p2p.observability.tracing.TraceIdGenerator;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.testutil.base.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive DHT (Distributed Hash Table) Integration Tests.
 *
 * <p>These tests validate the complete DHT functionality including:</p>
 * <ul>
 *   <li>Kademlia-style node ID operations</li>
 *   <li>XOR distance metric calculations</li>
 *   <li>K-bucket routing table operations</li>
 *   <li>Value storage and retrieval</li>
 *   <li>Expiration and republishing</li>
 *   <li>Multi-node DHT simulation</li>
 * </ul>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 * @since 2026-02-04
 */
@Tag("integration")
@Tag("dht")
@DisplayName("DHT (Distributed Hash Table) Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DHTIntegrationTest extends BaseIntegrationTest {

    // ===================== Test Constants =====================

    private static final int K_BUCKET_SIZE = 20;
    private static final int ALPHA = 3;
    private static final Duration VALUE_EXPIRATION = Duration.ofHours(24);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    // ===================== Test Infrastructure =====================

    private DHTConfig config;
    private SimulatedDHTNetwork network;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        config = DHTConfig.defaults();
        executorService = Executors.newFixedThreadPool(8);
        network = new SimulatedDHTNetwork(config, executorService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (network != null) network.shutdown();
        if (executorService != null) {
            executorService.shutdownNow();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ===================== 1. Node ID Operations =====================

    @Nested
    @DisplayName("1. Node ID Operations")
    class NodeIdOperationsTests {

        @Test
        @Order(1)
        @DisplayName("1.1 - NodeId creation and uniqueness")
        void testNodeIdCreationAndUniqueness() {
            // Given/When: Creating multiple random NodeIds
            Set<NodeId> nodeIds = new HashSet<>();
            int count = 1000;

            for (int i = 0; i < count; i++) {
                nodeIds.add(NodeId.random());
            }

            // Then: All NodeIds are unique
            assertEquals(count, nodeIds.size(), "All generated NodeIds should be unique");
        }

        @Test
        @Order(2)
        @DisplayName("1.2 - NodeId from string is deterministic")
        void testNodeIdFromStringDeterministic() {
            // Given: A string identifier
            String identifier = "test-node-identifier";

            // When: Creating NodeId from string multiple times
            NodeId id1 = NodeId.fromString(identifier);
            NodeId id2 = NodeId.fromString(identifier);
            NodeId id3 = NodeId.fromString("different-identifier");

            // Then: Same input produces same NodeId
            assertEquals(id1, id2, "Same input should produce same NodeId");
            assertNotEquals(id1, id3, "Different input should produce different NodeId");
        }

        @Test
        @Order(3)
        @DisplayName("1.3 - XOR distance is symmetric and non-negative")
        void testXorDistanceProperties() {
            // Given: Multiple NodeIds
            NodeId a = NodeId.random();
            NodeId b = NodeId.random();
            NodeId c = NodeId.random();

            // Then: XOR distance is symmetric
            NodeId distAB = a.xorDistance(b);
            NodeId distBA = b.xorDistance(a);
            assertEquals(distAB, distBA, "XOR distance should be symmetric");

            // And: Distance to self is zero
            NodeId distAA = a.xorDistance(a);
            byte[] expected = new byte[NodeId.ID_LENGTH_BYTES];
            assertArrayEquals(expected, distAA.getBytes(), "Distance to self should be zero");
        }

        @Test
        @Order(4)
        @DisplayName("1.4 - Common prefix length calculation")
        void testCommonPrefixLength() {
            // Given: Node IDs with known relationship
            NodeId localNode = NodeId.fromString("local-node");

            // When: Calculating prefix lengths
            int selfPrefix = localNode.commonPrefixLength(localNode);

            // Then: Self has maximum prefix length
            assertEquals(NodeId.ID_LENGTH_BITS, selfPrefix,
                "Common prefix with self should be maximum");

            // And: Random nodes have varying prefix lengths
            List<Integer> prefixLengths = IntStream.range(0, 100)
                .mapToObj(i -> NodeId.random())
                .map(localNode::commonPrefixLength)
                .collect(Collectors.toList());

            // Prefix lengths should be distributed (not all the same)
            long uniquePrefixes = prefixLengths.stream().distinct().count();
            assertTrue(uniquePrefixes > 1, "Should have variety in prefix lengths");
        }

        @Test
        @Order(5)
        @DisplayName("1.5 - Bucket index calculation for routing")
        void testBucketIndexCalculation() {
            // Given: A local node
            NodeId localNode = NodeId.fromString("local-routing-node");

            // When: Calculating bucket indices for various peers
            Map<Integer, List<NodeId>> buckets = new HashMap<>();

            for (int i = 0; i < 1000; i++) {
                NodeId peer = NodeId.random();
                int bucket = localNode.getBucketIndex(peer);
                buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(peer);
            }

            // Then: Bucket indices are within valid range
            assertTrue(buckets.keySet().stream().allMatch(b -> b >= 0 && b < NodeId.ID_LENGTH_BITS),
                "All bucket indices should be in valid range");

            // And: We have some distribution across buckets (not all in one bucket)
            assertTrue(buckets.size() > 5,
                "Should have nodes distributed across multiple buckets, got: " + buckets.size());
        }
    }

    // ===================== 2. DHT Value Operations =====================

    @Nested
    @DisplayName("2. DHT Value Operations")
    class DHTValueOperationsTests {

        @Test
        @Order(10)
        @DisplayName("2.1 - Store and retrieve value")
        void testStoreAndRetrieveValue() {
            // Given: A DHT network with nodes
            network.createNodes(10);
            NodeId publisherNode = network.getRandomNode();

            // When: Storing a value
            NodeId key = NodeId.fromString("test-key-1");
            byte[] data = "Test value data".getBytes();

            DHTValue value = network.store(publisherNode, key, data);

            // Then: Value can be retrieved
            Optional<DHTValue> retrieved = network.lookup(key);
            assertTrue(retrieved.isPresent(), "Value should be retrievable");
            assertArrayEquals(data, retrieved.get().data(), "Data should match");
        }

        @Test
        @Order(11)
        @DisplayName("2.2 - Value expiration is enforced")
        void testValueExpiration() {
            // Given: A value with immediate expiration
            NodeId key = NodeId.fromString("expiring-key");
            byte[] data = "Expiring data".getBytes();
            NodeId publisher = NodeId.random();

            DHTValue expiredValue = new DHTValue(
                key, data, publisher,
                Instant.now().minus(Duration.ofHours(2)),
                Instant.now().minus(Duration.ofHours(1)), // Already expired
                0
            );

            // Then: Value reports as expired
            assertTrue(expiredValue.isExpired(), "Value should be expired");
        }

        @Test
        @Order(12)
        @DisplayName("2.3 - Value republish tracking")
        void testValueRepublishTracking() {
            // Given: A value that's been republished multiple times
            NodeId key = NodeId.fromString("republished-key");
            byte[] data = "Republished data".getBytes();
            NodeId publisher = NodeId.random();

            DHTValue value = new DHTValue(
                key, data, publisher,
                Instant.now(),
                Instant.now().plus(VALUE_EXPIRATION),
                5 // Republished 5 times
            );

            // Then: Republish count is tracked
            assertEquals(5, value.republishCount());
            assertFalse(value.isExpired());
        }

        @Test
        @Order(13)
        @DisplayName("2.4 - Large value storage")
        void testLargeValueStorage() {
            // Given: A large value
            network.createNodes(5);
            NodeId key = NodeId.fromString("large-key");
            byte[] largeData = new byte[64 * 1024]; // 64KB
            new Random().nextBytes(largeData);

            // When: Storing large value
            network.store(network.getRandomNode(), key, largeData);

            // Then: Can be retrieved intact
            Optional<DHTValue> retrieved = network.lookup(key);
            assertTrue(retrieved.isPresent());
            assertArrayEquals(largeData, retrieved.get().data());
        }
    }

    // ===================== 3. Multi-Node DHT Operations =====================

    @Nested
    @DisplayName("3. Multi-Node DHT Operations")
    class MultiNodeDHTTests {

        @Test
        @Order(20)
        @DisplayName("3.1 - Value replication across nodes")
        void testValueReplication() {
            // Given: A network with multiple nodes
            int nodeCount = 20;
            network.createNodes(nodeCount);

            // When: Storing a value
            NodeId key = NodeId.fromString("replicated-key");
            byte[] data = "Replicated value".getBytes();
            network.store(network.getRandomNode(), key, data);

            // Then: Value is replicated to k-closest nodes
            int nodesWithValue = network.countNodesWithValue(key);
            assertTrue(nodesWithValue >= Math.min(config.replicationFactor(), nodeCount),
                "Value should be replicated to at least replication factor nodes");
        }

        @Test
        @Order(21)
        @DisplayName("3.2 - Lookup finds value from any node")
        void testLookupFromAnyNode() {
            // Given: A network with values stored
            network.createNodes(15);
            NodeId key = NodeId.fromString("findable-key");
            byte[] data = "Findable value".getBytes();
            network.store(network.getRandomNode(), key, data);

            // When: Querying from different nodes
            List<NodeId> allNodes = network.getAllNodes();
            int successfulLookups = 0;

            for (NodeId queryNode : allNodes) {
                Optional<DHTValue> result = network.lookupFrom(queryNode, key);
                if (result.isPresent()) {
                    successfulLookups++;
                }
            }

            // Then: All nodes can find the value
            assertEquals(allNodes.size(), successfulLookups,
                "All nodes should be able to find the value");
        }

        @ParameterizedTest(name = "Network with {0} nodes handles concurrent operations")
        @ValueSource(ints = {5, 10, 20, 50})
        @Order(22)
        void testConcurrentDHTOperations(int nodeCount) throws Exception {
            // Given: A network of specified size
            network.createNodes(nodeCount);

            int operationCount = 100;
            AtomicInteger successfulStores = new AtomicInteger(0);
            AtomicInteger successfulLookups = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(operationCount * 2);

            // When: Concurrent stores and lookups
            for (int i = 0; i < operationCount; i++) {
                final int index = i;

                // Store operation
                executorService.submit(() -> {
                    try {
                        NodeId key = NodeId.fromString("concurrent-key-" + index);
                        byte[] data = ("Value-" + index).getBytes();
                        network.store(network.getRandomNode(), key, data);
                        successfulStores.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });

                // Lookup operation (for previously stored keys)
                if (index > 10) {
                    executorService.submit(() -> {
                        try {
                            NodeId key = NodeId.fromString("concurrent-key-" + (index - 10));
                            Optional<DHTValue> result = network.lookup(key);
                            if (result.isPresent()) {
                                successfulLookups.incrementAndGet();
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                } else {
                    latch.countDown();
                }
            }

            // Then: Most operations succeed
            assertTrue(latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(successfulStores.get() >= operationCount * 0.9,
                "At least 90% of stores should succeed");
        }

        @Test
        @Order(23)
        @DisplayName("3.3 - Find k-closest nodes")
        void testFindClosestNodes() {
            // Given: A populated network
            int nodeCount = 50;
            network.createNodes(nodeCount);

            // When: Finding k-closest nodes to a key
            NodeId targetKey = NodeId.fromString("target-key");
            List<NodeId> closest = network.findClosestNodes(targetKey, K_BUCKET_SIZE);

            // Then: Returns correct number of nodes
            assertEquals(Math.min(K_BUCKET_SIZE, nodeCount), closest.size());

            // And: Nodes are ordered by distance
            for (int i = 1; i < closest.size(); i++) {
                NodeId prev = closest.get(i - 1);
                NodeId curr = closest.get(i);

                int cmp = targetKey.xorDistance(prev).compareTo(targetKey.xorDistance(curr));
                assertTrue(cmp <= 0, "Nodes should be ordered by XOR distance");
            }
        }
    }

    // ===================== 4. DHT Configuration Tests =====================

    @Nested
    @DisplayName("4. DHT Configuration")
    class DHTConfigurationTests {

        @Test
        @Order(30)
        @DisplayName("4.1 - Default configuration values")
        void testDefaultConfiguration() {
            DHTConfig defaultConfig = DHTConfig.defaults();

            assertEquals(20, defaultConfig.kBucketSize());
            assertEquals(3, defaultConfig.alpha());
            assertEquals(5, defaultConfig.replicationFactor());
            assertNotNull(defaultConfig.operationTimeout());
            assertNotNull(defaultConfig.bucketRefreshInterval());
            assertNotNull(defaultConfig.republishInterval());
            assertNotNull(defaultConfig.valueExpiration());
            assertTrue(defaultConfig.maxStoredValues() > 0);
        }

        @Test
        @Order(31)
        @DisplayName("4.2 - Custom configuration")
        void testCustomConfiguration() {
            DHTConfig customConfig = new DHTConfig(
                10, 5, 3,
                Duration.ofSeconds(10),
                Duration.ofMinutes(30),
                Duration.ofMinutes(45),
                Duration.ofHours(12),
                5000
            );

            assertEquals(10, customConfig.kBucketSize());
            assertEquals(5, customConfig.alpha());
            assertEquals(3, customConfig.replicationFactor());
            assertEquals(Duration.ofSeconds(10), customConfig.operationTimeout());
            assertEquals(5000, customConfig.maxStoredValues());
        }

        @Test
        @Order(32)
        @DisplayName("4.3 - Invalid configuration gets defaults")
        void testInvalidConfigurationDefaults() {
            // When: Creating config with invalid values
            DHTConfig invalidConfig = new DHTConfig(0, 0, 0, null, null, null, null, 0);

            // Then: Defaults are applied
            assertEquals(20, invalidConfig.kBucketSize());
            assertEquals(3, invalidConfig.alpha());
            assertEquals(5, invalidConfig.replicationFactor());
            assertNotNull(invalidConfig.operationTimeout());
        }
    }

    // ===================== Simulated DHT Network =====================

    /**
     * Simulates a DHT network for testing purposes.
     */
    private static class SimulatedDHTNetwork {
        private final DHTConfig config;
        private final ExecutorService executor;
        private final Map<NodeId, SimulatedDHTNode> nodes = new ConcurrentHashMap<>();
        private final Random random = new Random();

        SimulatedDHTNetwork(DHTConfig config, ExecutorService executor) {
            this.config = config;
            this.executor = executor;
        }

        void createNodes(int count) {
            for (int i = 0; i < count; i++) {
                NodeId nodeId = NodeId.random();
                nodes.put(nodeId, new SimulatedDHTNode(nodeId, config));
            }
        }

        NodeId getRandomNode() {
            List<NodeId> nodeList = new ArrayList<>(nodes.keySet());
            return nodeList.get(random.nextInt(nodeList.size()));
        }

        List<NodeId> getAllNodes() {
            return new ArrayList<>(nodes.keySet());
        }

        DHTValue store(NodeId publisher, NodeId key, byte[] data) {
            DHTValue value = new DHTValue(
                key, data, publisher,
                Instant.now(),
                Instant.now().plus(config.valueExpiration()),
                0
            );

            // Store on k-closest nodes
            List<NodeId> closest = findClosestNodes(key, config.replicationFactor());
            for (NodeId nodeId : closest) {
                nodes.get(nodeId).store(key, value);
            }

            return value;
        }

        Optional<DHTValue> lookup(NodeId key) {
            return lookupFrom(getRandomNode(), key);
        }

        Optional<DHTValue> lookupFrom(NodeId queryNode, NodeId key) {
            // First check locally
            SimulatedDHTNode node = nodes.get(queryNode);
            Optional<DHTValue> local = node.get(key);
            if (local.isPresent()) return local;

            // Query closest nodes
            List<NodeId> closest = findClosestNodes(key, config.alpha());
            for (NodeId closeNode : closest) {
                Optional<DHTValue> result = nodes.get(closeNode).get(key);
                if (result.isPresent()) return result;
            }

            return Optional.empty();
        }

        List<NodeId> findClosestNodes(NodeId target, int count) {
            return nodes.keySet().stream()
                .sorted(Comparator.comparing(n -> target.xorDistance(n)))
                .limit(count)
                .collect(Collectors.toList());
        }

        int countNodesWithValue(NodeId key) {
            return (int) nodes.values().stream()
                .filter(n -> n.get(key).isPresent())
                .count();
        }

        void shutdown() {
            nodes.clear();
        }
    }

    /**
     * Simulates a single DHT node.
     */
    private static class SimulatedDHTNode {
        private final NodeId nodeId;
        private final DHTConfig config;
        private final Map<NodeId, DHTValue> storage = new ConcurrentHashMap<>();

        SimulatedDHTNode(NodeId nodeId, DHTConfig config) {
            this.nodeId = nodeId;
            this.config = config;
        }

        void store(NodeId key, DHTValue value) {
            if (storage.size() < config.maxStoredValues()) {
                storage.put(key, value);
            }
        }

        Optional<DHTValue> get(NodeId key) {
            DHTValue value = storage.get(key);
            if (value != null && !value.isExpired()) {
                return Optional.of(value);
            }
            return Optional.empty();
        }
    }
}
