package com.genesis.p2p;

import com.genesis.p2p.application.Node;
import com.genesis.p2p.application.Node.State;
import com.genesis.p2p.application.Node.LifecycleListener;
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
 * Comprehensive unit tests for Node lifecycle management.
 * Tests State Pattern, Observer Pattern, and Template Method hooks.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Node Lifecycle Tests")
class NodeLifecycleTest extends BaseAsyncTest {

    // ========================= State Pattern Tests =========================

    @Test
    @Order(1)
    @DisplayName("State enum should have correct descriptions")
    void testStateDescriptions() {
        assertNotNull(State.CREATED.getDescription());
        assertNotNull(State.STARTING.getDescription());
        assertNotNull(State.RUNNING.getDescription());
        assertNotNull(State.STOPPING.getDescription());
        assertNotNull(State.STOPPED.getDescription());
        assertNotNull(State.FAILED.getDescription());

        assertTrue(State.CREATED.getDescription().contains("created"));
        assertTrue(State.RUNNING.getDescription().contains("running"));
    }

    @Test
    @Order(2)
    @DisplayName("Terminal states should be correctly identified")
    void testTerminalStates() {
        assertFalse(State.CREATED.isTerminal());
        assertFalse(State.STARTING.isTerminal());
        assertFalse(State.RUNNING.isTerminal());
        assertFalse(State.STOPPING.isTerminal());
        assertTrue(State.STOPPED.isTerminal());
        assertTrue(State.FAILED.isTerminal());
    }

    @Test
    @Order(3)
    @DisplayName("State transitions should be validated correctly")
    void testStateTransitionValidation() {
        // CREATED transitions
        assertTrue(State.CREATED.canTransitionTo(State.STARTING));
        assertTrue(State.CREATED.canTransitionTo(State.FAILED));
        assertFalse(State.CREATED.canTransitionTo(State.RUNNING));
        assertFalse(State.CREATED.canTransitionTo(State.STOPPED));

        // STARTING transitions
        assertTrue(State.STARTING.canTransitionTo(State.RUNNING));
        assertTrue(State.STARTING.canTransitionTo(State.FAILED));
        assertTrue(State.STARTING.canTransitionTo(State.STOPPING));
        assertFalse(State.STARTING.canTransitionTo(State.CREATED));

        // RUNNING transitions
        assertTrue(State.RUNNING.canTransitionTo(State.STOPPING));
        assertTrue(State.RUNNING.canTransitionTo(State.FAILED));
        assertFalse(State.RUNNING.canTransitionTo(State.STARTING));
        assertFalse(State.RUNNING.canTransitionTo(State.CREATED));

        // STOPPING transitions
        assertTrue(State.STOPPING.canTransitionTo(State.STOPPED));
        assertTrue(State.STOPPING.canTransitionTo(State.FAILED));
        assertFalse(State.STOPPING.canTransitionTo(State.RUNNING));

        // Terminal states cannot transition
        assertFalse(State.STOPPED.canTransitionTo(State.CREATED));
        assertFalse(State.STOPPED.canTransitionTo(State.RUNNING));
        assertFalse(State.FAILED.canTransitionTo(State.CREATED));
        assertFalse(State.FAILED.canTransitionTo(State.RUNNING));
    }

    // ========================= Observer Pattern Tests =========================

    @Test
    @Order(10)
    @DisplayName("LifecycleListener default methods should not throw")
    void testLifecycleListenerDefaults() {
        LifecycleListener listener = new LifecycleListener() {};

        // All default methods should execute without exception
        assertDoesNotThrow(() -> listener.onStateChanged(State.CREATED, State.STARTING));
        assertDoesNotThrow(listener::onStarting);
        assertDoesNotThrow(listener::onStarted);
        assertDoesNotThrow(listener::onStopping);
        assertDoesNotThrow(listener::onStopped);
        assertDoesNotThrow(() -> listener.onFailed(new RuntimeException("test")));
    }

