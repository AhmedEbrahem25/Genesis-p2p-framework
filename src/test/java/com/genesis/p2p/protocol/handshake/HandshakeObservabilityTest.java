package com.genesis.p2p.protocol.handshake;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for handshake observability components.
 *
 * Includes:
 * - Unit tests for HandshakeConfig
 * - Unit tests for HandshakeMetrics (Prometheus-style)
 * - Unit tests for HandshakeRetryPolicy with failure injection
 * - Unit tests for HandshakeTimeline
 * - Unit tests for HandshakeDebugCommand
 * - Failure injection scenarios
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HandshakeObservabilityTest {

    // ========================= HandshakeConfig Tests =========================

    @Nested
    @DisplayName("HandshakeConfig Tests")
    class HandshakeConfigTests {

        @Test
        @DisplayName("Should create default config with sensible values")
        void testDefaultConfig() {
            HandshakeConfig config = HandshakeConfig.defaults();

            assertNotNull(config);
            assertEquals(Duration.ofSeconds(5), config.getConnectionTimeout());
            assertEquals(Duration.ofSeconds(10), config.getResponseTimeout());
            assertEquals(Duration.ofSeconds(30), config.getTotalHandshakeTimeout());
            assertEquals(3, config.getMaxRetryAttempts());
            assertEquals(Duration.ofMillis(200), config.getInitialRetryDelay());
            assertTrue(config.getRetryBackoffMultiplier() >= 1.0);
        }

        @Test
        @DisplayName("Should create development config")
        void testDevelopmentConfig() {
            HandshakeConfig config = HandshakeConfig.forDevelopment();

            assertTrue(config.isTimelineEnabled());
            assertTrue(config.isDetailedMetricsEnabled());
            assertEquals(5, config.getMaxRetryAttempts());
        }

        @Test
        @DisplayName("Should create production config")
        void testProductionConfig() {
            HandshakeConfig config = HandshakeConfig.forProduction();

            assertFalse(config.isTimelineEnabled());
            assertTrue(config.isDetailedMetricsEnabled());
            assertEquals(3, config.getMaxRetryAttempts());
        }

        @Test
        @DisplayName("Should create high-latency config")
        void testHighLatencyConfig() {
            HandshakeConfig config = HandshakeConfig.forHighLatency();

            assertEquals(Duration.ofSeconds(15), config.getConnectionTimeout());
            assertEquals(Duration.ofSeconds(30), config.getResponseTimeout());
            assertEquals(5, config.getMaxRetryAttempts());
        }

        @Test
        @DisplayName("Should validate config constraints")
        void testConfigValidation() {
            assertThrows(IllegalArgumentException.class, () ->
                    new HandshakeConfig.Builder()
                            .connectionTimeout(Duration.ofSeconds(20))
                            .responseTimeout(Duration.ofSeconds(20))
                            .totalHandshakeTimeout(Duration.ofSeconds(10)) // Too short
                            .build()
            );
        }

        @Test
        @DisplayName("Should build custom config")
        void testCustomConfig() {
            HandshakeConfig config = new HandshakeConfig.Builder()
                    .connectionTimeout(Duration.ofSeconds(10))
                    .responseTimeout(Duration.ofSeconds(15))
                    .totalHandshakeTimeout(Duration.ofMinutes(1))
                    .maxRetryAttempts(5)
                    .initialRetryDelay(Duration.ofMillis(500))
                    .retryBackoffMultiplier(1.5)
                    .retryJitterFactor(0.15)
                    .enableTimeline(true)
                    .enableDetailedMetrics(true)
                    .build();

            assertEquals(Duration.ofSeconds(10), config.getConnectionTimeout());
            assertEquals(5, config.getMaxRetryAttempts());
            assertEquals(1.5, config.getRetryBackoffMultiplier());
            assertTrue(config.isTimelineEnabled());
        }
    }

    // ========================= HandshakeMetrics Tests =========================

    @Nested
    @DisplayName("HandshakeMetrics Tests")
    class HandshakeMetricsTests {

        private HandshakeMetrics metrics;

        @BeforeEach
        void setUp() {
            metrics = new HandshakeMetrics();
        }

        @Test
        @DisplayName("Should track handshake success")
        void testTrackSuccess() {
            String peerId = "peer-001";

            metrics.recordHandshakeInitiated(peerId);
            metrics.recordHandshakeSuccess(peerId, Duration.ofMillis(150));

            assertEquals(1, metrics.getHandshakeTotal());
            assertEquals(1, metrics.getHandshakeSuccess());
            assertEquals(0, metrics.getHandshakeFailed());
            assertEquals(100.0, metrics.getSuccessRate());
        }

        @Test
        @DisplayName("Should track handshake failure")
        void testTrackFailure() {
            String peerId = "peer-002";

            metrics.recordHandshakeInitiated(peerId);
            metrics.recordHandshakeFailed(peerId, "timeout", Duration.ofMillis(5000));

            assertEquals(1, metrics.getHandshakeTotal());
            assertEquals(0, metrics.getHandshakeSuccess());
            assertEquals(1, metrics.getHandshakeFailed());
            assertEquals(0.0, metrics.getSuccessRate());
        }

        @Test
        @DisplayName("Should track timeouts separately")
        void testTrackTimeouts() {
            String peerId = "peer-003";

            metrics.recordHandshakeInitiated(peerId);
            metrics.recordConnectionTimeout(peerId);

            assertEquals(1, metrics.getHandshakeTimeout());
            assertEquals(1, metrics.getHandshakeFailed());
        }

        @Test
        @DisplayName("Should track deduplicated handshakes")
        void testTrackDeduplicated() {
            String peerId = "peer-004";

            metrics.recordDeduplicated(peerId);

            assertEquals(1, metrics.getHandshakeDeduplicated());
        }

        @Test
        @DisplayName("Should track retries")
        void testTrackRetries() {
            String peerId = "peer-005";

            metrics.recordRetryAttempt(peerId, 1, Duration.ofMillis(200));
            metrics.recordRetryAttempt(peerId, 2, Duration.ofMillis(400));
            metrics.recordRetrySuccess(peerId, 2);

            assertEquals(2, metrics.getRetryTotal());
        }

        @Test
        @DisplayName("Should calculate latency percentiles")
        void testLatencyPercentiles() {
            // Record handshakes with varying durations
            for (int i = 0; i < 100; i++) {
                String peerId = "peer-" + i;
                metrics.recordHandshakeInitiated(peerId);
                // Vary duration: 10ms to 1000ms
                metrics.recordHandshakeSuccess(peerId, Duration.ofMillis(10 + i * 10));
            }

            assertTrue(metrics.getP50LatencyMs() > 0);
            assertTrue(metrics.getP95LatencyMs() > metrics.getP50LatencyMs());
            assertTrue(metrics.getP99LatencyMs() >= metrics.getP95LatencyMs());
        }

        @Test
        @DisplayName("Should export Prometheus format")
        void testPrometheusExport() {
            metrics.recordHandshakeInitiated("peer-001");
            metrics.recordHandshakeSuccess("peer-001", Duration.ofMillis(100));

            String prometheus = metrics.toPrometheusFormat();

            assertNotNull(prometheus);
            assertTrue(prometheus.contains("genesis_handshake_total"));
            assertTrue(prometheus.contains("genesis_handshake_success_total"));
            assertTrue(prometheus.contains("# TYPE"));
            assertTrue(prometheus.contains("# HELP"));
        }

        @Test
        @DisplayName("Should get summary snapshot")
        void testSummarySnapshot() {
            metrics.recordHandshakeInitiated("peer-001");
            metrics.recordHandshakeSuccess("peer-001", Duration.ofMillis(100));

            HandshakeMetrics.MetricsSummary summary = metrics.getSummary();

            assertNotNull(summary);
            assertEquals(1, summary.total());
            assertEquals(1, summary.success());
            assertEquals(0, summary.failed());
            assertTrue(summary.successRate() > 0);
        }

        @Test
        @DisplayName("Should track per-peer metrics")
        void testPerPeerMetrics() {
            metrics.recordHandshakeInitiated("peer-001");
            metrics.recordHandshakeSuccess("peer-001", Duration.ofMillis(100));
            metrics.recordHandshakeInitiated("peer-001");
            metrics.recordHandshakeFailed("peer-001", "error", Duration.ofMillis(200));

            var peerMetrics = metrics.getPerPeerMetrics();

            assertTrue(peerMetrics.containsKey("peer-001"));
            assertEquals(2, peerMetrics.get("peer-001").initiated.sum());
            assertEquals(1, peerMetrics.get("peer-001").success.sum());
            assertEquals(1, peerMetrics.get("peer-001").failed.sum());
        }

        @Test
        @DisplayName("Should reset metrics")
        void testReset() {
            metrics.recordHandshakeInitiated("peer-001");
            metrics.recordHandshakeSuccess("peer-001", Duration.ofMillis(100));
            metrics.reset();

            assertEquals(0, metrics.getHandshakeTotal());
            assertEquals(0, metrics.getHandshakeSuccess());
        }
    }

    // ========================= HandshakeRetryPolicy Tests =========================

    @Nested
    @DisplayName("HandshakeRetryPolicy Tests")
    class HandshakeRetryPolicyTests {

        private HandshakeConfig config;
        private HandshakeMetrics metrics;
        private HandshakeRetryPolicy retryPolicy;
        private ScheduledExecutorService scheduler;

        @BeforeEach
        void setUp() {
            config = new HandshakeConfig.Builder()
                    .maxRetryAttempts(3)
                    .initialRetryDelay(Duration.ofMillis(10))
                    .maxRetryDelay(Duration.ofMillis(100))
                    .retryBackoffMultiplier(2.0)
                    .retryJitterFactor(0.0) // No jitter for deterministic tests
                    .build();
            metrics = new HandshakeMetrics();
            scheduler = Executors.newScheduledThreadPool(2);
            retryPolicy = new HandshakeRetryPolicy(config, metrics, scheduler);
        }

        @AfterEach
        void tearDown() {
            retryPolicy.shutdown();
            scheduler.shutdownNow();
        }

        @Test
        @DisplayName("Should calculate exponential backoff delays")
        void testExponentialBackoff() {
            Duration delay1 = retryPolicy.calculateDelay(1);
            Duration delay2 = retryPolicy.calculateDelay(2);
            Duration delay3 = retryPolicy.calculateDelay(3);

            // With multiplier 2.0: 10ms, 20ms, 40ms
            assertTrue(delay1.toMillis() >= 10);
            assertTrue(delay2.toMillis() >= delay1.toMillis());
            assertTrue(delay3.toMillis() >= delay2.toMillis());
        }

        @Test
        @DisplayName("Should succeed on first attempt")
        void testSuccessFirstAttempt() throws Exception {
            CompletableFuture<String> successOperation =
                    CompletableFuture.completedFuture("success");

            var result = retryPolicy.executeWithRetry("peer-001", () -> successOperation)
                    .get(1, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertEquals(1, result.getAttempts());
            assertEquals("success", result.getValue());
        }

        @Test
        @DisplayName("Should retry on failure and eventually succeed")
        void testRetryThenSuccess() throws Exception {
            AtomicInteger attempts = new AtomicInteger(0);

            var result = retryPolicy.executeWithRetry("peer-002", () -> {
                if (attempts.incrementAndGet() < 2) {
                    CompletableFuture<String> future = new CompletableFuture<>();
                    future.completeExceptionally(new TimeoutException("Simulated timeout"));
                    return future;
                }
                return CompletableFuture.completedFuture("success");
            }).get(5, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertEquals(2, result.getAttempts());
        }

        @Test
        @DisplayName("Should fail after retries on persistent failure")
        void testRetryExhausted() throws Exception {
            var result = retryPolicy.executeWithRetry("peer-003", () -> {
                CompletableFuture<String> future = new CompletableFuture<>();
                future.completeExceptionally(new TimeoutException("Persistent failure"));
                return future;
            }).get(10, TimeUnit.SECONDS);

            assertFalse(result.isSuccess());
            // FAILED is returned when all retries are done through the loop
            assertEquals(HandshakeRetryPolicy.RetryResult.Status.FAILED, result.getStatus());
        }

        @Test
        @DisplayName("Should blacklist after repeated failures")
        void testBlacklisting() throws Exception {
            // Exhaust retries
            retryPolicy.executeWithRetry("peer-004", () -> {
                CompletableFuture<String> future = new CompletableFuture<>();
                future.completeExceptionally(new TimeoutException("Failure"));
                return future;
            }).get(10, TimeUnit.SECONDS);

            // Try again - should be blacklisted
            var result = retryPolicy.executeWithRetry("peer-004", () ->
                    CompletableFuture.completedFuture("success")
            ).get(1, TimeUnit.SECONDS);

            assertEquals(HandshakeRetryPolicy.RetryResult.Status.BLACKLISTED, result.getStatus());
            assertTrue(retryPolicy.isBlacklisted("peer-004"));
        }

        @Test
        @DisplayName("Should remove from blacklist")
        void testRemoveFromBlacklist() {
            retryPolicy.addToBlacklist("peer-005");
            assertTrue(retryPolicy.isBlacklisted("peer-005"));

            retryPolicy.removeFromBlacklist("peer-005");
            assertFalse(retryPolicy.isBlacklisted("peer-005"));
        }

        @Test
        @DisplayName("Should not retry non-retryable errors")
        void testNonRetryableError() throws Exception {
            var result = retryPolicy.executeWithRetry("peer-006", () -> {
                CompletableFuture<String> future = new CompletableFuture<>();
                // IllegalArgumentException is not retryable
                future.completeExceptionally(new IllegalArgumentException("Bad argument"));
                return future;
            }).get(1, TimeUnit.SECONDS);

            assertEquals(HandshakeRetryPolicy.RetryResult.Status.FAILED, result.getStatus());
            assertEquals(1, result.getAttempts()); // Only one attempt, no retry
        }
    }

    // ========================= HandshakeTimeline Tests =========================

    @Nested
    @DisplayName("HandshakeTimeline Tests")
    class HandshakeTimelineTests {

        private HandshakeTimeline timeline;

        @BeforeEach
        void setUp() {
            timeline = new HandshakeTimeline("node-001", true);
        }

        @Test
        @DisplayName("Should create and track timeline")
        void testCreateTimeline() {
            String handshakeId = timeline.startHandshake("peer-001", true);

            assertNotNull(handshakeId);
            assertTrue(handshakeId.startsWith("hs-"));

            var activeTimelines = timeline.getActiveTimelines();
            assertEquals(1, activeTimelines.size());
        }

        @Test
        @DisplayName("Should record events")
        void testRecordEvents() {
            String handshakeId = timeline.startHandshake("peer-001", true);

            timeline.recordEvent(handshakeId, HandshakeTimeline.Phase.CONNECTING, "Connecting to peer");
            timeline.recordEvent(handshakeId, HandshakeTimeline.Phase.REQUEST_SENT, "Request sent");
            timeline.recordEvent(handshakeId, HandshakeTimeline.Phase.RESPONSE_RECEIVED, "Response received");

            var completed = timeline.completeHandshake(handshakeId, true);

            assertNotNull(completed);
            assertTrue(completed.isSuccess());
            assertEquals(5, completed.getEvents().size()); // Started + 3 events + Completed
        }

        @Test
        @DisplayName("Should record errors")
        void testRecordError() {
            String handshakeId = timeline.startHandshake("peer-001", true);

            timeline.recordError(handshakeId, "Connection refused", new java.io.IOException("Connection refused"));
            var completed = timeline.completeHandshake(handshakeId, false);

            assertNotNull(completed);
            assertFalse(completed.isSuccess());
        }

        @Test
        @DisplayName("Should generate ASCII timeline")
        void testAsciiTimeline() {
            String handshakeId = timeline.startHandshake("peer-001", true);
            timeline.recordEvent(handshakeId, HandshakeTimeline.Phase.CONNECTING, "Connecting");
            timeline.recordEvent(handshakeId, HandshakeTimeline.Phase.KEY_EXCHANGE, "Exchanging keys");
            timeline.completeHandshake(handshakeId, true);

            String ascii = timeline.getAsciiTimeline(handshakeId);

            assertNotNull(ascii);
            assertTrue(ascii.contains("HANDSHAKE TIMELINE"));
            assertTrue(ascii.contains("SUCCESS"));
            assertTrue(ascii.contains("Connecting"));
        }

        @Test
        @DisplayName("Should generate JSON timeline")
        void testJsonTimeline() {
            String handshakeId = timeline.startHandshake("peer-001", true);
            timeline.recordEvent(handshakeId, HandshakeTimeline.Phase.CONNECTING, "Connecting");
            timeline.completeHandshake(handshakeId, true);

            String json = timeline.getJsonTimeline(handshakeId);

            assertNotNull(json);
            assertTrue(json.contains("\"handshakeId\""));
            assertTrue(json.contains("\"events\""));
            // Gson adds space after colon
            assertTrue(json.contains("\"success\": true"));
        }

        @Test
        @DisplayName("Should track multiple concurrent handshakes")
        void testConcurrentHandshakes() throws InterruptedException {
            int handshakeCount = 10;
            CountDownLatch latch = new CountDownLatch(handshakeCount);
            ExecutorService executor = Executors.newFixedThreadPool(5);

            for (int i = 0; i < handshakeCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        String id = timeline.startHandshake("peer-" + index, index % 2 == 0);
                        Thread.sleep(10);
                        timeline.recordEvent(id, HandshakeTimeline.Phase.CONNECTING, "Test");
                        Thread.sleep(10);
                        timeline.completeHandshake(id, true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            var completed = timeline.getRecentCompletedTimelines(20);
            assertEquals(handshakeCount, completed.size());
        }

        @Test
        @DisplayName("Should limit completed timeline history")
        void testHistoryLimit() {
            // Create more timelines than history limit (100)
            for (int i = 0; i < 120; i++) {
                String id = timeline.startHandshake("peer-" + i, true);
                timeline.completeHandshake(id, true);
            }

            var recent = timeline.getRecentCompletedTimelines(200);
            assertTrue(recent.size() <= 100); // Max history is 100
        }

        @Test
        @DisplayName("Should return null when disabled")
        void testDisabledTimeline() {
            HandshakeTimeline disabled = new HandshakeTimeline("node-001", false);

            String handshakeId = disabled.startHandshake("peer-001", true);
            assertNull(handshakeId);
        }
    }

    // ========================= HandshakeDebugCommand Tests =========================

    @Nested
    @DisplayName("HandshakeDebugCommand Tests")
    class HandshakeDebugCommandTests {

        private HandshakeMetrics metrics;
        private HandshakeTimeline timeline;
        private HandshakeConfig config;
        private HandshakeRetryPolicy retryPolicy;
        private HandshakeDebugCommand debugCommand;
        private ByteArrayOutputStream outputStream;
        private PrintStream printStream;

        @BeforeEach
        void setUp() {
            metrics = new HandshakeMetrics();
            timeline = new HandshakeTimeline("node-001", true);
            config = HandshakeConfig.defaults();
            retryPolicy = new HandshakeRetryPolicy(config, metrics);

            outputStream = new ByteArrayOutputStream();
            printStream = new PrintStream(outputStream);

            // Create a mock processor for stats
            debugCommand = new HandshakeDebugCommand(
                    null, // processor - we'll handle null gracefully
                    metrics,
                    timeline,
                    retryPolicy,
                    config,
                    printStream
            );
        }

        @AfterEach
        void tearDown() {
            retryPolicy.shutdown();
        }

        @Test
        @DisplayName("Should show help")
        void testShowHelp() {
            debugCommand.execute("help");

            String output = outputStream.toString();
            assertTrue(output.contains("Usage:"));
            assertTrue(output.contains("status"));
            assertTrue(output.contains("metrics"));
            assertTrue(output.contains("timeline"));
        }

        @Test
        @DisplayName("Should show metrics")
        void testShowMetrics() {
            metrics.recordHandshakeInitiated("peer-001");
            metrics.recordHandshakeSuccess("peer-001", Duration.ofMillis(100));

            debugCommand.execute("metrics");

            String output = outputStream.toString();
            assertTrue(output.contains("HANDSHAKE METRICS"));
            assertTrue(output.contains("handshake_total"));
            assertTrue(output.contains("handshake_success_total"));
        }

        @Test
        @DisplayName("Should show Prometheus format")
        void testShowPrometheus() {
            metrics.recordHandshakeInitiated("peer-001");
            metrics.recordHandshakeSuccess("peer-001", Duration.ofMillis(100));

            debugCommand.execute("prometheus");

            String output = outputStream.toString();
            assertTrue(output.contains("# HELP"));
            assertTrue(output.contains("# TYPE"));
            assertTrue(output.contains("genesis_handshake"));
        }

        @Test
        @DisplayName("Should show peer metrics")
        void testShowPeerMetrics() {
            metrics.recordHandshakeInitiated("peer-001");
            metrics.recordHandshakeSuccess("peer-001", Duration.ofMillis(100));

            debugCommand.execute("peers");

            String output = outputStream.toString();
            assertTrue(output.contains("PER-PEER METRICS"));
        }

        @Test
        @DisplayName("Should show config")
        void testShowConfig() {
            debugCommand.execute("config");

            String output = outputStream.toString();
            assertTrue(output.contains("HANDSHAKE CONFIGURATION"));
            assertTrue(output.contains("TIMEOUTS"));
            assertTrue(output.contains("RETRY POLICY"));
        }

        @Test
        @DisplayName("Should show blacklist")
        void testShowBlacklist() {
            retryPolicy.addToBlacklist("peer-001");

            debugCommand.execute("blacklist");

            String output = outputStream.toString();
            assertTrue(output.contains("BLACKLISTED PEERS"));
            assertTrue(output.contains("1")); // One blacklisted
        }

        @Test
        @DisplayName("Should clear blacklist")
        void testClearBlacklist() {
            retryPolicy.addToBlacklist("peer-001");
            assertTrue(retryPolicy.isBlacklisted("peer-001"));

            debugCommand.execute("clear-blacklist", "peer-001");

            assertFalse(retryPolicy.isBlacklisted("peer-001"));
        }

        @Test
        @DisplayName("Should show timeline summary")
        void testShowTimelineSummary() {
            String id = timeline.startHandshake("peer-001", true);
            timeline.completeHandshake(id, true);

            debugCommand.execute("timeline");

            String output = outputStream.toString();
            assertTrue(output.length() > 0);
        }

        @Test
        @DisplayName("Should show history")
        void testShowHistory() {
            String id = timeline.startHandshake("peer-001", true);
            timeline.completeHandshake(id, true);

            debugCommand.execute("history", "5");

            String output = outputStream.toString();
            assertTrue(output.contains("RECENT HANDSHAKES"));
        }
    }

    // ========================= Failure Injection Tests =========================

    @Nested
    @DisplayName("Failure Injection Tests")
    class FailureInjectionTests {

        private HandshakeConfig config;
        private HandshakeMetrics metrics;
        private HandshakeRetryPolicy retryPolicy;
        private ScheduledExecutorService scheduler;

        @BeforeEach
        void setUp() {
            config = new HandshakeConfig.Builder()
                    .maxRetryAttempts(3)
                    .initialRetryDelay(Duration.ofMillis(5))
                    .maxRetryDelay(Duration.ofMillis(50))
                    .build();
            metrics = new HandshakeMetrics();
            scheduler = Executors.newScheduledThreadPool(2);
            retryPolicy = new HandshakeRetryPolicy(config, metrics, scheduler);
        }

        @AfterEach
        void tearDown() {
            retryPolicy.shutdown();
            scheduler.shutdownNow();
        }

        @Test
        @DisplayName("Should handle connection refused")
        void testConnectionRefused() throws Exception {
            var result = retryPolicy.executeWithRetry("peer-conn-refused", () -> {
                CompletableFuture<String> future = new CompletableFuture<>();
                future.completeExceptionally(new java.net.ConnectException("Connection refused"));
                return future;
            }).get(10, TimeUnit.SECONDS);

            assertFalse(result.isSuccess());
            // FAILED is returned when retries complete through the loop
            assertEquals(HandshakeRetryPolicy.RetryResult.Status.FAILED, result.getStatus());
            assertEquals(3, result.getAttempts()); // Retried 3 times
        }

        @Test
        @DisplayName("Should handle socket timeout")
        void testSocketTimeout() throws Exception {
            var result = retryPolicy.executeWithRetry("peer-socket-timeout", () -> {
                CompletableFuture<String> future = new CompletableFuture<>();
                future.completeExceptionally(new java.net.SocketTimeoutException("Read timed out"));
                return future;
            }).get(10, TimeUnit.SECONDS);

            assertFalse(result.isSuccess());
            assertEquals(3, result.getAttempts()); // Socket timeout is retryable
        }

        @Test
        @DisplayName("Should handle intermittent failures")
        void testIntermittentFailure() throws Exception {
            AtomicInteger failures = new AtomicInteger(0);

            var result = retryPolicy.executeWithRetry("peer-intermittent", () -> {
                if (failures.incrementAndGet() <= 2) {
                    // Fail first 2 attempts
                    CompletableFuture<String> future = new CompletableFuture<>();
                    future.completeExceptionally(new java.io.IOException("Network error"));
                    return future;
                }
                return CompletableFuture.completedFuture("recovered");
            }).get(10, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertEquals("recovered", result.getValue());
            assertEquals(3, result.getAttempts()); // 2 failures + 1 success
        }

        @Test
        @DisplayName("Should track failure metrics correctly")
        void testFailureMetrics() throws Exception {
            // Generate various failure scenarios
            for (int i = 0; i < 5; i++) {
                String peerId = "fail-peer-" + i;
                metrics.recordHandshakeInitiated(peerId);
                metrics.recordHandshakeFailed(peerId, "test-failure", Duration.ofMillis(100));
            }

            for (int i = 0; i < 3; i++) {
                String peerId = "timeout-peer-" + i;
                metrics.recordHandshakeInitiated(peerId);
                metrics.recordConnectionTimeout(peerId);
            }

            assertEquals(8, metrics.getHandshakeTotal());
            assertEquals(8, metrics.getHandshakeFailed());
            assertEquals(3, metrics.getHandshakeTimeout());
            assertEquals(0.0, metrics.getSuccessRate());
        }

        @Test
        @DisplayName("Should handle rapid concurrent failures")
        void testRapidConcurrentFailures() throws Exception {
            int concurrency = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(concurrency);
            ExecutorService executor = Executors.newFixedThreadPool(10);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            for (int i = 0; i < concurrency; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        var result = retryPolicy.executeWithRetry("concurrent-" + index, () -> {
                            // 50% failure rate
                            if (index % 2 == 0) {
                                CompletableFuture<String> future = new CompletableFuture<>();
                                future.completeExceptionally(new TimeoutException("Simulated"));
                                return future;
                            }
                            return CompletableFuture.completedFuture("ok");
                        }).get(15, TimeUnit.SECONDS);

                        if (result.isSuccess()) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            // Verify we processed all requests
            assertEquals(concurrency, successCount.get() + failCount.get());

            // Half should have succeeded (odd indices)
            assertEquals(10, successCount.get());
        }

        @Test
        @DisplayName("Should recover from partial failure sequence")
        void testPartialFailureRecovery() throws Exception {
            AtomicInteger callCount = new AtomicInteger(0);

            // Fail, succeed, fail, succeed pattern
            var result = retryPolicy.executeWithRetry("partial-fail", () -> {
                int count = callCount.incrementAndGet();
                if (count == 1) {
                    CompletableFuture<String> future = new CompletableFuture<>();
                    future.completeExceptionally(new TimeoutException("First failure"));
                    return future;
                }
                return CompletableFuture.completedFuture("recovered-at-" + count);
            }).get(10, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertEquals("recovered-at-2", result.getValue());
        }
    }
}
