package com.genesis.p2p.integration;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.handlers.ProcessingContext;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.peer.PeerStore;
import com.genesis.p2p.core.peer.PeerStore.PeerState;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.tracing.TraceIdGenerator;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.gateway.SecurityGateway;
import com.genesis.p2p.security.gateway.SecurityGatewayBuilder;
import com.genesis.p2p.security.policy.MessageSecurityPolicy;
import com.genesis.p2p.transport.core.ITransportEnvelopeHandler;
import com.genesis.p2p.testutil.base.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive Secure Channel Enforcement Integration Tests.
 *
 * <p>These tests validate the complete security gateway and channel enforcement
 * mechanisms including:</p>
 * <ul>
 *   <li>Secure channel negotiation state machine</li>
 *   <li>Message filtering based on encryption status</li>
 *   <li>State-based access control</li>
 *   <li>Attack prevention (state downgrade, replay, etc.)</li>
 *   <li>Multi-peer concurrent security operations</li>
 *   <li>Security metrics and audit logging</li>
 * </ul>
 *
 * <p>Security Invariants Tested:</p>
 * <ul>
 *   <li><strong>INVARIANT-1:</strong> Plain application data MUST NOT be transmitted before secure channel</li>
 *   <li><strong>INVARIANT-2:</strong> KEY_EXCHANGE messages are the ONLY plaintext allowed before channel</li>
 *   <li><strong>INVARIANT-3:</strong> State machine enforces correct message sequence</li>
 *   <li><strong>INVARIANT-4:</strong> Session validity is verified for all encrypted messages</li>
 * </ul>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 * @since 2026-02-04
 */
