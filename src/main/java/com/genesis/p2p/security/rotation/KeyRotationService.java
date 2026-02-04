package com.genesis.p2p.security.rotation;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.api.ISecureSession;
import com.genesis.p2p.security.api.ISessionManager;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Key Rotation Service - Manages automatic and manual key rotation for secure sessions.
 *
 * Features:
 * - Background monitoring of session age and message count
 * - Automatic rotation when thresholds exceeded
 * - Manual/forced rotation API
 * - Grace period for old key acceptance
 * - Event notifications for rotation events
 * - Metrics and logging integration
 *
 * Thread-safe and designed for production use.
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class KeyRotationService implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(KeyRotationService.class);

    private final ISessionManager sessionManager;
    private final KeyRotationConfig config;
    private final MetricsRegistry metrics;
    private final ScheduledExecutorService scheduler;

    // Rotation callbacks
    private final CopyOnWriteArrayList<BiConsumer<String, RotationResult>> rotationListeners;

    // State
    private final AtomicBoolean running;
    private ScheduledFuture<?> checkTask;

    // Statistics
    private final AtomicLong totalRotations;
    private final AtomicLong successfulRotations;
    private final AtomicLong failedRotations;
    private final AtomicLong forcedRotations;

    // Grace period tracking: peerId -> old session expiry
    private final ConcurrentHashMap<String, GracePeriodEntry> gracePeriodSessions;

    /**
     * Creates key rotation service.
     *
     * @param sessionManager session manager to rotate sessions in
     * @param config rotation configuration
     * @param metrics metrics registry
     */
    public KeyRotationService(ISessionManager sessionManager,
                              KeyRotationConfig config,
                              MetricsRegistry metrics) {
        this.sessionManager = sessionManager;
        this.config = config;
        this.metrics = metrics;
        this.scheduler = ThreadPoolFactory.createNamedScheduler("KeyRotation", "security");
        this.rotationListeners = new CopyOnWriteArrayList<>();
        this.running = new AtomicBoolean(false);
        this.totalRotations = new AtomicLong(0);
        this.successfulRotations = new AtomicLong(0);
        this.failedRotations = new AtomicLong(0);
        this.forcedRotations = new AtomicLong(0);
        this.gracePeriodSessions = new ConcurrentHashMap<>();

        log.info("KeyRotationService created",
                "maxAge", config.maxSessionAge(),
                "maxMessages", config.maxMessageCount(),
                "checkInterval", config.checkInterval());
    }

    // ==================== Lifecycle ====================

    /**
     * Starts the rotation service.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            if (config.autoRotationEnabled()) {
                long intervalMs = config.checkInterval().toMillis();
                checkTask = scheduler.scheduleAtFixedRate(
                        this::checkAllSessions,
                        intervalMs,
                        intervalMs,
                        TimeUnit.MILLISECONDS
                );
                log.info("KeyRotationService started", "checkInterval", config.checkInterval());
            } else {
                log.info("KeyRotationService started (auto-rotation disabled)");
            }
        }
    }

    /**
     * Stops the rotation service.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (checkTask != null) {
                checkTask.cancel(false);
                checkTask = null;
            }
            log.info("KeyRotationService stopped",
                    "totalRotations", totalRotations.get(),
                    "successful", successfulRotations.get(),
                    "failed", failedRotations.get());
        }
    }

    @Override
    public void close() {
        stop();
        ThreadPoolFactory.shutdownGracefully(scheduler, "KeyRotation", 5);
    }

    // ==================== Rotation Operations ====================

    /**
     * Checks if a session needs rotation.
     *
     * @param peerId peer to check
     * @return true if rotation is needed
     */
    public boolean needsRotation(String peerId) {
        ISecureSession session = sessionManager.getSession(peerId);
        if (session == null || !session.isActive()) {
            return false;
        }

        return checkRotationNeeded(session);
    }

    /**
     * Initiates key rotation for a peer.
     *
     * @param peerId peer to rotate keys for
     * @return future containing rotation result
     */
    public CompletableFuture<RotationResult> rotateKeys(String peerId) {
        return rotateKeysInternal(peerId, RotationReason.MANUAL);
    }

    /**
     * Forces immediate key rotation (e.g., after security incident).
     *
     * @param peerId peer to rotate keys for
     * @return future containing rotation result
     */
    public CompletableFuture<RotationResult> forceRotation(String peerId) {
        if (!config.forceRotationEnabled()) {
            return CompletableFuture.completedFuture(
                    RotationResult.failure(peerId, "Force rotation disabled"));
        }

        forcedRotations.incrementAndGet();
        metrics.incrementCounter("security.rotation.forced");

        return rotateKeysInternal(peerId, RotationReason.FORCED);
    }

    /**
     * Internal rotation implementation.
     */
    private CompletableFuture<RotationResult> rotateKeysInternal(String peerId, RotationReason reason) {
        totalRotations.incrementAndGet();
        metrics.incrementCounter("security.rotation.attempts");

        return CompletableFuture.supplyAsync(() -> {
            try {
                ISecureSession oldSession = sessionManager.getSession(peerId);
                if (oldSession == null) {
                    failedRotations.incrementAndGet();
                    metrics.incrementCounter("security.rotation.failed.no_session");
                    return RotationResult.failure(peerId, "No active session");
                }

                String oldSessionId = oldSession.getSessionId();
                long oldMessageCount = oldSession.getMessageCount();
                Duration oldAge = oldSession.getAge();

                log.info("Initiating key rotation",
                        "peerId", peerId,
                        "reason", reason,
                        "sessionAge", oldAge,
                        "messageCount", oldMessageCount);

                // Perform rotation via session manager
                boolean success = sessionManager.rotateSession(peerId);

                if (success) {
                    successfulRotations.incrementAndGet();
                    metrics.incrementCounter("security.rotation.successful");

                    // Start grace period for old session
                    startGracePeriod(peerId, oldSessionId);

                    ISecureSession newSession = sessionManager.getSession(peerId);
                    String newSessionId = newSession != null ? newSession.getSessionId() : "unknown";

                    log.info("Key rotation completed",
                            "peerId", peerId,
                            "oldSession", oldSessionId.substring(0, 8) + "...",
                            "newSession", newSessionId.substring(0, 8) + "...",
                            "reason", reason);

                    RotationResult result = RotationResult.success(
                            peerId, oldSessionId, newSessionId, reason);

                    // Notify listeners
                    notifyListeners(peerId, result);

                    return result;
                } else {
                    failedRotations.incrementAndGet();
                    metrics.incrementCounter("security.rotation.failed");
                    return RotationResult.failure(peerId, "Session manager rotation failed");
                }

            } catch (Exception e) {
                failedRotations.incrementAndGet();
                metrics.incrementCounter("security.rotation.failed.exception");
                log.error("Key rotation failed", e, "peerId", peerId);
                return RotationResult.failure(peerId, e.getMessage());
            }
        }, scheduler);
    }

    // ==================== Background Checking ====================

    /**
     * Checks all active sessions for rotation needs.
     */
    private void checkAllSessions() {
        if (!running.get()) return;

        try {
            // Clean expired grace periods
            cleanGracePeriods();

            // Check each active session
            for (String peerId : sessionManager.getActivePeerIds()) {
                ISecureSession session = sessionManager.getSession(peerId);
                if (session != null && session.isActive() && checkRotationNeeded(session)) {
                    log.debug("Session needs rotation", "peerId", peerId);

                    RotationReason reason = determineReason(session);
                    rotateKeysInternal(peerId, reason);
                }
            }
        } catch (Exception e) {
            log.error("Error during session check", e);
        }
    }

    /**
     * Checks if session needs rotation based on config thresholds.
     */
    private boolean checkRotationNeeded(ISecureSession session) {
        // Check message count
        if (session.getMessageCount() >= config.maxMessageCount()) {
            return true;
        }

        // Check age
        if (session.getAge().compareTo(config.maxSessionAge()) >= 0) {
            return true;
        }

        // Check security limit
        if (session.isNearingMessageLimit()) {
            return true;
        }

        return false;
    }

    /**
     * Determines the reason for rotation.
     */
    private RotationReason determineReason(ISecureSession session) {
        if (session.isNearingMessageLimit()) {
            return RotationReason.SECURITY_LIMIT;
        }
        if (session.getMessageCount() >= config.maxMessageCount()) {
            return RotationReason.MESSAGE_COUNT;
        }
        if (session.getAge().compareTo(config.maxSessionAge()) >= 0) {
            return RotationReason.TIME_BASED;
        }
        return RotationReason.AUTOMATIC;
    }

    // ==================== Grace Period Management ====================

    /**
     * Starts grace period for old session.
     */
    private void startGracePeriod(String peerId, String oldSessionId) {
        Instant expiry = Instant.now().plus(config.gracePeriod());
        gracePeriodSessions.put(peerId, new GracePeriodEntry(oldSessionId, expiry));

        log.debug("Grace period started",
                "peerId", peerId,
                "oldSession", oldSessionId.substring(0, 8) + "...",
                "expiry", expiry);
    }

    /**
     * Checks if message from old session should be accepted.
     */
    public boolean isInGracePeriod(String peerId, String sessionId) {
        GracePeriodEntry entry = gracePeriodSessions.get(peerId);
        if (entry == null) return false;

        return entry.sessionId.equals(sessionId) &&
               Instant.now().isBefore(entry.expiry);
    }

    /**
     * Cleans expired grace periods.
     */
    private void cleanGracePeriods() {
        Instant now = Instant.now();
        gracePeriodSessions.entrySet().removeIf(
                entry -> now.isAfter(entry.getValue().expiry)
        );
    }

    // ==================== Listeners ====================

    /**
     * Adds rotation listener.
     */
    public void addRotationListener(BiConsumer<String, RotationResult> listener) {
        rotationListeners.add(listener);
    }

    /**
     * Removes rotation listener.
     */
    public void removeRotationListener(BiConsumer<String, RotationResult> listener) {
        rotationListeners.remove(listener);
    }

    private void notifyListeners(String peerId, RotationResult result) {
        for (BiConsumer<String, RotationResult> listener : rotationListeners) {
            try {
                listener.accept(peerId, result);
            } catch (Exception e) {
                log.error("Rotation listener error", e);
            }
        }
    }

    // ==================== Statistics ====================

    /**
     * Gets rotation statistics.
     */
    public RotationStats getStats() {
        return new RotationStats(
                totalRotations.get(),
                successfulRotations.get(),
                failedRotations.get(),
                forcedRotations.get(),
                gracePeriodSessions.size(),
                running.get()
        );
    }

    // ==================== Inner Classes ====================

    /**
     * Rotation reason enum.
     */
    public enum RotationReason {
        MANUAL,          // Manually triggered
        AUTOMATIC,       // Background auto-rotation
        TIME_BASED,      // Session age exceeded
        MESSAGE_COUNT,   // Message count exceeded
        SECURITY_LIMIT,  // Near security limit
        FORCED           // Forced by administrator
    }

    /**
     * Rotation result record.
     */
    public record RotationResult(
            String peerId,
            boolean success,
            String oldSessionId,
            String newSessionId,
            RotationReason reason,
            String errorMessage,
            Instant timestamp
    ) {
        public static RotationResult success(String peerId, String oldId, String newId, RotationReason reason) {
            return new RotationResult(peerId, true, oldId, newId, reason, null, Instant.now());
        }

        public static RotationResult failure(String peerId, String error) {
            return new RotationResult(peerId, false, null, null, null, error, Instant.now());
        }
    }

    /**
     * Rotation statistics record.
     */
    public record RotationStats(
            long totalRotations,
            long successfulRotations,
            long failedRotations,
            long forcedRotations,
            int activeGracePeriods,
            boolean serviceRunning
    ) {}

    /**
     * Grace period entry.
     */
    private record GracePeriodEntry(String sessionId, Instant expiry) {}
}

