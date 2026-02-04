package com.genesis.p2p.observability.metrics;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MetricsRegistry Tests")
class MetricsRegistryTest extends BaseUnitTest {

    private MetricsRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MetricsRegistry("test-node");
    }

    @Test
    @DisplayName("Should create registry with default constructor")
    void testDefaultConstructor() {
        MetricsRegistry defaultRegistry = new MetricsRegistry();
        assertNotNull(defaultRegistry);
    }

    @Test
    @DisplayName("Should increment counter")
    void testIncrementCounter() {
        registry.incrementCounter("test.counter");
        assertEquals(1, registry.getCounter("test.counter"));

        registry.incrementCounter("test.counter");
        assertEquals(2, registry.getCounter("test.counter"));
    }

    @Test
    @DisplayName("Should increment counter by delta")
    void testIncrementCounterByDelta() {
        registry.incrementCounter("test.counter", 10);
        assertEquals(10, registry.getCounter("test.counter"));

        registry.incrementCounter("test.counter", 5);
        assertEquals(15, registry.getCounter("test.counter"));
    }

    @Test
    @DisplayName("Should return zero for unknown counter")
    void testUnknownCounter() {
        assertEquals(0, registry.getCounter("unknown.counter"));
    }

    @Test
    @DisplayName("Should reset counter")
    void testResetCounter() {
        registry.incrementCounter("test.counter", 100);
        registry.resetCounter("test.counter");
        assertEquals(0, registry.getCounter("test.counter"));
    }

    @Test
    @DisplayName("Should set and get gauge")
    void testGauge() {
        registry.setGauge("test.gauge", 42);
        assertEquals(42, registry.getGauge("test.gauge"));

        registry.setGauge("test.gauge", 100);
        assertEquals(100, registry.getGauge("test.gauge"));
    }

    @Test
    @DisplayName("Should return zero for unknown gauge")
    void testUnknownGauge() {
        assertEquals(0, registry.getGauge("unknown.gauge"));
    }

    @Test
    @DisplayName("Should increment gauge")
    void testIncrementGauge() {
        registry.setGauge("test.gauge", 10);
        registry.incrementGauge("test.gauge");
        assertEquals(11, registry.getGauge("test.gauge"));
    }

    @Test
    @DisplayName("Should decrement gauge")
    void testDecrementGauge() {
        registry.setGauge("test.gauge", 10);
        registry.decrementGauge("test.gauge");
        assertEquals(9, registry.getGauge("test.gauge"));
    }

    @Test
    @DisplayName("Should record timer")
    void testRecordTimer() {
        registry.recordTimer("test.timer", 100);
        registry.recordTimer("test.timer", 200);
        registry.recordTimer("test.timer", 150);

        assertNotNull(registry.getTimer("test.timer"));
    }

    @Test
    @DisplayName("Should handle multiple counters")
    void testMultipleCounters() {
        registry.incrementCounter("counter1", 10);
        registry.incrementCounter("counter2", 20);
        registry.incrementCounter("counter3", 30);

        assertEquals(10, registry.getCounter("counter1"));
        assertEquals(20, registry.getCounter("counter2"));
        assertEquals(30, registry.getCounter("counter3"));
    }

    @Test
    @DisplayName("Should be thread-safe")
    void testThreadSafety() throws InterruptedException {
        int threads = 10;
        int increments = 1000;

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < increments; j++) {
                    registry.incrementCounter("concurrent.counter");
                }
            });
            workers[i].start();
        }

        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(threads * increments, registry.getCounter("concurrent.counter"));
    }
}

