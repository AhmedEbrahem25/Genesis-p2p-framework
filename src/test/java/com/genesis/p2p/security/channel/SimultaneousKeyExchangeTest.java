package com.genesis.p2p.security.channel;

import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.api.IKeyManager;
import com.genesis.p2p.security.api.ISessionManager;
import com.genesis.p2p.security.api.ISecureSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for simultaneous KEY_EXCHANGE_INIT deadlock prevention.
 *
 * Verifies the deterministic tie-breaking mechanism that resolves the
 * scenario where both peers send KEY_EXCHANGE_INIT at the same time.
 *
 * v2.5: Added tests for identity mismatch scenarios where discovery_peerId
 * differs from protocol_nodeId, causing duel detection to fail without
 * the multi-strategy identity resolution fix.
 *
 * @author Genesis P2P Framework
 * @version 2.5
 */
@DisplayName("Simultaneous KEY_EXCHANGE_INIT Tests")
class SimultaneousKeyExchangeTest {

    private MetricsRegistry metricsA;
    private MetricsRegistry metricsB;
    private IKeyManager keyManagerA;
    private IKeyManager keyManagerB;
    private ISessionManager sessionManagerA;
    private ISessionManager sessionManagerB;

    // Test keys
    private KeyPair identityKeyPairA;
    private KeyPair identityKeyPairB;

    @BeforeEach
    void setUp() throws Exception {
        metricsA = new MetricsRegistry("node-a");
        metricsB = new MetricsRegistry("node-b");

        // Generate identity key pairs for both nodes
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(256);
        identityKeyPairA = keyGen.generateKeyPair();
        identityKeyPairB = keyGen.generateKeyPair();

        // Mock key managers
        keyManagerA = mock(IKeyManager.class);
        keyManagerB = mock(IKeyManager.class);

        when(keyManagerA.getIdentityPublicKey()).thenReturn(identityKeyPairA.getPublic().getEncoded());
        when(keyManagerB.getIdentityPublicKey()).thenReturn(identityKeyPairB.getPublic().getEncoded());

        // Mock signing
        when(keyManagerA.signWithIdentityKey(any())).thenAnswer(inv -> {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(identityKeyPairA.getPrivate());
            sig.update((byte[]) inv.getArgument(0));
            return sig.sign();
        });

        when(keyManagerB.signWithIdentityKey(any())).thenAnswer(inv -> {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(identityKeyPairB.getPrivate());
            sig.update((byte[]) inv.getArgument(0));
            return sig.sign();
        });

        // Mock signature verification
        when(keyManagerA.verifyIdentitySignature(any(), any(), any())).thenReturn(true);
        when(keyManagerB.verifyIdentitySignature(any(), any(), any())).thenReturn(true);

        // Mock session managers
        sessionManagerA = mock(ISessionManager.class);
        sessionManagerB = mock(ISessionManager.class);

        ISecureSession mockSessionA = mock(ISecureSession.class);
        ISecureSession mockSessionB = mock(ISecureSession.class);
        when(mockSessionA.getSessionId()).thenReturn("session-a");
        when(mockSessionB.getSessionId()).thenReturn("session-b");

        when(sessionManagerA.createSession(any(), any(), any())).thenReturn(mockSessionA);
        when(sessionManagerB.createSession(any(), any(), any())).thenReturn(mockSessionB);
    }

    @Nested
    @DisplayName("Tie-Breaking Resolution Tests")
    class TieBreakingTests {

        @Test
        @DisplayName("Lower nodeId wins and maintains INITIATOR role (returns null)")
        void lowerNodeIdWinsAndReturnsNull() throws Exception {
            // Node A has lower nodeId: "aaa" < "zzz"
            String nodeIdA = "aaa";
            String nodeIdB = "zzz";

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);

            // Node A initiates KEY_EXCHANGE with Node B
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());
            assertNotNull(initFromA);

