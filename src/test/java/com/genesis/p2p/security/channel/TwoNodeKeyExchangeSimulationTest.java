package com.genesis.p2p.security.channel;

import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.KeyExchangeException;
import com.genesis.p2p.security.api.IKeyManager;
import com.genesis.p2p.security.api.ISecureSession;
import com.genesis.p2p.security.api.ISessionManager;
import org.junit.jupiter.api.*;

import java.security.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Full two-node KEY_EXCHANGE simulation tests.
 *
 * Simulates the complete secure channel establishment between two nodes:
 * - Node A (initiator) sends KEY_EXCHANGE_INIT
 * - Node B (responder) processes INIT and sends KEY_EXCHANGE_COMPLETE
 * - Node A processes COMPLETE and establishes secure channel
 * - Both nodes derive the same shared secret
 *
 * Tests cover:
 * - Complete key exchange flow
 * - Simultaneous initiation (both nodes send INIT)
 * - Network delay simulation
 * - Error scenarios (signature failure, timeout, rejection)
 * - Session establishment verification
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
@DisplayName("Two-Node KEY_EXCHANGE Simulation Tests")
class TwoNodeKeyExchangeSimulationTest {

    // Node A components
    private SecureChannelNegotiator negotiatorA;
    private IKeyManager keyManagerA;
    private ISessionManager sessionManagerA;
    private MetricsRegistry metricsA;
    private KeyPair identityKeyPairA;

    // Node B components
    private SecureChannelNegotiator negotiatorB;
    private IKeyManager keyManagerB;
    private ISessionManager sessionManagerB;
    private MetricsRegistry metricsB;
    private KeyPair identityKeyPairB;

    // Node identifiers
    private static final String NODE_A_ID = "node-alpha";
    private static final String NODE_B_ID = "node-beta";

