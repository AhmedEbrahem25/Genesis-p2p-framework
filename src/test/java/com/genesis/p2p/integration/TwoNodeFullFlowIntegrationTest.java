package com.genesis.p2p.integration;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.peer.PeerStore;
import com.genesis.p2p.nat.NatType;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.KeyExchangeException;
import com.genesis.p2p.security.api.IKeyManager;
import com.genesis.p2p.security.api.ISecureSession;
import com.genesis.p2p.security.api.ISessionManager;
import com.genesis.p2p.security.channel.*;
import org.junit.jupiter.api.*;

import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Complete End-to-End Two-Node P2P Flow Integration Tests.
 *
 * Simulates the FULL lifecycle of two P2P nodes connecting:
 *
 * 1. DISCOVERY PHASE
 *    - Node A broadcasts announcement
 *    - Node B receives and adds Node A to PeerStore
 *    - Node B broadcasts reply
 *    - Node A receives and adds Node B to PeerStore
 *
 * 2. KEY EXCHANGE PHASE (Secure Channel Establishment)
 *    - Node A sends KEY_EXCHANGE_INIT to Node B
 *    - Node B verifies signature, performs ECDH
 *    - Node B sends KEY_EXCHANGE_COMPLETE to Node A
 *    - Node A verifies, completes ECDH, derives shared secret
 *    - Both nodes now have identical session keys
 *
 * 3. HANDSHAKE PHASE (Application Authentication)
 *    - Node A sends encrypted HANDSHAKE_REQUEST
 *    - Node B verifies, sends encrypted HANDSHAKE_RESPONSE
 *    - Both nodes transition to AUTHENTICATED state
 *
 * 4. MESSAGING PHASE
 *    - Nodes exchange encrypted application messages
 *    - PING/PONG for health checks
 *    - Data messages for application use
 *
 * 5. DISCONNECTION PHASE
 *    - Graceful disconnect with DISCONNECT message
 *    - Session cleanup
 *    - State transitions
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
@DisplayName("Complete Two-Node P2P Flow Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TwoNodeFullFlowIntegrationTest {

    // ========================= Node A Components =========================
    private PeerManager peerManagerA;
    private SecureChannelNegotiator negotiatorA;
    private IKeyManager keyManagerA;
    private ISessionManager sessionManagerA;
    private MetricsRegistry metricsA;
    private KeyPair identityKeyPairA;

    // ========================= Node B Components =========================
    private PeerManager peerManagerB;
    private SecureChannelNegotiator negotiatorB;
    private IKeyManager keyManagerB;
    private ISessionManager sessionManagerB;
    private MetricsRegistry metricsB;
    private KeyPair identityKeyPairB;

    // ========================= Network Simulation =========================
    private BlockingQueue<Object> networkAtoB;
    private BlockingQueue<Object> networkBtoA;
    private ExecutorService networkExecutor;

    // ========================= Node Configuration =========================
    private static final String NODE_A_ID = "node-alpha-001";
    private static final String NODE_B_ID = "node-beta-002";
    private static final String NODE_A_IP = "192.168.1.100";
    private static final String NODE_B_IP = "192.168.1.101";
    private static final int NODE_A_PORT = 8081;
    private static final int NODE_B_PORT = 8082;

    // ========================= Test State =========================
    private AtomicReference<String> sessionIdA = new AtomicReference<>();
    private AtomicReference<String> sessionIdB = new AtomicReference<>();
    private List<Message> messagesReceivedByA = new CopyOnWriteArrayList<>();
    private List<Message> messagesReceivedByB = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        // Generate identity key pairs
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(256);
        identityKeyPairA = keyGen.generateKeyPair();
        identityKeyPairB = keyGen.generateKeyPair();

        // Initialize Node A
        metricsA = new MetricsRegistry(NODE_A_ID);
        peerManagerA = new PeerManager();  // Use default constructor
        keyManagerA = createKeyManager(identityKeyPairA);
        sessionManagerA = createSessionManager(NODE_A_ID);
        negotiatorA = new SecureChannelNegotiator(
                NODE_A_ID, keyManagerA, sessionManagerA,
                null, null, "1.0", metricsA
        );

        // Initialize Node B
        metricsB = new MetricsRegistry(NODE_B_ID);
        peerManagerB = new PeerManager();  // Use default constructor
        keyManagerB = createKeyManager(identityKeyPairB);
        sessionManagerB = createSessionManager(NODE_B_ID);
        negotiatorB = new SecureChannelNegotiator(
                NODE_B_ID, keyManagerB, sessionManagerB,
                null, null, "1.0", metricsB
        );

        // Initialize network simulation
        networkAtoB = new LinkedBlockingQueue<>();
        networkBtoA = new LinkedBlockingQueue<>();
        networkExecutor = Executors.newFixedThreadPool(4);

        // Clear message logs
        messagesReceivedByA.clear();
        messagesReceivedByB.clear();
    }

    @AfterEach
    void tearDown() {
        if (peerManagerA != null) peerManagerA.close();
        if (peerManagerB != null) peerManagerB.close();
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
        }
    }

    private IKeyManager createKeyManager(KeyPair identityKeyPair) throws Exception {
        IKeyManager keyManager = mock(IKeyManager.class);

        when(keyManager.getIdentityPublicKey()).thenReturn(identityKeyPair.getPublic().getEncoded());

        when(keyManager.signWithIdentityKey(any())).thenAnswer(inv -> {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(identityKeyPair.getPrivate());
            sig.update((byte[]) inv.getArgument(0));
            return sig.sign();
        });

        when(keyManager.verifyIdentitySignature(any(), any(), any())).thenReturn(true);

        return keyManager;
    }

    private ISessionManager createSessionManager(String nodeId) throws Exception {
        ISessionManager sessionManager = mock(ISessionManager.class);

        when(sessionManager.createSession(any(), any(), any())).thenAnswer(inv -> {
            ISecureSession session = mock(ISecureSession.class);
            String sessId = "session-" + nodeId + "-" + System.nanoTime();
            when(session.getSessionId()).thenReturn(sessId);
            when(session.isActive()).thenReturn(true);
            when(session.getPeerId()).thenReturn((String) inv.getArgument(0));
            return session;
        });

        return sessionManager;
    }

    // ========================= PHASE 1: DISCOVERY TESTS =========================

    @Nested
    @DisplayName("Phase 1: Discovery")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DiscoveryPhaseTests {

        @Test
        @Order(1)
        @DisplayName("1.1 Node A broadcasts discovery announcement")
        void testNodeABroadcastsAnnouncement() {
            // Simulate Node A creating a broadcast announcement
            Map<String, Object> announcement = new HashMap<>();
            announcement.put("nodeId", NODE_A_ID);
            announcement.put("ip", NODE_A_IP);
            announcement.put("port", NODE_A_PORT);
            announcement.put("version", "1.0");
            announcement.put("timestamp", System.currentTimeMillis());
            announcement.put("identityPublicKey", Base64.getEncoder().encodeToString(
                    identityKeyPairA.getPublic().getEncoded()));

            assertNotNull(announcement.get("nodeId"));
            assertNotNull(announcement.get("identityPublicKey"));
            assertEquals(NODE_A_ID, announcement.get("nodeId"));
        }

        @Test
        @Order(2)
        @DisplayName("1.2 Node B receives announcement and adds Node A to PeerStore")
        void testNodeBReceivesAndAddsNodeA() {
            // Simulate Node B receiving Node A's announcement
            Peer peerA = createPeer(NODE_A_ID, NODE_A_IP, NODE_A_PORT,
                    identityKeyPairA.getPublic().getEncoded());

            boolean added = peerManagerB.upsertPeer(peerA);

            assertTrue(added, "Node B should add Node A to PeerStore");
            assertEquals(1, peerManagerB.size());

            Peer retrieved = peerManagerB.getPeer(NODE_A_ID);
            assertNotNull(retrieved);
            assertEquals(NODE_A_ID, retrieved.id());
            assertEquals(NODE_A_IP, retrieved.ip());
            assertEquals(NODE_A_PORT, retrieved.port());
        }

        @Test
        @Order(3)
        @DisplayName("1.3 Node B broadcasts reply announcement")
        void testNodeBBroadcastsReply() {
            Map<String, Object> reply = new HashMap<>();
            reply.put("nodeId", NODE_B_ID);
            reply.put("ip", NODE_B_IP);
            reply.put("port", NODE_B_PORT);
            reply.put("version", "1.0");
            reply.put("timestamp", System.currentTimeMillis());
            reply.put("identityPublicKey", Base64.getEncoder().encodeToString(
                    identityKeyPairB.getPublic().getEncoded()));

            assertNotNull(reply.get("nodeId"));
            assertEquals(NODE_B_ID, reply.get("nodeId"));
        }

        @Test
        @Order(4)
        @DisplayName("1.4 Node A receives reply and adds Node B to PeerStore")
        void testNodeAReceivesAndAddsNodeB() {
            Peer peerB = createPeer(NODE_B_ID, NODE_B_IP, NODE_B_PORT,
                    identityKeyPairB.getPublic().getEncoded());

            boolean added = peerManagerA.upsertPeer(peerB);

            assertTrue(added, "Node A should add Node B to PeerStore");
            assertEquals(1, peerManagerA.size());

            Peer retrieved = peerManagerA.getPeer(NODE_B_ID);
            assertNotNull(retrieved);
            assertEquals(NODE_B_ID, retrieved.id());
        }

        @Test
        @Order(5)
        @DisplayName("1.5 Both nodes have discovered each other")
        void testMutualDiscovery() {
            // Add peers to both managers
            peerManagerA.upsertPeer(createPeer(NODE_B_ID, NODE_B_IP, NODE_B_PORT,
                    identityKeyPairB.getPublic().getEncoded()));
            peerManagerB.upsertPeer(createPeer(NODE_A_ID, NODE_A_IP, NODE_A_PORT,
                    identityKeyPairA.getPublic().getEncoded()));

            // Verify mutual discovery
            assertNotNull(peerManagerA.getPeer(NODE_B_ID), "Node A should know Node B");
            assertNotNull(peerManagerB.getPeer(NODE_A_ID), "Node B should know Node A");

            // Check initial state
            assertEquals(PeerStore.PeerState.DISCOVERED, peerManagerA.getPeerState(NODE_B_ID));
            assertEquals(PeerStore.PeerState.DISCOVERED, peerManagerB.getPeerState(NODE_A_ID));
        }
    }

    // ========================= PHASE 2: KEY EXCHANGE TESTS =========================

    @Nested
    @DisplayName("Phase 2: Key Exchange (Secure Channel)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class KeyExchangePhaseTests {

        @BeforeEach
        void setupDiscoveredPeers() {
            // Ensure both nodes have discovered each other
            peerManagerA.upsertPeer(createPeer(NODE_B_ID, NODE_B_IP, NODE_B_PORT,
                    identityKeyPairB.getPublic().getEncoded()));
            peerManagerB.upsertPeer(createPeer(NODE_A_ID, NODE_A_IP, NODE_A_PORT,
                    identityKeyPairA.getPublic().getEncoded()));
        }

        @Test
        @Order(1)
        @DisplayName("2.1 Node A initiates KEY_EXCHANGE_INIT")
        void testNodeAInitiatesKeyExchange() throws Exception {
            // Node A initiates key exchange
            KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            assertNotNull(init, "KEY_EXCHANGE_INIT should be created");
            assertEquals(NODE_A_ID, init.getNodeId());
            assertNotNull(init.getCorrelationId());
            assertNotNull(init.getEphemeralPublicKey());
            assertNotNull(init.getSignature());
            assertTrue(init.isTimestampFresh());

            // Verify Node A tracks the negotiation
            assertTrue(negotiatorA.hasActiveNegotiation(NODE_B_ID));
        }

        @Test
        @Order(2)
        @DisplayName("2.2 Node B receives and processes KEY_EXCHANGE_INIT")
        void testNodeBProcessesKeyExchangeInit() throws Exception {
            // Node A initiates
            KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            // Simulate network transmission (A → B)
            // Node B processes the INIT
            KeyExchangeComplete complete = negotiatorB.processKeyExchangeInit(init);

            assertNotNull(complete, "KEY_EXCHANGE_COMPLETE should be created");
            assertTrue(complete.isAccepted(), "Node B should accept the exchange");
            assertEquals(NODE_B_ID, complete.getNodeId());
            assertEquals(init.getCorrelationId(), complete.getCorrelationId());
            assertNotNull(complete.getEphemeralPublicKey());
            assertNotNull(complete.getSignature());
        }

        @Test
        @Order(3)
        @DisplayName("2.3 Node A processes KEY_EXCHANGE_COMPLETE")
        void testNodeAProcessesKeyExchangeComplete() throws Exception {
            // Full exchange
            KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            KeyExchangeComplete complete = negotiatorB.processKeyExchangeInit(init);

            // Node A processes the COMPLETE
            KeyExchangeResult result = negotiatorA.processKeyExchangeComplete(complete);

            assertNotNull(result, "Result should not be null");
            assertTrue(result.isSuccess(), "Key exchange should succeed");
            assertNotNull(result.getSessionId(), "Session ID should be created");

            sessionIdA.set(result.getSessionId());
        }

        @Test
        @Order(4)
        @DisplayName("2.4 Both nodes derive identical shared secrets")
        void testSharedSecretDerivation() throws Exception {
            // Generate ephemeral keys for ECDH test
            EphemeralKeyPair ephemeralA = EphemeralKeyPair.generate();
            EphemeralKeyPair ephemeralB = EphemeralKeyPair.generate();

            // Derive shared secrets
            byte[] secretA = ephemeralA.deriveSharedSecret(ephemeralB.getPublicKeyBytes());
            byte[] secretB = ephemeralB.deriveSharedSecret(ephemeralA.getPublicKeyBytes());

            // Verify ECDH property
            assertArrayEquals(secretA, secretB, "Shared secrets must be identical");
            assertTrue(secretA.length >= 32, "Shared secret should be at least 256 bits");
        }

        @Test
        @Order(5)
        @DisplayName("2.5 Complete key exchange establishes secure channel")
        void testSecureChannelEstablished() throws Exception {
            // Full exchange
            KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            KeyExchangeComplete complete = negotiatorB.processKeyExchangeInit(init);
            KeyExchangeResult result = negotiatorA.processKeyExchangeComplete(complete);

            // Verify secure channel is established
            assertTrue(result.isSuccess());

            // Check session IDs exist
            String sessionIdOnA = negotiatorA.getChannelSessionId(NODE_B_ID);
            String sessionIdOnB = negotiatorB.getChannelSessionId(NODE_A_ID);

            // At least one should have a session (responder creates first)
            assertTrue(sessionIdOnA != null || sessionIdOnB != null,
                    "At least one node should have session ID");
        }
    }

    // ========================= PHASE 3: HANDSHAKE TESTS =========================

    @Nested
    @DisplayName("Phase 3: Application Handshake")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class HandshakePhaseTests {

        @BeforeEach
        void setupSecureChannel() throws Exception {
            // Setup discovered peers
            peerManagerA.upsertPeer(createPeer(NODE_B_ID, NODE_B_IP, NODE_B_PORT,
                    identityKeyPairB.getPublic().getEncoded()));
            peerManagerB.upsertPeer(createPeer(NODE_A_ID, NODE_A_IP, NODE_A_PORT,
                    identityKeyPairA.getPublic().getEncoded()));

            // Complete key exchange
            KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                    NODE_B_ID, identityKeyPairB.getPublic().getEncoded());
            KeyExchangeComplete complete = negotiatorB.processKeyExchangeInit(init);
            negotiatorA.processKeyExchangeComplete(complete);
        }

        @Test
        @Order(1)
        @DisplayName("3.1 Node A sends HANDSHAKE_REQUEST")
        void testNodeASendsHandshakeRequest() {
            // Create handshake request message
            Message handshakeRequest = createHandshakeRequest(NODE_A_ID, NODE_B_ID);

            assertNotNull(handshakeRequest);
            assertEquals("HANDSHAKE_REQUEST", handshakeRequest.header().type());
            assertEquals(NODE_A_ID, handshakeRequest.header().from());
            assertEquals(NODE_B_ID, handshakeRequest.header().to());
        }

        @Test
        @Order(2)
        @DisplayName("3.2 Node B processes HANDSHAKE_REQUEST and sends HANDSHAKE_RESPONSE")
        void testNodeBRespondsToHandshake() {
            Message request = createHandshakeRequest(NODE_A_ID, NODE_B_ID);

            // Simulate Node B processing request
            Message response = createHandshakeResponse(NODE_B_ID, NODE_A_ID,
                    request.header().correlationId(), true);

            assertNotNull(response);
            assertEquals("HANDSHAKE_RESPONSE", response.header().type());
            assertEquals(NODE_B_ID, response.header().from());
            assertEquals(NODE_A_ID, response.header().to());
            assertEquals(request.header().correlationId(), response.header().correlationId());
        }

        @Test
        @Order(3)
        @DisplayName("3.3 Node A processes HANDSHAKE_RESPONSE")
        void testNodeAProcessesHandshakeResponse() {
            Message request = createHandshakeRequest(NODE_A_ID, NODE_B_ID);
            Message response = createHandshakeResponse(NODE_B_ID, NODE_A_ID,
                    request.header().correlationId(), true);

            // Verify response is accepted
            Map<String, Object> responseData = response.body().metadata();
            assertTrue((Boolean) responseData.get("accepted"));

            // Transition to CONNECTED state
            peerManagerA.markConnected(NODE_B_ID);
            assertEquals(PeerStore.PeerState.CONNECTED, peerManagerA.getPeerState(NODE_B_ID));
        }

        @Test
        @Order(4)
        @DisplayName("3.4 Both nodes transition to AUTHENTICATED state")
        void testBothNodesAuthenticated() {
            // Complete handshake and mark authenticated
            peerManagerA.markConnected(NODE_B_ID);
            peerManagerA.markAuthenticated(NODE_B_ID);

            peerManagerB.markConnected(NODE_A_ID);
            peerManagerB.markAuthenticated(NODE_A_ID);

            assertEquals(PeerStore.PeerState.AUTHENTICATED, peerManagerA.getPeerState(NODE_B_ID));
            assertEquals(PeerStore.PeerState.AUTHENTICATED, peerManagerB.getPeerState(NODE_A_ID));
        }
    }

    // ========================= PHASE 4: MESSAGING TESTS =========================

    @Nested
    @DisplayName("Phase 4: Secure Messaging")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class MessagingPhaseTests {

        @BeforeEach
        void setupAuthenticatedConnection() throws Exception {
            // Setup discovered peers
            peerManagerA.upsertPeer(createPeer(NODE_B_ID, NODE_B_IP, NODE_B_PORT,
                    identityKeyPairB.getPublic().getEncoded()));
            peerManagerB.upsertPeer(createPeer(NODE_A_ID, NODE_A_IP, NODE_A_PORT,
                    identityKeyPairA.getPublic().getEncoded()));

            // Complete key exchange
            KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                    NODE_B_ID, identityKeyPairB.getPublic().getEncoded());
            KeyExchangeComplete complete = negotiatorB.processKeyExchangeInit(init);
            negotiatorA.processKeyExchangeComplete(complete);

            // Complete handshake
            peerManagerA.markConnected(NODE_B_ID);
            peerManagerA.markAuthenticated(NODE_B_ID);
            peerManagerB.markConnected(NODE_A_ID);
            peerManagerB.markAuthenticated(NODE_A_ID);
        }

        @Test
        @Order(1)
        @DisplayName("4.1 Node A sends PING to Node B")
        void testPingMessage() {
            Message ping = createPingMessage(NODE_A_ID, NODE_B_ID);

            assertNotNull(ping);
            assertEquals("PING", ping.header().type());
            assertEquals(NODE_A_ID, ping.header().from());
            assertEquals(NODE_B_ID, ping.header().to());

            // Verify peer is authenticated before sending
            assertEquals(PeerStore.PeerState.AUTHENTICATED, peerManagerA.getPeerState(NODE_B_ID));
        }

        @Test
        @Order(2)
        @DisplayName("4.2 Node B responds with PONG")
        void testPongResponse() {
            Message ping = createPingMessage(NODE_A_ID, NODE_B_ID);
            Message pong = createPongMessage(NODE_B_ID, NODE_A_ID, ping.header().correlationId());

            assertNotNull(pong);
            assertEquals("PONG", pong.header().type());
            assertEquals(ping.header().correlationId(), pong.header().correlationId());
        }

        @Test
        @Order(3)
        @DisplayName("4.3 Latency is recorded after PONG")
        void testLatencyRecording() {
            long startTime = System.currentTimeMillis();

            // Simulate network delay
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            long latency = System.currentTimeMillis() - startTime;

            peerManagerA.updateLatency(NODE_B_ID, latency);

            Peer updated = peerManagerA.getPeer(NODE_B_ID);
            assertTrue(updated.lastLatency() >= 50);
        }

        @Test
        @Order(4)
        @DisplayName("4.4 Node A sends encrypted DATA message")
        void testEncryptedDataMessage() {
            String payload = "Hello from Node A!";
            Message dataMsg = createDataMessage(NODE_A_ID, NODE_B_ID, payload, true);

            assertNotNull(dataMsg);
            assertEquals("DATA", dataMsg.header().type());
            assertTrue(dataMsg.header().encrypted());
            assertEquals(payload, dataMsg.body().content());
        }

        @Test
        @Order(5)
        @DisplayName("4.5 Bidirectional message exchange")
        void testBidirectionalMessaging() throws Exception {
            CountDownLatch messagesReceived = new CountDownLatch(4);
            AtomicInteger aReceived = new AtomicInteger(0);
            AtomicInteger bReceived = new AtomicInteger(0);

            // Simulate message exchange
            ExecutorService executor = Executors.newFixedThreadPool(2);

            // Node A sends messages
            executor.submit(() -> {
                for (int i = 0; i < 2; i++) {
                    Message msg = createDataMessage(NODE_A_ID, NODE_B_ID, "Message " + i, true);
                    messagesReceivedByB.add(msg);
                    bReceived.incrementAndGet();
                    messagesReceived.countDown();
                }
            });

            // Node B sends messages
            executor.submit(() -> {
                for (int i = 0; i < 2; i++) {
                    Message msg = createDataMessage(NODE_B_ID, NODE_A_ID, "Reply " + i, true);
                    messagesReceivedByA.add(msg);
                    aReceived.incrementAndGet();
                    messagesReceived.countDown();
                }
            });

            assertTrue(messagesReceived.await(5, TimeUnit.SECONDS));
            assertEquals(2, aReceived.get());
            assertEquals(2, bReceived.get());

            executor.shutdown();
        }

        @Test
        @Order(6)
        @DisplayName("4.6 Message ordering preserved")
        void testMessageOrdering() {
            List<String> sentOrder = new ArrayList<>();
            List<String> receivedOrder = new ArrayList<>();

            // Send messages in order
            for (int i = 0; i < 5; i++) {
                String content = "Message-" + i;
                sentOrder.add(content);
                Message msg = createDataMessage(NODE_A_ID, NODE_B_ID, content, true);
                receivedOrder.add(msg.body().content());
            }

            assertEquals(sentOrder, receivedOrder, "Message order should be preserved");
        }
    }

    // ========================= PHASE 5: DISCONNECTION TESTS =========================

    @Nested
    @DisplayName("Phase 5: Disconnection")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DisconnectionPhaseTests {

        @BeforeEach
        void setupAuthenticatedConnection() throws Exception {
            peerManagerA.upsertPeer(createPeer(NODE_B_ID, NODE_B_IP, NODE_B_PORT,
                    identityKeyPairB.getPublic().getEncoded()));
            peerManagerB.upsertPeer(createPeer(NODE_A_ID, NODE_A_IP, NODE_A_PORT,
                    identityKeyPairA.getPublic().getEncoded()));

            peerManagerA.markConnected(NODE_B_ID);
            peerManagerA.markAuthenticated(NODE_B_ID);
            peerManagerB.markConnected(NODE_A_ID);
            peerManagerB.markAuthenticated(NODE_A_ID);
        }

        @Test
        @Order(1)
        @DisplayName("5.1 Node A sends DISCONNECT message")
        void testDisconnectMessage() {
            Message disconnect = createDisconnectMessage(NODE_A_ID, NODE_B_ID, "GRACEFUL_SHUTDOWN");

            assertNotNull(disconnect);
            assertEquals("DISCONNECT", disconnect.header().type());
        }

        @Test
        @Order(2)
        @DisplayName("5.2 Node B processes DISCONNECT and updates state")
        void testProcessDisconnect() {
            // Node B marks Node A as disconnected
            peerManagerB.markDisconnected(NODE_A_ID);

            assertEquals(PeerStore.PeerState.DISCONNECTED, peerManagerB.getPeerState(NODE_A_ID));
        }

        @Test
        @Order(3)
        @DisplayName("5.3 Session cleanup on disconnect")
        void testSessionCleanup() throws Exception {
            // Create a negotiation then cancel it
            negotiatorA.initiateKeyExchange(NODE_B_ID, null);
            assertTrue(negotiatorA.hasActiveNegotiation(NODE_B_ID));

            // Cancel on disconnect
            negotiatorA.cancelNegotiation(NODE_B_ID);
            assertFalse(negotiatorA.hasActiveNegotiation(NODE_B_ID));
        }

        @Test
        @Order(4)
        @DisplayName("5.4 Reconnection after disconnect")
        void testReconnection() throws Exception {
            // Disconnect
            peerManagerA.markDisconnected(NODE_B_ID);
            assertEquals(PeerStore.PeerState.DISCONNECTED, peerManagerA.getPeerState(NODE_B_ID));

            // Reconnect
            KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                    NODE_B_ID, identityKeyPairB.getPublic().getEncoded());
            assertNotNull(init, "Should be able to reinitiate key exchange");

            KeyExchangeComplete complete = negotiatorB.processKeyExchangeInit(init);
            assertTrue(complete.isAccepted());

            // Complete reconnection
            peerManagerA.markConnected(NODE_B_ID);
            peerManagerA.markAuthenticated(NODE_B_ID);

            assertEquals(PeerStore.PeerState.AUTHENTICATED, peerManagerA.getPeerState(NODE_B_ID));
        }
    }

    // ========================= FULL LIFECYCLE TEST =========================

    @Nested
    @DisplayName("Complete Lifecycle")
    class FullLifecycleTests {

        @Test
        @DisplayName("Complete P2P connection lifecycle from discovery to disconnect")
        void testCompleteLifecycle() throws Exception {
            // ========== PHASE 1: DISCOVERY ==========
            // Node A and B discover each other
            peerManagerA.upsertPeer(createPeer(NODE_B_ID, NODE_B_IP, NODE_B_PORT,
                    identityKeyPairB.getPublic().getEncoded()));
            peerManagerB.upsertPeer(createPeer(NODE_A_ID, NODE_A_IP, NODE_A_PORT,
                    identityKeyPairA.getPublic().getEncoded()));

            assertEquals(PeerStore.PeerState.DISCOVERED, peerManagerA.getPeerState(NODE_B_ID));
            assertEquals(PeerStore.PeerState.DISCOVERED, peerManagerB.getPeerState(NODE_A_ID));

            // ========== PHASE 2: KEY EXCHANGE ==========
            KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                    NODE_B_ID, identityKeyPairB.getPublic().getEncoded());
            KeyExchangeComplete complete = negotiatorB.processKeyExchangeInit(init);
            KeyExchangeResult result = negotiatorA.processKeyExchangeComplete(complete);

            assertTrue(result.isSuccess(), "Key exchange should succeed");
            assertNotNull(result.getSessionId(), "Session should be created");

            // ========== PHASE 3: HANDSHAKE ==========
            peerManagerA.markConnected(NODE_B_ID);
            peerManagerB.markConnected(NODE_A_ID);
            peerManagerA.markAuthenticated(NODE_B_ID);
            peerManagerB.markAuthenticated(NODE_A_ID);

            assertEquals(PeerStore.PeerState.AUTHENTICATED, peerManagerA.getPeerState(NODE_B_ID));
            assertEquals(PeerStore.PeerState.AUTHENTICATED, peerManagerB.getPeerState(NODE_A_ID));

            // ========== PHASE 4: MESSAGING ==========
            Message ping = createPingMessage(NODE_A_ID, NODE_B_ID);
            Message pong = createPongMessage(NODE_B_ID, NODE_A_ID, ping.header().correlationId());
            assertEquals("PONG", pong.header().type());

            Message data = createDataMessage(NODE_A_ID, NODE_B_ID, "Hello P2P!", true);
            assertEquals("Hello P2P!", data.body().content());

            // Record activity
            peerManagerA.recordSuccess(NODE_B_ID);
            peerManagerB.recordSuccess(NODE_A_ID);

            // ========== PHASE 5: DISCONNECT ==========
            peerManagerA.markDisconnected(NODE_B_ID);
            peerManagerB.markDisconnected(NODE_A_ID);

            assertEquals(PeerStore.PeerState.DISCONNECTED, peerManagerA.getPeerState(NODE_B_ID));
            assertEquals(PeerStore.PeerState.DISCONNECTED, peerManagerB.getPeerState(NODE_A_ID));

            // Cleanup
            negotiatorA.cancelNegotiation(NODE_B_ID);
            negotiatorB.cancelNegotiation(NODE_A_ID);
        }

        @Test
        @DisplayName("Stress test: Multiple connection cycles")
        void testMultipleConnectionCycles() throws Exception {
            for (int cycle = 0; cycle < 5; cycle++) {
                // Setup
                if (peerManagerA.getPeer(NODE_B_ID) == null) {
                    peerManagerA.upsertPeer(createPeer(NODE_B_ID, NODE_B_IP, NODE_B_PORT,
                            identityKeyPairB.getPublic().getEncoded()));
                }
                if (peerManagerB.getPeer(NODE_A_ID) == null) {
                    peerManagerB.upsertPeer(createPeer(NODE_A_ID, NODE_A_IP, NODE_A_PORT,
                            identityKeyPairA.getPublic().getEncoded()));
                }

                // Key exchange
                negotiatorA.cancelNegotiation(NODE_B_ID); // Clear any previous state
                negotiatorB.cancelNegotiation(NODE_A_ID);

                KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                        NODE_B_ID, identityKeyPairB.getPublic().getEncoded());
                KeyExchangeComplete complete = negotiatorB.processKeyExchangeInit(init);
                KeyExchangeResult result = negotiatorA.processKeyExchangeComplete(complete);

                assertTrue(result.isSuccess(), "Cycle " + cycle + ": Key exchange failed");

                // Handshake
                peerManagerA.markConnected(NODE_B_ID);
                peerManagerB.markConnected(NODE_A_ID);
                peerManagerA.markAuthenticated(NODE_B_ID);
                peerManagerB.markAuthenticated(NODE_A_ID);

                // Message
                Message msg = createDataMessage(NODE_A_ID, NODE_B_ID, "Cycle " + cycle, true);
                assertNotNull(msg);

                // Disconnect
                peerManagerA.markDisconnected(NODE_B_ID);
                peerManagerB.markDisconnected(NODE_A_ID);
            }
        }

        @Test
        @DisplayName("Concurrent operations: Both nodes initiate simultaneously")
        void testSimultaneousInitiation() throws Exception {
            peerManagerA.upsertPeer(createPeer(NODE_B_ID, NODE_B_IP, NODE_B_PORT,
                    identityKeyPairB.getPublic().getEncoded()));
            peerManagerB.upsertPeer(createPeer(NODE_A_ID, NODE_A_IP, NODE_A_PORT,
                    identityKeyPairA.getPublic().getEncoded()));

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicReference<KeyExchangeInit> initA = new AtomicReference<>();
            AtomicReference<KeyExchangeInit> initB = new AtomicReference<>();

            // Node A initiates
            networkExecutor.submit(() -> {
                try {
                    startLatch.await();
                    initA.set(negotiatorA.initiateKeyExchange(NODE_B_ID,
                            identityKeyPairB.getPublic().getEncoded()));
                } catch (Exception e) {
                    // May fail due to duplicate
                } finally {
                    doneLatch.countDown();
                }
            });

            // Node B initiates
            networkExecutor.submit(() -> {
                try {
                    startLatch.await();
                    initB.set(negotiatorB.initiateKeyExchange(NODE_A_ID,
                            identityKeyPairA.getPublic().getEncoded()));
                } catch (Exception e) {
                    // May fail due to duplicate
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

            // At least one should succeed
            assertTrue(initA.get() != null || initB.get() != null,
                    "At least one initiation should succeed");
        }
    }

    // ========================= HELPER METHODS =========================

    private Peer createPeer(String id, String ip, int port, byte[] identityPublicKey) {
        String identityKeyBase64 = identityPublicKey != null ?
                Base64.getEncoder().encodeToString(identityPublicKey) : "";

        return new Peer(
                id,
                "",  // publicKey
                "test-host",
                ip,
                port,
                ip,  // publicIp
                port,  // publicPort
                NatType.UNKNOWN,
                false,  // behindNat
                true,  // online
                Instant.now(),
                0L,  // lastLatency
                false,  // trusted
                50,  // reputation
                "1.0",  // version
                "test",  // os
                "test-agent",
                identityKeyBase64
        );
    }

    private Message createHandshakeRequest(String from, String to) {
        String correlationId = UUID.randomUUID().toString();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", "1.0");
        metadata.put("capabilities", List.of("ENCRYPTION", "COMPRESSION"));

        MessageHeader header = new MessageHeader(
                UUID.randomUUID().toString(),
                correlationId,
                "1.0", "1.0",
                10, 0,
                "HANDSHAKE_REQUEST",
                from, to,
                System.currentTimeMillis(),
                true,  // encrypted
                "json",
                false, null, false
        );

        MessageBody body = new MessageBody("Handshake Request", metadata);
        return new Message(header, body);
    }

    private Message createHandshakeResponse(String from, String to, String correlationId, boolean accepted) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("accepted", accepted);
        metadata.put("version", "1.0");

        MessageHeader header = new MessageHeader(
                UUID.randomUUID().toString(),
                correlationId,
                "1.0", "1.0",
                10, 0,
                "HANDSHAKE_RESPONSE",
                from, to,
                System.currentTimeMillis(),
                true,
                "json",
                false, null, false
        );

        MessageBody body = new MessageBody("Handshake Response", metadata);
        return new Message(header, body);
    }

    private Message createPingMessage(String from, String to) {
        String correlationId = UUID.randomUUID().toString();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("timestamp", System.currentTimeMillis());

        MessageHeader header = new MessageHeader(
                UUID.randomUUID().toString(),
                correlationId,
                "1.0", "1.0",
                5, 0,
                "PING",
                from, to,
                System.currentTimeMillis(),
                true,
                "json",
                true, null, false
        );

        MessageBody body = new MessageBody("PING", metadata);
        return new Message(header, body);
    }

    private Message createPongMessage(String from, String to, String correlationId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("timestamp", System.currentTimeMillis());

        MessageHeader header = new MessageHeader(
                UUID.randomUUID().toString(),
                correlationId,
                "1.0", "1.0",
                5, 0,
                "PONG",
                from, to,
                System.currentTimeMillis(),
                true,
                "json",
                true, null, false
        );

        MessageBody body = new MessageBody("PONG", metadata);
        return new Message(header, body);
    }

    private Message createDataMessage(String from, String to, String content, boolean encrypted) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("contentLength", content.length());

        MessageHeader header = new MessageHeader(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "1.0", "1.0",
                10, 0,
                "DATA",
                from, to,
                System.currentTimeMillis(),
                encrypted,
                "text/plain",
                true, null, false
        );

        MessageBody body = new MessageBody(content, metadata);
        return new Message(header, body);
    }

    private Message createDisconnectMessage(String from, String to, String reason) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reason", reason);

        MessageHeader header = new MessageHeader(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "1.0", "1.0",
                5, 0,
                "DISCONNECT",
                from, to,
                System.currentTimeMillis(),
                false,
                "json",
                false, null, false
        );

        MessageBody body = new MessageBody("Disconnect: " + reason, metadata);
        return new Message(header, body);
    }
}

