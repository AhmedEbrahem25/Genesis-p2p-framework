package com.genesis.p2p.protocol.handshake;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for HANDSHAKE_REJECT functionality.
 *
 * Includes:
 * - Unit tests for HandshakeReject model
 * - Rejection reason tests
 * - Metrics tests for rejection tracking
 * - Retry/permanent rejection categorization
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HandshakeRejectTest {

    // ========================= HandshakeReject Model Tests =========================

    @Nested
    @DisplayName("HandshakeReject Model Tests")
    class HandshakeRejectModelTests {

        @Test
        @DisplayName("Should build rejection with required fields")
        void testBuildWithRequiredFields() {
            HandshakeReject reject = new HandshakeReject.Builder()
                    .nodeId("node-123")
                    .correlationId("corr-456")
                    .reason(HandshakeReject.RejectionReason.RATE_LIMITED)
                    .build();

            assertNotNull(reject);
            assertEquals("node-123", reject.getNodeId());
            assertEquals("corr-456", reject.getCorrelationId());
            assertEquals(HandshakeReject.RejectionReason.RATE_LIMITED, reject.getReason());
            assertNotNull(reject.getMessage());
            assertTrue(reject.getTimestamp() > 0);
        }

        @Test
        @DisplayName("Should throw when missing required fields")
        void testMissingRequiredFields() {
            // Missing reason
            assertThrows(NullPointerException.class, () ->
                    new HandshakeReject.Builder()
                            .nodeId("node-123")
                            .build()
            );

            // Missing nodeId
            assertThrows(NullPointerException.class, () ->
                    new HandshakeReject.Builder()
                            .reason(HandshakeReject.RejectionReason.RATE_LIMITED)
                            .build()
            );
        }

        @Test
        @DisplayName("Should use default message from reason")
        void testDefaultMessageFromReason() {
            HandshakeReject reject = new HandshakeReject.Builder()
                    .nodeId("node-123")
                    .reason(HandshakeReject.RejectionReason.PROTOCOL_MISMATCH)
                    .build();

            assertEquals("Protocol version incompatible", reject.getMessage());
        }

        @Test
        @DisplayName("Should allow custom message")
        void testCustomMessage() {
            HandshakeReject reject = new HandshakeReject.Builder()
                    .nodeId("node-123")
                    .reason(HandshakeReject.RejectionReason.VALIDATION_FAILED)
                    .message("Custom validation error message")
                    .build();

            assertEquals("Custom validation error message", reject.getMessage());
        }

        @Test
        @DisplayName("Should store metadata")
        void testMetadata() {
            HandshakeReject reject = new HandshakeReject.Builder()
                    .nodeId("node-123")
                    .reason(HandshakeReject.RejectionReason.PROTOCOL_MISMATCH)
                    .addMetadata("expectedVersion", "2.0")
                    .addMetadata("actualVersion", "1.0")
                    .build();

            Map<String, String> metadata = reject.getMetadata();
            assertEquals("2.0", metadata.get("expectedVersion"));
            assertEquals("1.0", metadata.get("actualVersion"));
        }
    }

    // ========================= Rejection Reason Tests =========================

    @Nested
    @DisplayName("Rejection Reason Tests")
    class RejectionReasonTests {

        @Test
        @DisplayName("Should correctly identify retryable reasons")
        void testRetryableReasons() {
            // Retryable
            assertTrue(buildReject(HandshakeReject.RejectionReason.RATE_LIMITED).isRetryable());
            assertTrue(buildReject(HandshakeReject.RejectionReason.RESOURCE_EXHAUSTED).isRetryable());
            assertTrue(buildReject(HandshakeReject.RejectionReason.INTERNAL_ERROR).isRetryable());

            // Not retryable
            assertFalse(buildReject(HandshakeReject.RejectionReason.BLACKLISTED).isRetryable());
            assertFalse(buildReject(HandshakeReject.RejectionReason.PROTOCOL_MISMATCH).isRetryable());
            assertFalse(buildReject(HandshakeReject.RejectionReason.AUTH_FAILED).isRetryable());
            assertFalse(buildReject(HandshakeReject.RejectionReason.VALIDATION_FAILED).isRetryable());
            assertFalse(buildReject(HandshakeReject.RejectionReason.SESSION_ERROR).isRetryable());
        }

        @Test
        @DisplayName("Should correctly identify permanent reasons")
        void testPermanentReasons() {
            // Permanent
            assertTrue(buildReject(HandshakeReject.RejectionReason.BLACKLISTED).isPermanent());
            assertTrue(buildReject(HandshakeReject.RejectionReason.PROTOCOL_MISMATCH).isPermanent());

            // Not permanent
            assertFalse(buildReject(HandshakeReject.RejectionReason.RATE_LIMITED).isPermanent());
            assertFalse(buildReject(HandshakeReject.RejectionReason.RESOURCE_EXHAUSTED).isPermanent());
            assertFalse(buildReject(HandshakeReject.RejectionReason.INTERNAL_ERROR).isPermanent());
            assertFalse(buildReject(HandshakeReject.RejectionReason.AUTH_FAILED).isPermanent());
        }

        @Test
        @DisplayName("Each reason should have a default message")
        void testDefaultMessages() {
            for (HandshakeReject.RejectionReason reason : HandshakeReject.RejectionReason.values()) {
                assertNotNull(reason.getDefaultMessage(),
                        "Reason " + reason + " should have default message");
                assertFalse(reason.getDefaultMessage().isEmpty(),
                        "Reason " + reason + " should have non-empty default message");
            }
        }

        private HandshakeReject buildReject(HandshakeReject.RejectionReason reason) {
            return new HandshakeReject.Builder()
                    .nodeId("test-node")
                    .reason(reason)
                    .build();
        }
    }

    // ========================= Factory Method Tests =========================

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("Should create rate limited rejection")
        void testRateLimitedFactory() {
            HandshakeReject reject = HandshakeReject.rateLimited("node-123", "corr-456");

            assertEquals(HandshakeReject.RejectionReason.RATE_LIMITED, reject.getReason());
            assertEquals("node-123", reject.getNodeId());
            assertEquals("corr-456", reject.getCorrelationId());
        }

        @Test
        @DisplayName("Should create protocol mismatch rejection")
        void testProtocolMismatchFactory() {
            HandshakeReject reject = HandshakeReject.protocolMismatch("node-123", "corr-456", "2.0");

            assertEquals(HandshakeReject.RejectionReason.PROTOCOL_MISMATCH, reject.getReason());
            assertTrue(reject.getMessage().contains("2.0"));
            assertEquals("2.0", reject.getMetadata().get("expectedVersion"));
        }

        @Test
        @DisplayName("Should create validation failed rejection")
        void testValidationFailedFactory() {
            HandshakeReject reject = HandshakeReject.validationFailed("node-123", "corr-456",
                    "Missing public key");

            assertEquals(HandshakeReject.RejectionReason.VALIDATION_FAILED, reject.getReason());
            assertEquals("Missing public key", reject.getMessage());
        }

        @Test
        @DisplayName("Should create session error rejection")
        void testSessionErrorFactory() {
            HandshakeReject reject = HandshakeReject.sessionError("node-123", "corr-456",
                    "ECDH key derivation failed");

            assertEquals(HandshakeReject.RejectionReason.SESSION_ERROR, reject.getReason());
            assertTrue(reject.getMessage().contains("ECDH key derivation failed"));
        }

        @Test
        @DisplayName("Should create blacklisted rejection")
        void testBlacklistedFactory() {
            HandshakeReject reject = HandshakeReject.blacklisted("node-123", "corr-456");

            assertEquals(HandshakeReject.RejectionReason.BLACKLISTED, reject.getReason());
            assertTrue(reject.isPermanent());
        }

        @Test
        @DisplayName("Should create auth failed rejection")
        void testAuthFailedFactory() {
            HandshakeReject reject = HandshakeReject.authFailed("node-123", "corr-456",
                    "Invalid signature");

            assertEquals(HandshakeReject.RejectionReason.AUTH_FAILED, reject.getReason());
            assertEquals("Invalid signature", reject.getMessage());
        }

        @Test
        @DisplayName("Should create resource exhausted rejection")
        void testResourceExhaustedFactory() {
            HandshakeReject reject = HandshakeReject.resourceExhausted("node-123", "corr-456");

            assertEquals(HandshakeReject.RejectionReason.RESOURCE_EXHAUSTED, reject.getReason());
            assertTrue(reject.isRetryable());
        }

        @Test
        @DisplayName("Should create internal error rejection")
        void testInternalErrorFactory() {
            HandshakeReject reject = HandshakeReject.internalError("node-123", "corr-456",
                    "Unexpected exception");

            assertEquals(HandshakeReject.RejectionReason.INTERNAL_ERROR, reject.getReason());
            assertTrue(reject.getMessage().contains("Unexpected exception"));
            assertTrue(reject.isRetryable());
        }
    }

    // ========================= Metrics Tests =========================

    @Nested
    @DisplayName("Rejection Metrics Tests")
    class RejectionMetricsTests {

        private HandshakeMetrics metrics;

        @BeforeEach
        void setUp() {
            metrics = new HandshakeMetrics();
        }

        @Test
        @DisplayName("Should track rejection sent")
        void testRejectionSentMetrics() {
            metrics.recordRejectionSent("peer-1", HandshakeReject.RejectionReason.RATE_LIMITED);
            metrics.recordRejectionSent("peer-2", HandshakeReject.RejectionReason.PROTOCOL_MISMATCH);

            assertEquals(2, metrics.getRejectionSentTotal());
        }

        @Test
        @DisplayName("Should track rejection received")
        void testRejectionReceivedMetrics() {
            metrics.recordRejectionReceived("peer-1",
                    HandshakeReject.RejectionReason.BLACKLISTED,
                    Duration.ofMillis(100));
            metrics.recordRejectionReceived("peer-2",
                    HandshakeReject.RejectionReason.VALIDATION_FAILED,
                    Duration.ofMillis(200));

            assertEquals(2, metrics.getRejectionReceivedTotal());
            assertEquals(2, metrics.getHandshakeFailed()); // Rejections count as failures
        }

        @Test
        @DisplayName("Should track rejections by reason")
        void testRejectionByReason() {
            metrics.recordRejectionSent("peer-1", HandshakeReject.RejectionReason.RATE_LIMITED);
            metrics.recordRejectionSent("peer-2", HandshakeReject.RejectionReason.RATE_LIMITED);
            metrics.recordRejectionSent("peer-3", HandshakeReject.RejectionReason.PROTOCOL_MISMATCH);

            Map<String, Long> byReason = metrics.getRejectionByReasonCounts();
            assertEquals(2, byReason.get("RATE_LIMITED"));
            assertEquals(1, byReason.get("PROTOCOL_MISMATCH"));
        }

        @Test
        @DisplayName("Should export rejections in Prometheus format")
        void testPrometheusExport() {
            metrics.recordRejectionSent("peer-1", HandshakeReject.RejectionReason.RATE_LIMITED);
            metrics.recordRejectionReceived("peer-2",
                    HandshakeReject.RejectionReason.BLACKLISTED,
                    Duration.ofMillis(100));

            String prometheus = metrics.toPrometheusFormat();

            assertTrue(prometheus.contains("genesis_handshake_rejection_sent_total"));
            assertTrue(prometheus.contains("genesis_handshake_rejection_received_total"));
            assertTrue(prometheus.contains("genesis_handshake_rejection_total{reason="));
        }

        @Test
        @DisplayName("Should reset rejection metrics")
        void testReset() {
            metrics.recordRejectionSent("peer-1", HandshakeReject.RejectionReason.RATE_LIMITED);
            metrics.recordRejectionReceived("peer-2",
                    HandshakeReject.RejectionReason.BLACKLISTED,
                    Duration.ofMillis(100));

            assertEquals(1, metrics.getRejectionSentTotal());
            assertEquals(1, metrics.getRejectionReceivedTotal());

            metrics.reset();

            assertEquals(0, metrics.getRejectionSentTotal());
            assertEquals(0, metrics.getRejectionReceivedTotal());
            assertTrue(metrics.getRejectionByReasonCounts().isEmpty());
        }
    }

    // ========================= Equality and HashCode Tests =========================

    @Nested
    @DisplayName("Equality and HashCode Tests")
    class EqualityTests {

        @Test
        @DisplayName("Should be equal when key fields match")
        void testEquality() {
            long timestamp = System.currentTimeMillis();

            HandshakeReject reject1 = new HandshakeReject.Builder()
                    .nodeId("node-123")
                    .correlationId("corr-456")
                    .reason(HandshakeReject.RejectionReason.RATE_LIMITED)
                    .timestamp(timestamp)
                    .build();

            HandshakeReject reject2 = new HandshakeReject.Builder()
                    .nodeId("node-123")
                    .correlationId("corr-456")
                    .reason(HandshakeReject.RejectionReason.RATE_LIMITED)
                    .timestamp(timestamp)
                    .build();

            assertEquals(reject1, reject2);
            assertEquals(reject1.hashCode(), reject2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when reason differs")
        void testInequalityByReason() {
            long timestamp = System.currentTimeMillis();

            HandshakeReject reject1 = new HandshakeReject.Builder()
                    .nodeId("node-123")
                    .correlationId("corr-456")
                    .reason(HandshakeReject.RejectionReason.RATE_LIMITED)
                    .timestamp(timestamp)
                    .build();

            HandshakeReject reject2 = new HandshakeReject.Builder()
                    .nodeId("node-123")
                    .correlationId("corr-456")
                    .reason(HandshakeReject.RejectionReason.BLACKLISTED)
                    .timestamp(timestamp)
                    .build();

            assertNotEquals(reject1, reject2);
        }

        @Test
        @DisplayName("Should have meaningful toString")
        void testToString() {
            HandshakeReject reject = new HandshakeReject.Builder()
                    .nodeId("node-123")
                    .correlationId("corr-456")
                    .reason(HandshakeReject.RejectionReason.PROTOCOL_MISMATCH)
                    .message("Test message")
                    .build();

            String str = reject.toString();
            assertTrue(str.contains("PROTOCOL_MISMATCH"));
            assertTrue(str.contains("node-123"));
            assertTrue(str.contains("corr-456"));
        }
    }
}
