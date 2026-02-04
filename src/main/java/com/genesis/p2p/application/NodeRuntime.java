package com.genesis.p2p.application;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.resource.AutoCloser;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NodeRuntime - Runtime container for P2P Node with lifecycle management.
 *
 * Provides advanced runtime features:
 * - Lifecycle state management
 * - Graceful startup/shutdown
 * - Health monitoring
 * - Metrics collection
 * - Error handling
 * - Resource cleanup
 *
 * States: CREATED → STARTING → RUNNING → STOPPING → STOPPED → FAILED
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class NodeRuntime implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(NodeRuntime.class);

    private final Node node;
    private final AtomicReference<RuntimeState> state;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant stoppedAt;

    private final AutoCloser closer;
    private final ScheduledExecutorService scheduler;
    private HealthCheckService healthCheckService;

    /**
     * Runtime states.
     */
    public enum RuntimeState {
        CREATED,     // Just created, not started
        STARTING,    // Start in progress
        RUNNING,     // Fully operational
        STOPPING,    // Stop in progress
        STOPPED,     // Stopped cleanly
        FAILED       // Failed to start or crashed
    }

    /**
     * Creates a NodeRuntime wrapping a Node.
     */
    public NodeRuntime(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("Node cannot be null");
        }

        this.node = node;
        this.state = new AtomicReference<>(RuntimeState.CREATED);
        this.createdAt = Instant.now();
        this.closer = new AutoCloser();
        this.scheduler = ThreadPoolFactory.createNamedScheduler(
                "NodeRuntime-Scheduler", node.getNodeId());

        closer.add(scheduler::shutdown);

        log.info("NodeRuntime created", "nodeId", node.getNodeId());
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the node runtime.
     */
    public CompletableFuture<Void> start() {
        if (!state.compareAndSet(RuntimeState.CREATED, RuntimeState.STARTING)) {
            RuntimeState current = state.get();
            log.warn("Cannot start - invalid state", "state", current);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot start from state: " + current));
        }

        log.info("Starting node runtime...", "nodeId", node.getNodeId());

        return CompletableFuture.runAsync(() -> {
            try {
                // Start the node
                node.start();

                // Initialize health checks
                initializeHealthChecks();

                // Mark as started
                startedAt = Instant.now();
                state.set(RuntimeState.RUNNING);

                log.info("Node runtime started successfully",
                        "nodeId", node.getNodeId(),
                        "startupTime", Duration.between(createdAt, startedAt).toMillis() + "ms");

            } catch (Exception e) {
                state.set(RuntimeState.FAILED);
                log.error("Failed to start node runtime", e);
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Stops the node runtime gracefully.
     */
    public CompletableFuture<Void> stop() {
        RuntimeState current = state.get();
        if (current != RuntimeState.RUNNING && current != RuntimeState.FAILED) {
            log.warn("Cannot stop - not running", "state", current);
            return CompletableFuture.completedFuture(null);
        }

        if (!state.compareAndSet(current, RuntimeState.STOPPING)) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("Stopping node runtime...", "nodeId", node.getNodeId());

        return CompletableFuture.runAsync(() -> {
            try {
                // Stop health checks
                if (healthCheckService != null) {
                    healthCheckService.stopMonitoring();
                }

                // Stop the node
                node.stop();

                // Mark as stopped
                stoppedAt = Instant.now();
                state.set(RuntimeState.STOPPED);

                if (startedAt != null) {
                    log.info("Node runtime stopped",
                            "nodeId", node.getNodeId(),
                            "uptime", Duration.between(startedAt, stoppedAt).toMillis() + "ms");
                } else {
                    log.info("Node runtime stopped", "nodeId", node.getNodeId());
                }

            } catch (Exception e) {
                log.error("Error stopping node runtime", e);
                state.set(RuntimeState.FAILED);
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Restarts the node runtime.
     */
    public CompletableFuture<Void> restart() {
        log.info("Restarting node runtime...", "nodeId", node.getNodeId());

        return stop().thenCompose(v -> {
            // Reset to CREATED state for restart
            state.set(RuntimeState.CREATED);
            return start();
        });
    }

    /**
     * Waits for the node to reach RUNNING state.
     */
    public boolean awaitRunning(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        while (System.nanoTime() < deadline) {
            RuntimeState current = state.get();
            if (current == RuntimeState.RUNNING) {
                return true;
            }
            if (current == RuntimeState.FAILED || current == RuntimeState.STOPPED) {
                return false;
            }
            Thread.sleep(100);
        }

        return false;
    }

    /**
     * Blocks until the node stops.
     */
    public void awaitTermination() throws InterruptedException {
        while (state.get() != RuntimeState.STOPPED && state.get() != RuntimeState.FAILED) {
            Thread.sleep(100);
        }
    }

    // ==================== Health Checks ====================

    /**
     * Initializes health check service.
     */
    private void initializeHealthChecks() {
        healthCheckService = new HealthCheckService();

        // Register default health checks
        healthCheckService.registerCheck("node-running",
            new NodeRunningCheck(() -> node.isRunning()));
        healthCheckService.registerCheck("memory",
            new MemoryCheck(0.9));

        // Start monitoring
        healthCheckService.startMonitoring(com.genesis.p2p.util.constants.TimeoutConstants.HEALTH_CHECK_INTERVAL);
    }

    /**
     * Performs a health check.
     */
    private void performHealthCheck() {
        try {
            if (state.get() == RuntimeState.RUNNING) {
                HealthResult health = healthCheckService.runAllChecks();

                if (health.getStatus() != HealthStatus.HEALTHY) {
                    log.warn("Node health check failed",
                            "status", health.getStatus(),
                            "message", health.getMessage());

                    // Could trigger auto-recovery here
                }
            }
        } catch (Exception e) {
            log.error("Health check failed", e);
        }
    }

    /**
     * Gets current health status.
     */
    public HealthResult getHealth() {
        if (healthCheckService == null) {
            return HealthResult.unhealthy("Health service not initialized");
        }
        return healthCheckService.getLastResult();
    }

    // ==================== State & Info ====================

    /**
     * Gets current runtime state.
     */
    public RuntimeState getState() {
        return state.get();
    }

    /**
     * Checks if runtime is running.
     */
    public boolean isRunning() {
        return state.get() == RuntimeState.RUNNING;
    }

    /**
     * Checks if runtime is stopped.
     */
    public boolean isStopped() {
        RuntimeState current = state.get();
        return current == RuntimeState.STOPPED || current == RuntimeState.FAILED;
    }

    /**
     * Gets the wrapped node.
     */
    public Node getNode() {
        return node;
    }

    /**
     * Gets creation timestamp.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Gets start timestamp.
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * Gets stop timestamp.
     */
    public Instant getStoppedAt() {
        return stoppedAt;
    }

    /**
     * Gets uptime in milliseconds.
     */
    public long getUptimeMillis() {
        if (startedAt == null) {
            return 0;
        }
        Instant end = stoppedAt != null ? stoppedAt : Instant.now();
        return Duration.between(startedAt, end).toMillis();
    }

    /**
     * Gets runtime statistics.
     */
    public RuntimeStats getStats() {
        return new RuntimeStats(
                state.get(),
                createdAt,
                startedAt,
                stoppedAt,
                getUptimeMillis()
        );
    }

    // ==================== Shutdown Hook ====================

    /**
     * Adds JVM shutdown hook for graceful shutdown.
     */
    public void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered");
            try {
                stop().get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Error in shutdown hook", e);
            }
        }, "NodeRuntime-ShutdownHook"));
    }

    // ==================== AutoCloseable ====================

    @Override
    public void close() {
        try {
            if (state.get() == RuntimeState.RUNNING) {
                stop().get(30, TimeUnit.SECONDS);
            }
            closer.close();
        } catch (Exception e) {
            log.error("Error closing NodeRuntime", e);
        }
    }

    // ==================== Statistics ====================

    /**
     * Runtime statistics.
     */
    public static class RuntimeStats {
        private final RuntimeState state;
        private final Instant createdAt;
        private final Instant startedAt;
        private final Instant stoppedAt;
        private final long uptimeMillis;

        public RuntimeStats(RuntimeState state, Instant createdAt, Instant startedAt,
                          Instant stoppedAt, long uptimeMillis) {
            this.state = state;
            this.createdAt = createdAt;
            this.startedAt = startedAt;
            this.stoppedAt = stoppedAt;
            this.uptimeMillis = uptimeMillis;
        }

        public RuntimeState getState() { return state; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getStartedAt() { return startedAt; }
        public Instant getStoppedAt() { return stoppedAt; }
        public long getUptimeMillis() { return uptimeMillis; }

        @Override
        public String toString() {
            return String.format("RuntimeStats[state=%s, uptime=%dms]",
                    state, uptimeMillis);
        }
    }

    @Override
    public String toString() {
        return String.format("NodeRuntime[nodeId=%s, state=%s, uptime=%dms]",
                node.getNodeId(), state.get(), getUptimeMillis());
    }
}

