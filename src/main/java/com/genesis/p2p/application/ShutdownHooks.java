package com.genesis.p2p.application;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ShutdownHooks - Manages graceful shutdown hooks for the application.
 *
 * Design Patterns Applied:
 * - Chain of Responsibility: Ordered shutdown handler chain
 * - Observer Pattern: Shutdown event notifications
 * - Template Method: Customizable shutdown phases
 *
 * Provides centralized management of shutdown procedures:
 * - JVM shutdown hooks
 * - Resource cleanup
 * - Ordered shutdown sequence
 * - Timeout handling
 * - Phase-based shutdown
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ShutdownHooks {

    private static final NodeLogger log = NodeLogger.getLogger(ShutdownHooks.class);
    private static final long DEFAULT_TIMEOUT_MS = 30_000; // 30 seconds

    // Chain of Responsibility: Linked handlers
    private ShutdownHandler handlerChain;
    private final List<ShutdownListener> listeners;
    private final Thread shutdownThread;
    private final AtomicBoolean shutdownInitiated;

    // Configuration
    private long timeoutMs = DEFAULT_TIMEOUT_MS;

    public ShutdownHooks() {
        this.listeners = new ArrayList<>();
        this.shutdownInitiated = new AtomicBoolean(false);
        this.shutdownThread = createShutdownThread();

        // Register JVM shutdown hook
        Runtime.getRuntime().addShutdownHook(shutdownThread);

        // Initialize default handler chain
        initializeDefaultHandlerChain();

        log.info("Shutdown hooks initialized");
    }

    // ==================== Chain of Responsibility Pattern ====================

    /**
     * Shutdown handler interface.
     * Chain of Responsibility - Each handler can process or pass to next.
     */
    public abstract static class ShutdownHandler {
        protected ShutdownHandler next;
        protected final String name;
        protected final int priority;

        protected ShutdownHandler(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        /**
         * Sets the next handler in the chain.
         */
        public ShutdownHandler setNext(ShutdownHandler next) {
            this.next = next;
            return next;
        }

        /**
         * Handles the shutdown request.
         *
         * @param context the shutdown context
         * @return result of handling
         */
        public final ShutdownResult handle(ShutdownContext context) {
            log.info("Executing shutdown handler", "name", name, "priority", priority);

            Instant start = Instant.now();
            ShutdownResult result;

            try {
                result = doHandle(context);
            } catch (Exception e) {
                log.error("Shutdown handler failed", e, "name", name);
                result = ShutdownResult.failure(name, e.getMessage());
            }

            result = result.withDuration(Duration.between(start, Instant.now()));

            // Pass to next handler in chain
            if (next != null && !context.isAborted()) {
                ShutdownResult nextResult = next.handle(context);
                return result.merge(nextResult);
            }

            return result;
        }

        /**
         * Performs the actual shutdown handling.
         */
        protected abstract ShutdownResult doHandle(ShutdownContext context);

        public String getName() {
            return name;
        }

        public int getPriority() {
            return priority;
        }
    }

    /**
     * Shutdown context passed through the chain.
     */
    public static class ShutdownContext {
        private final Map<String, Object> attributes;
        private final Instant startTime;
        private final long timeoutMs;
        private boolean aborted;
        private String abortReason;

        public ShutdownContext(long timeoutMs) {
            this.attributes = new HashMap<>();
            this.startTime = Instant.now();
            this.timeoutMs = timeoutMs;
            this.aborted = false;
        }

        public void setAttribute(String key, Object value) {
            attributes.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T getAttribute(String key) {
            return (T) attributes.get(key);
        }

        public long getRemainingTimeMs() {
            long elapsed = Duration.between(startTime, Instant.now()).toMillis();
            return Math.max(0, timeoutMs - elapsed);
        }

        public boolean isTimedOut() {
            return getRemainingTimeMs() <= 0;
        }

        public void abort(String reason) {
            this.aborted = true;
            this.abortReason = reason;
        }

        public boolean isAborted() {
            return aborted;
        }

        public String getAbortReason() {
            return abortReason;
        }
    }

    /**
     * Shutdown result.
     */
    public record ShutdownResult(
            String handlerName,
            boolean success,
            String message,
            Duration duration,
            List<ShutdownResult> childResults
    ) {
        public static ShutdownResult success(String handlerName) {
            return new ShutdownResult(handlerName, true, "OK", Duration.ZERO, new ArrayList<>());
        }

        public static ShutdownResult failure(String handlerName, String message) {
            return new ShutdownResult(handlerName, false, message, Duration.ZERO, new ArrayList<>());
        }

        public ShutdownResult withDuration(Duration duration) {
            return new ShutdownResult(handlerName, success, message, duration, childResults);
        }

        public ShutdownResult merge(ShutdownResult other) {
            List<ShutdownResult> merged = new ArrayList<>(childResults);
            merged.add(other);
            return new ShutdownResult(
                    handlerName,
                    success && other.success,
                    success ? other.message : message,
                    duration,
                    merged
            );
        }
    }

    // ==================== Built-in Handlers ====================

    /**
     * Handler for stopping services.
     */
    public static class StopServicesHandler extends ShutdownHandler {
        private final List<Runnable> services;

        public StopServicesHandler(List<Runnable> services) {
            super("StopServices", Priority.HIGH);
            this.services = services;
        }

        @Override
        protected ShutdownResult doHandle(ShutdownContext context) {
            int stopped = 0;
            for (Runnable service : services) {
                try {
                    service.run();
                    stopped++;
                } catch (Exception e) {
                    log.error("Failed to stop service", e);
                }
            }
            return ShutdownResult.success(name)
                    .withDuration(Duration.ZERO);
        }
    }

    /**
     * Handler for closing resources.
     */
    public static class CloseResourcesHandler extends ShutdownHandler {
        private final List<AutoCloseable> resources;

        public CloseResourcesHandler(List<AutoCloseable> resources) {
            super("CloseResources", Priority.NORMAL);
            this.resources = resources;
        }

        @Override
        protected ShutdownResult doHandle(ShutdownContext context) {
            int closed = 0;
            for (AutoCloseable resource : resources) {
                try {
                    resource.close();
                    closed++;
                } catch (Exception e) {
                    log.error("Failed to close resource", e);
                }
            }
            return ShutdownResult.success(name);
        }
    }

    /**
     * Handler for flushing data.
     */
    public static class FlushDataHandler extends ShutdownHandler {
        private final List<Runnable> flushActions;

        public FlushDataHandler(List<Runnable> flushActions) {
            super("FlushData", Priority.HIGHEST);
            this.flushActions = flushActions;
        }

        @Override
        protected ShutdownResult doHandle(ShutdownContext context) {
            for (Runnable action : flushActions) {
                try {
                    action.run();
                } catch (Exception e) {
                    log.error("Failed to flush data", e);
                }
            }
            return ShutdownResult.success(name);
        }
    }

    /**
     * Lambda-based handler for custom shutdown actions.
     */
    public static class LambdaHandler extends ShutdownHandler {
        private final Runnable action;

        public LambdaHandler(String name, int priority, Runnable action) {
            super(name, priority);
            this.action = action;
        }

        @Override
        protected ShutdownResult doHandle(ShutdownContext context) {
            action.run();
            return ShutdownResult.success(name);
        }
    }

    // ==================== Chain Management ====================

    /**
     * Initializes the default handler chain.
     */
    private void initializeDefaultHandlerChain() {
        // Default empty chain - handlers added via addHandler
        handlerChain = null;
    }

    /**
     * Adds a handler to the chain (sorted by priority).
     *
     * @param handler the handler to add
     */
    public void addHandler(ShutdownHandler handler) {
        if (handlerChain == null) {
            handlerChain = handler;
        } else {
            // Insert in priority order (higher priority first)
            if (handler.priority > handlerChain.priority) {
                handler.setNext(handlerChain);
                handlerChain = handler;
            } else {
                ShutdownHandler current = handlerChain;
                while (current.next != null && current.next.priority >= handler.priority) {
                    current = current.next;
                }
                handler.setNext(current.next);
                current.setNext(handler);
            }
        }
        log.debug("Shutdown handler added", "name", handler.name, "priority", handler.priority);
    }

    /**
     * Adds a simple shutdown hook with priority.
     *
     * @param name     hook name
     * @param priority priority level
     * @param action   action to run
     */
    public void addHook(String name, int priority, Runnable action) {
        addHandler(new LambdaHandler(name, priority, action));
    }

    /**
     * Adds a shutdown hook with default priority.
     *
     * @param name   hook name
     * @param action action to run
     */
    public void addHook(String name, Runnable action) {
        addHook(name, Priority.NORMAL, action);
    }

    // ==================== Observer Pattern: Listeners ====================

    /**
     * Shutdown listener interface.
     * Observer Pattern - Notified of shutdown events.
     */
    public interface ShutdownListener {
        default void onShutdownStarted() {}
        default void onShutdownPhaseCompleted(String phaseName, boolean success) {}
        default void onShutdownCompleted(ShutdownResult result) {}
        default void onShutdownFailed(Exception error) {}
    }

    /**
     * Adds a shutdown listener.
     */
    public void addListener(ShutdownListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a shutdown listener.
     */
    public void removeListener(ShutdownListener listener) {
        listeners.remove(listener);
    }

    private void notifyShutdownStarted() {
        listeners.forEach(l -> {
            try {
                l.onShutdownStarted();
            } catch (Exception e) {
                log.error("Shutdown listener error", e);
            }
        });
    }

    private void notifyShutdownCompleted(ShutdownResult result) {
        listeners.forEach(l -> {
            try {
                l.onShutdownCompleted(result);
            } catch (Exception e) {
                log.error("Shutdown listener error", e);
            }
        });
    }

    // ==================== Shutdown Execution ====================

    /**
     * Creates the shutdown thread.
     */
    private Thread createShutdownThread() {
        return new Thread(() -> {
            if (shutdownInitiated.getAndSet(true)) {
                return;
            }

            log.info("Initiating graceful shutdown...");
            long startTime = System.currentTimeMillis();

            notifyShutdownStarted();

            try {
                ShutdownContext context = new ShutdownContext(timeoutMs);
                ShutdownResult result;

                if (handlerChain != null) {
                    result = handlerChain.handle(context);
                } else {
                    result = ShutdownResult.success("NoHandlers");
                }

                notifyShutdownCompleted(result);

                long duration = Time.currentMillis() - startTime;
                log.info("Shutdown completed",
                        "success", result.success(),
                        "durationMs", duration);

            } catch (Exception e) {
                log.error("Shutdown failed", e);
                listeners.forEach(l -> {
                    try {
                        l.onShutdownFailed(e);
                    } catch (Exception ex) {
                        log.error("Shutdown listener error", ex);
                    }
                });
            }

        }, "ShutdownHook-Thread");
    }

    /**
     * Manually triggers shutdown (for testing).
     */
    public void shutdown() {
        if (!shutdownInitiated.get()) {
            shutdownThread.run();
        }
    }

    /**
     * Triggers shutdown asynchronously.
     */
    public void shutdownAsync() {
        if (!shutdownInitiated.get()) {
            new Thread(shutdownThread, "Async-Shutdown").start();
        }
    }

    /**
     * Removes the JVM shutdown hook.
     */
    public void removeShutdownHook() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownThread);
            log.info("Shutdown hook removed");
        } catch (IllegalStateException e) {
            // Already shutting down
        }
    }

    /**
     * Sets shutdown timeout.
     *
     * @param timeout the timeout duration
     */
    public void setTimeout(Duration timeout) {
        this.timeoutMs = timeout.toMillis();
    }

    /**
     * Checks if shutdown has been initiated.
     */
    public boolean isShutdownInitiated() {
        return shutdownInitiated.get();
    }

    // ==================== Priority Levels ====================

    /**
     * Priority levels for shutdown handlers.
     */
    public static class Priority {
        public static final int HIGHEST = 100;
        public static final int HIGH = 75;
        public static final int NORMAL = 50;
        public static final int LOW = 25;
        public static final int LOWEST = 0;
    }

    // ==================== Shutdown Phases ====================

    /**
     * Shutdown phase enumeration.
     */
    public enum ShutdownPhase {
        FLUSH_DATA(Priority.HIGHEST, "Flushing data"),
        STOP_ACCEPTING(Priority.HIGH, "Stop accepting new connections"),
        DRAIN_REQUESTS(Priority.HIGH, "Draining pending requests"),
        STOP_SERVICES(Priority.NORMAL, "Stopping services"),
        CLOSE_CONNECTIONS(Priority.NORMAL, "Closing connections"),
        RELEASE_RESOURCES(Priority.LOW, "Releasing resources"),
        CLEANUP(Priority.LOWEST, "Final cleanup");

        private final int priority;
        private final String description;

        ShutdownPhase(int priority, String description) {
            this.priority = priority;
            this.description = description;
        }

        public int getPriority() {
            return priority;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Creates a phase-based shutdown handler.
     *
     * @param phase  the shutdown phase
     * @param action the action to run
     * @return a shutdown handler for the phase
     */
    public ShutdownHandler createPhaseHandler(ShutdownPhase phase, Runnable action) {
        return new LambdaHandler(phase.name(), phase.getPriority(), action);
    }
}
