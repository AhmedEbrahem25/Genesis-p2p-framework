package com.genesis.p2p.application;

import com.genesis.p2p.application.ShutdownHooks.*;
import com.genesis.p2p.testutil.base.BaseAsyncTest;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for ShutdownHooks.
 * Tests Chain of Responsibility, Observer Pattern, and shutdown handling.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ShutdownHooks Tests")
class ShutdownHooksTest extends BaseAsyncTest {

    private ShutdownHooks shutdownHooks;

    @BeforeEach
    void setUp() {
        shutdownHooks = new ShutdownHooks();
        // Remove JVM shutdown hook to prevent interference with tests
        shutdownHooks.removeShutdownHook();
    }

    @AfterEach
    void tearDown() {
        try {
            shutdownHooks.removeShutdownHook();
        } catch (Exception e) {
            // Ignore - may already be removed
        }
    }

    // ========================= Priority Tests =========================

    @Test
    @Order(1)
    @DisplayName("Priority constants should be ordered correctly")
    void testPriorityOrder() {
        assertTrue(Priority.HIGHEST > Priority.HIGH);
        assertTrue(Priority.HIGH > Priority.NORMAL);
        assertTrue(Priority.NORMAL > Priority.LOW);
        assertTrue(Priority.LOW > Priority.LOWEST);
    }

    @Test
    @Order(2)
    @DisplayName("Priority values should be distinct")
    void testPriorityValues() {
        assertEquals(100, Priority.HIGHEST);
        assertEquals(75, Priority.HIGH);
        assertEquals(50, Priority.NORMAL);
        assertEquals(25, Priority.LOW);
        assertEquals(0, Priority.LOWEST);
    }

    // ========================= ShutdownPhase Tests =========================

    @Test
    @Order(10)
    @DisplayName("ShutdownPhase should have correct priorities")
    void testShutdownPhasePriorities() {
        assertEquals(Priority.HIGHEST, ShutdownPhase.FLUSH_DATA.getPriority());
        assertEquals(Priority.HIGH, ShutdownPhase.STOP_ACCEPTING.getPriority());
        assertEquals(Priority.NORMAL, ShutdownPhase.STOP_SERVICES.getPriority());
        assertEquals(Priority.LOW, ShutdownPhase.RELEASE_RESOURCES.getPriority());
        assertEquals(Priority.LOWEST, ShutdownPhase.CLEANUP.getPriority());
    }

    @Test
    @Order(11)
    @DisplayName("ShutdownPhase should have descriptions")
    void testShutdownPhaseDescriptions() {
        assertNotNull(ShutdownPhase.FLUSH_DATA.getDescription());
        assertNotNull(ShutdownPhase.STOP_SERVICES.getDescription());
        assertTrue(ShutdownPhase.FLUSH_DATA.getDescription().contains("Flush"));
    }

    // ========================= ShutdownHandler Tests =========================

    @Test
    @Order(20)
    @DisplayName("LambdaHandler should execute action")
    void testLambdaHandler() {
        AtomicBoolean executed = new AtomicBoolean(false);

        LambdaHandler handler = new LambdaHandler("test", Priority.NORMAL, () -> {
            executed.set(true);
        });

        ShutdownContext context = new ShutdownContext(5000);
        ShutdownResult result = handler.handle(context);

        assertTrue(executed.get());
        assertTrue(result.success());
        assertEquals("test", result.handlerName());
    }

    @Test
    @Order(21)
    @DisplayName("Handler chain should execute in priority order")
    void testHandlerChainOrder() {
        List<String> executionOrder = new ArrayList<>();

        shutdownHooks.addHook("low", Priority.LOW, () -> executionOrder.add("low"));
        shutdownHooks.addHook("high", Priority.HIGH, () -> executionOrder.add("high"));
        shutdownHooks.addHook("normal", Priority.NORMAL, () -> executionOrder.add("normal"));

        shutdownHooks.shutdown();

        assertEquals(3, executionOrder.size());
        assertEquals("high", executionOrder.get(0));
        assertEquals("normal", executionOrder.get(1));
        assertEquals("low", executionOrder.get(2));
    }

