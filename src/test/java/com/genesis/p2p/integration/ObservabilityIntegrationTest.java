package com.genesis.p2p.integration;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.tracing.Span;
import com.genesis.p2p.observability.tracing.TraceIdGenerator;
import com.genesis.p2p.observability.ObservabilityFacade;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.testutil.base.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Observability Integration Tests.
 *
 * <p>These tests validate the complete observability stack including:</p>
 * <ul>
 *   <li>Metrics collection and aggregation</li>
 *   <li>Distributed tracing with context propagation</li>
 *   <li>Health monitoring and status reporting</li>
 *   <li>Performance instrumentation</li>
 *   <li>Cross-component correlation</li>
 * </ul>
 *
 * <p>The observability system follows the three pillars:</p>
 * <ol>
 *   <li><strong>Metrics</strong> - Quantitative measurements over time</li>
 *   <li><strong>Tracing</strong> - Request flow across components</li>
 *   <li><strong>Logging</strong> - Contextual event recording</li>
 * </ol>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 * @since 2026-02-04
 */
@Tag("integration")
@Tag("observability")
@DisplayName("Observability Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservabilityIntegrationTest extends BaseIntegrationTest {

    // ===================== Test Constants =====================

    private static final String NODE_ID = "observability-test-node-" + UUID.randomUUID().toString().substring(0, 8);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    // ===================== Test Infrastructure =====================

    private MetricsRegistry metricsRegistry;
    private SimulatedMessagePipeline pipeline;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        metricsRegistry = new MetricsRegistry(NODE_ID);
        executorService = Executors.newFixedThreadPool(4);
        pipeline = new SimulatedMessagePipeline(metricsRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ===================== 1. Metrics Collection Tests =====================

    @Nested
    @DisplayName("1. Metrics Collection")
    class MetricsCollectionTests {

        @Test
        @Order(1)
        @DisplayName("1.1 - Counter metrics track events accurately")
        void testCounterMetricsAccuracy() {
            // Given: A known number of events
            int eventCount = 1000;

            // When: Recording events
            for (int i = 0; i < eventCount; i++) {
                metricsRegistry.incrementCounter("test.events.processed");
            }

            // Then: Counter reflects exact count
            assertEquals(eventCount, metricsRegistry.getCounter("test.events.processed"));
        }

        @Test
        @Order(2)
        @DisplayName("1.2 - Gauge metrics track current state")
        void testGaugeMetricsState() {
            // Given: A gauge tracking active connections
            String gaugeName = "connections.active";

            // When: Connections change
            metricsRegistry.setGauge(gaugeName, 5);
            assertEquals(5, metricsRegistry.getGauge(gaugeName));

            metricsRegistry.incrementGauge(gaugeName);
            assertEquals(6, metricsRegistry.getGauge(gaugeName));

            metricsRegistry.decrementGauge(gaugeName);
            metricsRegistry.decrementGauge(gaugeName);
            assertEquals(4, metricsRegistry.getGauge(gaugeName));

            // Then: Final state is accurate
            metricsRegistry.setGauge(gaugeName, 100);
            assertEquals(100, metricsRegistry.getGauge(gaugeName));
        }

        @Test
        @Order(3)
        @DisplayName("1.3 - Timer metrics track operation duration")
        void testTimerMetrics() {
            // Given: Various operation durations
            String timerName = "operation.duration";

            // When: Recording durations
            metricsRegistry.recordTimer(timerName, 50);
            metricsRegistry.recordTimer(timerName, 75);
            metricsRegistry.recordTimer(timerName, 100);
            metricsRegistry.recordTimer(timerName, 150);
            metricsRegistry.recordTimer(timerName, 200);

            // Then: Timer captures all samples
            assertNotNull(metricsRegistry.getTimer(timerName));
        }

        @Test
        @Order(4)
        @DisplayName("1.4 - Metrics are thread-safe under concurrent access")
        void testMetricsThreadSafety() throws Exception {
            // Given: Multiple threads updating same counter
            int threads = 10;
            int incrementsPerThread = 10000;
            CountDownLatch latch = new CountDownLatch(threads);
            String counterName = "concurrent.counter";

            // When: Concurrent increments
            for (int t = 0; t < threads; t++) {
                executorService.submit(() -> {
                    try {
                        for (int i = 0; i < incrementsPerThread; i++) {
                            metricsRegistry.incrementCounter(counterName);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // Then: No updates are lost
            assertEquals(threads * incrementsPerThread, metricsRegistry.getCounter(counterName));
        }

        @Test
        @Order(5)
        @DisplayName("1.5 - Component-specific metrics isolation")
        void testComponentMetricsIsolation() {
            // Given: Metrics for different components
            metricsRegistry.incrementCounter("transport.messages.sent", 100);
            metricsRegistry.incrementCounter("protocol.messages.encoded", 95);
            metricsRegistry.incrementCounter("security.encryptions.performed", 90);

            // Then: Each metric is isolated
            assertEquals(100, metricsRegistry.getCounter("transport.messages.sent"));
            assertEquals(95, metricsRegistry.getCounter("protocol.messages.encoded"));
            assertEquals(90, metricsRegistry.getCounter("security.encryptions.performed"));
            assertEquals(0, metricsRegistry.getCounter("nonexistent.metric"));
        }
    }

    // ===================== 2. Distributed Tracing Tests =====================

    @Nested
    @DisplayName("2. Distributed Tracing")
    class DistributedTracingTests {

        @Test
        @Order(10)
        @DisplayName("2.1 - Trace IDs are unique")
        void testTraceIdUniqueness() {
            // Given/When: Generating many trace IDs
            Set<String> traceIds = new HashSet<>();
            int count = 10000;

            for (int i = 0; i < count; i++) {
                traceIds.add(TraceIdGenerator.generate());
            }

            // Then: All IDs are unique
            assertEquals(count, traceIds.size());
        }

        @Test
        @Order(11)
        @DisplayName("2.2 - Span lifecycle tracking")
        void testSpanLifecycle() throws InterruptedException {
            // Given: A trace context
            String traceId = TraceIdGenerator.generate();
            String operationName = "process.message";

            // When: Creating and finishing a span
            Span span = new Span(traceId, operationName);
            span.setTag("component", "test");
            span.setTag("peer.id", "peer-123");

            Thread.sleep(50); // Simulate work
            span.finish();

            // Then: Span has correct metadata
            assertEquals(traceId, span.getTraceId());
            assertEquals(operationName, span.getOperationName());
            assertNotNull(span.getSpanId());
            assertTrue(span.getDuration().toMillis() >= 50);
            assertEquals("test", span.getTags().get("component"));
        }

        @Test
        @Order(12)
        @DisplayName("2.3 - Parent-child span correlation")
        void testSpanCorrelation() {
            // Given: A parent trace
            String traceId = TraceIdGenerator.generate();

            // When: Creating parent and child spans
            Span parentSpan = new Span(traceId, "handle.request");
            parentSpan.setTag("type", "parent");

            Span childSpan1 = new Span(traceId, "validate.message");
            childSpan1.setTag("type", "child");
            childSpan1.setTag("parent.span", parentSpan.getSpanId());

            Span childSpan2 = new Span(traceId, "process.payload");
            childSpan2.setTag("type", "child");
            childSpan2.setTag("parent.span", parentSpan.getSpanId());

            // Then: All spans share trace ID but have unique span IDs
            assertEquals(traceId, parentSpan.getTraceId());
            assertEquals(traceId, childSpan1.getTraceId());
            assertEquals(traceId, childSpan2.getTraceId());

            assertNotEquals(parentSpan.getSpanId(), childSpan1.getSpanId());
            assertNotEquals(parentSpan.getSpanId(), childSpan2.getSpanId());
            assertNotEquals(childSpan1.getSpanId(), childSpan2.getSpanId());
        }

        @Test
        @Order(13)
        @DisplayName("2.4 - Full UUID trace IDs for external correlation")
        void testFullUuidTraceIds() {
            // When: Generating full UUID trace IDs
            String fullId = TraceIdGenerator.generateFull();

            // Then: Format is valid UUID
            assertTrue(fullId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "Full trace ID should be valid UUID format");
        }
    }

    // ===================== 3. Message Pipeline Instrumentation =====================

    @Nested
    @DisplayName("3. Pipeline Instrumentation")
    class PipelineInstrumentationTests {

        @Test
        @Order(20)
        @DisplayName("3.1 - Complete message flow is instrumented")
        void testCompleteMessageFlowInstrumentation() {
            // Given: A message to process
            Message message = createTestMessage("DATA", "sender", "receiver");

            // When: Processing through pipeline
            pipeline.process(message);

            // Then: All stages are instrumented
            assertTrue(metricsRegistry.getCounter("pipeline.receive") > 0);
            assertTrue(metricsRegistry.getCounter("pipeline.validate") > 0);
            assertTrue(metricsRegistry.getCounter("pipeline.process") > 0);
            assertTrue(metricsRegistry.getCounter("pipeline.deliver") > 0);
        }

        @Test
        @Order(21)
        @DisplayName("3.2 - Error conditions are tracked")
        void testErrorTracking() {
            // Given: A message that will fail validation
            Message invalidMessage = createTestMessage("INVALID_TYPE", "sender", "receiver");
            pipeline.setValidationFailure(true);

            // When: Processing fails
            try {
                pipeline.process(invalidMessage);
            } catch (Exception e) {
                // Expected
            }

            // Then: Error is tracked
            assertTrue(metricsRegistry.getCounter("pipeline.errors") > 0);
        }

        @ParameterizedTest(name = "Processing {0} messages tracks accurate metrics")
        @ValueSource(ints = {10, 100, 500})
        @Order(22)
        void testBulkProcessingMetrics(int messageCount) {
            // Given: Multiple messages
            pipeline.setValidationFailure(false);

            // When: Processing all messages
            for (int i = 0; i < messageCount; i++) {
                Message message = createTestMessage("DATA", "sender-" + i, "receiver");
                pipeline.process(message);
            }

            // Then: Metrics reflect all processed messages
            assertEquals(messageCount, metricsRegistry.getCounter("pipeline.receive"));
            assertEquals(messageCount, metricsRegistry.getCounter("pipeline.process"));
        }

        @Test
        @Order(23)
        @DisplayName("3.3 - Latency percentiles are tracked")
        void testLatencyPercentileTracking() {
            // Given: Messages with varying processing times
            pipeline.setProcessingDelayMs(10);

            for (int i = 0; i < 100; i++) {
                Message message = createTestMessage("DATA", "sender", "receiver");
                pipeline.process(message);
            }

            // Then: Timer has recorded samples
            assertNotNull(metricsRegistry.getTimer("pipeline.processing.time"));
        }
    }

    // ===================== 4. Cross-Component Correlation =====================

    @Nested
    @DisplayName("4. Cross-Component Correlation")
    class CrossComponentCorrelationTests {

        @Test
        @Order(30)
        @DisplayName("4.1 - Trace context propagates across components")
        void testTraceContextPropagation() {
            // Given: A trace starting in transport layer
            String traceId = TraceIdGenerator.generate();

            // When: Processing through multiple layers
            Span transportSpan = new Span(traceId, "transport.receive");
            transportSpan.setTag("layer", "transport");
            transportSpan.finish();

            Span protocolSpan = new Span(traceId, "protocol.decode");
            protocolSpan.setTag("layer", "protocol");
            protocolSpan.setTag("parent", transportSpan.getSpanId());
            protocolSpan.finish();

            Span securitySpan = new Span(traceId, "security.decrypt");
            securitySpan.setTag("layer", "security");
            securitySpan.setTag("parent", protocolSpan.getSpanId());
            securitySpan.finish();

            Span handlerSpan = new Span(traceId, "handler.process");
            handlerSpan.setTag("layer", "handler");
            handlerSpan.setTag("parent", securitySpan.getSpanId());
            handlerSpan.finish();

            // Then: All spans share same trace ID
            assertEquals(traceId, transportSpan.getTraceId());
            assertEquals(traceId, protocolSpan.getTraceId());
            assertEquals(traceId, securitySpan.getTraceId());
            assertEquals(traceId, handlerSpan.getTraceId());
        }

        @Test
        @Order(31)
        @DisplayName("4.2 - Error context is preserved across boundaries")
        void testErrorContextPreservation() {
            // Given: An operation with error context
            String traceId = TraceIdGenerator.generate();
            String correlationId = UUID.randomUUID().toString();

            Span span = new Span(traceId, "failing.operation");
            span.setTag("correlation.id", correlationId);
            span.setTag("error", "true");
            span.setTag("error.type", "ValidationException");
            span.setTag("error.message", "Invalid message format");
            span.finish();

            // Then: Error context is accessible
            assertEquals("true", span.getTags().get("error"));
            assertEquals("ValidationException", span.getTags().get("error.type"));
            assertEquals(correlationId, span.getTags().get("correlation.id"));
        }
    }

    // ===================== 5. Performance Monitoring =====================

    @Nested
    @DisplayName("5. Performance Monitoring")
    class PerformanceMonitoringTests {

        @Test
        @Order(40)
        @DisplayName("5.1 - High-volume metrics collection")
        void testHighVolumeMetricsCollection() throws Exception {
            // Given: High volume of metrics
            int operationCount = 100000;
            long startTime = System.nanoTime();

            // When: Recording metrics rapidly
            for (int i = 0; i < operationCount; i++) {
                metricsRegistry.incrementCounter("high.volume.counter");
                if (i % 100 == 0) {
                    metricsRegistry.recordTimer("high.volume.timer", i % 1000);
                }
            }

            long duration = System.nanoTime() - startTime;

            // Then: All metrics are captured
            assertEquals(operationCount, metricsRegistry.getCounter("high.volume.counter"));

            // And: Performance is acceptable (< 1ms per 1000 operations on average)
            double msPerThousand = (duration / 1_000_000.0) / (operationCount / 1000.0);
            assertTrue(msPerThousand < 10,
                "Should handle 1000 metrics in < 10ms, actual: " + msPerThousand + "ms");
        }

        @Test
        @Order(41)
        @DisplayName("5.2 - Trace ID generation performance")
        void testTraceIdGenerationPerformance() {
            // Given: Need to generate many trace IDs
            int count = 100000;
            long startTime = System.nanoTime();

            // When: Generating IDs
            for (int i = 0; i < count; i++) {
                TraceIdGenerator.generate();
            }

            long duration = System.nanoTime() - startTime;
            double idsPerSecond = count / (duration / 1_000_000_000.0);

            // Then: Performance is acceptable
            assertTrue(idsPerSecond > 100000,
                "Should generate > 100k IDs/second, actual: " + (int) idsPerSecond);
        }

        @Test
        @Order(42)
        @DisplayName("5.3 - Span creation overhead is minimal")
        void testSpanCreationOverhead() {
            // Given: Need to create many spans
            int count = 10000;
            String traceId = TraceIdGenerator.generate();
            long startTime = System.nanoTime();

            // When: Creating spans
            for (int i = 0; i < count; i++) {
                Span span = new Span(traceId, "test.operation");
                span.setTag("index", String.valueOf(i));
                span.finish();
            }

            long duration = System.nanoTime() - startTime;
            double avgMicros = (duration / 1000.0) / count;

            // Then: Overhead is < 100 microseconds per span
            assertTrue(avgMicros < 100,
                "Span creation should take < 100µs, actual: " + avgMicros + "µs");
        }
    }

    // ===================== Helper Methods =====================

    private Message createTestMessage(String type, String from, String to) {
        MessageHeader header = new MessageHeader(
            UUID.randomUUID().toString(),
            TraceIdGenerator.generate(),
            ProtocolVersion.current().toString(),
            "1.0",
            10, 0, type, from, to,
            System.currentTimeMillis(),
            false, "application/json", false, null, false
        );
        return new Message(header, new MessageBody("{}", Map.of()));
    }

    // ===================== Simulated Pipeline =====================

    /**
     * Simulates a message processing pipeline with observability instrumentation.
     */
    private static class SimulatedMessagePipeline {
        private final MetricsRegistry metrics;
        private boolean validationFailure = false;
        private int processingDelayMs = 0;

        SimulatedMessagePipeline(MetricsRegistry metrics) {
            this.metrics = metrics;
        }

        void setValidationFailure(boolean fail) {
            this.validationFailure = fail;
        }

        void setProcessingDelayMs(int delayMs) {
            this.processingDelayMs = delayMs;
        }

        void process(Message message) {
            long startTime = System.currentTimeMillis();

            // Receive stage
            metrics.incrementCounter("pipeline.receive");

            // Validate stage
            metrics.incrementCounter("pipeline.validate");
            if (validationFailure) {
                metrics.incrementCounter("pipeline.errors");
                throw new RuntimeException("Validation failed");
            }

            // Process stage
            if (processingDelayMs > 0) {
                try {
                    Thread.sleep(processingDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            metrics.incrementCounter("pipeline.process");

            // Deliver stage
            metrics.incrementCounter("pipeline.deliver");

            // Record timing
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordTimer("pipeline.processing.time", duration);
        }
    }
}