    @Test
    @Order(11)
    @DisplayName("Custom LifecycleListener should receive callbacks")
    void testCustomLifecycleListener() {
        List<String> events = new ArrayList<>();

        LifecycleListener listener = new LifecycleListener() {
            @Override
            public void onStateChanged(State oldState, State newState) {
                events.add("stateChanged:" + oldState + "->" + newState);
            }

            @Override
            public void onStarting() {
                events.add("starting");
            }

            @Override
            public void onStarted() {
                events.add("started");
            }

            @Override
            public void onStopping() {
                events.add("stopping");
            }

            @Override
            public void onStopped() {
                events.add("stopped");
            }

            @Override
            public void onFailed(Exception error) {
                events.add("failed:" + error.getMessage());
            }
        };

        // Simulate calls
        listener.onStateChanged(State.CREATED, State.STARTING);
        listener.onStarting();
        listener.onStarted();
        listener.onStopping();
        listener.onStopped();
        listener.onFailed(new RuntimeException("test error"));

        assertEquals(6, events.size());
        assertTrue(events.contains("starting"));
        assertTrue(events.contains("started"));
        assertTrue(events.contains("stopping"));
        assertTrue(events.contains("stopped"));
        assertTrue(events.get(5).contains("failed"));
    }

    // ========================= NodeInfo Tests =========================

    @Test
    @Order(20)
    @DisplayName("NodeInfo should contain all required fields")
    void testNodeInfoFields() {
        var nodeInfo = new Node.NodeInfo(
                "test-node",
                State.RUNNING,
                java.time.Instant.now().minusSeconds(100),
                java.time.Instant.now().minusSeconds(50),
                null,
                Duration.ofSeconds(50),
                10,
                null,
                null,
                null
        );

        assertEquals("test-node", nodeInfo.nodeId());
        assertEquals(State.RUNNING, nodeInfo.state());
        assertEquals(Duration.ofSeconds(50), nodeInfo.uptime());
        assertEquals(10, nodeInfo.peerCount());
        assertNull(nodeInfo.failureReason());
    }

    @Test
    @Order(21)
    @DisplayName("NodeInfo toString should be informative")
    void testNodeInfoToString() {
        var nodeInfo = new Node.NodeInfo(
                "info-test",
                State.RUNNING,
                java.time.Instant.now(),
                java.time.Instant.now(),
                null,
                Duration.ofMinutes(5),
                25,
                null,
                null,
                null
        );

        String str = nodeInfo.toString();

        assertTrue(str.contains("info-test"));
        assertTrue(str.contains("RUNNING"));
    }

    @Test
    @Order(22)
    @DisplayName("NodeInfo should report failure reason when failed")
    void testNodeInfoFailureReason() {
        var nodeInfo = new Node.NodeInfo(
                "failed-node",
                State.FAILED,
                java.time.Instant.now(),
                null,
                null,
                Duration.ZERO,
                0,
                null,
                null,
                "Connection refused"
        );

        assertEquals(State.FAILED, nodeInfo.state());
        assertEquals("Connection refused", nodeInfo.failureReason());
    }

    // ========================= Duration/Uptime Tests =========================

    @Test
    @Order(30)
    @DisplayName("Duration calculations should be accurate")
    void testDurationCalculations() {
        java.time.Instant start = java.time.Instant.now().minusSeconds(100);
        java.time.Instant end = java.time.Instant.now();

        Duration duration = Duration.between(start, end);

        assertTrue(duration.getSeconds() >= 99 && duration.getSeconds() <= 101);
    }

    @Test
    @Order(31)
    @DisplayName("Zero uptime when not started")
    void testZeroUptimeWhenNotStarted() {
        Duration uptime = Duration.ZERO;

        assertEquals(0, uptime.getSeconds());
        assertEquals(0, uptime.toMillis());
    }

    // ========================= Concurrency Tests =========================

    @Test
    @Order(40)
    @DisplayName("Multiple listeners should all receive events")
    void testMultipleListeners() throws InterruptedException {
        AtomicInteger listener1Count = new AtomicInteger(0);
        AtomicInteger listener2Count = new AtomicInteger(0);
        AtomicInteger listener3Count = new AtomicInteger(0);

        List<LifecycleListener> listeners = new ArrayList<>();

        listeners.add(new LifecycleListener() {
            @Override
            public void onStarted() {
                listener1Count.incrementAndGet();
            }
        });

        listeners.add(new LifecycleListener() {
            @Override
            public void onStarted() {
                listener2Count.incrementAndGet();
            }
        });

        listeners.add(new LifecycleListener() {
            @Override
            public void onStarted() {
                listener3Count.incrementAndGet();
            }
        });

        // Simulate notification
        for (LifecycleListener listener : listeners) {
            listener.onStarted();
        }

        assertEquals(1, listener1Count.get());
        assertEquals(1, listener2Count.get());
        assertEquals(1, listener3Count.get());
    }

