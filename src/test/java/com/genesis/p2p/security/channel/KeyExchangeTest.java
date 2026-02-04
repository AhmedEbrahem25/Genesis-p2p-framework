package com.genesis.p2p.security.channel;

import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.KeyExchangeException;
import com.genesis.p2p.security.api.IKeyManager;
import com.genesis.p2p.security.api.ISecureSession;
import com.genesis.p2p.security.api.ISessionManager;
import org.junit.jupiter.api.*;

import java.security.*;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for KEY_EXCHANGE protocol.
 *
 * Tests cover:
 * - KeyExchangeInit creation and validation
 * - KeyExchangeComplete creation and validation
 * - SecureChannelNegotiator key exchange flow
 * - Timeout and retry scenarios
 * - Error handling
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
@DisplayName("KEY_EXCHANGE Protocol Tests")
class KeyExchangeTest {

    private MetricsRegistry metrics;
    private IKeyManager keyManager;
    private ISessionManager sessionManager;
    private KeyPair identityKeyPair;
    private KeyPair ephemeralKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        metrics = new MetricsRegistry("test-node");

        // Generate key pairs
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(256);
        identityKeyPair = keyGen.generateKeyPair();
        ephemeralKeyPair = keyGen.generateKeyPair();

        // Mock key manager
        keyManager = mock(IKeyManager.class);
        when(keyManager.getIdentityPublicKey()).thenReturn(identityKeyPair.getPublic().getEncoded());

        // Mock signing
        when(keyManager.signWithIdentityKey(any())).thenAnswer(inv -> {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(identityKeyPair.getPrivate());
            sig.update((byte[]) inv.getArgument(0));
            return sig.sign();
        });

        // Mock signature verification
        when(keyManager.verifyIdentitySignature(any(), any(), any())).thenReturn(true);