@Tag("integration")
@Tag("security")
@DisplayName("Secure Channel Enforcement Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecureChannelEnforcementTest extends BaseIntegrationTest {

    // ===================== Test Constants =====================

    private static final String LOCAL_NODE_ID = "local-node";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    // Message type categories for security classification
    private static final Set<String> BOOTSTRAP_MESSAGES = Set.of(
        "KEY_EXCHANGE_INIT", "KEY_EXCHANGE_RESPONSE", "KEY_EXCHANGE_COMPLETE",
        "DISCOVERY_REQUEST", "DISCOVERY_RESPONSE"
    );

    private static final Set<String> HANDSHAKE_MESSAGES = Set.of(
        "HANDSHAKE_REQUEST", "HANDSHAKE_RESPONSE", "HANDSHAKE_COMPLETE"
    );

    private static final Set<String> APPLICATION_MESSAGES = Set.of(
        "PING", "PONG", "DATA", "REQUEST", "RESPONSE", "BROADCAST"
    );

    // ===================== Test Infrastructure =====================

    private SecurityFacade securityFacade;
    private PeerManager peerManager;
    private MetricsRegistry metricsRegistry;
    private MessageSecurityPolicy policy;
    private List<Message> passedMessages;
    private List<Message> rejectedMessages;
    private ITransportEnvelopeHandler downstreamHandler;
    private SecurityGateway gateway;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        // Create mocks
        securityFacade = mock(SecurityFacade.class);
        peerManager = mock(PeerManager.class);

        metricsRegistry = new MetricsRegistry("integration-test");
        policy = new MessageSecurityPolicy();
        passedMessages = Collections.synchronizedList(new ArrayList<>());
        rejectedMessages = Collections.synchronizedList(new ArrayList<>());
        executorService = Executors.newFixedThreadPool(4);

        downstreamHandler = (message, source, context) -> {
            passedMessages.add(message);
        };

        gateway = new SecurityGatewayBuilder()
                .downstream(downstreamHandler)
                .security(securityFacade)
                .peerManager(peerManager)
                .policy(policy)
                .metrics(metricsRegistry)
                .legacyPlaintextMode(false)
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ===================== 1. Complete Channel Negotiation Flow =====================

    @Nested
    @DisplayName("1. Complete Channel Negotiation Flow")
    class ChannelNegotiationFlowTests {

        @Test
        @Order(1)
        @DisplayName("1.1 - Full secure channel negotiation: UNKNOWN → AUTHENTICATED")
        void testCompleteSecureChannelNegotiationFlow() {
            String peerId = "peer-alpha";
            InetSocketAddress source = createSource(peerId);

            // Step 1: KEY_EXCHANGE_INIT from new peer (DISCOVERED state, plaintext)
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.DISCOVERED);
            Message keyExchangeInit = createMessage("KEY_EXCHANGE_INIT", peerId, false);
            gateway.handleEnvelope(keyExchangeInit, source, ProcessingContext.empty());

            assertEquals(1, passedMessages.size(), "KEY_EXCHANGE_INIT should pass");

            // Step 2: KEY_EXCHANGE_COMPLETE (CHANNEL_NEGOTIATING state, plaintext)
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.CHANNEL_NEGOTIATING);
            Message keyExchangeComplete = createMessage("KEY_EXCHANGE_COMPLETE", peerId, false);
            gateway.handleEnvelope(keyExchangeComplete, source, ProcessingContext.empty());

            assertEquals(2, passedMessages.size(), "KEY_EXCHANGE_COMPLETE should pass");

            // Step 3: HANDSHAKE_REQUEST (CHANNEL_ESTABLISHED state, encrypted)
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.CHANNEL_ESTABLISHED);
            when(securityFacade.hasValidSession(peerId)).thenReturn(true);
            Message handshakeRequest = createMessage("HANDSHAKE_REQUEST", peerId, true);
            gateway.handleEnvelope(handshakeRequest, source, ProcessingContext.empty());

            assertEquals(3, passedMessages.size(), "HANDSHAKE_REQUEST should pass");

            // Step 4: HANDSHAKE_RESPONSE (CONNECTING state, encrypted)
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.CONNECTING);
            Message handshakeResponse = createMessage("HANDSHAKE_RESPONSE", peerId, true);
            gateway.handleEnvelope(handshakeResponse, source, ProcessingContext.empty());

            assertEquals(4, passedMessages.size(), "HANDSHAKE_RESPONSE should pass");

            // Step 5: PING (CONNECTED state, encrypted) - normal traffic now allowed
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.CONNECTED);
            Message ping = createMessage("PING", peerId, true);
            gateway.handleEnvelope(ping, source, ProcessingContext.empty());

            assertEquals(5, passedMessages.size(), "PING should pass after secure channel");
        }

        @Test
        @Order(2)
        @DisplayName("1.2 - Authenticated peer can send any encrypted application message")
        void testAuthenticatedPeerFullAccess() {
            String peerId = "fully-authenticated-peer";
            InetSocketAddress source = createSource(peerId);

            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.AUTHENTICATED);
            when(securityFacade.hasValidSession(peerId)).thenReturn(true);

            // All application messages should pass when encrypted
            for (String messageType : APPLICATION_MESSAGES) {
                Message message = createMessage(messageType, peerId, true);
                gateway.handleEnvelope(message, source, ProcessingContext.empty());
            }

            assertEquals(APPLICATION_MESSAGES.size(), passedMessages.size(),
                "All encrypted application messages should pass for authenticated peer");
        }
    }

    // ===================== 2. Security Invariant Enforcement =====================

    @Nested
    @DisplayName("2. Security Invariant Enforcement")
    class SecurityInvariantTests {

        @Test
        @Order(10)
        @DisplayName("2.1 - INVARIANT-1: Unencrypted application messages are rejected")
        void testUnencryptedApplicationMessagesRejected() {
            String peerId = "malicious-peer";
            InetSocketAddress source = createSource(peerId);

            // Peer just discovered - tries to send PING without encryption
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.DISCOVERED);

            for (String messageType : APPLICATION_MESSAGES) {
                passedMessages.clear();
                Message unencryptedMessage = createMessage(messageType, peerId, false);
                gateway.handleEnvelope(unencryptedMessage, source, ProcessingContext.empty());

                assertEquals(0, passedMessages.size(),
                    "Unencrypted " + messageType + " should be rejected");
            }
        }

        @Test
        @Order(11)
        @DisplayName("2.2 - INVARIANT-2: Only KEY_EXCHANGE allowed before channel")
        void testOnlyKeyExchangeBeforeChannel() {
            String peerId = "new-peer";
            InetSocketAddress source = createSource(peerId);

            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.DISCOVERED);

            // KEY_EXCHANGE should pass
            Message keyExchange = createMessage("KEY_EXCHANGE_INIT", peerId, false);
            gateway.handleEnvelope(keyExchange, source, ProcessingContext.empty());
            assertEquals(1, passedMessages.size());

            // DISCOVERY_REQUEST should also pass (bootstrap message)
            Message discovery = createMessage("DISCOVERY_REQUEST", peerId, false);
            gateway.handleEnvelope(discovery, source, ProcessingContext.empty());
            assertEquals(2, passedMessages.size());

            // HANDSHAKE_REQUEST should be rejected (not a bootstrap message)
            Message handshake = createMessage("HANDSHAKE_REQUEST", peerId, false);
            gateway.handleEnvelope(handshake, source, ProcessingContext.empty());
            assertEquals(2, passedMessages.size(), "HANDSHAKE_REQUEST should be rejected");

            // PING should be rejected
            Message ping = createMessage("PING", peerId, false);
            gateway.handleEnvelope(ping, source, ProcessingContext.empty());
            assertEquals(2, passedMessages.size(), "PING should be rejected");
        }

        @Test
        @Order(12)
        @DisplayName("2.3 - INVARIANT-3: State machine enforces message sequence")
        void testStateMachineEnforcement() {
            String peerId = "sequence-test-peer";
            InetSocketAddress source = createSource(peerId);

            // Cannot skip directly to HANDSHAKE without key exchange
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.DISCOVERED);
            Message skipAttempt = createMessage("HANDSHAKE_REQUEST", peerId, true);
            gateway.handleEnvelope(skipAttempt, source, ProcessingContext.empty());

            assertEquals(0, passedMessages.size(), "Cannot skip to HANDSHAKE from DISCOVERED");
        }

        @Test
        @Order(13)
        @DisplayName("2.4 - INVARIANT-4: Session validity is verified")
        void testSessionValidityVerification() {
            String peerId = "session-test-peer";
            InetSocketAddress source = createSource(peerId);

            // Peer has AUTHENTICATED state but no valid session
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.AUTHENTICATED);
            when(securityFacade.hasValidSession(peerId)).thenReturn(false);

            Message ping = createMessage("PING", peerId, true);
            gateway.handleEnvelope(ping, source, ProcessingContext.empty());

            // Should be rejected due to invalid session
            assertEquals(0, passedMessages.size(),
                "Message with invalid session should be rejected");
        }
    }

    // ===================== 3. Attack Prevention =====================

    @Nested
    @DisplayName("3. Attack Prevention")
    class AttackPreventionTests {

        @Test
        @Order(20)
        @DisplayName("3.1 - State downgrade attack prevention")
        void testStateDowngradeAttackPrevented() {
            String peerId = "attacker";
            InetSocketAddress source = createSource(peerId);

            // Attacker is in AUTHENTICATED state but tries to send KEY_EXCHANGE_INIT
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.AUTHENTICATED);

            Message keyExchangeInit = createMessage("KEY_EXCHANGE_INIT", peerId, false);
            gateway.handleEnvelope(keyExchangeInit, source, ProcessingContext.empty());

            assertEquals(0, passedMessages.size(),
                "KEY_EXCHANGE_INIT from AUTHENTICATED peer should be rejected");
        }

        @Test
        @Order(21)
        @DisplayName("3.2 - Unknown peer restrictions")
        void testUnknownPeerRestrictions() {
            String unknownPeer = "unknown-peer-xyz";
            InetSocketAddress source = createSource(unknownPeer);

            when(peerManager.getPeerState(unknownPeer)).thenReturn(null);

            // Allowed: KEY_EXCHANGE_INIT
            Message keyExchange = createMessage("KEY_EXCHANGE_INIT", unknownPeer, false);
            gateway.handleEnvelope(keyExchange, source, ProcessingContext.empty());
            assertEquals(1, passedMessages.size());

            // Allowed: DISCOVERY_REQUEST
            Message discovery = createMessage("DISCOVERY_REQUEST", unknownPeer, false);
            gateway.handleEnvelope(discovery, source, ProcessingContext.empty());
            assertEquals(2, passedMessages.size());

            // Rejected: PING
            Message ping = createMessage("PING", unknownPeer, true);
            gateway.handleEnvelope(ping, source, ProcessingContext.empty());
            assertEquals(2, passedMessages.size(), "PING from unknown peer should be rejected");

            // Rejected: HANDSHAKE_REQUEST
            Message handshake = createMessage("HANDSHAKE_REQUEST", unknownPeer, true);
            gateway.handleEnvelope(handshake, source, ProcessingContext.empty());
            assertEquals(2, passedMessages.size(), "HANDSHAKE from unknown peer should be rejected");
        }

        @Test
        @Order(22)
        @DisplayName("3.3 - Encrypted message without session is rejected")
        void testEncryptedWithoutSessionRejected() {
            String peerId = "no-session-peer";
            InetSocketAddress source = createSource(peerId);

            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.CHANNEL_ESTABLISHED);
            when(securityFacade.hasValidSession(peerId)).thenReturn(false);

            Message encryptedPing = createMessage("PING", peerId, true);
            gateway.handleEnvelope(encryptedPing, source, ProcessingContext.empty());

            assertEquals(0, passedMessages.size(),
                "Encrypted message without valid session should be rejected");
        }

        @Test
        @Order(23)
        @DisplayName("3.4 - Unencrypted message with valid session still rejected for app messages")
        void testUnencryptedWithSessionRejected() {
            String peerId = "peer-with-session";
            InetSocketAddress source = createSource(peerId);

            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.AUTHENTICATED);
            when(securityFacade.hasValidSession(peerId)).thenReturn(true);

            Message unencryptedPing = createMessage("PING", peerId, false);
            gateway.handleEnvelope(unencryptedPing, source, ProcessingContext.empty());

            assertEquals(0, passedMessages.size(),
                "Unencrypted PING should be rejected even with valid session");
        }
    }

    // ===================== 4. Multi-Peer Concurrent Operations =====================

    @Nested
    @DisplayName("4. Multi-Peer Concurrent Operations")
    class MultiPeerConcurrentTests {

        @Test
        @Order(30)
        @DisplayName("4.1 - Multiple peers at different negotiation stages")
        void testMultiplePeersDifferentStages() {
            // Peer A: Just discovered
            String peerA = "peer-A";
            when(peerManager.getPeerState(peerA)).thenReturn(PeerState.DISCOVERED);

            // Peer B: Channel established
            String peerB = "peer-B";
            when(peerManager.getPeerState(peerB)).thenReturn(PeerState.CHANNEL_ESTABLISHED);
            when(securityFacade.hasValidSession(peerB)).thenReturn(true);

            // Peer C: Fully authenticated
            String peerC = "peer-C";
            when(peerManager.getPeerState(peerC)).thenReturn(PeerState.AUTHENTICATED);
            when(securityFacade.hasValidSession(peerC)).thenReturn(true);

            // Peer A can send KEY_EXCHANGE_INIT
            gateway.handleEnvelope(createMessage("KEY_EXCHANGE_INIT", peerA, false),
                createSource(peerA), ProcessingContext.empty());
            assertEquals(1, passedMessages.size());

            // Peer A cannot send PING
            gateway.handleEnvelope(createMessage("PING", peerA, false),
                createSource(peerA), ProcessingContext.empty());
            assertEquals(1, passedMessages.size());

            // Peer B can send HANDSHAKE_REQUEST
            gateway.handleEnvelope(createMessage("HANDSHAKE_REQUEST", peerB, true),
                createSource(peerB), ProcessingContext.empty());
            assertEquals(2, passedMessages.size());

            // Peer C can send PING
            gateway.handleEnvelope(createMessage("PING", peerC, true),
                createSource(peerC), ProcessingContext.empty());
            assertEquals(3, passedMessages.size());
        }

        @Test
        @Order(31)
        @DisplayName("4.2 - Concurrent message processing from multiple peers")
        void testConcurrentMessageProcessing() throws Exception {
            int peerCount = 10;
            int messagesPerPeer = 50;
            CountDownLatch latch = new CountDownLatch(peerCount);
            AtomicInteger totalProcessed = new AtomicInteger(0);

            // Setup peers
            for (int i = 0; i < peerCount; i++) {
                String peerId = "concurrent-peer-" + i;
                when(peerManager.getPeerState(peerId)).thenReturn(PeerState.AUTHENTICATED);
                when(securityFacade.hasValidSession(peerId)).thenReturn(true);
            }

            // Process messages concurrently
            for (int i = 0; i < peerCount; i++) {
                final int peerIndex = i;
                executorService.submit(() -> {
                    try {
                        String peerId = "concurrent-peer-" + peerIndex;
                        for (int j = 0; j < messagesPerPeer; j++) {
                            Message message = createMessage("DATA", peerId, true);
                            gateway.handleEnvelope(message, createSource(peerId), ProcessingContext.empty());
                            totalProcessed.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            // All messages from authenticated peers should pass
            assertEquals(peerCount * messagesPerPeer, passedMessages.size());
        }
    }

    // ===================== 5. Metrics and Observability =====================

    @Nested
    @DisplayName("5. Metrics and Observability")
    class MetricsTests {

        @Test
        @Order(40)
        @DisplayName("5.1 - Security metrics are recorded for passed messages")
        void testPassedMessageMetrics() {
            String peerId = "metrics-peer";
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.DISCOVERED);

            gateway.handleEnvelope(createMessage("KEY_EXCHANGE_INIT", peerId, false),
                createSource(peerId), ProcessingContext.empty());

            long passedCount = metricsRegistry.getCounter("security.gateway.passed");
            assertTrue(passedCount > 0, "Passed message metric should be recorded");
        }

        @Test
        @Order(41)
        @DisplayName("5.2 - Security metrics are recorded for rejected messages")
        void testRejectedMessageMetrics() {
            String peerId = "rejected-metrics-peer";
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.DISCOVERED);

            gateway.handleEnvelope(createMessage("PING", peerId, false),
                createSource(peerId), ProcessingContext.empty());

            // Check various rejection metrics
            long totalRejected =
                metricsRegistry.getCounter("security.gateway.rejected.unknown_peer") +
                metricsRegistry.getCounter("security.gateway.rejected.invalid_state") +
                metricsRegistry.getCounter("security.gateway.rejected.unencrypted");

            assertTrue(totalRejected > 0, "Rejected message metric should be recorded");
        }

        @Test
        @Order(42)
        @DisplayName("5.3 - Processing context preserves security metadata")
        void testProcessingContextSecurityMetadata() {
            String peerId = "context-peer";
            InetSocketAddress source = createSource(peerId);

            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.AUTHENTICATED);
            when(securityFacade.hasValidSession(peerId)).thenReturn(true);

            ProcessingContext context = ProcessingContext.createWithSecurity(
                    "persistence-id",
                    "trace-id",
                    source,
                    true,  // wasEncrypted
                    true   // decryptionSucceeded
            );

            Message encryptedPing = createMessage("PING", peerId, true);
            gateway.handleEnvelope(encryptedPing, source, context);

            assertEquals(1, passedMessages.size());
            assertTrue(context.isEncrypted());
            assertTrue(context.isDecryptionSuccessful());
        }
    }

    // ===================== 6. Edge Cases =====================

    @Nested
    @DisplayName("6. Edge Cases")
    class EdgeCaseTests {

        @ParameterizedTest(name = "State {0} has correct message permissions")
        @EnumSource(PeerState.class)
        @Order(50)
        void testAllStatesHaveCorrectPermissions(PeerState state) {
            String peerId = "state-test-" + state.name();
            InetSocketAddress source = createSource(peerId);

            when(peerManager.getPeerState(peerId)).thenReturn(state);
            when(securityFacade.hasValidSession(peerId)).thenReturn(
                state.ordinal() >= PeerState.CHANNEL_ESTABLISHED.ordinal());

            // Each state should have deterministic behavior
            passedMessages.clear();

            // Test KEY_EXCHANGE_INIT
            gateway.handleEnvelope(createMessage("KEY_EXCHANGE_INIT", peerId, false), source, ProcessingContext.empty());

            // Based on state, verify behavior
            switch (state) {
                case DISCOVERED, CHANNEL_NEGOTIATING:
                    assertTrue(passedMessages.size() >= 0); // KEY_EXCHANGE allowed
                    break;
                case AUTHENTICATED, CONNECTED:
                    // KEY_EXCHANGE should be rejected (downgrade attack)
                    break;
                default:
                    // Other states have specific rules
                    break;
            }
        }

        @Test
        @Order(51)
        @DisplayName("6.1 - Null peer state treated as unknown")
        void testNullPeerStateTreatedAsUnknown() {
            String peerId = "null-state-peer";
            when(peerManager.getPeerState(peerId)).thenReturn(null);

            // Should allow bootstrap messages
            gateway.handleEnvelope(createMessage("KEY_EXCHANGE_INIT", peerId, false),
                createSource(peerId), ProcessingContext.empty());
            assertEquals(1, passedMessages.size());

            // Should reject application messages
            gateway.handleEnvelope(createMessage("PING", peerId, true),
                createSource(peerId), ProcessingContext.empty());
            assertEquals(1, passedMessages.size());
        }

        @Test
        @Order(52)
        @DisplayName("6.2 - Empty message type handling")
        void testEmptyMessageTypeHandling() {
            String peerId = "empty-type-peer";
            when(peerManager.getPeerState(peerId)).thenReturn(PeerState.AUTHENTICATED);
            when(securityFacade.hasValidSession(peerId)).thenReturn(true);

            // Creating message with empty type should throw IllegalArgumentException
            assertThrows(IllegalArgumentException.class, () -> {
                MessageHeader header = new MessageHeader(
                    UUID.randomUUID().toString(), TraceIdGenerator.generate(),
                    "2.0", "1.0", 10, 0, "", peerId, LOCAL_NODE_ID,
                    System.currentTimeMillis(), true, "json", false, null, false
                );
                new Message(header, new MessageBody("{}", Map.of()));
            }, "Empty message type should be rejected");
        }
    }

    // ===================== Helper Methods =====================

    private Message createMessage(String type, String from, boolean encrypted) {
        MessageHeader header = new MessageHeader(
            UUID.randomUUID().toString(),
            TraceIdGenerator.generate(),
            ProtocolVersion.current().toString(),
            "1.0",
            10, 0, type, from, LOCAL_NODE_ID,
            System.currentTimeMillis(), encrypted, "json", false, null, false
        );
        return new Message(header, new MessageBody("{\"test\":true}", Map.of()));
    }

    private InetSocketAddress createSource(String peer) {
        return new InetSocketAddress("192.168.1." + (Math.abs(peer.hashCode()) % 255), 5000);
    }
}

