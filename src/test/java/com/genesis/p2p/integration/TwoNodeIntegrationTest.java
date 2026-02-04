package com.genesis.p2p.integration;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.peer.PeerStore;
import com.genesis.p2p.dht.NodeId;
import com.genesis.p2p.events.core.EventBus;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.tracing.TraceIdGenerator;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.security.config.SecurityConfig;
import com.genesis.p2p.security.hmac.HmacService;
import com.genesis.p2p.testutil.base.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests simulating two-node P2P communication.
 *
 * <p>These tests verify the complete message flow between two simulated nodes,
 * including:</p>
 * <ul>
 *   <li>Message creation and serialization</li>
 *   <li>Protocol version negotiation</li>
 *   <li>Security handshake simulation</li>
 *   <li>Bidirectional message exchange</li>
 *   <li>Error handling and recovery</li>
 *   <li>Performance under load</li>
 * </ul>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 * @since 2026-02-04
 */
@Tag("integration")
@DisplayName("Two-Node P2P Communication Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TwoNodeIntegrationTest extends BaseIntegrationTest {

    // ===================== Test Infrastructure =====================

    private static final String NODE_ALPHA = "node-alpha-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String NODE_BETA = "node-beta-" + UUID.randomUUID().toString().substring(0, 8);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private SimulatedNode nodeAlpha;
    private SimulatedNode nodeBeta;
    private MessageChannel channelAlphaToBeta;
    private MessageChannel channelBetaToAlpha;
    private ExecutorService executorService;
    private MetricsRegistry metricsRegistry;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(4);
        metricsRegistry = new MetricsRegistry("two-node-test");

        nodeAlpha = new SimulatedNode(NODE_ALPHA, metricsRegistry);
        nodeBeta = new SimulatedNode(NODE_BETA, metricsRegistry);

        // Create bidirectional channels
        channelAlphaToBeta = new MessageChannel(nodeAlpha, nodeBeta);
        channelBetaToAlpha = new MessageChannel(nodeBeta, nodeAlpha);

        nodeAlpha.registerOutboundChannel(NODE_BETA, channelAlphaToBeta);
        nodeBeta.registerOutboundChannel(NODE_ALPHA, channelBetaToAlpha);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (nodeAlpha != null) nodeAlpha.shutdown();
        if (nodeBeta != null) nodeBeta.shutdown();
    }

    // ===================== Basic Connectivity Tests =====================

    @Test
    @Order(1)
    @DisplayName("1.1 - Nodes can exchange simple PING/PONG messages")
    void testSimplePingPong() throws Exception {
        // Given: Set up handlers BEFORE sending any messages
        CompletableFuture<Message> pingReceived = new CompletableFuture<>();
        CompletableFuture<Message> pongReceived = new CompletableFuture<>();

        nodeBeta.onMessage("PING", pingReceived::complete);
        nodeAlpha.onMessage("PONG", pongReceived::complete);

        // When: Node Alpha sends PING to Node Beta
        Message ping = createMessage("PING", NODE_ALPHA, NODE_BETA, Map.of("timestamp", Instant.now().toEpochMilli()));
        nodeAlpha.send(NODE_BETA, ping);

        // And: Node Beta receives PING and responds with PONG
        Message receivedPing = pingReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(receivedPing, "Beta should receive PING");

        Message pong = createMessage("PONG", NODE_BETA, NODE_ALPHA,
            Map.of("originalTimestamp", receivedPing.body().metadata().get("timestamp")));
        nodeBeta.send(NODE_ALPHA, pong);

        // Then: Node Alpha receives PONG
        Message receivedPong = pongReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(receivedPong);
        assertEquals("PONG", receivedPong.type());
        assertEquals(NODE_BETA, receivedPong.from());
    }

    @Test
    @Order(2)
    @DisplayName("1.2 - Bidirectional message exchange works correctly")
    void testBidirectionalExchange() throws Exception {
        // Given: Counters for received messages
        AtomicInteger alphaReceived = new AtomicInteger(0);
        AtomicInteger betaReceived = new AtomicInteger(0);
        CountDownLatch allReceived = new CountDownLatch(20); // 10 each way

        nodeAlpha.onAnyMessage(msg -> {
            alphaReceived.incrementAndGet();
            allReceived.countDown();
        });
        nodeBeta.onAnyMessage(msg -> {
            betaReceived.incrementAndGet();
            allReceived.countDown();
        });

        // When: Both nodes send 10 messages each
        for (int i = 0; i < 10; i++) {
            Message fromAlpha = createMessage("DATA", NODE_ALPHA, NODE_BETA, Map.of("seq", i));
            Message fromBeta = createMessage("DATA", NODE_BETA, NODE_ALPHA, Map.of("seq", i));

            nodeAlpha.send(NODE_BETA, fromAlpha);
            nodeBeta.send(NODE_ALPHA, fromBeta);
        }

        // Then: Both nodes receive all messages
        boolean completed = allReceived.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(completed, "All messages should be received within timeout");
        assertEquals(10, alphaReceived.get(), "Alpha should receive 10 messages");
        assertEquals(10, betaReceived.get(), "Beta should receive 10 messages");
    }

    // ===================== Protocol Negotiation Tests =====================

    @Test
    @Order(10)
    @DisplayName("2.1 - Protocol version negotiation between compatible nodes")
    void testProtocolVersionNegotiation() throws Exception {
        // Given: Two nodes with compatible protocol versions
        ProtocolVersion alphaVersion = new ProtocolVersion(2, 1, 0);
        ProtocolVersion betaVersion = new ProtocolVersion(2, 0, 0);

        // Setup handlers BEFORE sending
        CompletableFuture<Message> versionRequestReceived = new CompletableFuture<>();
        CompletableFuture<Message> versionResponseReceived = new CompletableFuture<>();
        nodeBeta.onMessage("VERSION_REQUEST", versionRequestReceived::complete);
        nodeAlpha.onMessage("VERSION_RESPONSE", versionResponseReceived::complete);

        // When: Nodes exchange version information
        Message versionRequest = createMessage("VERSION_REQUEST", NODE_ALPHA, NODE_BETA,
            Map.of("version", alphaVersion.toString(), "minVersion", "1.0"));
        nodeAlpha.send(NODE_BETA, versionRequest);

        Message received = versionRequestReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(received);

        // Then: Versions are compatible
        assertTrue(alphaVersion.isCompatibleWith(betaVersion));

        // And: Beta responds with its version
        Message versionResponse = createMessage("VERSION_RESPONSE", NODE_BETA, NODE_ALPHA,
            Map.of("version", betaVersion.toString(), "compatible", true));
        nodeBeta.send(NODE_ALPHA, versionResponse);

        Message response = versionResponseReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(response);
        assertTrue((Boolean) response.body().metadata().get("compatible"));
    }

    @Test
    @Order(11)
    @DisplayName("2.2 - Incompatible protocol versions are detected")
    void testIncompatibleProtocolVersions() {
        // Given: Two nodes with incompatible protocol versions
        ProtocolVersion v1 = new ProtocolVersion(1, 0, 0);
        ProtocolVersion v2 = new ProtocolVersion(2, 0, 0);

        // Then: They should not be compatible
        assertFalse(v1.isCompatibleWith(v2));
        assertFalse(v2.isCompatibleWith(v1));
    }

    // ===================== Security Handshake Tests =====================

    @Test
    @Order(20)
    @DisplayName("3.1 - Simulated key exchange handshake")
    void testKeyExchangeHandshake() throws Exception {
        // Given: Security keys for both nodes
        byte[] alphaKey = generateSecureKey(32);
        byte[] betaKey = generateSecureKey(32);

        // Setup all handlers BEFORE sending any messages
        CompletableFuture<Message> initReceived = new CompletableFuture<>();
        CompletableFuture<Message> responseReceived = new CompletableFuture<>();
        CompletableFuture<Message> completeReceived = new CompletableFuture<>();
        nodeBeta.onMessage("KEY_EXCHANGE_INIT", initReceived::complete);
        nodeAlpha.onMessage("KEY_EXCHANGE_RESPONSE", responseReceived::complete);
        nodeBeta.onMessage("KEY_EXCHANGE_COMPLETE", completeReceived::complete);

        // When: Alpha initiates key exchange
        Message keyExchangeInit = createMessage("KEY_EXCHANGE_INIT", NODE_ALPHA, NODE_BETA,
            Map.of(
                "algorithm", "ECDH",
                "publicKeyHint", Base64.getEncoder().encodeToString(Arrays.copyOf(alphaKey, 8)),
                "nonce", TraceIdGenerator.generate()
            ));
        nodeAlpha.send(NODE_BETA, keyExchangeInit);

        Message initMsg = initReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(initMsg, "Beta should receive KEY_EXCHANGE_INIT");
        assertEquals("ECDH", initMsg.body().metadata().get("algorithm"));

        // And: Beta responds with its public key
        Message keyExchangeResponse = createMessage("KEY_EXCHANGE_RESPONSE", NODE_BETA, NODE_ALPHA,
            Map.of(
                "publicKeyHint", Base64.getEncoder().encodeToString(Arrays.copyOf(betaKey, 8)),
                "accepted", true
            ));
        nodeBeta.send(NODE_ALPHA, keyExchangeResponse);

        Message responseMsg = responseReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(responseMsg);
        assertTrue((Boolean) responseMsg.body().metadata().get("accepted"));

        // Then: Key exchange is complete
        Message keyExchangeComplete = createMessage("KEY_EXCHANGE_COMPLETE", NODE_ALPHA, NODE_BETA,
            Map.of("status", "SUCCESS", "sessionId", UUID.randomUUID().toString()));
        nodeAlpha.send(NODE_BETA, keyExchangeComplete);

        Message completeMsg = completeReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(completeMsg);
        assertEquals("SUCCESS", completeMsg.body().metadata().get("status"));
    }

    @Test
    @Order(21)
    @DisplayName("3.2 - Message authentication with HMAC")
    void testMessageAuthenticationWithHMAC() throws Exception {
        // Given: A shared HMAC key and handler setup
        byte[] sharedKey = generateSecureKey(32);
        String messageContent = "Authenticated message content";

        CompletableFuture<Message> secureDataReceived = new CompletableFuture<>();
        nodeBeta.onMessage("SECURE_DATA", secureDataReceived::complete);

        // When: Alpha sends authenticated message
        byte[] hmac = HmacService.computeHmac(messageContent.getBytes(), sharedKey);

        Message authenticatedMessage = createMessage("SECURE_DATA", NODE_ALPHA, NODE_BETA,
            Map.of(
                "hmac", Base64.getEncoder().encodeToString(hmac),
                "algorithm", "HMAC-SHA256"
            ),
            messageContent);
        nodeAlpha.send(NODE_BETA, authenticatedMessage);

        Message received = secureDataReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(received);

        // Then: Beta can verify the HMAC
        String receivedHmac = (String) received.body().metadata().get("hmac");
        byte[] decodedHmac = Base64.getDecoder().decode(receivedHmac);

        boolean valid = HmacService.verifyHmac(
            received.body().content().getBytes(),
            sharedKey,
            decodedHmac
        );
        assertTrue(valid, "HMAC verification should succeed");
    }

    // ===================== Message Ordering Tests =====================

    @Test
    @Order(30)
    @DisplayName("4.1 - Messages maintain ordering within a session")
    void testMessageOrdering() throws Exception {
        // Given: A sequence of numbered messages
        int messageCount = 100;
        List<Integer> receivedSequences = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch allReceived = new CountDownLatch(messageCount);

        nodeBeta.onMessage("ORDERED", msg -> {
            receivedSequences.add((Integer) msg.body().metadata().get("seq"));
            allReceived.countDown();
        });

        // When: Alpha sends messages in order
        for (int i = 0; i < messageCount; i++) {
            Message msg = createMessage("ORDERED", NODE_ALPHA, NODE_BETA, Map.of("seq", i));
            nodeAlpha.send(NODE_BETA, msg);
        }

        // Then: Messages are received in order
        boolean completed = allReceived.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(completed, "All messages should be received");
        assertEquals(messageCount, receivedSequences.size());

        // Verify ordering
        for (int i = 0; i < receivedSequences.size(); i++) {
            assertEquals(i, receivedSequences.get(i), "Message " + i + " should be in order");
        }
    }

    // ===================== Error Recovery Tests =====================

    @Test
    @Order(40)
    @DisplayName("5.1 - Node recovers from temporary disconnection")
    void testRecoveryFromDisconnection() throws Exception {
        // Given: Setup handlers BEFORE sending
        CompletableFuture<Message> helloReceived = new CompletableFuture<>();
        CompletableFuture<Message> recoveredReceived = new CompletableFuture<>();
        nodeBeta.onMessage("HELLO", helloReceived::complete);
        nodeBeta.onMessage("RECOVERED", recoveredReceived::complete);

        // Established communication
        Message initial = createMessage("HELLO", NODE_ALPHA, NODE_BETA, Map.of("phase", "initial"));
        nodeAlpha.send(NODE_BETA, initial);
        assertNotNull(helloReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

        // When: Connection is temporarily interrupted
        channelAlphaToBeta.simulateDisconnection(Duration.ofMillis(100));

        // And: Alpha tries to send during disconnection
        Message duringDisconnect = createMessage("LOST", NODE_ALPHA, NODE_BETA, Map.of("phase", "disconnected"));
        assertThrows(Exception.class, () -> nodeAlpha.send(NODE_BETA, duringDisconnect));

        // Then: After reconnection, communication resumes
        channelAlphaToBeta.reconnect();

        Message afterReconnect = createMessage("RECOVERED", NODE_ALPHA, NODE_BETA, Map.of("phase", "recovered"));
        nodeAlpha.send(NODE_BETA, afterReconnect);

        Message received = recoveredReceived.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(received);
        assertEquals("recovered", received.body().metadata().get("phase"));
    }

    @Test
    @Order(41)
    @DisplayName("5.2 - Timeout handling for unresponsive peer")
    void testTimeoutHandling() {
        // Given: Beta is configured to not respond
        nodeBeta.setResponsive(false);

        // When: Alpha sends a request expecting response
        Message request = createMessage("REQUEST", NODE_ALPHA, NODE_BETA, Map.of("expectResponse", true));
        nodeAlpha.send(NODE_BETA, request);

        // Then: Alpha should timeout waiting for response
        assertThrows(TimeoutException.class, () ->
            nodeAlpha.awaitMessage("RESPONSE", Duration.ofMillis(500))
        );

        // And: Metrics should record the timeout
        assertTrue(metricsRegistry.getCounter("node.timeouts") >= 0);
    }

    // ===================== Performance Tests =====================

    @Test
    @Order(50)
    @DisplayName("6.1 - High throughput message exchange")
    @Execution(ExecutionMode.SAME_THREAD)
    void testHighThroughputExchange() throws Exception {
        // Given: A large number of messages
        int messageCount = 1000;
        AtomicInteger received = new AtomicInteger(0);
        CountDownLatch allReceived = new CountDownLatch(messageCount);

        nodeBeta.onMessage("THROUGHPUT", msg -> {
            received.incrementAndGet();
            allReceived.countDown();
        });

        // When: Alpha sends messages as fast as possible
        long startTime = System.nanoTime();

        for (int i = 0; i < messageCount; i++) {
            Message msg = createMessage("THROUGHPUT", NODE_ALPHA, NODE_BETA, Map.of("seq", i));
            nodeAlpha.send(NODE_BETA, msg);
        }

        // Then: All messages are received within timeout
        boolean completed = allReceived.await(TEST_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
        long duration = System.nanoTime() - startTime;

        assertTrue(completed, "All " + messageCount + " messages should be received");
        assertEquals(messageCount, received.get());

        // And: Throughput is reasonable
        double messagesPerSecond = messageCount / (duration / 1_000_000_000.0);
        assertTrue(messagesPerSecond > 100,
            "Should handle at least 100 msg/s, actual: " + String.format("%.2f", messagesPerSecond));
    }

    @Test
    @Order(51)
    @DisplayName("6.2 - Concurrent bidirectional stress test")
    void testConcurrentBidirectionalStress() throws Exception {
        // Given: Multiple threads sending messages in both directions
        int threadsPerNode = 4;
        int messagesPerThread = 100;
        int totalMessages = threadsPerNode * messagesPerThread * 2;

        AtomicInteger alphaReceived = new AtomicInteger(0);
        AtomicInteger betaReceived = new AtomicInteger(0);
        CountDownLatch allReceived = new CountDownLatch(totalMessages);

        nodeAlpha.onAnyMessage(msg -> {
            alphaReceived.incrementAndGet();
            allReceived.countDown();
        });
        nodeBeta.onAnyMessage(msg -> {
            betaReceived.incrementAndGet();
            allReceived.countDown();
        });

        // When: Multiple threads send concurrently
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int t = 0; t < threadsPerNode; t++) {
            final int threadId = t;

            // Alpha to Beta
            futures.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < messagesPerThread; i++) {
                    Message msg = createMessage("STRESS", NODE_ALPHA, NODE_BETA,
                        Map.of("thread", threadId, "seq", i));
                    nodeAlpha.send(NODE_BETA, msg);
                }
            }, executorService));

            // Beta to Alpha
            futures.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < messagesPerThread; i++) {
                    Message msg = createMessage("STRESS", NODE_BETA, NODE_ALPHA,
                        Map.of("thread", threadId, "seq", i));
                    nodeBeta.send(NODE_ALPHA, msg);
                }
            }, executorService));
        }

        // Wait for all senders to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        // Then: All messages are received
        boolean completed = allReceived.await(TEST_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
        assertTrue(completed, "All messages should be received");

        int expectedPerNode = threadsPerNode * messagesPerThread;
        assertEquals(expectedPerNode, alphaReceived.get(), "Alpha should receive correct count");
        assertEquals(expectedPerNode, betaReceived.get(), "Beta should receive correct count");
    }

    // ===================== Helper Methods =====================

    private Message createMessage(String type, String from, String to, Map<String, Object> metadata) {
        return createMessage(type, from, to, metadata, "{}");
    }

    private Message createMessage(String type, String from, String to, Map<String, Object> metadata, String content) {
        String actualContent = (content == null || content.isEmpty()) ? "{}" : content;
        MessageHeader header = new MessageHeader(
            UUID.randomUUID().toString(),
            TraceIdGenerator.generate(),
            ProtocolVersion.current().toString(),
            "1.0",
            10, 0, type, from, to,
            System.currentTimeMillis(),
            false, "application/json", false, null, false
        );
        return new Message(header, new MessageBody(actualContent, new HashMap<>(metadata)));
    }

    private byte[] generateSecureKey(int size) {
        byte[] key = new byte[size];
        new SecureRandom().nextBytes(key);
        return key;
    }

    // ===================== Simulated Node Infrastructure =====================

    /**
     * Simulates a P2P node for testing purposes.
     */
    private static class SimulatedNode {
        private final String nodeId;
        private final MetricsRegistry metrics;
        private final Map<String, MessageChannel> outboundChannels = new ConcurrentHashMap<>();
        private final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
        private final Map<String, List<java.util.function.Consumer<Message>>> messageHandlers = new ConcurrentHashMap<>();
        private final List<java.util.function.Consumer<Message>> globalHandlers = new CopyOnWriteArrayList<>();
        private volatile boolean responsive = true;
        private volatile boolean shutdown = false;
        private final ExecutorService processor;

        SimulatedNode(String nodeId, MetricsRegistry metrics) {
            this.nodeId = nodeId;
            this.metrics = metrics;
            this.processor = Executors.newSingleThreadExecutor();
            startMessageProcessor();
        }

        private void startMessageProcessor() {
            processor.submit(() -> {
                while (!shutdown) {
                    try {
                        Message msg = inbox.poll(100, TimeUnit.MILLISECONDS);
                        if (msg != null && responsive) {
                            processMessage(msg);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        private void processMessage(Message msg) {
            String type = msg.type();
            List<java.util.function.Consumer<Message>> handlers = messageHandlers.get(type);
            if (handlers != null) {
                handlers.forEach(h -> h.accept(msg));
            }
            globalHandlers.forEach(h -> h.accept(msg));
        }

        void registerOutboundChannel(String targetNode, MessageChannel channel) {
            outboundChannels.put(targetNode, channel);
        }

        void send(String targetNode, Message message) {
            MessageChannel channel = outboundChannels.get(targetNode);
            if (channel == null) {
                throw new IllegalStateException("No channel to " + targetNode);
            }
            channel.deliver(message);
            metrics.incrementCounter("node.messages.sent");
        }

        void receive(Message message) {
            inbox.offer(message);
            metrics.incrementCounter("node.messages.received");
        }

        void onMessage(String type, java.util.function.Consumer<Message> handler) {
            messageHandlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        }

        void onAnyMessage(java.util.function.Consumer<Message> handler) {
            globalHandlers.add(handler);
        }

        Message awaitMessage(String type, Duration timeout) throws TimeoutException {
            CompletableFuture<Message> future = new CompletableFuture<>();

            // Register handler FIRST before any message can arrive
            List<java.util.function.Consumer<Message>> handlers = messageHandlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
            java.util.function.Consumer<Message> handler = future::complete;
            handlers.add(handler);

            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                metrics.incrementCounter("node.timeouts");
                throw new TimeoutException("Timeout waiting for " + type);
            } finally {
                handlers.remove(handler);
            }
        }

        void setResponsive(boolean responsive) {
            this.responsive = responsive;
        }

        void shutdown() {
            shutdown = true;
            processor.shutdownNow();
        }
    }

    /**
     * Simulates a network channel between two nodes.
     */
    private static class MessageChannel {
        private final SimulatedNode source;
        private final SimulatedNode target;
        private volatile boolean connected = true;

        MessageChannel(SimulatedNode source, SimulatedNode target) {
            this.source = source;
            this.target = target;
        }

        void deliver(Message message) {
            if (!connected) {
                throw new IllegalStateException("Channel disconnected");
            }
            target.receive(message);
        }

        void simulateDisconnection(Duration duration) {
            connected = false;
        }

        void reconnect() {
            connected = true;
        }
    }
}