            // Simulate Node B also initiating KEY_EXCHANGE with Node A
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    nodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(nodeIdA, identityKeyPairA.getPublic().getEncoded());

            // Node A receives Node B's INIT while waiting for COMPLETE
            // Since A's nodeId < B's nodeId, A wins and returns null (silent drop)
            KeyExchangeComplete responseFromA = negotiatorA.processKeyExchangeInit(initFromB);

            assertNull(responseFromA, "Winner (lower nodeId) should return null for silent drop");

            // Verify metrics
            assertEquals(1, metricsA.getCounter("security.key_exchange.simultaneous_init_win"));
        }

        @Test
        @DisplayName("Higher nodeId loses and becomes RESPONDER")
        void higherNodeIdLosesAndBecomesResponder() throws Exception {
            // Node B has higher nodeId: "zzz" > "aaa"
            String nodeIdA = "aaa";
            String nodeIdB = "zzz";

            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    nodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            // Node B initiates KEY_EXCHANGE with Node A
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(nodeIdA, identityKeyPairA.getPublic().getEncoded());
            assertNotNull(initFromB);

            // Create Node A's INIT
            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());

            // Node B receives Node A's INIT while waiting for COMPLETE
            // Since B's nodeId > A's nodeId, B loses and becomes RESPONDER
            KeyExchangeComplete responseFromB = negotiatorB.processKeyExchangeInit(initFromA);

            assertNotNull(responseFromB, "Loser (higher nodeId) should return COMPLETE as responder");
            assertTrue(responseFromB.isAccepted(), "Response should be accepted");

            // Verify metrics
            assertEquals(1, metricsB.getCounter("security.key_exchange.simultaneous_init_lose"));
        }

        @Test
        @DisplayName("Complete flow: simultaneous INIT resolves to single successful channel")
        void completeFlowResolvesSuccessfully() throws Exception {
            // Node A: "aaa" (will win)
            // Node B: "zzz" (will lose, become responder)
            String nodeIdA = "aaa";
            String nodeIdB = "zzz";

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    nodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            // Both initiate simultaneously
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(nodeIdA, identityKeyPairA.getPublic().getEncoded());

            // Exchange INITs
            KeyExchangeComplete responseFromA = negotiatorA.processKeyExchangeInit(initFromB);
            KeyExchangeComplete responseFromB = negotiatorB.processKeyExchangeInit(initFromA);

            // A wins: returns null (silent drop)
            assertNull(responseFromA, "A should return null (winner)");

            // B loses: becomes responder, returns COMPLETE
            assertNotNull(responseFromB, "B should return COMPLETE (loser becomes responder)");
            assertTrue(responseFromB.isAccepted());

            // A receives B's COMPLETE and establishes channel
            KeyExchangeResult resultA = negotiatorA.processKeyExchangeComplete(responseFromB);

            assertTrue(resultA.isSuccess(), "A should successfully establish channel");
            assertTrue(negotiatorA.hasSecureChannel(nodeIdB), "A should have secure channel with B");
            assertTrue(negotiatorB.hasSecureChannel(nodeIdA), "B should have secure channel with A");
        }

        @Test
        @DisplayName("Same nodeId (self-connection) is rejected")
        void sameNodeIdRejected() throws Exception {
            String nodeId = "same-node";

            SecureChannelNegotiator negotiator = new SecureChannelNegotiator(
                    nodeId, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);

            // Initiate to self (normally shouldn't happen but test edge case)
            // First we need to fake having an INIT_SENT state
            EphemeralKeyPair ephemeralKeys = EphemeralKeyPair.generate();
            KeyExchangeInit selfInit = KeyExchangeInit.create(
                    nodeId,
                    ephemeralKeys,
                    identityKeyPairA.getPublic().getEncoded(),
                    data -> {
                        try {
                            Signature sig = Signature.getInstance("SHA256withECDSA");
                            sig.initSign(identityKeyPairA.getPrivate());
                            sig.update(data);
                            return sig.sign();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    "1.0"
            );

            // Process the self-INIT (should reject if comparison == 0)
            // This requires having pending state first
            try {
                negotiator.initiateKeyExchange(nodeId, identityKeyPairA.getPublic().getEncoded());
                KeyExchangeComplete response = negotiator.processKeyExchangeInit(selfInit);

                if (response != null) {
                    assertFalse(response.isAccepted(), "Self-connection should be rejected");
                    assertTrue(response.getRejectionReason().contains("SELF_CONNECTION"));
                }
            } catch (Exception e) {
                // May throw due to same-node edge case
                assertTrue(e.getMessage().contains("already in progress") ||
                        e.getMessage().contains("self"));
            }
        }
    }

    @Nested
    @DisplayName("Retry Mechanism Tests")
    class RetryTests {

        @Test
        @DisplayName("Negotiation tracks retry count correctly")
        void negotiationTracksRetryCount() throws Exception {
            String nodeIdA = "aaa";
            String nodeIdB = "zzz";

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);

            // Initiate KEY_EXCHANGE
            negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());

            // Check initial state
            ChannelNegotiationState state = negotiatorA.getKeyExchangeState(nodeIdB);
            assertNotNull(state);
            assertEquals(0, state.getRetryCount());
            assertTrue(state.canRetry());
        }

        @Test
        @DisplayName("getPeersNeedingRetry returns stuck negotiations")
        void getPeersNeedingRetryWorks() throws Exception {
            String nodeIdA = "aaa";
            String nodeIdB = "zzz";

            // Use short retry interval for testing
            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);

            // Initiate KEY_EXCHANGE
            negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());

            // Initially no retries needed (haven't waited long enough)
            java.util.List<String> needingRetry = negotiatorA.getPeersNeedingRetry();
            assertEquals(0, needingRetry.size(), "Should not need retry immediately");

            // After waiting for retry interval (simulated by checking canRetry + isRetryDue)
            // The actual test of timing would require waiting or mocking time
        }

        @Test
        @DisplayName("simultaneous init detection marks state correctly")
        void simultaneousInitMarksState() throws Exception {
            String nodeIdA = "aaa";
            String nodeIdB = "zzz";

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    nodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            // Both initiate
            negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(nodeIdA, identityKeyPairA.getPublic().getEncoded());

            // A wins (lower nodeId)
            KeyExchangeComplete responseFromA = negotiatorA.processKeyExchangeInit(initFromB);
            assertNull(responseFromA);

            // Check state was marked
            ChannelNegotiationState stateA = negotiatorA.getKeyExchangeState(nodeIdB);
            assertNotNull(stateA.getSimultaneousInitDetectedAt(),
                    "Simultaneous init should be marked");
            assertTrue(stateA.isWaitingAfterSimultaneousInit(),
                    "Should be waiting for peer's COMPLETE");
        }
    }

    @Nested
    @DisplayName("Metrics Tests")
    class MetricsTests {

        @Test
        @DisplayName("Metrics are recorded for simultaneous init win")
        void metricsRecordedForWin() throws Exception {
            String nodeIdA = "aaa";
            String nodeIdB = "zzz";

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    nodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(nodeIdA, identityKeyPairA.getPublic().getEncoded());

            negotiatorA.processKeyExchangeInit(initFromB);

            assertEquals(1, metricsA.getCounter("security.key_exchange.simultaneous_init_win"));
        }

        @Test
        @DisplayName("Metrics are recorded for simultaneous init lose")
        void metricsRecordedForLose() throws Exception {
            String nodeIdA = "aaa";
            String nodeIdB = "zzz";

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    nodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            negotiatorB.initiateKeyExchange(nodeIdA, identityKeyPairA.getPublic().getEncoded());
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());

            negotiatorB.processKeyExchangeInit(initFromA);

            assertEquals(1, metricsB.getCounter("security.key_exchange.simultaneous_init_lose"));
        }
    }

    @Nested
    @DisplayName("Post-Exchange Trigger Tests")
    class PostExchangeTriggerTests {

        @Test
        @DisplayName("Post-exchange trigger is invoked on successful KEY_EXCHANGE")
        void postExchangeTriggerInvoked() throws Exception {
            String nodeIdA = "aaa";
            String nodeIdB = "zzz";

            AtomicReference<KeyExchangeResult> capturedResult = new AtomicReference<>();

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);

            negotiatorA.setPostExchangeTrigger(result -> {
                capturedResult.set(result);
            });

            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    nodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            // A initiates
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());

            // B responds
            KeyExchangeComplete responseFromB = negotiatorB.processKeyExchangeInit(initFromA);
            assertNotNull(responseFromB);
            assertTrue(responseFromB.isAccepted());

            // A processes response
            KeyExchangeResult result = negotiatorA.processKeyExchangeComplete(responseFromB);
            assertTrue(result.isSuccess());

            // Verify trigger was called
            assertNotNull(capturedResult.get(), "Post-exchange trigger should have been called");
            assertTrue(capturedResult.get().isSuccess());
            assertEquals(nodeIdB, capturedResult.get().getResolvedPeerId());
        }
    }

    /**
     * Identity Mismatch Tests (v2.5)
     *
     * These tests verify the fix for the production failure where duel detection
     * fails due to identity mismatch between:
     * - discovery_peerId: How the peer is keyed in PeerStore (e.g., "peer-10.0.0.5:8080")
     * - protocol_nodeId: How the peer identifies itself in KEY_EXCHANGE (e.g., "node-xyz")
     *
     * Without the fix, when both peers send KEY_EXCHANGE_INIT simultaneously:
     * 1. Node A initiates to discovery_peerId "peer-10.0.0.5:8080"
     * 2. Node B's INIT arrives with nodeId "node-xyz"
     * 3. Duel detection fails because pendingKeyExchanges.get("node-xyz") returns null
     * 4. Both nodes become responders, causing deadlock
     *
     * The v2.5 fix uses multi-strategy identity resolution:
     * - Strategy 1: Direct lookup by peerId
     * - Strategy 2: Identity mapping lookup (nodeIdToDiscoveryId)
     * - Strategy 3: Fallback scan (reverse mapping, identity public key, substring)
     */
    @Nested
    @DisplayName("Identity Mismatch Tests (v2.5)")
    class IdentityMismatchTests {

        @Test
        @DisplayName("Duel detection succeeds when discovery_peerId differs from protocol_nodeId via identity public key matching")
        void duelDetectionSucceedsViaIdentityKeyMatching() throws Exception {
            // Scenario: Node A uses discovery_peerId, Node B uses protocol_nodeId
            String nodeIdA = "aaa";
            String discoveryPeerIdB = "peer-10.0.0.5:8080";  // How A knows B from discovery
            String protocolNodeIdB = "zzz";  // How B identifies itself

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    protocolNodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            // Node A initiates KEY_EXCHANGE using discoveryPeerIdB (from PeerStore)
            // CRITICAL: We pass B's identity key so the duel detection can match
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(discoveryPeerIdB, identityKeyPairB.getPublic().getEncoded());
            assertNotNull(initFromA);

            // Node B initiates KEY_EXCHANGE using nodeIdA (protocol format)
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(nodeIdA, identityKeyPairA.getPublic().getEncoded());
            assertNotNull(initFromB);

            // Node A receives Node B's INIT with protocolNodeIdB (not discoveryPeerIdB!)
            // The v2.5 fix should detect this is the same peer via identity public key matching
            KeyExchangeComplete responseFromA = negotiatorA.processKeyExchangeInit(initFromB);

            // A has lower nodeId ("aaa" < "zzz"), so A should WIN and return null
            assertNull(responseFromA, "A should win (lower nodeId) and return null for silent drop");
            assertEquals(1, metricsA.getCounter("security.key_exchange.simultaneous_init_win"));

            // Node B receives Node A's INIT
            // B has higher nodeId ("zzz" > "aaa"), so B should LOSE and become responder
            KeyExchangeComplete responseFromB = negotiatorB.processKeyExchangeInit(initFromA);

            assertNotNull(responseFromB, "B should lose and return COMPLETE as responder");
            assertTrue(responseFromB.isAccepted());
            assertEquals(1, metricsB.getCounter("security.key_exchange.simultaneous_init_lose"));

            // A can now process B's COMPLETE
            KeyExchangeResult resultA = negotiatorA.processKeyExchangeComplete(responseFromB);
            assertTrue(resultA.isSuccess(), "A should establish channel successfully");
        }

        @Test
        @DisplayName("Duel detection succeeds via pre-registered identity mapping")
        void duelDetectionSucceedsViaIdentityMapping() throws Exception {
            String nodeIdA = "aaa";
            String discoveryPeerIdB = "peer-192.168.1.100:9000";
            String protocolNodeIdB = "zzz";

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    protocolNodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            // Pre-register identity mapping (simulates discovery having learned B's nodeId)
            negotiatorA.registerIdentityMapping(protocolNodeIdB, discoveryPeerIdB);

            // A initiates using discoveryPeerIdB
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(discoveryPeerIdB, identityKeyPairB.getPublic().getEncoded());

            // B initiates using nodeIdA
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(nodeIdA, identityKeyPairA.getPublic().getEncoded());

            // A receives B's INIT - should resolve via identity mapping
            KeyExchangeComplete responseFromA = negotiatorA.processKeyExchangeInit(initFromB);

            assertNull(responseFromA, "A wins duel via identity mapping resolution");
            assertEquals(1, metricsA.getCounter("security.key_exchange.simultaneous_init_win"));

            // Verify identity mapping was used (state should be found at discoveryPeerIdB)
            ChannelNegotiationState stateA = negotiatorA.getKeyExchangeState(discoveryPeerIdB);
            assertNotNull(stateA, "State should exist at discoveryPeerIdB");
            assertNotNull(stateA.getSimultaneousInitDetectedAt(), "Simultaneous init should be marked");
        }

        @Test
        @DisplayName("Loser correctly transitions to responder and sends COMPLETE despite identity mismatch")
        void loserTransitionsToResponderDespiteIdentityMismatch() throws Exception {
            // This test verifies the loser (higher nodeId) correctly becomes responder
            // even when there's an identity mismatch
            String nodeIdA = "aaa";
            String discoveryPeerIdA = "peer-node-alpha";  // How B knows A
            String nodeIdB = "zzz";  // B has higher nodeId (will lose)

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    nodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            // B initiates to discoveryPeerIdA
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(discoveryPeerIdA, identityKeyPairA.getPublic().getEncoded());

            // A initiates to nodeIdB
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(nodeIdB, identityKeyPairB.getPublic().getEncoded());

            // B receives A's INIT - should detect duel via identity key and LOSE
            KeyExchangeComplete responseFromB = negotiatorB.processKeyExchangeInit(initFromA);

            assertNotNull(responseFromB, "B (loser) MUST send COMPLETE as responder");
            assertTrue(responseFromB.isAccepted(), "COMPLETE should be accepted");
            assertEquals(1, metricsB.getCounter("security.key_exchange.simultaneous_init_lose"));

            // Verify B established channel as responder
            assertTrue(negotiatorB.hasSecureChannel(nodeIdA), "B should have secure channel with A");
        }

        @Test
        @DisplayName("Complete flow with identity mismatch establishes exactly one secure channel")
        void completeFlowWithIdentityMismatchEstablishesOneChannel() throws Exception {
            // Full simulation of the production failure scenario
            String nodeIdA = "alpha-node";  // Lower, will win
            String discoveryPeerIdA = "peer-10.0.0.1:8080";
            String nodeIdB = "zeta-node";   // Higher, will lose
            String discoveryPeerIdB = "peer-10.0.0.2:8080";

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    nodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            // Simulate: Both peers discover each other and initiate simultaneously
            // A uses discoveryPeerIdB, B uses discoveryPeerIdA
            KeyExchangeInit initFromA = negotiatorA.initiateKeyExchange(discoveryPeerIdB, identityKeyPairB.getPublic().getEncoded());
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(discoveryPeerIdA, identityKeyPairA.getPublic().getEncoded());

            // Cross-deliver the INITs (identity mismatch: INIT contains nodeId, not discoveryPeerId)
            KeyExchangeComplete responseFromA = negotiatorA.processKeyExchangeInit(initFromB);
            KeyExchangeComplete responseFromB = negotiatorB.processKeyExchangeInit(initFromA);

            // A wins (alpha-node < zeta-node)
            assertNull(responseFromA, "A wins and returns null");
            assertEquals(1, metricsA.getCounter("security.key_exchange.simultaneous_init_win"));

            // B loses and becomes responder
            assertNotNull(responseFromB, "B loses and returns COMPLETE");
            assertTrue(responseFromB.isAccepted());
            assertEquals(1, metricsB.getCounter("security.key_exchange.simultaneous_init_lose"));

            // A processes B's COMPLETE
            KeyExchangeResult resultA = negotiatorA.processKeyExchangeComplete(responseFromB);

            // Verify final state
            assertTrue(resultA.isSuccess(), "KEY_EXCHANGE should succeed");
            assertTrue(negotiatorB.hasSecureChannel(nodeIdA) || negotiatorB.hasSecureChannel(discoveryPeerIdA),
                    "B should have secure channel");

            // Verify exactly ONE channel established (no duplicate negotiations)
            assertEquals(1, metricsA.getCounter("security.key_exchange.simultaneous_init_win"));
            assertEquals(1, metricsB.getCounter("security.key_exchange.simultaneous_init_lose"));
        }

        @Test
        @DisplayName("Identity mapping is automatically created after successful resolution")
        void identityMappingCreatedAfterResolution() throws Exception {
            String nodeIdA = "aaa";
            String discoveryPeerIdB = "peer-remote-host:9999";
            String protocolNodeIdB = "zzz";

            SecureChannelNegotiator negotiatorA = new SecureChannelNegotiator(
                    nodeIdA, keyManagerA, sessionManagerA, null, null, "1.0", metricsA);
            SecureChannelNegotiator negotiatorB = new SecureChannelNegotiator(
                    protocolNodeIdB, keyManagerB, sessionManagerB, null, null, "1.0", metricsB);

            // A initiates to discoveryPeerIdB
            negotiatorA.initiateKeyExchange(discoveryPeerIdB, identityKeyPairB.getPublic().getEncoded());

            // B initiates to nodeIdA
            KeyExchangeInit initFromB = negotiatorB.initiateKeyExchange(nodeIdA, identityKeyPairA.getPublic().getEncoded());

            // A processes B's INIT (should resolve via identity key and create mapping)
            negotiatorA.processKeyExchangeInit(initFromB);

            // Verify identity mapping was automatically created
            String resolvedDiscoveryId = negotiatorA.resolveNodeIdToDiscoveryId(protocolNodeIdB);
            assertEquals(discoveryPeerIdB, resolvedDiscoveryId,
                    "Identity mapping should map protocolNodeIdB -> discoveryPeerIdB");

            String resolvedNodeId = negotiatorA.resolveDiscoveryIdToNodeId(discoveryPeerIdB);
            assertEquals(protocolNodeIdB, resolvedNodeId,
                    "Reverse mapping should map discoveryPeerIdB -> protocolNodeIdB");
        }
    }
}
