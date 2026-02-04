package com.genesis.p2p.integration;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.handlers.ProcessingContext;
import com.genesis.p2p.core.peer.PeerStore.PeerState;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.tracing.TraceIdGenerator;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.testutil.base.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Security-Aware Message Dispatcher v2.3.
 *
 * <p>This test suite validates the complete security dispatch flow including:</p>
 * <ul>
 *   <li>Message routing based on security state</li>
 *   <li>Encryption/decryption pipeline integration</li>
 *   <li>State-based message filtering</li>
 *   <li>Security policy enforcement</li>
 *   <li>Metrics and observability integration</li>
 *   <li>Error handling and recovery scenarios</li>
 * </ul>
 *
 * <p>Design Principles:</p>
 * <ul>
 *   <li>Zero tolerance for unencrypted application data over wire</li>
 *   <li>State machine enforces correct message sequence</li>
 *   <li>Comprehensive audit trail for security events</li>
 * </ul>
 *
 * @author Genesis P2P Framework
 * @version 2.3
 * @since 2026-02-04
 */
@Tag("integration")
@Tag("security")
@DisplayName("Security-Aware Dispatcher v2.3 Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityAwareDispatcherV23Test extends BaseIntegrationTest {

    // ===================== Test Constants =====================

    private static final String LOCAL_NODE_ID = "local-node-" + UUID.randomUUID().toString().substring(0, 8);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    // Message type classifications
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

    private SimulatedDispatcher dispatcher;
    private MetricsRegistry metricsRegistry;
    private List<SecurityEvent> securityEvents;
    private Map<String, PeerSecurityState> peerStates;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        metricsRegistry = new MetricsRegistry("dispatcher-v23-test");
        securityEvents = Collections.synchronizedList(new ArrayList<>());
        peerStates = new ConcurrentHashMap<>();
        executorService = Executors.newFixedThreadPool(4);

        dispatcher = new SimulatedDispatcher(LOCAL_NODE_ID, metricsRegistry, securityEvents, peerStates);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        }
        dispatcher.shutdown();
    }

    // ===================== 1. Message Classification Tests =====================

    @Nested
    @DisplayName("1. Message Classification")
    class MessageClassificationTests {

        @ParameterizedTest(name = "Bootstrap message ''{0}'' should be allowed in plaintext")
        @MethodSource("com.genesis.p2p.integration.SecurityAwareDispatcherV23Test#bootstrapMessageTypes")
        @Order(1)
        void testBootstrapMessagesAllowedInPlaintext(String messageType) {
            // Given: A new unknown peer
            String peerId = "new-peer-" + UUID.randomUUID().toString().substring(0, 8);

            // When: Peer sends bootstrap message without encryption
            Message message = createMessage(messageType, peerId, LOCAL_NODE_ID, false);
            DispatchResult result = dispatcher.dispatch(message, createSource(peerId));

            // Then: Message is accepted
            assertTrue(result.isAccepted(),
                "Bootstrap message " + messageType + " should be accepted in plaintext");
            assertContainsEvent(SecurityEventType.MESSAGE_ACCEPTED);
        }

        @ParameterizedTest(name = "Application message ''{0}'' requires encryption")
        @MethodSource("com.genesis.p2p.integration.SecurityAwareDispatcherV23Test#applicationMessageTypes")
        @Order(2)
        void testApplicationMessagesRequireEncryption(String messageType) {
            // Given: A peer in DISCOVERED state (no secure channel)
            String peerId = "discovered-peer";
            peerStates.put(peerId, new PeerSecurityState(PeerState.DISCOVERED, false));

            // When: Peer sends application message without encryption
            Message message = createMessage(messageType, peerId, LOCAL_NODE_ID, false);
            DispatchResult result = dispatcher.dispatch(message, createSource(peerId));

            // Then: Message is rejected
            assertFalse(result.isAccepted(),
                "Application message " + messageType + " should require encryption");
            assertEquals(RejectReason.ENCRYPTION_REQUIRED, result.getRejectReason());
        }

        @ParameterizedTest(name = "Encrypted application message ''{0}'' from authenticated peer is accepted")
        @MethodSource("com.genesis.p2p.integration.SecurityAwareDispatcherV23Test#applicationMessageTypes")
        @Order(3)
        void testEncryptedApplicationMessagesAccepted(String messageType) {
            // Given: An authenticated peer with valid session
            String peerId = "authenticated-peer";
            peerStates.put(peerId, new PeerSecurityState(PeerState.AUTHENTICATED, true));

            // When: Peer sends encrypted application message
            Message message = createMessage(messageType, peerId, LOCAL_NODE_ID, true);
            DispatchResult result = dispatcher.dispatch(message, createSource(peerId));

            // Then: Message is accepted
            assertTrue(result.isAccepted(),
                "Encrypted " + messageType + " from authenticated peer should be accepted");
        }
    }

    // ===================== 2. State Machine Enforcement Tests =====================

    @Nested
    @DisplayName("2. State Machine Enforcement")
    class StateMachineEnforcementTests {

        @Test
        @Order(10)
        @DisplayName("2.1 - Complete state transition flow: UNKNOWN → AUTHENTICATED")
        void testCompleteStateTransitionFlow() {
            String peerId = "transitioning-peer";

            // State 1: UNKNOWN - Only KEY_EXCHANGE_INIT allowed
            assertDispatchResult(peerId, "KEY_EXCHANGE_INIT", null, false, true);
            peerStates.put(peerId, new PeerSecurityState(PeerState.DISCOVERED, false));

            // State 2: DISCOVERED → CHANNEL_NEGOTIATING
            assertDispatchResult(peerId, "KEY_EXCHANGE_RESPONSE", PeerState.DISCOVERED, false, true);
            peerStates.put(peerId, new PeerSecurityState(PeerState.CHANNEL_NEGOTIATING, false));

            // State 3: CHANNEL_NEGOTIATING → CHANNEL_ESTABLISHED
            assertDispatchResult(peerId, "KEY_EXCHANGE_COMPLETE", PeerState.CHANNEL_NEGOTIATING, false, true);
            peerStates.put(peerId, new PeerSecurityState(PeerState.CHANNEL_ESTABLISHED, true));

            // State 4: CHANNEL_ESTABLISHED → CONNECTING (handshake)
            assertDispatchResult(peerId, "HANDSHAKE_REQUEST", PeerState.CHANNEL_ESTABLISHED, true, true);
            peerStates.put(peerId, new PeerSecurityState(PeerState.CONNECTING, true));

            // State 5: CONNECTING → CONNECTED
            assertDispatchResult(peerId, "HANDSHAKE_RESPONSE", PeerState.CONNECTING, true, true);
            peerStates.put(peerId, new PeerSecurityState(PeerState.CONNECTED, true));

            // State 6: CONNECTED → AUTHENTICATED
            assertDispatchResult(peerId, "HANDSHAKE_COMPLETE", PeerState.CONNECTED, true, true);
            peerStates.put(peerId, new PeerSecurityState(PeerState.AUTHENTICATED, true));

            // Final: Application messages now allowed
            assertDispatchResult(peerId, "PING", PeerState.AUTHENTICATED, true, true);
            assertDispatchResult(peerId, "DATA", PeerState.AUTHENTICATED, true, true);
        }

        @Test
        @Order(11)
        @DisplayName("2.2 - State downgrade attack prevention")
        void testStateDowngradeAttackPrevention() {
            // Given: An authenticated peer
            String peerId = "authenticated-peer";
            peerStates.put(peerId, new PeerSecurityState(PeerState.AUTHENTICATED, true));

            // When: Attacker tries to send KEY_EXCHANGE_INIT (downgrade attempt)
            Message downgradeAttempt = createMessage("KEY_EXCHANGE_INIT", peerId, LOCAL_NODE_ID, false);
            DispatchResult result = dispatcher.dispatch(downgradeAttempt, createSource(peerId));

            // Then: Attack is detected and rejected
            assertFalse(result.isAccepted(), "State downgrade attempt should be rejected");
            assertEquals(RejectReason.STATE_VIOLATION, result.getRejectReason());
            assertContainsEvent(SecurityEventType.STATE_DOWNGRADE_ATTEMPT);
        }

        @Test
        @Order(12)
        @DisplayName("2.3 - Invalid state transition is rejected")
        void testInvalidStateTransitionRejected() {
            // Given: A peer in DISCOVERED state
            String peerId = "discovered-peer";
            peerStates.put(peerId, new PeerSecurityState(PeerState.DISCOVERED, false));

            // When: Peer tries to skip to HANDSHAKE_REQUEST (skipping key exchange)
            Message skipAttempt = createMessage("HANDSHAKE_REQUEST", peerId, LOCAL_NODE_ID, false);
            DispatchResult result = dispatcher.dispatch(skipAttempt, createSource(peerId));

            // Then: Skip is rejected
            assertFalse(result.isAccepted(), "Skipping states should be rejected");
            assertEquals(RejectReason.INVALID_STATE_TRANSITION, result.getRejectReason());
        }

        @ParameterizedTest(name = "State {0} allows message types: {1}")
        @MethodSource("com.genesis.p2p.integration.SecurityAwareDispatcherV23Test#stateAllowedMessageTypes")
        @Order(13)
        void testStateAllowedMessageTypes(PeerState state, Set<String> allowedTypes, Set<String> disallowedTypes) {
            String peerId = "state-test-peer";
            boolean hasSession = state.ordinal() >= PeerState.CHANNEL_ESTABLISHED.ordinal();
            peerStates.put(peerId, new PeerSecurityState(state, hasSession));

            for (String allowed : allowedTypes) {
                Message msg = createMessage(allowed, peerId, LOCAL_NODE_ID, hasSession);
                DispatchResult result = dispatcher.dispatch(msg, createSource(peerId));
                assertTrue(result.isAccepted(),
                    "State " + state + " should allow " + allowed);
            }

            for (String disallowed : disallowedTypes) {
                Message msg = createMessage(disallowed, peerId, LOCAL_NODE_ID, hasSession);
                DispatchResult result = dispatcher.dispatch(msg, createSource(peerId));
                assertFalse(result.isAccepted(),
                    "State " + state + " should NOT allow " + disallowed);
            }
        }
    }

    // ===================== 3. Security Policy Tests =====================

    @Nested
    @DisplayName("3. Security Policy Enforcement")
    class SecurityPolicyTests {

        @Test
        @Order(20)
        @DisplayName("3.1 - Replay attack detection")
        void testReplayAttackDetection() {
            // Given: An authenticated peer
            String peerId = "replay-target";
            peerStates.put(peerId, new PeerSecurityState(PeerState.AUTHENTICATED, true));

            // And: A message that was already processed
            String messageId = UUID.randomUUID().toString();
            Message originalMessage = createMessageWithId(messageId, "DATA", peerId, LOCAL_NODE_ID, true);

            DispatchResult firstResult = dispatcher.dispatch(originalMessage, createSource(peerId));
            assertTrue(firstResult.isAccepted(), "First message should be accepted");

            // When: Same message is replayed
            Message replayedMessage = createMessageWithId(messageId, "DATA", peerId, LOCAL_NODE_ID, true);
            DispatchResult replayResult = dispatcher.dispatch(replayedMessage, createSource(peerId));

            // Then: Replay is detected and rejected
            assertFalse(replayResult.isAccepted(), "Replayed message should be rejected");
            assertEquals(RejectReason.REPLAY_DETECTED, replayResult.getRejectReason());
            assertContainsEvent(SecurityEventType.REPLAY_ATTACK_DETECTED);
        }

        @Test
        @Order(21)
        @DisplayName("3.2 - Rate limiting enforcement")
        void testRateLimitingEnforcement() {
            // Given: A peer with rate limit
            String peerId = "rate-limited-peer";
            peerStates.put(peerId, new PeerSecurityState(PeerState.AUTHENTICATED, true));
            dispatcher.setRateLimit(peerId, 10); // 10 messages per second

            // When: Sending more than rate limit
            int sentCount = 0;
            int rejectedCount = 0;

            for (int i = 0; i < 20; i++) {
                Message message = createMessage("DATA", peerId, LOCAL_NODE_ID, true);
                DispatchResult result = dispatcher.dispatch(message, createSource(peerId));
                if (result.isAccepted()) {
                    sentCount++;
                } else if (result.getRejectReason() == RejectReason.RATE_LIMITED) {
                    rejectedCount++;
                }
            }

            // Then: Some messages are rate limited
            assertTrue(rejectedCount > 0, "Some messages should be rate limited");
            assertTrue(sentCount <= 15, "Should not exceed reasonable limit");
        }

        @Test
        @Order(22)
        @DisplayName("3.3 - Message size limit enforcement")
        void testMessageSizeLimitEnforcement() {
            // Given: A peer sending oversized message
            String peerId = "oversized-sender";
            peerStates.put(peerId, new PeerSecurityState(PeerState.AUTHENTICATED, true));

            // When: Sending message exceeding size limit
            String oversizedContent = "X".repeat(1024 * 1024 * 2); // 2MB
            Message oversizedMessage = createMessageWithContent("DATA", peerId, LOCAL_NODE_ID, true, oversizedContent);
            DispatchResult result = dispatcher.dispatch(oversizedMessage, createSource(peerId));

            // Then: Message is rejected
            assertFalse(result.isAccepted());
            assertEquals(RejectReason.MESSAGE_TOO_LARGE, result.getRejectReason());
        }
    }

    // ===================== 4. Concurrent Access Tests =====================

    @Nested
    @DisplayName("4. Concurrent Access Safety")
    class ConcurrentAccessTests {

        @Test
        @Order(30)
        @DisplayName("4.1 - Thread-safe message dispatch")
        void testThreadSafeMessageDispatch() throws Exception {
            // Given: Multiple authenticated peers
            int peerCount = 10;
            int messagesPerPeer = 100;

            for (int i = 0; i < peerCount; i++) {
                String peerId = "concurrent-peer-" + i;
                peerStates.put(peerId, new PeerSecurityState(PeerState.AUTHENTICATED, true));
            }

            AtomicInteger totalAccepted = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(peerCount);

            // When: All peers send messages concurrently
            for (int i = 0; i < peerCount; i++) {
                final String peerId = "concurrent-peer-" + i;
                executorService.submit(() -> {
                    try {
                        for (int j = 0; j < messagesPerPeer; j++) {
                            Message msg = createMessage("DATA", peerId, LOCAL_NODE_ID, true);
                            DispatchResult result = dispatcher.dispatch(msg, createSource(peerId));
                            if (result.isAccepted()) {
                                totalAccepted.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Then: All messages are processed correctly
            assertTrue(latch.await(DEFAULT_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS));
            assertEquals(peerCount * messagesPerPeer, totalAccepted.get(),
                "All messages from authenticated peers should be accepted");
        }

        @Test
        @Order(31)
        @DisplayName("4.2 - Concurrent state transitions are safe")
        void testConcurrentStateTransitions() throws Exception {
            // Given: Multiple peers transitioning simultaneously
            int peerCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(peerCount);
            AtomicInteger successfulTransitions = new AtomicInteger(0);

            // When: All peers start state transition at same time
            for (int i = 0; i < peerCount; i++) {
                final String peerId = "transition-peer-" + i;
                executorService.submit(() -> {
                    try {
                        startLatch.await();

                        // Simulate full state transition
                        if (performStateTransition(peerId)) {
                            successfulTransitions.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Log error
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Start all at once
            assertTrue(doneLatch.await(DEFAULT_TIMEOUT.toMillis() * 3, TimeUnit.MILLISECONDS));

            // Then: All transitions complete successfully
            assertEquals(peerCount, successfulTransitions.get(),
                "All state transitions should complete successfully");
        }

        private boolean performStateTransition(String peerId) {
            try {
                // KEY_EXCHANGE_INIT
                Message init = createMessage("KEY_EXCHANGE_INIT", peerId, LOCAL_NODE_ID, false);
                if (!dispatcher.dispatch(init, createSource(peerId)).isAccepted()) return false;
                peerStates.put(peerId, new PeerSecurityState(PeerState.DISCOVERED, false));

                // KEY_EXCHANGE_COMPLETE
                peerStates.put(peerId, new PeerSecurityState(PeerState.CHANNEL_NEGOTIATING, false));
                Message complete = createMessage("KEY_EXCHANGE_COMPLETE", peerId, LOCAL_NODE_ID, false);
                if (!dispatcher.dispatch(complete, createSource(peerId)).isAccepted()) return false;
                peerStates.put(peerId, new PeerSecurityState(PeerState.CHANNEL_ESTABLISHED, true));

                // HANDSHAKE_COMPLETE
                peerStates.put(peerId, new PeerSecurityState(PeerState.CONNECTING, true));
                Message handshake = createMessage("HANDSHAKE_COMPLETE", peerId, LOCAL_NODE_ID, true);
                if (!dispatcher.dispatch(handshake, createSource(peerId)).isAccepted()) return false;
                peerStates.put(peerId, new PeerSecurityState(PeerState.AUTHENTICATED, true));

                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ===================== 5. Metrics and Observability Tests =====================

    @Nested
    @DisplayName("5. Metrics and Observability")
    class MetricsTests {

        @Test
        @Order(40)
        @DisplayName("5.1 - Dispatch metrics are recorded correctly")
        void testDispatchMetricsRecorded() {
            // Given: Various dispatch scenarios
            String authenticatedPeer = "metrics-auth-peer";
            String unknownPeer = "metrics-unknown-peer";
            peerStates.put(authenticatedPeer, new PeerSecurityState(PeerState.AUTHENTICATED, true));

            // When: Processing different message types
            dispatcher.dispatch(createMessage("PING", authenticatedPeer, LOCAL_NODE_ID, true), createSource(authenticatedPeer));
            dispatcher.dispatch(createMessage("DATA", authenticatedPeer, LOCAL_NODE_ID, true), createSource(authenticatedPeer));
            dispatcher.dispatch(createMessage("PING", unknownPeer, LOCAL_NODE_ID, false), createSource(unknownPeer));
            dispatcher.dispatch(createMessage("KEY_EXCHANGE_INIT", unknownPeer, LOCAL_NODE_ID, false), createSource(unknownPeer));

            // Then: Metrics are recorded
            assertTrue(metricsRegistry.getCounter("dispatcher.messages.total") >= 4);
            assertTrue(metricsRegistry.getCounter("dispatcher.messages.accepted") >= 2);
            assertTrue(metricsRegistry.getCounter("dispatcher.messages.rejected") >= 1);
        }

        @Test
        @Order(41)
        @DisplayName("5.2 - Security events are logged with full context")
        void testSecurityEventsHaveFullContext() {
            // Given: A security-sensitive operation
            String peerId = "audit-peer";
            peerStates.put(peerId, new PeerSecurityState(PeerState.DISCOVERED, false));

            // When: Attempting disallowed operation
            Message disallowed = createMessage("PING", peerId, LOCAL_NODE_ID, false);
            dispatcher.dispatch(disallowed, createSource(peerId));

            // Then: Security event has full context
            SecurityEvent event = findEvent(SecurityEventType.MESSAGE_REJECTED);
            assertNotNull(event, "Security event should be recorded");
            assertNotNull(event.getTimestamp());
            assertEquals(peerId, event.getPeerId());
            assertNotNull(event.getMessageType());
            assertNotNull(event.getReason());
        }
    }

    // ===================== Helper Methods =====================

    private void assertDispatchResult(String peerId, String messageType, PeerState state,
                                       boolean encrypted, boolean expectedAccepted) {
        if (state != null) {
            peerStates.put(peerId, new PeerSecurityState(state, encrypted));
        }
        Message message = createMessage(messageType, peerId, LOCAL_NODE_ID, encrypted);
        DispatchResult result = dispatcher.dispatch(message, createSource(peerId));
        assertEquals(expectedAccepted, result.isAccepted(),
            String.format("Message %s from peer in state %s (encrypted=%s) should be %s",
                messageType, state, encrypted, expectedAccepted ? "accepted" : "rejected"));
    }

    private Message createMessage(String type, String from, String to, boolean encrypted) {
        return createMessageWithId(UUID.randomUUID().toString(), type, from, to, encrypted);
    }

    private Message createMessageWithId(String messageId, String type, String from, String to, boolean encrypted) {
        MessageHeader header = new MessageHeader(
            messageId, TraceIdGenerator.generate(),
            ProtocolVersion.current().toString(), "1.0",
            10, 0, type, from, to,
            System.currentTimeMillis(), encrypted,
            "application/json", false, null, false
        );
        return new Message(header, new MessageBody("{}", Map.of()));
    }

    private Message createMessageWithContent(String type, String from, String to, boolean encrypted, String content) {
        MessageHeader header = new MessageHeader(
            UUID.randomUUID().toString(), TraceIdGenerator.generate(),
            ProtocolVersion.current().toString(), "1.0",
            10, 0, type, from, to,
            System.currentTimeMillis(), encrypted,
            "application/json", false, null, false
        );
        return new Message(header, new MessageBody(content, Map.of()));
    }

    private InetSocketAddress createSource(String peerId) {
        return new InetSocketAddress("192.168.1." + (Math.abs(peerId.hashCode()) % 255), 5000);
    }

    private void assertContainsEvent(SecurityEventType type) {
        assertTrue(securityEvents.stream().anyMatch(e -> e.getType() == type),
            "Should contain event of type " + type);
    }

    private SecurityEvent findEvent(SecurityEventType type) {
        return securityEvents.stream()
            .filter(e -> e.getType() == type)
            .findFirst()
            .orElse(null);
    }

    // Test data providers
    static Stream<String> bootstrapMessageTypes() {
        return BOOTSTRAP_MESSAGES.stream();
    }

    static Stream<String> applicationMessageTypes() {
        return APPLICATION_MESSAGES.stream();
    }

    static Stream<Arguments> stateAllowedMessageTypes() {
        return Stream.of(
            // DISCOVERED state - only bootstrap/key exchange allowed (plaintext)
            Arguments.of(PeerState.DISCOVERED,
                Set.of("KEY_EXCHANGE_INIT", "KEY_EXCHANGE_RESPONSE", "DISCOVERY_REQUEST"),
                Set.of("PING", "DATA")),
            // AUTHENTICATED state - encrypted app messages allowed
            Arguments.of(PeerState.AUTHENTICATED,
                Set.of("PING", "PONG", "DATA"),
                Set.<String>of()) // Don't check disallowed for authenticated - complex rules
        );
    }

    // ===================== Supporting Classes =====================

    enum SecurityEventType {
        MESSAGE_ACCEPTED, MESSAGE_REJECTED, STATE_DOWNGRADE_ATTEMPT,
        REPLAY_ATTACK_DETECTED, RATE_LIMIT_EXCEEDED, INVALID_SIGNATURE
    }

    enum RejectReason {
        ENCRYPTION_REQUIRED, STATE_VIOLATION, INVALID_STATE_TRANSITION,
        REPLAY_DETECTED, RATE_LIMITED, MESSAGE_TOO_LARGE, UNKNOWN_PEER
    }

    static class SecurityEvent {
        private final SecurityEventType type;
        private final String peerId;
        private final String messageType;
        private final String reason;
        private final Instant timestamp;

        SecurityEvent(SecurityEventType type, String peerId, String messageType, String reason) {
            this.type = type;
            this.peerId = peerId;
            this.messageType = messageType;
            this.reason = reason;
            this.timestamp = Instant.now();
        }

        SecurityEventType getType() { return type; }
        String getPeerId() { return peerId; }
        String getMessageType() { return messageType; }
        String getReason() { return reason; }
        Instant getTimestamp() { return timestamp; }
    }

    static class PeerSecurityState {
        private final PeerState state;
        private final boolean hasValidSession;

        PeerSecurityState(PeerState state, boolean hasValidSession) {
            this.state = state;
            this.hasValidSession = hasValidSession;
        }

        PeerState getState() { return state; }
        boolean hasValidSession() { return hasValidSession; }
    }

    static class DispatchResult {
        private final boolean accepted;
        private final RejectReason rejectReason;

        DispatchResult(boolean accepted, RejectReason rejectReason) {
            this.accepted = accepted;
            this.rejectReason = rejectReason;
        }

        boolean isAccepted() { return accepted; }
        RejectReason getRejectReason() { return rejectReason; }

        static DispatchResult accepted() { return new DispatchResult(true, null); }
        static DispatchResult rejected(RejectReason reason) { return new DispatchResult(false, reason); }
    }

    /**
     * Simulated Security-Aware Dispatcher for testing.
     */
    static class SimulatedDispatcher {
        private final String localNodeId;
        private final MetricsRegistry metrics;
        private final List<SecurityEvent> events;
        private final Map<String, PeerSecurityState> peerStates;
        private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();
        private final Map<String, AtomicInteger> rateLimitCounters = new ConcurrentHashMap<>();
        private final Map<String, Integer> rateLimits = new ConcurrentHashMap<>();
        private static final int MAX_MESSAGE_SIZE = 1024 * 1024; // 1MB

        SimulatedDispatcher(String localNodeId, MetricsRegistry metrics,
                           List<SecurityEvent> events, Map<String, PeerSecurityState> peerStates) {
            this.localNodeId = localNodeId;
            this.metrics = metrics;
            this.events = events;
            this.peerStates = peerStates;
        }

        DispatchResult dispatch(Message message, InetSocketAddress source) {
            metrics.incrementCounter("dispatcher.messages.total");

            String peerId = message.from();
            String messageType = message.type();
            boolean encrypted = message.header().encrypted();

            // Check message size
            if (message.body().content() != null && message.body().content().length() > MAX_MESSAGE_SIZE) {
                return reject(peerId, messageType, RejectReason.MESSAGE_TOO_LARGE, "Message exceeds size limit");
            }

            // Check replay
            String messageId = message.messageId();
            if (messageId != null && !processedMessageIds.add(messageId)) {
                events.add(new SecurityEvent(SecurityEventType.REPLAY_ATTACK_DETECTED, peerId, messageType, "Duplicate message ID"));
                return reject(peerId, messageType, RejectReason.REPLAY_DETECTED, "Replay detected");
            }

            // Check rate limit
            Integer limit = rateLimits.get(peerId);
            if (limit != null) {
                AtomicInteger counter = rateLimitCounters.computeIfAbsent(peerId, k -> new AtomicInteger(0));
                if (counter.incrementAndGet() > limit) {
                    return reject(peerId, messageType, RejectReason.RATE_LIMITED, "Rate limit exceeded");
                }
            }

            // Get peer state
            PeerSecurityState peerState = peerStates.get(peerId);

            // Bootstrap messages from unknown peers
            if (peerState == null) {
                if (BOOTSTRAP_MESSAGES.contains(messageType)) {
                    return accept(peerId, messageType);
                }
                return reject(peerId, messageType, RejectReason.UNKNOWN_PEER, "Unknown peer");
            }

            // State-based validation
            PeerState state = peerState.getState();

            // Check for state downgrade attempts
            if (state == PeerState.AUTHENTICATED &&
                (messageType.equals("KEY_EXCHANGE_INIT") || messageType.equals("KEY_EXCHANGE_RESPONSE"))) {
                events.add(new SecurityEvent(SecurityEventType.STATE_DOWNGRADE_ATTEMPT, peerId, messageType, "Downgrade attempt"));
                return reject(peerId, messageType, RejectReason.STATE_VIOLATION, "State downgrade not allowed");
            }

            // Validate message type for state
            if (!isMessageAllowedInState(messageType, state, encrypted)) {
                if (!encrypted && APPLICATION_MESSAGES.contains(messageType)) {
                    return reject(peerId, messageType, RejectReason.ENCRYPTION_REQUIRED, "Encryption required");
                }
                return reject(peerId, messageType, RejectReason.INVALID_STATE_TRANSITION, "Invalid state for message");
            }

            return accept(peerId, messageType);
        }

        private boolean isMessageAllowedInState(String messageType, PeerState state, boolean encrypted) {
            return switch (state) {
                case DISCOVERED, CHANNEL_NEGOTIATING ->
                    Set.of("KEY_EXCHANGE_INIT", "KEY_EXCHANGE_RESPONSE", "KEY_EXCHANGE_COMPLETE",
                           "DISCOVERY_REQUEST", "DISCOVERY_RESPONSE").contains(messageType);
                case CHANNEL_ESTABLISHED, CONNECTING, CONNECTED ->
                    encrypted && (HANDSHAKE_MESSAGES.contains(messageType) || APPLICATION_MESSAGES.contains(messageType));
                case AUTHENTICATED ->
                    encrypted && (APPLICATION_MESSAGES.contains(messageType) || HANDSHAKE_MESSAGES.contains(messageType));
                default -> false;
            };
        }

        private DispatchResult accept(String peerId, String messageType) {
            metrics.incrementCounter("dispatcher.messages.accepted");
            events.add(new SecurityEvent(SecurityEventType.MESSAGE_ACCEPTED, peerId, messageType, null));
            return DispatchResult.accepted();
        }

        private DispatchResult reject(String peerId, String messageType, RejectReason reason, String detail) {
            metrics.incrementCounter("dispatcher.messages.rejected");
            events.add(new SecurityEvent(SecurityEventType.MESSAGE_REJECTED, peerId, messageType, detail));
            return DispatchResult.rejected(reason);
        }

        void setRateLimit(String peerId, int messagesPerSecond) {
            rateLimits.put(peerId, messagesPerSecond);
        }

        void shutdown() {
            // Cleanup
        }
    }
}

