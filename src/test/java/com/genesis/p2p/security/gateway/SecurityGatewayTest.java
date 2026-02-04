package com.genesis.p2p.security.gateway;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.handlers.ProcessingContext;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.peer.PeerStore.PeerState;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.policy.MessageSecurityPolicy;
import com.genesis.p2p.transport.core.ITransportEnvelopeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SecurityGateway.
 *
 * Tests the security enforcement logic:
 * - Bootstrap messages (KEY_EXCHANGE) allowed in plaintext
 * - Application messages rejected without valid session
 * - Peer state validation
 * - Fail-closed behavior
 */
class SecurityGatewayTest {

    private SecurityFacade securityFacade;
    private PeerManager peerManager;

    private MetricsRegistry metricsRegistry;
    private MessageSecurityPolicy policy;
    private AtomicBoolean downstreamCalled;
    private AtomicReference<Message> receivedMessage;
    private ITransportEnvelopeHandler downstreamHandler;
    private InetSocketAddress testSource;

    @BeforeEach
    void setUp() {
        // Create mocks manually
        securityFacade = mock(SecurityFacade.class);
        peerManager = mock(PeerManager.class);

        metricsRegistry = new MetricsRegistry("test");
        policy = new MessageSecurityPolicy();
        downstreamCalled = new AtomicBoolean(false);
        receivedMessage = new AtomicReference<>();

        downstreamHandler = (message, source, context) -> {
            downstreamCalled.set(true);
            receivedMessage.set(message);
        };

        testSource = new InetSocketAddress("192.168.1.100", 5000);
    }

    private Message createMessage(String type, String from, boolean encrypted) {
        MessageHeader header = new MessageHeader(
                null,  // messageId - auto-generated
                null,  // correlationId
                "1.0", // protocolVersion
                "1.0", // messageVersion
                10,    // ttl
                0,     // hopCount
                type,
                from,
                "node-local",
                System.currentTimeMillis(),
                encrypted,
                "json",
                false, // authenticated
                null,  // signature
                false  // requiresAck
        );
        MessageBody body = new MessageBody("{\"test\": true}", java.util.Map.of());
        return new Message(header, body);
    }

    private SecurityGateway createGateway(boolean legacyMode) {
        return new SecurityGatewayBuilder()
                .downstream(downstreamHandler)
                .security(securityFacade)
                .peerManager(peerManager)
                .policy(policy)
                .metrics(metricsRegistry)
                .legacyPlaintextMode(legacyMode)
                .build();
    }

    @Nested
    @DisplayName("Bootstrap Message Tests")
    class BootstrapMessageTests {

        @Test
        @DisplayName("KEY_EXCHANGE_INIT allowed in plaintext from unknown peer")
        void keyExchangeInitAllowedFromUnknownPeer() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("KEY_EXCHANGE_INIT", "unknown-peer", false);

            // Unknown peer - no state
            when(peerManager.getPeerState("unknown-peer")).thenReturn(null);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertTrue(downstreamCalled.get(), "Downstream should be called for KEY_EXCHANGE_INIT");
            assertEquals(message, receivedMessage.get());
        }

        @Test
        @DisplayName("KEY_EXCHANGE_COMPLETE allowed in plaintext in CHANNEL_NEGOTIATING state")
        void keyExchangeCompleteAllowedInNegotiatingState() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("KEY_EXCHANGE_COMPLETE", "peer-1", false);