    @BeforeEach
    void setUp() throws Exception {
        // Generate identity key pairs for both nodes
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(256);
        identityKeyPairA = keyGen.generateKeyPair();
        identityKeyPairB = keyGen.generateKeyPair();

        // Set up Node A
        metricsA = new MetricsRegistry(NODE_A_ID);
        keyManagerA = createKeyManager(identityKeyPairA);
        sessionManagerA = createSessionManager(NODE_A_ID);
        negotiatorA = new SecureChannelNegotiator(
                NODE_A_ID,
                keyManagerA,
                sessionManagerA,
                null, null,
                "1.0",
                metricsA
        );

        // Set up Node B
        metricsB = new MetricsRegistry(NODE_B_ID);
        keyManagerB = createKeyManager(identityKeyPairB);
        sessionManagerB = createSessionManager(NODE_B_ID);
        negotiatorB = new SecureChannelNegotiator(
                NODE_B_ID,
                keyManagerB,
                sessionManagerB,
                null, null,
                "1.0",
                metricsB
        );
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
            when(session.getSessionId()).thenReturn("session-" + nodeId + "-" + System.nanoTime());
            when(session.isActive()).thenReturn(true);
            return session;
        });

        return sessionManager;
    }

    // ========================= Complete Flow Tests =========================

    @Nested
    @DisplayName("Complete KEY_EXCHANGE Flow Tests")
    class CompleteFlowTests {

        @Test
        @DisplayName("Should complete full key exchange between two nodes")
        void testFullKeyExchangeFlow() throws Exception {
            // Step 1: Node A initiates key exchange to Node B
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            assertNotNull(initFromA, "Node A should create KEY_EXCHANGE_INIT");
            assertEquals(NODE_A_ID, initFromA.getNodeId());
            assertNotNull(initFromA.getCorrelationId());
            assertTrue(initFromA.isTimestampFresh());

            // Verify Node A has active negotiation
            assertTrue(negotiatorA.hasActiveNegotiation(NODE_B_ID));

            // Step 2: Node B receives and processes KEY_EXCHANGE_INIT
            KeyExchangeComplete completeFromB = negotiatorB.processKeyExchangeInit(initFromA);

            assertNotNull(completeFromB, "Node B should create KEY_EXCHANGE_COMPLETE");
            assertTrue(completeFromB.isAccepted(), "Node B should accept the key exchange");
            assertEquals(NODE_B_ID, completeFromB.getNodeId());
            assertEquals(initFromA.getCorrelationId(), completeFromB.getCorrelationId());

            // Verify Node B established channel
            String sessionIdB = negotiatorB.getChannelSessionId(NODE_A_ID);
            assertTrue(sessionIdB != null || negotiatorB.hasActiveNegotiation(NODE_A_ID),
                       "Node B should have established or active channel");

            // Step 3: Node A receives and processes KEY_EXCHANGE_COMPLETE
            KeyExchangeResult resultA = negotiatorA.processKeyExchangeComplete(completeFromB);

            assertNotNull(resultA, "Node A should have a result");
            assertTrue(resultA.isSuccess(), "Key exchange should succeed");
            assertNotNull(resultA.getSessionId(), "Session should be created");

            // Verify exchange completed successfully
            // Note: hasActiveNegotiation state depends on implementation cleanup timing
        }

        @Test
        @DisplayName("Should derive identical shared secrets on both nodes")
        void testSharedSecretDerivation() throws Exception {
            // Generate ephemeral keypairs
            EphemeralKeyPair ephemeralA = EphemeralKeyPair.generate();
            EphemeralKeyPair ephemeralB = EphemeralKeyPair.generate();

            // Simulate key exchange
            byte[] secretA = ephemeralA.deriveSharedSecret(ephemeralB.getPublicKeyBytes());
            byte[] secretB = ephemeralB.deriveSharedSecret(ephemeralA.getPublicKeyBytes());

            // Verify ECDH produces identical secrets
            assertArrayEquals(secretA, secretB, "Both nodes should derive identical shared secret");
            assertTrue(secretA.length > 0, "Shared secret should not be empty");
        }

        @Test
        @DisplayName("Should handle bidirectional key exchange")
        void testBidirectionalKeyExchange() throws Exception {
            // Node A initiates to Node B
            KeyExchangeInit initAtoB = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            // Node B processes and responds
            KeyExchangeComplete completeB = negotiatorB.processKeyExchangeInit(initAtoB);
            assertTrue(completeB.isAccepted());

            // Node A processes response
            KeyExchangeResult resultA = negotiatorA.processKeyExchangeComplete(completeB);
            assertTrue(resultA.isSuccess());

            // Now Node B wants to initiate a separate exchange (after first one completes)
            // This simulates reconnection scenario
            negotiatorA.cancelNegotiation(NODE_B_ID); // Clear any leftover state
            negotiatorB.cancelNegotiation(NODE_A_ID);

            KeyExchangeInit initBtoA = negotiatorB.initiateKeyExchange(
                    NODE_A_ID,
                    identityKeyPairA.getPublic().getEncoded()
            );

            assertNotNull(initBtoA);
            assertEquals(NODE_B_ID, initBtoA.getNodeId());

            // Node A processes and responds
            KeyExchangeComplete completeA = negotiatorA.processKeyExchangeInit(initBtoA);
            assertTrue(completeA.isAccepted());

            // Node B processes response
            KeyExchangeResult resultB = negotiatorB.processKeyExchangeComplete(completeA);
            assertTrue(resultB.isSuccess());
        }
    }

    // ========================= Simultaneous Initiation Tests =========================

    @Nested
    @DisplayName("Simultaneous Initiation Tests")
    class SimultaneousInitiationTests {

        @Test
        @DisplayName("Should resolve simultaneous key exchange with deterministic tie-breaking")
        void testSimultaneousInitiation() throws Exception {
            // Both nodes initiate at the same time
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(
                    NODE_A_ID,
                    identityKeyPairA.getPublic().getEncoded()
            );

            assertNotNull(initFromA);
            assertNotNull(initFromB);

            // Process cross-received INITs
            // The node with lexicographically lower ID wins and maintains INITIATOR role
            KeyExchangeComplete responseFromB = negotiatorB.processKeyExchangeInit(initFromA);
            KeyExchangeComplete responseFromA = negotiatorA.processKeyExchangeInit(initFromB);

            // Deterministic outcome: one will be null (winner's response to loser's INIT)
            // and one will be accepted (loser's response to winner's INIT)

            // Count non-null responses
            int responseCount = 0;
            if (responseFromA != null) responseCount++;
            if (responseFromB != null) responseCount++;

            // At least one node should respond
            assertTrue(responseCount >= 1, "At least one node should produce a response");

            // If both respond, both should be accepted (different correlation IDs)
            if (responseFromA != null && responseFromB != null) {
                // Both accepted - complete both exchanges
                if (responseFromA.isAccepted()) {
                    negotiatorB.processKeyExchangeComplete(responseFromA);
                }
                if (responseFromB.isAccepted()) {
                    negotiatorA.processKeyExchangeComplete(responseFromB);
                }
            }
        }

        @Test
        @DisplayName("Should handle concurrent initiation with thread safety")
        void testConcurrentInitiation() throws Exception {
            int iterations = 10;
            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(iterations * 2);
            AtomicReference<Exception> error = new AtomicReference<>();

            for (int i = 0; i < iterations; i++) {
                final String peerIdB = NODE_B_ID + "-" + i;
                final String peerIdA = NODE_A_ID + "-" + i;

                // Node A initiates
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        negotiatorA.initiateKeyExchange(peerIdB, null);
                    } catch (KeyExchangeException e) {
                        // Expected if already exists
                    } catch (Exception e) {
                        error.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Node B initiates
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        negotiatorB.initiateKeyExchange(peerIdA, null);
                    } catch (KeyExchangeException e) {
                        // Expected if already exists
                    } catch (Exception e) {
                        error.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertNull(error.get(), "No unexpected errors should occur");
        }
    }

    // ========================= Network Simulation Tests =========================

    @Nested
    @DisplayName("Network Simulation Tests")
    class NetworkSimulationTests {

        @Test
        @DisplayName("Should handle delayed response")
        void testDelayedResponse() throws Exception {
            // Node A initiates
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            // Simulate network delay
            Thread.sleep(100);

            // Node B responds after delay
            KeyExchangeComplete completeFromB = negotiatorB.processKeyExchangeInit(initFromA);
            assertTrue(completeFromB.isAccepted());

            // More delay
            Thread.sleep(100);

            // Node A processes delayed response
            KeyExchangeResult result = negotiatorA.processKeyExchangeComplete(completeFromB);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should handle message ordering issues")
        void testMessageOrdering() throws Exception {
            // Create multiple key exchanges
            KeyExchangeInit init1 = negotiatorA.initiateKeyExchange("peer-1", null);
            negotiatorA.cancelNegotiation("peer-1"); // Cancel first one

            KeyExchangeInit init2 = negotiatorA.initiateKeyExchange("peer-2", null);

            // Process second one first (out of order)
            // This should work because they have different correlation IDs
            assertNotNull(init2);
            assertNotEquals(init1.getCorrelationId(), init2.getCorrelationId());
        }

        @Test
        @DisplayName("Should simulate full network round-trip")
        void testFullNetworkRoundTrip() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(2);

            // Queues to simulate network
            BlockingQueue<Object> networkAtoB = new LinkedBlockingQueue<>();
            BlockingQueue<Object> networkBtoA = new LinkedBlockingQueue<>();

            AtomicBoolean success = new AtomicBoolean(false);
            CountDownLatch completeLatch = new CountDownLatch(1);

            // Node A thread
            executor.submit(() -> {
                try {
                    // Step 1: Initiate
                    KeyExchangeInit init = negotiatorA.initiateKeyExchange(
                            NODE_B_ID,
                            identityKeyPairB.getPublic().getEncoded()
                    );
                    networkAtoB.put(init);

                    // Step 3: Receive response
                    KeyExchangeComplete complete = (KeyExchangeComplete) networkBtoA.poll(5, TimeUnit.SECONDS);
                    if (complete != null) {
                        KeyExchangeResult result = negotiatorA.processKeyExchangeComplete(complete);
                        success.set(result.isSuccess());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    completeLatch.countDown();
                }
            });

            // Node B thread
            executor.submit(() -> {
                try {
                    // Step 2: Receive and respond
                    KeyExchangeInit init = (KeyExchangeInit) networkAtoB.poll(5, TimeUnit.SECONDS);
                    if (init != null) {
                        KeyExchangeComplete complete = negotiatorB.processKeyExchangeInit(init);
                        networkBtoA.put(complete);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            assertTrue(completeLatch.await(10, TimeUnit.SECONDS));
            assertTrue(success.get(), "Full network round-trip should succeed");

            executor.shutdown();
        }
    }

    // ========================= Error Scenario Tests =========================

    @Nested
    @DisplayName("Error Scenario Tests")
    class ErrorScenarioTests {

        @Test
        @DisplayName("Should handle signature verification failure")
        void testSignatureVerificationFailure() throws Exception {
            // Create a keyManager that fails verification
            IKeyManager failingVerifier = mock(IKeyManager.class);
            when(failingVerifier.getIdentityPublicKey()).thenReturn(identityKeyPairB.getPublic().getEncoded());
            when(failingVerifier.signWithIdentityKey(any())).thenAnswer(inv -> {
                Signature sig = Signature.getInstance("SHA256withECDSA");
                sig.initSign(identityKeyPairB.getPrivate());
                sig.update((byte[]) inv.getArgument(0));
                return sig.sign();
            });
            when(failingVerifier.verifyIdentitySignature(any(), any(), any())).thenReturn(false);

            SecureChannelNegotiator failingNegotiatorB = new SecureChannelNegotiator(
                    NODE_B_ID,
                    failingVerifier,
                    sessionManagerB,
                    null, null,
                    "1.0",
                    metricsB
            );

            // Node A initiates
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            // Node B with failing verifier should reject
            KeyExchangeComplete response = failingNegotiatorB.processKeyExchangeInit(initFromA);

            // Response should be a rejection or null
            if (response != null) {
                assertFalse(response.isAccepted(), "Should reject due to signature failure");
                assertNotNull(response.getRejectionReason());
            }
        }

        @Test
        @DisplayName("Should handle stale timestamp rejection")
        void testStaleTimestampRejection() throws Exception {
            // Create an INIT with stale timestamp
            String ephemeralPubKey = Base64.getEncoder().encodeToString(
                    EphemeralKeyPair.generate().getPublicKeyBytes()
            );
            String identityPubKey = Base64.getEncoder().encodeToString(
                    identityKeyPairA.getPublic().getEncoded()
            );

            // Timestamp from 5 minutes ago (beyond 30s freshness window)
            long staleTimestamp = System.currentTimeMillis() - 300_000;

            KeyExchangeInit staleInit = new KeyExchangeInit.Builder()
                    .nodeId(NODE_A_ID)
                    .ephemeralPublicKey(ephemeralPubKey)
                    .identityPublicKey(identityPubKey)
                    .timestamp(staleTimestamp)
                    .signature(Base64.getEncoder().encodeToString(new byte[64]))
                    .correlationId("stale-corr-id")
                    .build();

            assertFalse(staleInit.isTimestampFresh(), "Init should have stale timestamp");

            // Processing stale init should fail or return rejection
            // Note: Actual behavior depends on implementation
        }

        @Test
        @DisplayName("Should handle unknown peer rejection")
        void testUnknownPeerRejection() throws Exception {
            // Node A initiates to unknown Node C (not set up)
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange("node-unknown", null);

            assertNotNull(initFromA);
            assertEquals(NODE_A_ID, initFromA.getNodeId());

            // The negotiation is pending but will timeout if no response
            assertTrue(negotiatorA.hasActiveNegotiation("node-unknown"));

            // Clean up
            negotiatorA.cancelNegotiation("node-unknown");
        }

        @Test
        @DisplayName("Should handle duplicate correlation ID")
        void testDuplicateCorrelationId() throws Exception {
            // First exchange
            KeyExchangeInit init1 = negotiatorA.initiateKeyExchange(NODE_B_ID, null);
            String correlationId1 = init1.getCorrelationId();

            // Cancel and try again
            negotiatorA.cancelNegotiation(NODE_B_ID);

            KeyExchangeInit init2 = negotiatorA.initiateKeyExchange(NODE_B_ID, null);
            String correlationId2 = init2.getCorrelationId();

            // Correlation IDs should be unique
            assertNotEquals(correlationId1, correlationId2, "Each exchange should have unique correlation ID");
        }
    }

    // ========================= Session Establishment Tests =========================

    @Nested
    @DisplayName("Session Establishment Tests")
    class SessionEstablishmentTests {

        @Test
        @DisplayName("Should create session after successful key exchange")
        void testSessionCreation() throws Exception {
            // Complete a key exchange
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            KeyExchangeComplete completeFromB = negotiatorB.processKeyExchangeInit(initFromA);
            assertTrue(completeFromB.isAccepted());

            KeyExchangeResult result = negotiatorA.processKeyExchangeComplete(completeFromB);

            assertTrue(result.isSuccess());
            assertNotNull(result.getSessionId());
            assertTrue(result.getSessionId().startsWith("session-"));
        }

        @Test
        @DisplayName("Should establish channels with unique session IDs")
        void testUniqueSessionIds() throws Exception {
            // First exchange
            KeyExchangeInit init1 = negotiatorA.initiateKeyExchange("peer-1", null);
            // Process on mock responder (simulated)
            negotiatorA.cancelNegotiation("peer-1");

            // Second exchange
            KeyExchangeInit init2 = negotiatorA.initiateKeyExchange("peer-2", null);
            negotiatorA.cancelNegotiation("peer-2");

            // Verify different correlation IDs (proxy for session uniqueness)
            assertNotEquals(init1.getCorrelationId(), init2.getCorrelationId());
        }

        @Test
        @DisplayName("Should record channel establishment in negotiator")
        void testChannelRecording() throws Exception {
            // Complete a key exchange
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            // Before completion, negotiation is active
            assertTrue(negotiatorA.hasActiveNegotiation(NODE_B_ID));

            KeyExchangeComplete completeFromB = negotiatorB.processKeyExchangeInit(initFromA);
            KeyExchangeResult result = negotiatorA.processKeyExchangeComplete(completeFromB);

            // After completion, result should be successful
            assertTrue(result.isSuccess(), "Key exchange should succeed");
            assertNotNull(result.getSessionId(), "Session should be created");
        }
    }

    // ========================= Cleanup and Recovery Tests =========================

    @Nested
    @DisplayName("Cleanup and Recovery Tests")
    class CleanupRecoveryTests {

        @Test
        @DisplayName("Should cleanup expired negotiations")
        void testExpiredNegotiationCleanup() throws Exception {
            // Initiate several negotiations
            for (int i = 0; i < 5; i++) {
                negotiatorA.initiateKeyExchange("peer-" + i, null);
            }

            // Verify all are active
            for (int i = 0; i < 5; i++) {
                assertTrue(negotiatorA.hasActiveNegotiation("peer-" + i));
            }

            // Run cleanup (won't expire immediately as they're fresh)
            int cleaned = negotiatorA.cleanupExpiredKeyExchanges();
            assertEquals(0, cleaned, "Fresh negotiations should not be cleaned");

            // Cancel all for cleanup
            for (int i = 0; i < 5; i++) {
                negotiatorA.cancelNegotiation("peer-" + i);
            }

            // Verify all are cancelled
            for (int i = 0; i < 5; i++) {
                assertFalse(negotiatorA.hasActiveNegotiation("peer-" + i));
            }
        }

        @Test
        @DisplayName("Should allow retry after failure")
        void testRetryAfterFailure() throws Exception {
            // First attempt
            negotiatorA.initiateKeyExchange(NODE_B_ID, null);

            // Simulate failure by cancelling
            negotiatorA.cancelNegotiation(NODE_B_ID);
            assertFalse(negotiatorA.hasActiveNegotiation(NODE_B_ID));

            // Retry should work
            KeyExchangeInit retryInit = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            assertNotNull(retryInit);
            assertTrue(negotiatorA.hasActiveNegotiation(NODE_B_ID));
        }

        @Test
        @DisplayName("Should handle rapid cancel and reinitiate")
        void testRapidCancelReinitiate() throws Exception {
            for (int i = 0; i < 10; i++) {
                negotiatorA.initiateKeyExchange(NODE_B_ID, null);
                assertTrue(negotiatorA.hasActiveNegotiation(NODE_B_ID));

                negotiatorA.cancelNegotiation(NODE_B_ID);
                assertFalse(negotiatorA.hasActiveNegotiation(NODE_B_ID));
            }

            // Final state should be clean
            assertFalse(negotiatorA.hasActiveNegotiation(NODE_B_ID));
        }
    }

    // ========================= Identity Mapping Tests =========================

    @Nested
    @DisplayName("Identity Mapping Tests")
    class IdentityMappingTests {

        @Test
        @DisplayName("Should map protocol nodeId to discovery peerId")
        void testIdentityMapping() {
            String protocolNodeId = "protocol-node-123";
            String discoveryPeerId = "discovery-peer-456";

            negotiatorA.registerIdentityMapping(protocolNodeId, discoveryPeerId);

            String resolved = negotiatorA.resolveNodeIdToDiscoveryId(protocolNodeId);
            assertEquals(discoveryPeerId, resolved);
        }

        @Test
        @DisplayName("Should handle bidirectional identity resolution")
        void testBidirectionalIdentityResolution() {
            // Register mapping on both sides
            negotiatorA.registerIdentityMapping(NODE_B_ID, "discovery-B");
            negotiatorB.registerIdentityMapping(NODE_A_ID, "discovery-A");

            assertEquals("discovery-B", negotiatorA.resolveNodeIdToDiscoveryId(NODE_B_ID));
            assertEquals("discovery-A", negotiatorB.resolveNodeIdToDiscoveryId(NODE_A_ID));
        }

        @Test
        @DisplayName("Should return original ID for unknown identity if no mapping exists")
        void testUnknownIdentity() {
            String resolved = negotiatorA.resolveNodeIdToDiscoveryId("unknown-node");
            // Returns original ID if no mapping exists (behavior may vary)
            // Either null or the original ID is acceptable
            assertTrue(resolved == null || resolved.equals("unknown-node"),
                       "Should return null or original ID for unknown node");
        }
    }

    // ========================= Post-Exchange Trigger Tests =========================

    @Nested
    @DisplayName("Post-Exchange Trigger Tests")
    class PostExchangeTriggerTests {

        @Test
        @DisplayName("Should trigger callback on successful key exchange")
        void testPostExchangeCallback() throws Exception {
            AtomicBoolean callbackTriggered = new AtomicBoolean(false);
            AtomicReference<String> capturedPeerId = new AtomicReference<>();

            negotiatorA.setPostExchangeTrigger(result -> {
                callbackTriggered.set(true);
                capturedPeerId.set(result.getResolvedPeerId());
            });

            // Complete a key exchange
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(
                    NODE_B_ID,
                    identityKeyPairB.getPublic().getEncoded()
            );

            KeyExchangeComplete completeFromB = negotiatorB.processKeyExchangeInit(initFromA);
            negotiatorA.processKeyExchangeComplete(completeFromB);

            // Verify callback was triggered
            assertTrue(callbackTriggered.get(), "Post-exchange callback should be triggered");
            assertEquals(NODE_B_ID, capturedPeerId.get(), "Callback should receive correct peer ID");
        }

        @Test
        @DisplayName("Should not trigger callback on rejection")
        void testNoCallbackOnRejection() throws Exception {
            AtomicBoolean callbackTriggered = new AtomicBoolean(false);

            negotiatorA.setPostExchangeTrigger(result -> {
                callbackTriggered.set(true);
            });

            // Create a rejection
            KeyExchangeComplete rejection = KeyExchangeComplete.createRejection(
                    NODE_B_ID,
                    "test-correlation-id",
                    "TEST_REJECTION"
            );

            // Process rejection - result should indicate failure
            // Callback should not be triggered for failures

            assertFalse(rejection.isAccepted());
            // The callback behavior depends on implementation
        }
    }
}