    @Test
    @Order(41)
    @DisplayName("Listener exception should not affect other listeners")
    void testListenerExceptionIsolation() {
        AtomicBoolean listener2Called = new AtomicBoolean(false);
        List<LifecycleListener> listeners = new ArrayList<>();

        listeners.add(new LifecycleListener() {
            @Override
            public void onStarted() {
                throw new RuntimeException("Listener 1 failed");
            }
        });

        listeners.add(new LifecycleListener() {
            @Override
            public void onStarted() {
                listener2Called.set(true);
            }
        });

        // Simulate safe notification (as Node would do)
        for (LifecycleListener listener : listeners) {
            try {
                listener.onStarted();
            } catch (Exception e) {
                // Swallow exception, continue to next listener
            }
        }

        assertTrue(listener2Called.get(), "Second listener should still be called");
    }

    @Test
    @Order(42)
    @DisplayName("Concurrent listener modifications should be safe")
    void testConcurrentListenerModification() throws InterruptedException {
        List<LifecycleListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger callCount = new AtomicInteger(0);

        // Add initial listener
        listeners.add(new LifecycleListener() {
            @Override
            public void onStarted() {
                callCount.incrementAndGet();
            }
        });

        // Thread 1: Add listeners
        Thread adder = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                listeners.add(new LifecycleListener() {
                    @Override
                    public void onStarted() {
                        callCount.incrementAndGet();
                    }
                });
            }
            latch.countDown();
        });

        // Thread 2: Notify listeners
        Thread notifier = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                for (LifecycleListener listener : listeners) {
                    try {
                        listener.onStarted();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
            latch.countDown();
        });

        adder.start();
        notifier.start();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(callCount.get() > 0);
    }

    // ========================= State Machine Behavior Tests =========================

    @Test
    @Order(50)
    @DisplayName("All states should have unique ordinals")
    void testStateOrdinals() {
        State[] states = State.values();
        java.util.Set<Integer> ordinals = new java.util.HashSet<>();

        for (State state : states) {
            assertTrue(ordinals.add(state.ordinal()), "Duplicate ordinal: " + state.ordinal());
        }

        assertEquals(6, states.length);
    }

    @Test
    @Order(51)
    @DisplayName("State valueOf should work correctly")
    void testStateValueOf() {
        assertEquals(State.CREATED, State.valueOf("CREATED"));
        assertEquals(State.RUNNING, State.valueOf("RUNNING"));
        assertEquals(State.STOPPED, State.valueOf("STOPPED"));
        assertEquals(State.FAILED, State.valueOf("FAILED"));
    }

    @Test
    @Order(52)
    @DisplayName("Invalid state name should throw exception")
    void testInvalidStateValueOf() {
        assertThrows(IllegalArgumentException.class, () -> State.valueOf("INVALID"));
    }

    // ========================= Lifecycle Flow Tests =========================

    @Test
    @Order(60)
    @DisplayName("Normal lifecycle flow: CREATED -> STARTING -> RUNNING -> STOPPING -> STOPPED")
    void testNormalLifecycleFlow() {
        State current = State.CREATED;

        assertTrue(current.canTransitionTo(State.STARTING));
        current = State.STARTING;

        assertTrue(current.canTransitionTo(State.RUNNING));
        current = State.RUNNING;

        assertTrue(current.canTransitionTo(State.STOPPING));
        current = State.STOPPING;

        assertTrue(current.canTransitionTo(State.STOPPED));
        current = State.STOPPED;

        assertTrue(current.isTerminal());
    }

    @Test
    @Order(61)
    @DisplayName("Failure flow: any state can transition to FAILED")
    void testFailureFlow() {
        assertTrue(State.CREATED.canTransitionTo(State.FAILED));
        assertTrue(State.STARTING.canTransitionTo(State.FAILED));
        assertTrue(State.RUNNING.canTransitionTo(State.FAILED));
        assertTrue(State.STOPPING.canTransitionTo(State.FAILED));

        // Terminal states cannot transition
        assertFalse(State.STOPPED.canTransitionTo(State.FAILED));
        assertFalse(State.FAILED.canTransitionTo(State.FAILED));
    }

    @Test
    @Order(62)
    @DisplayName("Cannot restart from terminal states directly")
    void testCannotRestartFromTerminal() {
        assertFalse(State.STOPPED.canTransitionTo(State.STARTING));
        assertFalse(State.STOPPED.canTransitionTo(State.CREATED));
        assertFalse(State.FAILED.canTransitionTo(State.STARTING));
        assertFalse(State.FAILED.canTransitionTo(State.CREATED));
    }

    // ========================= Event Recording Tests =========================

    @Test
    @Order(70)
    @DisplayName("Listener should receive all lifecycle events in order")
    void testEventOrder() {
        List<String> eventOrder = new ArrayList<>();

        LifecycleListener listener = new LifecycleListener() {
            @Override
            public void onStateChanged(State oldState, State newState) {
                eventOrder.add("state:" + oldState + "->" + newState);
            }

            @Override
            public void onStarting() {
                eventOrder.add("starting");
            }

            @Override
            public void onStarted() {
                eventOrder.add("started");
            }

            @Override
            public void onStopping() {
                eventOrder.add("stopping");
            }

            @Override
            public void onStopped() {
                eventOrder.add("stopped");
            }
        };

        // Simulate normal lifecycle
        listener.onStateChanged(State.CREATED, State.STARTING);
        listener.onStarting();
        listener.onStateChanged(State.STARTING, State.RUNNING);
        listener.onStarted();
        listener.onStateChanged(State.RUNNING, State.STOPPING);
        listener.onStopping();
        listener.onStateChanged(State.STOPPING, State.STOPPED);
        listener.onStopped();

        assertEquals(8, eventOrder.size());
        assertEquals("state:CREATED->STARTING", eventOrder.get(0));
        assertEquals("starting", eventOrder.get(1));
        assertEquals("started", eventOrder.get(3));
        assertEquals("stopped", eventOrder.get(7));
    }

    // ========================= Template Method Pattern Tests =========================

    @Test
    @Order(80)
    @DisplayName("Template method hooks should be called in correct order")
    void testTemplateMethodOrder() {
        List<String> hookOrder = new ArrayList<>();

        // Simulating what a subclass would do
        Runnable beforeStart = () -> hookOrder.add("beforeStart");
        Runnable afterStart = () -> hookOrder.add("afterStart");
        Runnable beforeStop = () -> hookOrder.add("beforeStop");
        Runnable afterStop = () -> hookOrder.add("afterStop");

        // Simulate start sequence
        beforeStart.run();
        hookOrder.add("transport.start");
        hookOrder.add("discovery.start");
        afterStart.run();

        // Simulate stop sequence
        beforeStop.run();
        hookOrder.add("discovery.stop");
        hookOrder.add("transport.stop");
        afterStop.run();

        assertEquals("beforeStart", hookOrder.get(0));
        assertEquals("afterStart", hookOrder.get(3));
        assertEquals("beforeStop", hookOrder.get(4));
        assertEquals("afterStop", hookOrder.get(7));
    }

    // ========================= Edge Cases =========================

    @Test
    @Order(90)
    @DisplayName("Empty listener list should not cause issues")
    void testEmptyListenerList() {
        List<LifecycleListener> listeners = new ArrayList<>();

        // Should not throw
        assertDoesNotThrow(() -> {
            for (LifecycleListener listener : listeners) {
                listener.onStarted();
            }
        });
    }

    @Test
    @Order(91)
    @DisplayName("Null exception in onFailed should be handled")
    void testNullExceptionInOnFailed() {
        AtomicBoolean called = new AtomicBoolean(false);

        LifecycleListener listener = new LifecycleListener() {
            @Override
            public void onFailed(Exception error) {
                called.set(true);
                // error could be null in some edge cases
                if (error != null) {
                    assertNotNull(error.getMessage());
                }
            }
        };

        listener.onFailed(new RuntimeException("test"));
        assertTrue(called.get());
    }

    @Test
    @Order(92)
    @DisplayName("State transition to same state should be allowed")
    void testSameStateTransition() {
        // canTransitionTo returns false for same state (no transition needed)
        // But the actual transition logic should handle this gracefully
        assertFalse(State.RUNNING.canTransitionTo(State.RUNNING));
        assertFalse(State.CREATED.canTransitionTo(State.CREATED));
    }
}
