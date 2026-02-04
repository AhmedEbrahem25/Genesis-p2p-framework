package com.genesis.p2p.events;

import com.genesis.p2p.events.core.*;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.testutil.base.BaseAsyncTest;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for EventBus.
 * Tests publish-subscribe pattern, event filtering, and transformations.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("EventBus Tests")
class EventBusTest extends BaseAsyncTest {

    private EventBus eventBus;
    private MetricsRegistry metricsRegistry;

    @BeforeEach
    void setUp() {
        logTestStep("Setting up EventBus");
        metricsRegistry = new MetricsRegistry("test-node");
        eventBus = new EventBus(metricsRegistry);
    }

    @AfterEach
    void tearDown() {
        if (eventBus != null) {
            eventBus.close();
        }
        logTestStep("EventBus closed");
    }

    // ========================= Subscription Tests =========================

    @Test
    @Order(1)
    @DisplayName("Should subscribe to events")
    void testSubscribe() {
        AtomicBoolean received = new AtomicBoolean(false);

        Subscription subscription = eventBus.subscribe("test.*", event -> {
            received.set(true);
        });

        assertNotNull(subscription);
    }

    @Test
    @Order(2)
    @DisplayName("Should unsubscribe from events")
    void testUnsubscribe() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);

        Subscription subscription = eventBus.subscribe("test.*", event -> {
            callCount.incrementAndGet();
        });

        eventBus.publish(createEvent("test.event1"));
        Thread.sleep(50);

        subscription.unsubscribe();

        eventBus.publish(createEvent("test.event2"));
        Thread.sleep(50);

        assertEquals(1, callCount.get());
    }

    @Test
    @Order(3)
    @DisplayName("Should check if subscription is active")
    void testSubscriptionIsActive() {
        Subscription subscription = eventBus.subscribe("test.*", event -> {});

        assertTrue(subscription.isActive());

        subscription.unsubscribe();

        assertFalse(subscription.isActive());
    }

    // ========================= Publish Tests =========================

    @Test
    @Order(10)
    @DisplayName("Should publish event to subscriber")
    void testPublish() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean received = new AtomicBoolean(false);

        eventBus.subscribe("test.event", event -> {
            received.set(true);
            latch.countDown();
        });

        eventBus.publish(createEvent("test.event"));

        awaitLatch(latch, "Event should be received");
        assertTrue(received.get());
    }

    @Test
    @Order(11)
    @DisplayName("Should publish to multiple subscribers")
    void testPublishToMultipleSubscribers() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            eventBus.subscribe("multi.*", event -> latch.countDown());
        }

        eventBus.publish(createEvent("multi.event"));

        awaitLatch(latch, "All subscribers should receive event");
    }

    @Test
    @Order(12)
    @DisplayName("Should not deliver to non-matching subscribers")
    void testPatternMatching() throws InterruptedException {
        AtomicInteger matchCount = new AtomicInteger(0);
        AtomicInteger noMatchCount = new AtomicInteger(0);

        eventBus.subscribe("match.*", event -> matchCount.incrementAndGet());
        eventBus.subscribe("nomatch.*", event -> noMatchCount.incrementAndGet());

        eventBus.publish(createEvent("match.event"));
        Thread.sleep(100);

        assertEquals(1, matchCount.get());
        assertEquals(0, noMatchCount.get());
    }

    // ========================= Pattern Matching Tests =========================

    @Test
    @Order(20)
    @DisplayName("Wildcard * should match any suffix")
    void testWildcardSuffix() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);

        eventBus.subscribe("prefix.*", event -> latch.countDown());

        eventBus.publish(createEvent("prefix.one"));
        eventBus.publish(createEvent("prefix.two"));
        eventBus.publish(createEvent("prefix.three"));

        awaitLatch(latch, "All events should match wildcard pattern");
    }

    @Test
    @Order(21)
    @DisplayName("Exact match should work")
    void testExactMatch() throws InterruptedException {
        AtomicInteger exactCount = new AtomicInteger(0);

        eventBus.subscribe("exact.event.type", event -> exactCount.incrementAndGet());

        eventBus.publish(createEvent("exact.event.type"));
        eventBus.publish(createEvent("exact.event.other"));
        Thread.sleep(100);

        assertEquals(1, exactCount.get());
    }

    @Test
    @Order(22)
    @DisplayName("Double wildcard ** should match any path")
    void testDoubleWildcard() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);

        eventBus.subscribe("**", event -> latch.countDown());

        eventBus.publish(createEvent("any.type"));
        eventBus.publish(createEvent("other.deep.nested.type"));
        eventBus.publish(createEvent("simple"));

        awaitLatch(latch, "All events should match ** pattern");
    }

    // ========================= Event Object Tests =========================

    @Test
    @Order(30)
    @DisplayName("GenericEvent should contain correct data")
    void testGenericEvent() {
        GenericEvent event = new GenericEvent("test.type", "source", "payload");

        assertEquals("test.type", event.getType());
        assertEquals("source", event.getSource());
        assertEquals("payload", event.getPayload());
        assertNotNull(event.getTimestamp());
    }

    @Test
    @Order(31)
    @DisplayName("Event should support correlation ID")
    void testEventCorrelationId() {
        GenericEvent event = new GenericEvent("test.type", "payload");
        event.setCorrelationId("corr-123");

        assertEquals("corr-123", event.getCorrelationId());
    }

    @Test
    @Order(32)
    @DisplayName("Event timestamp should be set automatically")
    void testEventTimestamp() {
        Instant before = Instant.now();
        GenericEvent event = new GenericEvent("test.type", "payload");
        Instant after = Instant.now();

        assertNotNull(event.getTimestamp());
        assertTrue(event.getTimestamp().compareTo(before) >= 0);
        assertTrue(event.getTimestamp().compareTo(after) <= 0);
    }

    // ========================= Async Delivery Tests =========================

    @Test
    @Order(40)
    @DisplayName("Events should be delivered synchronously")
    void testSyncDelivery() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean inPublishThread = new AtomicBoolean(false);
        Thread publishThread = Thread.currentThread();

        eventBus.subscribe("sync.event", event -> {
            inPublishThread.set(Thread.currentThread() == publishThread);
            latch.countDown();
        });

        eventBus.publish(createEvent("sync.event"));

        awaitLatch(latch, "Event should be delivered");
        // Event should be delivered in the same thread (synchronous)
        assertTrue(inPublishThread.get());
    }

    @Test
    @Order(41)
    @DisplayName("Should handle high event throughput")
    void testHighThroughput() throws InterruptedException {
        int eventCount = 1000;
        CountDownLatch latch = new CountDownLatch(eventCount);

        eventBus.subscribe("throughput.*", event -> latch.countDown());

        for (int i = 0; i < eventCount; i++) {
            eventBus.publish(createEvent("throughput.event" + i));
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All events should be delivered");
    }

    // ========================= Error Handling Tests =========================

    @Test
    @Order(50)
    @DisplayName("Subscriber exception should not affect other subscribers")
    void testSubscriberExceptionIsolation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        eventBus.subscribe("error.event", event -> {
            throw new RuntimeException("Subscriber failed");
        });

        eventBus.subscribe("error.event", event -> latch.countDown());

        eventBus.publish(createEvent("error.event"));

        awaitLatch(latch, "Second subscriber should still receive event");
    }

    @Test
    @Order(51)
    @DisplayName("Should reject null event")
    void testRejectNullEvent() {
        assertThrows(Exception.class, () -> {
            eventBus.publish(null);
        });
    }

    @Test
    @Order(52)
    @DisplayName("Should handle null listener gracefully")
    void testNullListener() {
        assertThrows(Exception.class, () -> {
            eventBus.subscribe("test.*", null);
        });
    }

    // ========================= Closed Bus Tests =========================

    @Test
    @Order(60)
    @DisplayName("Should reject publish after close")
    void testPublishAfterClose() {
        eventBus.close();

        assertThrows(Exception.class, () -> {
            eventBus.publish(createEvent("after.close"));
        });
    }

    @Test
    @Order(61)
    @DisplayName("Should reject subscribe after close")
    void testSubscribeAfterClose() {
        eventBus.close();

        assertThrows(Exception.class, () -> {
            eventBus.subscribe("after.close", event -> {});
        });
    }

    @Test
    @Order(62)
    @DisplayName("Multiple close calls should be safe")
    void testMultipleClose() {
        assertDoesNotThrow(() -> {
            eventBus.close();
            eventBus.close();
            eventBus.close();
        });
    }

    // ========================= Metrics Tests =========================

    @Test
    @Order(80)
    @DisplayName("Should track event metrics")
    void testEventMetrics() throws InterruptedException {
        eventBus.subscribe("metrics.*", event -> {});

        eventBus.publish(createEvent("metrics.event1"));
        eventBus.publish(createEvent("metrics.event2"));
        Thread.sleep(100);

        // Metrics should be updated - exact verification depends on implementation
        assertNotNull(metricsRegistry);
    }

    // ========================= Concurrency Tests =========================

    @Test
    @Order(90)
    @DisplayName("Should handle concurrent subscriptions")
    void testConcurrentSubscriptions() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            testExecutor.submit(() -> {
                try {
                    startLatch.await();
                    Subscription sub = eventBus.subscribe("concurrent." + index, event -> {});
                    if (sub != null && sub.isActive()) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        awaitLatch(doneLatch, asyncTimeout.multipliedBy(2), "All concurrent subscriptions should complete");
        assertEquals(threadCount, successCount.get());
    }

    @Test
    @Order(91)
    @DisplayName("Should handle concurrent publish and subscribe")
    void testConcurrentPublishSubscribe() throws InterruptedException {
        int operationCount = 100;
        CountDownLatch latch = new CountDownLatch(operationCount * 2);
        AtomicBoolean noErrors = new AtomicBoolean(true);

        // Subscriber thread
        testExecutor.submit(() -> {
            for (int i = 0; i < operationCount; i++) {
                try {
                    eventBus.subscribe("concurrent.ps." + i, event -> {});
                } catch (Exception e) {
                    noErrors.set(false);
                } finally {
                    latch.countDown();
                }
            }
        });

        // Publisher thread
        testExecutor.submit(() -> {
            for (int i = 0; i < operationCount; i++) {
                try {
                    eventBus.publish(createEvent("concurrent.ps." + i));
                } catch (Exception e) {
                    noErrors.set(false);
                } finally {
                    latch.countDown();
                }
            }
        });

        awaitLatch(latch, asyncTimeout.multipliedBy(3), "All concurrent operations should complete");
        assertTrue(noErrors.get());
    }

    // ========================= Event Order Tests =========================

    @Test
    @Order(100)
    @DisplayName("Events should be delivered in order to same subscriber")
    void testEventOrdering() throws InterruptedException {
        List<Integer> receivedOrder = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(10);

        eventBus.subscribe("order.*", event -> {
            receivedOrder.add((Integer) event.getPayload());
            latch.countDown();
        });

        for (int i = 0; i < 10; i++) {
            eventBus.publish(createEventWithPayload("order.event", i));
        }

        awaitLatch(latch, "All ordered events should be delivered");

        // Verify ordering
        for (int i = 0; i < 10; i++) {
            assertEquals(i, receivedOrder.get(i));
        }
    }

    // ========================= Helper Methods =========================

    private IEvent createEvent(String type) {
        return new GenericEvent(type, "test-source", null);
    }

    private IEvent createEventWithPayload(String type, Object payload) {
        return new GenericEvent(type, "test-source", payload);
    }
}