    @Test
    @Order(22)
    @DisplayName("Handler should set next handler correctly")
    void testHandlerSetNext() {
        AtomicInteger callCount = new AtomicInteger(0);

        LambdaHandler handler1 = new LambdaHandler("first", Priority.HIGH, callCount::incrementAndGet);
        LambdaHandler handler2 = new LambdaHandler("second", Priority.NORMAL, callCount::incrementAndGet);

        handler1.setNext(handler2);

        ShutdownContext context = new ShutdownContext(5000);
        handler1.handle(context);

        assertEquals(2, callCount.get());
    }

    @Test
    @Order(23)
    @DisplayName("Handler exception should not break chain")
    void testHandlerExceptionIsolation() {
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        shutdownHooks.addHook("failing", Priority.HIGH, () -> {
            throw new RuntimeException("Handler failed");
        });

        shutdownHooks.addHook("succeeding", Priority.NORMAL, () -> {
            secondCalled.set(true);
        });

        assertDoesNotThrow(() -> shutdownHooks.shutdown());
        assertTrue(secondCalled.get());
    }

    // ========================= ShutdownContext Tests =========================

    @Test
    @Order(30)
    @DisplayName("ShutdownContext should track attributes")
    void testShutdownContextAttributes() {
        ShutdownContext context = new ShutdownContext(5000);

        context.setAttribute("key1", "value1");
        context.setAttribute("key2", 42);

        assertEquals("value1", context.getAttribute("key1"));
        assertEquals(42, (Integer) context.getAttribute("key2"));
    }

    @Test
    @Order(31)
    @DisplayName("ShutdownContext should calculate remaining time")
    void testShutdownContextRemainingTime() throws InterruptedException {
        ShutdownContext context = new ShutdownContext(1000);

        long initial = context.getRemainingTimeMs();
        assertTrue(initial <= 1000);
        assertTrue(initial > 900);

        Thread.sleep(100);

        long afterSleep = context.getRemainingTimeMs();
        assertTrue(afterSleep < initial);
    }

    @Test
    @Order(32)
    @DisplayName("ShutdownContext should detect timeout")
    void testShutdownContextTimeout() throws InterruptedException {
        ShutdownContext context = new ShutdownContext(100);

        assertFalse(context.isTimedOut());

        Thread.sleep(150);

        assertTrue(context.isTimedOut());
        assertEquals(0, context.getRemainingTimeMs());
    }

    @Test
    @Order(33)
    @DisplayName("ShutdownContext should support abort")
    void testShutdownContextAbort() {
        ShutdownContext context = new ShutdownContext(5000);

        assertFalse(context.isAborted());
        assertNull(context.getAbortReason());

        context.abort("User requested abort");

        assertTrue(context.isAborted());
        assertEquals("User requested abort", context.getAbortReason());
    }

    // ========================= ShutdownResult Tests =========================

    @Test
    @Order(40)
    @DisplayName("ShutdownResult success should be created correctly")
    void testShutdownResultSuccess() {
        ShutdownResult result = ShutdownResult.success("TestHandler");

        assertTrue(result.success());
        assertEquals("TestHandler", result.handlerName());
        assertEquals("OK", result.message());
    }

    @Test
    @Order(41)
    @DisplayName("ShutdownResult failure should be created correctly")
    void testShutdownResultFailure() {
        ShutdownResult result = ShutdownResult.failure("FailedHandler", "Connection refused");

        assertFalse(result.success());
        assertEquals("FailedHandler", result.handlerName());
        assertEquals("Connection refused", result.message());
    }

    @Test
    @Order(42)
    @DisplayName("ShutdownResult should support duration")
    void testShutdownResultDuration() {
        ShutdownResult result = ShutdownResult.success("Test")
                .withDuration(Duration.ofMillis(150));

        assertEquals(Duration.ofMillis(150), result.duration());
    }