            when(peerManager.getPeerState("peer-1")).thenReturn(PeerState.CHANNEL_NEGOTIATING);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertTrue(downstreamCalled.get(), "Downstream should be called for KEY_EXCHANGE_COMPLETE");
        }

        @Test
        @DisplayName("DISCOVERY_REQUEST allowed in plaintext from any state")
        void discoveryRequestAllowedFromAnyState() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("DISCOVERY_REQUEST", "peer-1", false);

            // Even from unknown peer
            when(peerManager.getPeerState("peer-1")).thenReturn(null);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertTrue(downstreamCalled.get(), "Downstream should be called for DISCOVERY_REQUEST");
        }
    }

    @Nested
    @DisplayName("Application Message Tests")
    class ApplicationMessageTests {

        @Test
        @DisplayName("PING rejected from unknown peer")
        void pingRejectedFromUnknownPeer() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("PING", "unknown-peer", true);

            when(peerManager.getPeerState("unknown-peer")).thenReturn(null);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertFalse(downstreamCalled.get(), "PING from unknown peer should be rejected");
        }

        @Test
        @DisplayName("PING rejected without valid session")
        void pingRejectedWithoutSession() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("PING", "peer-1", false);  // Not encrypted

            when(peerManager.getPeerState("peer-1")).thenReturn(PeerState.CONNECTED);
            when(securityFacade.hasValidSession("peer-1")).thenReturn(false);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertFalse(downstreamCalled.get(), "PING without session should be rejected");
        }

        @Test
        @DisplayName("PING allowed with valid session and encryption")
        void pingAllowedWithSessionAndEncryption() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("PING", "peer-1", true);

            when(peerManager.getPeerState("peer-1")).thenReturn(PeerState.CONNECTED);
            when(securityFacade.hasValidSession("peer-1")).thenReturn(true);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertTrue(downstreamCalled.get(), "PING with session should be allowed");
        }

        @Test
        @DisplayName("Custom application message rejected in DISCOVERED state")
        void customMessageRejectedInDiscoveredState() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("CUSTOM_APP_MESSAGE", "peer-1", true);

            when(peerManager.getPeerState("peer-1")).thenReturn(PeerState.DISCOVERED);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertFalse(downstreamCalled.get(), "Custom message should be rejected in DISCOVERED state");
        }
    }

    @Nested
    @DisplayName("State Validation Tests")
    class StateValidationTests {

        @Test
        @DisplayName("HANDSHAKE_REQUEST allowed in CHANNEL_ESTABLISHED state")
        void handshakeRequestAllowedAfterChannelEstablished() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("HANDSHAKE_REQUEST", "peer-1", true);

            when(peerManager.getPeerState("peer-1")).thenReturn(PeerState.CHANNEL_ESTABLISHED);
            when(securityFacade.hasValidSession("peer-1")).thenReturn(true);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertTrue(downstreamCalled.get(), "HANDSHAKE_REQUEST should be allowed after channel established");
        }

        @Test
        @DisplayName("HANDSHAKE_REQUEST rejected in DISCOVERED state")
        void handshakeRequestRejectedInDiscoveredState() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("HANDSHAKE_REQUEST", "peer-1", true);

            when(peerManager.getPeerState("peer-1")).thenReturn(PeerState.DISCOVERED);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertFalse(downstreamCalled.get(), "HANDSHAKE_REQUEST should be rejected in DISCOVERED state");
        }

        @Test
        @DisplayName("PING allowed in AUTHENTICATED state")
        void pingAllowedInAuthenticatedState() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("PING", "peer-1", true);

            when(peerManager.getPeerState("peer-1")).thenReturn(PeerState.AUTHENTICATED);
            when(securityFacade.hasValidSession("peer-1")).thenReturn(true);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertTrue(downstreamCalled.get(), "PING should be allowed in AUTHENTICATED state");
        }
    }

    @Nested
    @DisplayName("Encryption Enforcement Tests")
    class EncryptionEnforcementTests {

        @Test
        @DisplayName("Unencrypted PING rejected when encryption required")
        void unencryptedPingRejected() {
            SecurityGateway gateway = createGateway(false);
            Message message = createMessage("PING", "peer-1", false);  // Not encrypted

            when(peerManager.getPeerState("peer-1")).thenReturn(PeerState.CONNECTED);
            when(securityFacade.hasValidSession("peer-1")).thenReturn(true);

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertFalse(downstreamCalled.get(), "Unencrypted PING should be rejected");
        }

        @Test
        @DisplayName("Unencrypted message allowed in legacy mode")
        void unencryptedAllowedInLegacyMode() {
            SecurityGateway gateway = createGateway(true);  // Legacy mode
            Message message = createMessage("PING", "peer-1", false);

            when(peerManager.getPeerState("peer-1")).thenReturn(PeerState.CONNECTED);
            // No session check in legacy mode for encryption

            gateway.handleEnvelope(message, testSource, ProcessingContext.empty());

            assertTrue(downstreamCalled.get(), "Unencrypted message should be allowed in legacy mode");
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Builder requires downstream handler")
        void builderRequiresDownstream() {
            assertThrows(IllegalStateException.class, () -> {
                new SecurityGatewayBuilder()
                        .security(securityFacade)
                        .peerManager(peerManager)
                        .build();
            });
        }

        @Test
        @DisplayName("Builder requires peer manager")
        void builderRequiresPeerManager() {
            assertThrows(IllegalStateException.class, () -> {
                new SecurityGatewayBuilder()
                        .downstream(downstreamHandler)
                        .security(securityFacade)
                        .build();
            });
        }

        @Test
        @DisplayName("Builder requires security facade when not in legacy mode")
        void builderRequiresSecurityFacade() {
            assertThrows(IllegalStateException.class, () -> {
                new SecurityGatewayBuilder()
                        .downstream(downstreamHandler)
                        .peerManager(peerManager)
                        .legacyPlaintextMode(false)
                        .build();
            });
        }

        @Test
        @DisplayName("Builder allows null security in legacy mode")
        void builderAllowsNullSecurityInLegacyMode() {
            SecurityGateway gateway = new SecurityGatewayBuilder()
                    .downstream(downstreamHandler)
                    .peerManager(peerManager)
                    .legacyPlaintextMode(true)
                    .build();

            assertNotNull(gateway);
            assertTrue(gateway.isLegacyPlaintextMode());
        }

        @Test
        @DisplayName("Builder creates default policy if not provided")
        void builderCreatesDefaultPolicy() {
            SecurityGateway gateway = new SecurityGatewayBuilder()
                    .downstream(downstreamHandler)
                    .security(securityFacade)
                    .peerManager(peerManager)
                    .build();

            assertNotNull(gateway);
        }
    }

    @Nested
    @DisplayName("Policy Tests")
    class PolicyTests {

        @Test
        @DisplayName("Policy correctly identifies bootstrap messages")
        void policyIdentifiesBootstrapMessages() {
            assertTrue(policy.isBootstrapMessage("KEY_EXCHANGE_INIT"));
            assertTrue(policy.isBootstrapMessage("KEY_EXCHANGE_COMPLETE"));
            assertFalse(policy.isBootstrapMessage("PING"));
            assertFalse(policy.isBootstrapMessage("HANDSHAKE_REQUEST"));
        }

        @Test
        @DisplayName("Policy correctly identifies discovery messages")
        void policyIdentifiesDiscoveryMessages() {
            assertTrue(policy.isDiscoveryMessage("DISCOVERY_REQUEST"));
            assertTrue(policy.isDiscoveryMessage("DISCOVERY_RESPONSE"));
            assertFalse(policy.isDiscoveryMessage("PING"));
        }

        @Test
        @DisplayName("Policy requires encryption for system messages")
        void policyRequiresEncryptionForSystemMessages() {
            assertTrue(policy.requiresEncryption("PING"));
            assertTrue(policy.requiresEncryption("PONG"));
            assertTrue(policy.requiresEncryption("HEARTBEAT"));
            assertTrue(policy.requiresEncryption("HELLO"));
        }

        @Test
        @DisplayName("Policy allows plaintext for bootstrap messages")
        void policyAllowsPlaintextForBootstrap() {
            assertTrue(policy.isPlaintextAllowed("KEY_EXCHANGE_INIT"));
            assertTrue(policy.isPlaintextAllowed("KEY_EXCHANGE_COMPLETE"));
            assertFalse(policy.isPlaintextAllowed("PING"));
        }

        @Test
        @DisplayName("Unknown message type defaults to strict policy")
        void unknownMessageTypeDefaultsToStrict() {
            assertTrue(policy.requiresEncryption("UNKNOWN_MESSAGE_TYPE"));
            assertFalse(policy.isPlaintextAllowed("UNKNOWN_MESSAGE_TYPE"));
        }
    }
}
