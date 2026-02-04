package com.genesis.p2p.protocol.handshake;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Configurable retry policy for handshake operations.
 *
 * Features:
 * - Exponential backoff with jitter
 * - Per-peer retry tracking
 * - Circuit breaker integration
 * - Retry budget limits
 * - Metrics integration
 *
 * Safe behaviors:
 * - Respects max retry limits to prevent infinite loops
 * - Applies jitter to prevent thundering herd
 * - Tracks per-peer state to avoid retrying bad peers
 * - Integrates with HandshakeMetrics for observability
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class HandshakeRetryPolicy {

    private static final NodeLogger log = NodeLogger.getLogger(HandshakeRetryPolicy.class);

    private final HandshakeConfig config;
    private final HandshakeMetrics metrics;
    private final ScheduledExecutorService scheduler;

    // Per-peer retry tracking
    private final Map<String, RetryState> peerRetryStates = new ConcurrentHashMap<>();

    // Global retry budget (prevents system-wide retry storms)
    private final Semaphore globalRetryBudget;
    private static final int DEFAULT_GLOBAL_RETRY_BUDGET = 100;

    // Blacklist for peers with repeated failures
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();
    private static final long BLACKLIST_DURATION_MS = TimeUnit.MINUTES.toMillis(5);

    public HandshakeRetryPolicy(HandshakeConfig config, HandshakeMetrics metrics) {
        this(config, metrics, Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "HandshakeRetry");
            t.setDaemon(true);
            return t;
        }));
    }

    public HandshakeRetryPolicy(HandshakeConfig config, HandshakeMetrics metrics,
                                 ScheduledExecutorService scheduler) {
        this.config = config;
        this.metrics = metrics;
        this.scheduler = scheduler;
        this.globalRetryBudget = new Semaphore(DEFAULT_GLOBAL_RETRY_BUDGET);

        log.info("HandshakeRetryPolicy initialized",
                "maxRetries", config.getMaxRetryAttempts(),
                "initialDelay", config.getInitialRetryDelay(),
                "maxDelay", config.getMaxRetryDelay(),
                "backoffMultiplier", config.getRetryBackoffMultiplier());
    }

    /**
     * Executes a handshake operation with retry support.
     *
     * @param peerId the target peer ID
     * @param operation the handshake operation to execute
     * @param <T> the result type
     * @return future containing the result or failure
     */
    public <T> CompletableFuture<RetryResult<T>> executeWithRetry(
            String peerId,
            Supplier<CompletableFuture<T>> operation) {

        // Check blacklist
        if (isBlacklisted(peerId)) {
            log.debug("Peer is blacklisted, skipping retry", "peerId", peerId);
            return CompletableFuture.completedFuture(
                    RetryResult.blacklisted(peerId));
        }

        // Get or create retry state for this peer
        RetryState state = peerRetryStates.computeIfAbsent(
                peerId, k -> new RetryState(config.getMaxRetryAttempts()));

        // Check if max retries exceeded
        if (state.getAttemptCount() >= config.getMaxRetryAttempts()) {
            log.debug("Max retries exceeded", "peerId", peerId,
                    "attempts", state.getAttemptCount());
            metrics.recordRetryExhausted(peerId, state.getAttemptCount());
            return CompletableFuture.completedFuture(
                    RetryResult.exhausted(peerId, state.getAttemptCount()));
        }

        return executeAttempt(peerId, operation, state, 1);
    }

    private <T> CompletableFuture<RetryResult<T>> executeAttempt(
            String peerId,
            Supplier<CompletableFuture<T>> operation,
            RetryState state,
            int attemptNumber) {

        CompletableFuture<RetryResult<T>> result = new CompletableFuture<>();

        try {
            // Try to acquire global retry budget
            if (attemptNumber > 1 && !globalRetryBudget.tryAcquire()) {
                log.warn("Global retry budget exhausted", "peerId", peerId);
                result.complete(RetryResult.budgetExhausted(peerId));
                return result;
            }

            state.incrementAttempt();
            long startTime = System.currentTimeMillis();

            // Execute the operation
            operation.get()
                    .whenComplete((value, error) -> {
                        long duration = System.currentTimeMillis() - startTime;

                        if (attemptNumber > 1) {
                            globalRetryBudget.release();
                        }

                        if (error == null) {
                            // Success
                            handleSuccess(peerId, state, attemptNumber, value, result);
                        } else {
                            // Failure - decide whether to retry
                            handleFailure(peerId, operation, state, attemptNumber,
                                    error, duration, result);
                        }
                    });

        } catch (Exception e) {
            log.error("Unexpected error in retry execution", e, "peerId", peerId);
            result.complete(RetryResult.failed(peerId, e, 0));
        }

        return result;
    }

    private <T> void handleSuccess(String peerId, RetryState state, int attemptNumber,
                                   T value, CompletableFuture<RetryResult<T>> result) {
        state.recordSuccess();

        if (attemptNumber > 1) {
            metrics.recordRetrySuccess(peerId, attemptNumber);
            log.info("Handshake succeeded after retry",
                    "peerId", peerId,
                    "attempt", attemptNumber);
        }

        // Clear retry state on success
        peerRetryStates.remove(peerId);

        result.complete(RetryResult.success(value, attemptNumber));
    }

    private <T> void handleFailure(String peerId,
                                   Supplier<CompletableFuture<T>> operation,
                                   RetryState state,
                                   int attemptNumber,
                                   Throwable error,
                                   long durationMs,
                                   CompletableFuture<RetryResult<T>> result) {

        state.recordFailure(error);

        // Check if we should retry
        if (!shouldRetry(peerId, error, attemptNumber, state)) {
            log.debug("Not retrying",
                    "peerId", peerId,
                    "attempt", attemptNumber,
                    "reason", getNoRetryReason(error, attemptNumber, state));

            // Add to blacklist if too many consecutive failures
            if (state.getConsecutiveFailures() >= config.getMaxRetryAttempts()) {
                addToBlacklist(peerId);
            }

            result.complete(RetryResult.failed(peerId, error, attemptNumber));
            return;
        }

        // Calculate delay with exponential backoff and jitter
        Duration delay = calculateDelay(attemptNumber);

        log.debug("Scheduling retry",
                "peerId", peerId,
                "attempt", attemptNumber + 1,
                "delayMs", delay.toMillis());

        metrics.recordRetryAttempt(peerId, attemptNumber, delay);

        // Schedule retry
        scheduler.schedule(() -> {
            executeAttempt(peerId, operation, state, attemptNumber + 1)
                    .whenComplete((retryResult, retryError) -> {
                        if (retryError != null) {
                            result.complete(RetryResult.failed(peerId, retryError, attemptNumber + 1));
                        } else {
                            result.complete(retryResult);
                        }
                    });
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Determines if a retry should be attempted.
     */
    private boolean shouldRetry(String peerId, Throwable error, int attemptNumber, RetryState state) {
        // Don't retry if max attempts reached
        if (attemptNumber >= config.getMaxRetryAttempts()) {
            return false;
        }

        // Don't retry for non-retryable errors
        if (!isRetryableError(error)) {
            return false;
        }

        // Don't retry if peer is blacklisted
        if (isBlacklisted(peerId)) {
            return false;
        }

        return true;
    }

    private String getNoRetryReason(Throwable error, int attemptNumber, RetryState state) {
        if (attemptNumber >= config.getMaxRetryAttempts()) {
            return "max_attempts_reached";
        }
        if (!isRetryableError(error)) {
            return "non_retryable_error: " + error.getClass().getSimpleName();
        }
        return "unknown";
    }

    /**
     * Determines if an error is retryable.
     */
    private boolean isRetryableError(Throwable error) {
        if (error == null) {
            return false;
        }

        // Timeout errors are retryable
        if (error instanceof TimeoutException) {
            return true;
        }

        // Connection errors are retryable
        if (error instanceof java.net.ConnectException ||
            error instanceof java.net.SocketTimeoutException) {
            return true;
        }

        // IO errors are generally retryable
        if (error instanceof java.io.IOException) {
            return true;
        }

        // Check cause
        if (error.getCause() != null && error.getCause() != error) {
            return isRetryableError(error.getCause());
        }

        // Default: don't retry unknown errors
        return false;
    }

    /**
     * Calculates the delay for a retry attempt using exponential backoff with jitter.
     */
    Duration calculateDelay(int attemptNumber) {
        // Exponential backoff: initialDelay * (multiplier ^ (attempt - 1))
        double delayMs = config.getInitialRetryDelay().toMillis() *
                Math.pow(config.getRetryBackoffMultiplier(), attemptNumber - 1);

        // Cap at max delay
        delayMs = Math.min(delayMs, config.getMaxRetryDelay().toMillis());

        // Add jitter: ±(jitterFactor * delay)
        double jitter = config.getRetryJitterFactor();
        if (jitter > 0) {
            double jitterAmount = delayMs * jitter;
            delayMs += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitterAmount;
        }

        // Ensure positive delay
        return Duration.ofMillis(Math.max(1, (long) delayMs));
    }

    /**
     * Checks if a peer is blacklisted.
     */
    public boolean isBlacklisted(String peerId) {
        Long blacklistedAt = blacklist.get(peerId);
        if (blacklistedAt == null) {
            return false;
        }

        // Check if blacklist has expired
        if (System.currentTimeMillis() - blacklistedAt > BLACKLIST_DURATION_MS) {
            blacklist.remove(peerId);
            log.debug("Peer removed from blacklist (expired)", "peerId", peerId);
            return false;
        }

        return true;
    }

    /**
     * Adds a peer to the blacklist.
     */
    public void addToBlacklist(String peerId) {
        blacklist.put(peerId, System.currentTimeMillis());
        log.info("Peer added to blacklist",
                "peerId", peerId,
                "durationMinutes", BLACKLIST_DURATION_MS / 60000);
    }

    /**
     * Removes a peer from the blacklist.
     */
    public void removeFromBlacklist(String peerId) {
        blacklist.remove(peerId);
        log.info("Peer removed from blacklist", "peerId", peerId);
    }

    /**
     * Clears all retry state for a peer.
     */
    public void clearRetryState(String peerId) {
        peerRetryStates.remove(peerId);
        log.debug("Retry state cleared", "peerId", peerId);
    }

    /**
     * Gets the current retry state for a peer.
     */
    public RetryState getRetryState(String peerId) {
        return peerRetryStates.get(peerId);
    }

    /**
     * Gets the number of blacklisted peers.
     */
    public int getBlacklistSize() {
        return blacklist.size();
    }

    /**
     * Shuts down the retry policy.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("HandshakeRetryPolicy shutdown");
    }

    // ========================= Inner Classes =========================

    /**
     * Tracks retry state for a peer.
     */
    public static class RetryState {
        private final int maxAttempts;
        private int attemptCount = 0;
        private int consecutiveFailures = 0;
        private long lastAttemptTime = 0;
        private Throwable lastError = null;

        public RetryState(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public synchronized void incrementAttempt() {
            attemptCount++;
            lastAttemptTime = System.currentTimeMillis();
        }

        public synchronized void recordSuccess() {
            consecutiveFailures = 0;
            lastError = null;
        }

        public synchronized void recordFailure(Throwable error) {
            consecutiveFailures++;
            lastError = error;
        }

        public int getAttemptCount() {
            return attemptCount;
        }

        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        public long getLastAttemptTime() {
            return lastAttemptTime;
        }

        public Throwable getLastError() {
            return lastError;
        }

        public boolean canRetry() {
            return attemptCount < maxAttempts;
        }
    }

    /**
     * Result of a retry operation.
     */
    public static class RetryResult<T> {
        private final T value;
        private final Throwable error;
        private final int attempts;
        private final Status status;
        private final String peerId;

        private RetryResult(T value, Throwable error, int attempts, Status status, String peerId) {
            this.value = value;
            this.error = error;
            this.attempts = attempts;
            this.status = status;
            this.peerId = peerId;
        }

        public static <T> RetryResult<T> success(T value, int attempts) {
            return new RetryResult<>(value, null, attempts, Status.SUCCESS, null);
        }

        public static <T> RetryResult<T> failed(String peerId, Throwable error, int attempts) {
            return new RetryResult<>(null, error, attempts, Status.FAILED, peerId);
        }

        public static <T> RetryResult<T> exhausted(String peerId, int attempts) {
            return new RetryResult<>(null, null, attempts, Status.EXHAUSTED, peerId);
        }

        public static <T> RetryResult<T> blacklisted(String peerId) {
            return new RetryResult<>(null, null, 0, Status.BLACKLISTED, peerId);
        }

        public static <T> RetryResult<T> budgetExhausted(String peerId) {
            return new RetryResult<>(null, null, 0, Status.BUDGET_EXHAUSTED, peerId);
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public T getValue() {
            return value;
        }

        public Throwable getError() {
            return error;
        }

        public int getAttempts() {
            return attempts;
        }

        public Status getStatus() {
            return status;
        }

        public String getPeerId() {
            return peerId;
        }

        public enum Status {
            SUCCESS,
            FAILED,
            EXHAUSTED,
            BLACKLISTED,
            BUDGET_EXHAUSTED
        }

        @Override
        public String toString() {
            return String.format("RetryResult[status=%s, attempts=%d, peerId=%s]",
                    status, attempts, peerId);
        }
    }
}
