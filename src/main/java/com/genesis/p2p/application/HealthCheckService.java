package com.genesis.p2p.application;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * HealthCheckService - Comprehensive health monitoring service.
 *
 * Design Patterns:
 * - Strategy Pattern: Pluggable health checks
 * - Composite Pattern: Aggregate health checks
 * - Observer Pattern: Health change notifications
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class HealthCheckService implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(HealthCheckService.class);

    private final Map<String, HealthCheckStrategy> checks;
    private final List<HealthChangeListener> listeners;
    private final AtomicReference<HealthResult> lastResult;
    private final AtomicReference<HealthStatus> currentStatus;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> monitoringTask;

    public HealthCheckService() {
        this.checks = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.lastResult = new AtomicReference<>(HealthResult.healthy("Initialized"));
        this.currentStatus = new AtomicReference<>(HealthStatus.HEALTHY);
    }

    // ==================== Registration ====================

    /**
     * Registers a health check.
     */
    public void registerCheck(String name, HealthCheckStrategy strategy) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Check name cannot be null or empty");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy cannot be null");
        }
        checks.put(name, strategy);
        log.debug("Health check registered", "name", name);
    }

    /**
     * Unregisters a health check.
     */
    public void unregisterCheck(String name) {
        checks.remove(name);
        log.debug("Health check unregistered", "name", name);
    }

    /**
     * Gets all registered check names.
     */
    public List<String> getRegisteredChecks() {
        return new ArrayList<>(checks.keySet());
    }

    // ==================== Check Execution ====================

    /**
     * Runs a specific health check.
     */
    public HealthResult runCheck(String name) {
        HealthCheckStrategy strategy = checks.get(name);
        if (strategy == null) {
            return HealthResult.unhealthy("Check not found: " + name);
        }

        try {
            Instant start = Instant.now();
            HealthResult result = strategy.check();
            Duration duration = Duration.between(start, Instant.now());
            return result.withDuration(duration);
        } catch (Exception e) {
            log.error("Health check failed", e, "name", name);
            return HealthResult.unhealthy("Check threw exception: " + e.getMessage());
        }
    }

    /**
     * Runs all registered health checks.
     */
    public HealthResult runAllChecks() {
        if (checks.isEmpty()) {
            HealthResult result = HealthResult.healthy("No checks registered");
            updateStatus(result);
            return result;
        }

        Instant start = Instant.now();
        CompositeHealthCheck composite = new CompositeHealthCheck("all-checks");

        checks.forEach((name, strategy) -> composite.addCheck(name, strategy));

        HealthResult result = composite.check();
        Duration duration = Duration.between(start, Instant.now());
        result = result.withDuration(duration);

        updateStatus(result);
        return result;
    }

    // ==================== Status Management ====================

    /**
     * Updates current status and notifies listeners if changed.
     */
    private void updateStatus(HealthResult result) {
        lastResult.set(result);
        HealthStatus oldStatus = currentStatus.get();
        HealthStatus newStatus = result.getStatus();

        if (oldStatus != newStatus) {
            currentStatus.set(newStatus);
            notifyListeners(oldStatus, newStatus, result);
        }
    }

    /**
     * Gets current health status.
     */
    public HealthStatus getCurrentStatus() {
        return currentStatus.get();
    }

    /**
     * Gets last health check result.
     */
    public HealthResult getLastResult() {
        return lastResult.get();
    }

    // ==================== Observer Pattern ====================

    /**
     * Adds a health change listener.
     */
    public void addListener(HealthChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a health change listener.
     */
    public void removeListener(HealthChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifies all listeners of health status change.
     */
    private void notifyListeners(HealthStatus oldStatus, HealthStatus newStatus, HealthResult result) {
        for (HealthChangeListener listener : listeners) {
            try {
                listener.onHealthChanged(oldStatus, newStatus, result);
            } catch (Exception e) {
                log.error("Listener notification failed", e);
            }
        }
    }

    // ==================== Background Monitoring ====================

    /**
     * Starts background health monitoring.
     */
    public void startMonitoring(Duration interval) {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }

        scheduler = ThreadPoolFactory.createNamedScheduler(
                "HealthCheck-Monitor", "service");

        long periodMs = interval.toMillis();
        monitoringTask = scheduler.scheduleAtFixedRate(
                this::runAllChecks,
                periodMs,
                periodMs,
                TimeUnit.MILLISECONDS
        );

        log.info("Health monitoring started", "interval", interval);
    }

    /**
     * Stops background health monitoring.
     */
    public void stopMonitoring() {
        if (monitoringTask != null) {
            monitoringTask.cancel(false);
            monitoringTask = null;
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }

        log.info("Health monitoring stopped");
    }

    // ==================== AutoCloseable ====================

    @Override
    public void close() {
        stopMonitoring();
        checks.clear();
        listeners.clear();
    }
}

// ==================== Health Status Enum ====================

// ==================== Health Result ====================

// ==================== Health Check Strategy ====================

/**
 * Strategy interface for health checks.
 */
@FunctionalInterface
interface HealthCheckStrategy {
    HealthResult check();
}

// ==================== Specific Health Check Implementations ====================

/**
 * Checks if node is running.
 */
class NodeRunningCheck implements HealthCheckStrategy {
    private final Supplier<Boolean> isRunningSupplier;

    public NodeRunningCheck(Supplier<Boolean> isRunningSupplier) {
        this.isRunningSupplier = isRunningSupplier;
    }

    @Override
    public HealthResult check() {
        boolean isRunning = isRunningSupplier.get();
        if (isRunning) {
            return HealthResult.healthy("Node is running");
        } else {
            return HealthResult.unhealthy("Node is not running");
        }
    }
}

/**
 * Checks thread pool usage.
 */
class ThreadPoolCheck implements HealthCheckStrategy {
    private final Supplier<Integer> activeThreadsSupplier;
    private final Supplier<Integer> maxThreadsSupplier;
    private final double threshold;

    public ThreadPoolCheck(Supplier<Integer> activeThreadsSupplier,
                           Supplier<Integer> maxThreadsSupplier,
                           double threshold) {
        this.activeThreadsSupplier = activeThreadsSupplier;
        this.maxThreadsSupplier = maxThreadsSupplier;
        this.threshold = threshold;
    }

    @Override
    public HealthResult check() {
        int activeThreads = activeThreadsSupplier.get();
        int maxThreads = maxThreadsSupplier.get();

        double usageRatio = (double) activeThreads / maxThreads;

        if (usageRatio >= threshold) {
            return HealthResult.degraded(
                    String.format("Thread pool usage high: %d/%d (%.1f%%)",
                            activeThreads, maxThreads, usageRatio * 100));
        } else {
            return HealthResult.healthy(
                    String.format("Thread pool usage normal: %d/%d",
                            activeThreads, maxThreads));
        }
    }
}

/**
 * Listener interface for health status changes.
 */
@FunctionalInterface
interface HealthChangeListener {
    void onHealthChanged(HealthStatus oldStatus, HealthStatus newStatus, HealthResult result);
}