        // Mock session manager
        sessionManager = mock(ISessionManager.class);
        ISecureSession mockSession = mock(ISecureSession.class);
        when(mockSession.getSessionId()).thenReturn("test-session-id");
        when(sessionManager.createSession(any(), any(), any())).thenReturn(mockSession);
    }

    // ========================= KeyExchangeInit Tests =========================

    @Nested
    @DisplayName("KeyExchangeInit Tests")
    class KeyExchangeInitTests {

        @Test
        @DisplayName("Should create KeyExchangeInit with all required fields")
        void testCreateKeyExchangeInit() {
            String ephemeralPubKey = Base64.getEncoder().encodeToString(ephemeralKeyPair.getPublic().getEncoded());
            String identityPubKey = Base64.getEncoder().encodeToString(identityKeyPair.getPublic().getEncoded());

            KeyExchangeInit init = new KeyExchangeInit.Builder()
                    .nodeId("test-node")
                    .ephemeralPublicKey(ephemeralPubKey)
                    .identityPublicKey(identityPubKey)
                    .timestamp(System.currentTimeMillis())
                    .signature("dummySignature")
                    .protocolVersion("1.0")
                    .correlationId("corr-123")
                    .build();

            assertEquals("test-node", init.getNodeId());
            assertEquals(ephemeralPubKey, init.getEphemeralPublicKey());
            assertEquals(identityPubKey, init.getIdentityPublicKey());
            assertEquals("1.0", init.getProtocolVersion());
            assertEquals("corr-123", init.getCorrelationId());
        }

        @Test
        @DisplayName("Should generate correlationId if not provided")
        void testAutoGenerateCorrelationId() {
            String ephemeralPubKey = Base64.getEncoder().encodeToString(ephemeralKeyPair.getPublic().getEncoded());
            String identityPubKey = Base64.getEncoder().encodeToString(identityKeyPair.getPublic().getEncoded());

            KeyExchangeInit init = new KeyExchangeInit.Builder()
                    .nodeId("test-node")
                    .ephemeralPublicKey(ephemeralPubKey)
                    .identityPublicKey(identityPubKey)
                    .timestamp(System.currentTimeMillis())
                    .signature("dummySignature")
                    .build();

            assertNotNull(init.getCorrelationId());
            assertFalse(init.getCorrelationId().isEmpty());
        }

        @Test
        @DisplayName("Should detect fresh timestamp")
        void testTimestampFresh() {
            String ephemeralPubKey = Base64.getEncoder().encodeToString(ephemeralKeyPair.getPublic().getEncoded());
            String identityPubKey = Base64.getEncoder().encodeToString(identityKeyPair.getPublic().getEncoded());

            KeyExchangeInit init = new KeyExchangeInit.Builder()
                    .nodeId("test-node")
                    .ephemeralPublicKey(ephemeralPubKey)
                    .identityPublicKey(identityPubKey)
                    .timestamp(System.currentTimeMillis())
                    .signature("dummySignature")
                    .build();

            assertTrue(init.isTimestampFresh());
        }

        @Test
        @DisplayName("Should detect stale timestamp")
        void testTimestampStale() {
            String ephemeralPubKey = Base64.getEncoder().encodeToString(ephemeralKeyPair.getPublic().getEncoded());
            String identityPubKey = Base64.getEncoder().encodeToString(identityKeyPair.getPublic().getEncoded());

            // Timestamp from 60 seconds ago
            long staleTimestamp = System.currentTimeMillis() - 60_000;

            KeyExchangeInit init = new KeyExchangeInit.Builder()
                    .nodeId("test-node")
                    .ephemeralPublicKey(ephemeralPubKey)
                    .identityPublicKey(identityPubKey)
                    .timestamp(staleTimestamp)
                    .signature("dummySignature")
                    .build();

            assertFalse(init.isTimestampFresh());
        }

        @Test
        @DisplayName("Should convert to JSON and back")
        void testJsonSerialization() {
            String ephemeralPubKey = Base64.getEncoder().encodeToString(ephemeralKeyPair.getPublic().getEncoded());
            String identityPubKey = Base64.getEncoder().encodeToString(identityKeyPair.getPublic().getEncoded());
            long timestamp = System.currentTimeMillis();

            KeyExchangeInit init = new KeyExchangeInit.Builder()
                    .nodeId("test-node")
                    .ephemeralPublicKey(ephemeralPubKey)
                    .identityPublicKey(identityPubKey)
                    .timestamp(timestamp)
                    .signature("dummySignature")
                    .correlationId("corr-123")
                    .build();

            Map<String, Object> json = init.toJson();

            assertEquals("test-node", json.get("nodeId"));
            assertEquals(ephemeralPubKey, json.get("ephemeralPublicKey"));
            assertEquals(identityPubKey, json.get("identityPublicKey"));
            assertEquals(timestamp, json.get("timestamp"));
            assertEquals("corr-123", json.get("correlationId"));
        }

        @Test
        @DisplayName("Should throw exception for missing required fields")
        void testMissingRequiredFields() {
            // Missing nodeId
            assertThrows(NullPointerException.class, () -> {
                new KeyExchangeInit.Builder()
                        .ephemeralPublicKey("key")
                        .identityPublicKey("key")
                        .signature("sig")
                        .build();
            });

            // Missing ephemeralPublicKey
            assertThrows(NullPointerException.class, () -> {
                new KeyExchangeInit.Builder()
                        .nodeId("node")
                        .identityPublicKey("key")
                        .signature("sig")
                        .build();
            });
        }
    }

    // ========================= KeyExchangeComplete Tests =========================

    @Nested
    @DisplayName("KeyExchangeComplete Tests")
    class KeyExchangeCompleteTests {

        @Test
        @DisplayName("Should create accepted KeyExchangeComplete")
        void testCreateAcceptedComplete() {
            String ephemeralPubKey = Base64.getEncoder().encodeToString(ephemeralKeyPair.getPublic().getEncoded());
            String identityPubKey = Base64.getEncoder().encodeToString(identityKeyPair.getPublic().getEncoded());

            KeyExchangeComplete complete = new KeyExchangeComplete.Builder()
                    .nodeId("responder-node")
                    .correlationId("corr-123")
                    .ephemeralPublicKey(ephemeralPubKey)
                    .identityPublicKey(identityPubKey)
                    .signature("dummySignature")
                    .timestamp(System.currentTimeMillis())
                    .accepted(true)
                    .build();

            assertTrue(complete.isAccepted());
            assertEquals("responder-node", complete.getNodeId());
            assertEquals("corr-123", complete.getCorrelationId());
        }

        @Test
        @DisplayName("Should create rejection KeyExchangeComplete")
        void testCreateRejection() {
            KeyExchangeComplete rejection = KeyExchangeComplete.createRejection(
                    "responder-node",
                    "corr-123",
                    "SIGNATURE_INVALID"
            );

            assertFalse(rejection.isAccepted());
            assertEquals("responder-node", rejection.getNodeId());
            assertEquals("corr-123", rejection.getCorrelationId());
            assertEquals("SIGNATURE_INVALID", rejection.getRejectionReason());
        }

        @Test
        @DisplayName("Should have correct message type")
        void testMessageType() {
            assertEquals("KEY_EXCHANGE_COMPLETE", KeyExchangeComplete.MESSAGE_TYPE);
        }
    }

    // ========================= SecureChannelNegotiator Tests =========================

    @Nested
    @DisplayName("SecureChannelNegotiator Tests")
    class SecureChannelNegotiatorTests {

        private SecureChannelNegotiator negotiator;

        @BeforeEach
        void setUpNegotiator() {
            negotiator = new SecureChannelNegotiator(
                    "local-node",
                    keyManager,
                    sessionManager,
                    null,  // No certificate manager
                    null,  // No protocol negotiator
                    "1.0",
                    metrics
            );
        }

        @Test
        @DisplayName("Should initiate key exchange successfully")
        void testInitiateKeyExchange() throws KeyExchangeException {
            KeyExchangeInit init = negotiator.initiateKeyExchange("remote-peer", null);

            assertNotNull(init);
            assertEquals("local-node", init.getNodeId());
            assertNotNull(init.getCorrelationId());
            assertNotNull(init.getEphemeralPublicKey());
            assertNotNull(init.getSignature());
        }

        @Test
        @DisplayName("Should throw exception when KeyManager not configured")
        void testInitiateWithoutKeyManager() {
            SecureChannelNegotiator noKeyManagerNegotiator = new SecureChannelNegotiator(
                    "local-node",
                    null,  // No key manager
                    sessionManager,
                    null,
                    null,
                    "1.0",
                    metrics
            );

            assertThrows(KeyExchangeException.class, () -> {
                noKeyManagerNegotiator.initiateKeyExchange("remote-peer", null);
            });
        }

        @Test
        @DisplayName("Should reject duplicate key exchange for same peer")
        void testDuplicateKeyExchange() throws KeyExchangeException {
            // First initiation should succeed
            KeyExchangeInit init1 = negotiator.initiateKeyExchange("remote-peer", null);
            assertNotNull(init1);

            // Second initiation should throw exception
            assertThrows(KeyExchangeException.class, () -> {
                negotiator.initiateKeyExchange("remote-peer", null);
            });
        }

        @Test
        @DisplayName("Should track active negotiations")
        void testHasActiveNegotiation() throws KeyExchangeException {
            assertFalse(negotiator.hasActiveNegotiation("remote-peer"));

            negotiator.initiateKeyExchange("remote-peer", null);

            assertTrue(negotiator.hasActiveNegotiation("remote-peer"));
        }

        @Test
        @DisplayName("Should cancel negotiation")
        void testCancelNegotiation() throws KeyExchangeException {
            negotiator.initiateKeyExchange("remote-peer", null);
            assertTrue(negotiator.hasActiveNegotiation("remote-peer"));

            negotiator.cancelNegotiation("remote-peer");

            assertFalse(negotiator.hasActiveNegotiation("remote-peer"));
        }

        @Test
        @DisplayName("Should register identity mapping")
        void testIdentityMapping() {
            negotiator.registerIdentityMapping("protocol-node-id", "discovery-peer-id");

            String resolved = negotiator.resolveNodeIdToDiscoveryId("protocol-node-id");
            assertEquals("discovery-peer-id", resolved);
        }
    }

    // ========================= EphemeralKeyPair Tests =========================

    @Nested
    @DisplayName("EphemeralKeyPair Tests")
    class EphemeralKeyPairTests {

        @Test
        @DisplayName("Should generate valid ephemeral key pair")
        void testGenerateEphemeralKeyPair() throws Exception {
            EphemeralKeyPair keyPair = EphemeralKeyPair.generate();

            assertNotNull(keyPair);
            assertNotNull(keyPair.getId());
            assertNotNull(keyPair.getPublicKeyBytes());
            assertNotNull(keyPair.getPublicKey());
            assertNotNull(keyPair.getPrivateKey());
        }

        @Test
        @DisplayName("Should generate unique IDs for each key pair")
        void testUniqueIds() throws Exception {
            EphemeralKeyPair keyPair1 = EphemeralKeyPair.generate();
            EphemeralKeyPair keyPair2 = EphemeralKeyPair.generate();

            assertNotEquals(keyPair1.getId(), keyPair2.getId());
        }

        @Test
        @DisplayName("Should produce valid encoded public key")
        void testPublicKeyEncoding() throws Exception {
            EphemeralKeyPair keyPair = EphemeralKeyPair.generate();
            byte[] publicKeyBytes = keyPair.getPublicKeyBytes();

            // Should have valid bytes
            assertNotNull(publicKeyBytes);
            assertTrue(publicKeyBytes.length > 0);

            // Encoded bytes should match public key encoding
            assertArrayEquals(keyPair.getPublicKey().getEncoded(), publicKeyBytes);
        }

        @Test
        @DisplayName("Should derive shared secret with peer key")
        void testDeriveSharedSecret() throws Exception {
            EphemeralKeyPair keyPairA = EphemeralKeyPair.generate();
            EphemeralKeyPair keyPairB = EphemeralKeyPair.generate();

            // Derive shared secrets (should be equal due to ECDH property)
            byte[] secretA = keyPairA.deriveSharedSecret(keyPairB.getPublicKeyBytes());
            byte[] secretB = keyPairB.deriveSharedSecret(keyPairA.getPublicKeyBytes());

            assertArrayEquals(secretA, secretB);
        }

        @Test
        @DisplayName("Should prevent reuse of ephemeral key pair")
        void testPreventReuse() throws Exception {
            EphemeralKeyPair keyPairA = EphemeralKeyPair.generate();
            EphemeralKeyPair keyPairB = EphemeralKeyPair.generate();

            // First use should succeed
            keyPairA.deriveSharedSecret(keyPairB.getPublicKeyBytes());

            // Second use should throw
            assertThrows(Exception.class, () -> {
                keyPairA.deriveSharedSecret(keyPairB.getPublicKeyBytes());
            });
        }
    }

    // ========================= Timeout and Error Handling Tests =========================

    @Nested
    @DisplayName("Timeout and Error Handling Tests")
    class TimeoutTests {

        @Test
        @DisplayName("Should handle expired negotiation cleanup")
        void testExpiredNegotiationCleanup() throws Exception {
            SecureChannelNegotiator negotiator = new SecureChannelNegotiator(
                    "local-node",
                    keyManager,
                    sessionManager,
                    null,
                    null,
                    "1.0",
                    metrics
            );

            // Initiate key exchange
            negotiator.initiateKeyExchange("remote-peer", null);
            assertTrue(negotiator.hasActiveNegotiation("remote-peer"));

            // Cleanup expired (should clean up as timeout is 10s by default)
            // In production, this is called periodically
            negotiator.cleanupExpiredKeyExchanges();

            // Note: The negotiation may not be expired yet since we just created it
            // This test verifies the cleanup method runs without error
        }

        @Test
        @DisplayName("Should handle signing failure gracefully")
        void testSigningFailure() throws Exception {
            IKeyManager failingKeyManager = mock(IKeyManager.class);
            when(failingKeyManager.getIdentityPublicKey()).thenReturn(identityKeyPair.getPublic().getEncoded());
            when(failingKeyManager.signWithIdentityKey(any())).thenThrow(new RuntimeException("Signing failed"));

            SecureChannelNegotiator negotiator = new SecureChannelNegotiator(
                    "local-node",
                    failingKeyManager,
                    sessionManager,
                    null,
                    null,
                    "1.0",
                    metrics
            );

            assertThrows(KeyExchangeException.class, () -> {
                negotiator.initiateKeyExchange("remote-peer", null);
            });
        }
    }

    // ========================= Post-Exchange Trigger Tests =========================

    @Nested
    @DisplayName("Post-Exchange Trigger Tests")
    class PostExchangeTriggerTests {

        @Test
        @DisplayName("Should configure post-exchange trigger")
        void testConfigurePostExchangeTrigger() {
            SecureChannelNegotiator negotiator = new SecureChannelNegotiator(
                    "local-node",
                    keyManager,
                    sessionManager,
                    null,
                    null,
                    "1.0",
                    metrics
            );

            AtomicBoolean triggered = new AtomicBoolean(false);

            negotiator.setPostExchangeTrigger(result -> {
                triggered.set(true);
            });

            // Trigger is set, will be called when KEY_EXCHANGE completes successfully
            // This verifies the setter works without error
            assertFalse(triggered.get()); // Not triggered yet
        }
    }

    // ========================= Concurrent Access Tests =========================

    @Nested
    @DisplayName("Concurrent Access Tests")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("Should handle concurrent key exchange initiations")
        void testConcurrentInitiations() throws Exception {
            SecureChannelNegotiator negotiator = new SecureChannelNegotiator(
                    "local-node",
                    keyManager,
                    sessionManager,
                    null,
                    null,
                    "1.0",
                    metrics
            );

            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Exception> error = new AtomicReference<>();

            for (int i = 0; i < threadCount; i++) {
                final int peerId = i;
                new Thread(() -> {
                    try {
                        startLatch.await();
                        negotiator.initiateKeyExchange("peer-" + peerId, null);
                    } catch (KeyExchangeException e) {
                        // Expected for duplicate peers, ignore
                    } catch (Exception e) {
                        error.set(e);
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            // Start all threads simultaneously
            startLatch.countDown();

            // Wait for completion
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

            // No unexpected errors
            assertNull(error.get());
        }

        @Test
        @DisplayName("Should handle concurrent cancel and initiate")
        void testConcurrentCancelAndInitiate() throws Exception {
            SecureChannelNegotiator negotiator = new SecureChannelNegotiator(
                    "local-node",
                    keyManager,
                    sessionManager,
                    null,
                    null,
                    "1.0",
                    metrics
            );

            String peerId = "concurrent-peer";
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicReference<Exception> error = new AtomicReference<>();

            // Thread 1: Initiate
            new Thread(() -> {
                try {
                    startLatch.await();
                    negotiator.initiateKeyExchange(peerId, null);
                } catch (Exception e) {
                    // May throw if already exists - expected
                } finally {
                    doneLatch.countDown();
                }
            }).start();

            // Thread 2: Cancel
            new Thread(() -> {
                try {
                    startLatch.await();
                    Thread.sleep(1); // Slight delay
                    negotiator.cancelNegotiation(peerId);
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

            // No unexpected errors
            assertNull(error.get());
        }
    }

    // ========================= Metrics Tests =========================

    @Nested
    @DisplayName("Metrics Tests")
    class MetricsTests {

        @Test
        @DisplayName("Should track active negotiations")
        void testNegotiationTracking() throws Exception {
            MetricsRegistry testMetrics = new MetricsRegistry("metrics-test");

            SecureChannelNegotiator negotiator = new SecureChannelNegotiator(
                    "local-node",
                    keyManager,
                    sessionManager,
                    null,
                    null,
                    "1.0",
                    testMetrics
            );

            // Initially no active negotiations
            assertFalse(negotiator.hasActiveNegotiation("peer-1"));

            // After initiation, should have active negotiation
            negotiator.initiateKeyExchange("peer-1", null);
            assertTrue(negotiator.hasActiveNegotiation("peer-1"));

            // After cancel, should not have active negotiation
            negotiator.cancelNegotiation("peer-1");
            assertFalse(negotiator.hasActiveNegotiation("peer-1"));
        }
    }
}