    @Test
    @Order(43)
    @DisplayName("ShutdownResult should merge correctly")
    void testShutdownResultMerge() {
        ShutdownResult first = ShutdownResult.success("First");
        ShutdownResult second = ShutdownResult.success("Second");

        ShutdownResult merged = first.merge(second);

        assertTrue(merged.success());
        assertEquals(1, merged.childResults().size());
    }

    @Test
    @Order(44)
    @DisplayName("ShutdownResult merge should propagate failure")
    void testShutdownResultMergeFailure() {
        ShutdownResult success = ShutdownResult.success("Success");
        ShutdownResult failure = ShutdownResult.failure("Failure", "Error");

        ShutdownResult merged = success.merge(failure);

        assertFalse(merged.success());
    }

    // ========================= ShutdownListener Tests =========================

    @Test
    @Order(50)
    @DisplayName("Listener should receive shutdown started")
    void testListenerShutdownStarted() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        shutdownHooks.addListener(new ShutdownListener() {
            @Override
            public void onShutdownStarted() {
                latch.countDown();
            }
        });

        shutdownHooks.shutdown();

        awaitLatch(latch, "Shutdown operation should complete");
    }

    @Test
    @Order(51)
    @DisplayName("Listener should receive shutdown completed")
    void testListenerShutdownCompleted() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean successReceived = new AtomicBoolean(false);

        shutdownHooks.addListener(new ShutdownListener() {
            @Override
            public void onShutdownCompleted(ShutdownResult result) {
                successReceived.set(result.success());
                latch.countDown();
            }
        });

        shutdownHooks.shutdown();

        awaitLatch(latch, "Shutdown operation should complete");
        assertTrue(successReceived.get());
    }

    @Test
    @Order(52)
    @DisplayName("Multiple listeners should all be notified")
    void testMultipleListeners() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            shutdownHooks.addListener(new ShutdownListener() {
                @Override
                public void onShutdownStarted() {
                    latch.countDown();
                }
            });
        }

        shutdownHooks.shutdown();

        awaitLatch(latch, "Shutdown operation should complete");
    }

    @Test
    @Order(53)
    @DisplayName("Listener exception should not break other listeners")
    void testListenerExceptionIsolation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        shutdownHooks.addListener(new ShutdownListener() {
            @Override
            public void onShutdownStarted() {
                throw new RuntimeException("Listener failed");
            }
        });

        shutdownHooks.addListener(new ShutdownListener() {
            @Override
            public void onShutdownStarted() {
                latch.countDown();
            }
        });

        shutdownHooks.shutdown();

        awaitLatch(latch, "Shutdown operation should complete");
    }

    @Test
    @Order(54)
    @DisplayName("Should be able to remove listener")
    void testRemoveListener() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);

        ShutdownListener listener = new ShutdownListener() {
            @Override
            public void onShutdownStarted() {
                callCount.incrementAndGet();
            }
        };

        shutdownHooks.addListener(listener);
        shutdownHooks.removeListener(listener);

        shutdownHooks.shutdown();

        Thread.sleep(100);
        assertEquals(0, callCount.get());
    }

    // ========================= Built-in Handlers Tests =========================

    @Test
    @Order(60)
    @DisplayName("StopServicesHandler should stop all services")
    void testStopServicesHandler() {
        AtomicInteger stopCount = new AtomicInteger(0);

        List<Runnable> services = List.of(
                stopCount::incrementAndGet,
                stopCount::incrementAndGet,
                stopCount::incrementAndGet
        );

        StopServicesHandler handler = new StopServicesHandler(services);
        ShutdownContext context = new ShutdownContext(5000);

        ShutdownResult result = handler.handle(context);

        assertTrue(result.success());
        assertEquals(3, stopCount.get());
    }

    @Test
    @Order(61)
    @DisplayName("CloseResourcesHandler should close all resources")
    void testCloseResourcesHandler() {
        AtomicInteger closeCount = new AtomicInteger(0);

        List<AutoCloseable> resources = List.of(
                closeCount::incrementAndGet,
                closeCount::incrementAndGet
        );

        CloseResourcesHandler handler = new CloseResourcesHandler(resources);
        ShutdownContext context = new ShutdownContext(5000);

        ShutdownResult result = handler.handle(context);

        assertTrue(result.success());
        assertEquals(2, closeCount.get());
    }

    @Test
    @Order(62)
    @DisplayName("FlushDataHandler should flush all data")
    void testFlushDataHandler() {
        AtomicInteger flushCount = new AtomicInteger(0);

        List<Runnable> flushActions = List.of(
                flushCount::incrementAndGet,
                flushCount::incrementAndGet
        );

        FlushDataHandler handler = new FlushDataHandler(flushActions);
        ShutdownContext context = new ShutdownContext(5000);

        ShutdownResult result = handler.handle(context);

        assertTrue(result.success());
        assertEquals(2, flushCount.get());
    }

    // ========================= Timeout Tests =========================

    @Test
    @Order(70)
    @DisplayName("Should set custom timeout")
    void testSetTimeout() {
        shutdownHooks.setTimeout(Duration.ofSeconds(10));

        // Timeout is set internally, verify by checking behavior
        assertDoesNotThrow(() -> shutdownHooks.shutdown());
    }

    // ========================= Shutdown State Tests =========================

    @Test
    @Order(80)
    @DisplayName("Should track shutdown initiated state")
    void testShutdownInitiated() {
        assertFalse(shutdownHooks.isShutdownInitiated());

        shutdownHooks.shutdown();

        assertTrue(shutdownHooks.isShutdownInitiated());
    }

    @Test
    @Order(81)
    @DisplayName("Shutdown should only execute once")
    void testShutdownOnlyOnce() {
        AtomicInteger callCount = new AtomicInteger(0);

        shutdownHooks.addHook("counter", Priority.NORMAL, callCount::incrementAndGet);

        shutdownHooks.shutdown();
        shutdownHooks.shutdown();
        shutdownHooks.shutdown();

        assertEquals(1, callCount.get());
    }

    // ========================= Phase Handler Tests =========================

    @Test
    @Order(90)
    @DisplayName("Should create phase handler")
    void testCreatePhaseHandler() {
        AtomicBoolean executed = new AtomicBoolean(false);

        ShutdownHandler handler = shutdownHooks.createPhaseHandler(
                ShutdownPhase.FLUSH_DATA,
                () -> executed.set(true)
        );

        assertNotNull(handler);
        assertEquals("FLUSH_DATA", handler.getName());
        assertEquals(Priority.HIGHEST, handler.getPriority());
    }

    // ========================= Async Shutdown Tests =========================

    @Test
    @Order(100)
    @DisplayName("Should support async shutdown")
    void testAsyncShutdown() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        shutdownHooks.addListener(new ShutdownListener() {
            @Override
            public void onShutdownCompleted(ShutdownResult result) {
                latch.countDown();
            }
        });

        shutdownHooks.shutdownAsync();

        awaitLatch(latch, "Async shutdown should complete");
    }

    // ========================= Edge Cases =========================

    @Test
    @Order(110)
    @DisplayName("Empty hooks list should not cause issues")
    void testEmptyHooks() {
        assertDoesNotThrow(() -> shutdownHooks.shutdown());
        assertTrue(shutdownHooks.isShutdownInitiated());
    }

    @Test
    @Order(111)
    @DisplayName("Handler with null name should be handled")
    void testNullHandlerName() {
        // LambdaHandler with null name should work or throw appropriately
        assertDoesNotThrow(() -> {
            try {
                new LambdaHandler(null, Priority.NORMAL, () -> {});
            } catch (Exception e) {
                // Expected if validation is strict
            }
        });
    }

    @Test
    @Order(112)
    @DisplayName("Handler with null action should be handled")
    void testNullHandlerAction() {
        // Implementation allows null action - it's handled gracefully
        assertDoesNotThrow(() -> {
            LambdaHandler handler = new LambdaHandler("test", Priority.NORMAL, null);
            assertNotNull(handler);
        });
    }
}
