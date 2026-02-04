package com.genesis.p2p.core.handlers.retry;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.FailedMessage;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manages message retry logic with exponential backoff.
 *
 * Features:
 * - Exponential backoff with jitter
 * - Configurable max retries
 * - Retry statistics
 * - Per-message retry tracking
 * - Circuit breaker integration
 */
public class RetryManager implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(RetryManager.class);

    private final int maxRetries;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double backoffMultiplier;
    private final double jitterFactor;

    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, RetryContext> retryContexts;
    private final MetricsRegistry metrics;

    private final AtomicInteger totalRetries;
    private final AtomicInteger successfulRetries;
    private final AtomicInteger failedRetries;

    /**
     * Creates RetryManager with default configuration.
     */
    public RetryManager(MetricsRegistry metrics) {
        this(3, Duration.ofSeconds(1), Duration.ofSeconds(60), 2.0, 0.1, metrics);
    }

    /**
     * Creates RetryManager with custom configuration.
     *
     * @param maxRetries maximum retry attempts
     * @param initialDelay initial delay before first retry
     * @param maxDelay maximum delay between retries
     * @param backoffMultiplier multiplier for exponential backoff
     * @param jitterFactor randomization factor (0.0 to 1.0)
     * @param metrics metrics registry
     */
    public RetryManager(int maxRetries, Duration initialDelay, Duration maxDelay,
                        double backoffMultiplier, double jitterFactor,
                        MetricsRegistry metrics) {
        this.maxRetries = maxRetries;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.backoffMultiplier = backoffMultiplier;
        this.jitterFactor = jitterFactor;
        this.metrics = metrics;

        this.scheduler = ThreadPoolFactory.createNamedScheduler(
                "RetryManager", "handler"
        );

        this.retryContexts = new ConcurrentHashMap<>();
        this.totalRetries = new AtomicInteger(0);
        this.successfulRetries = new AtomicInteger(0);
        this.failedRetries = new AtomicInteger(0);

        log.info("RetryManager initialized",
                "maxRetries", maxRetries,
                "initialDelay", initialDelay,
                "maxDelay", maxDelay);
    }

    // ========================= Retry Operations =========================

    /**
     * Schedules a retry for a failed message.
     *
     * @param message the message to retry
     * @param currentAttempt current attempt number
     * @param retryAction action to execute on retry
     * @return true if retry was scheduled, false if max retries exceeded
     */
    public boolean scheduleRetry(Message message, int currentAttempt,
                                 Consumer<Message> retryAction) {
        String messageId = message.header().messageId();

        if (currentAttempt >= maxRetries) {
            log.warn("Max retries exceeded", "messageId", messageId, "attempts", currentAttempt);
            failedRetries.incrementAndGet();
            metrics.incrementCounter("retry.max_exceeded");
            retryContexts.remove(messageId);
            return false;
        }

        long delayMs = calculateDelay(currentAttempt);

        RetryContext context = retryContexts.computeIfAbsent(messageId,
                k -> new RetryContext(message, currentAttempt));
        context.nextAttempt = currentAttempt + 1;
        context.lastRetryTime = Instant.now();

        scheduler.schedule(() -> {
            try {
                log.info("Retrying message",
                        "messageId", messageId,
                        "attempt", context.nextAttempt,
                        "delay", delayMs + "ms");

                totalRetries.incrementAndGet();
                metrics.incrementCounter("retry.attempted");

                retryAction.accept(message);

                successfulRetries.incrementAndGet();
                metrics.incrementCounter("retry.successful");
                retryContexts.remove(messageId);

            } catch (Exception e) {
                log.error("Retry failed", e, "messageId", messageId);
                metrics.incrementCounter("retry.failed");

                // Schedule next retry
                scheduleRetry(message, context.nextAttempt, retryAction);
            }
        }, delayMs, TimeUnit.MILLISECONDS);

        return true;
    }

    /**
     * Calculates retry delay with exponential backoff and jitter.
     *
     * @param attempt current attempt number
     * @return delay in milliseconds
     */
    private long calculateDelay(int attempt) {
        // Exponential backoff: initialDelay * (multiplier ^ attempt)
        double baseDelay = initialDelay.toMillis() * Math.pow(backoffMultiplier, attempt);

        // Cap at max delay
        baseDelay = Math.min(baseDelay, maxDelay.toMillis());

        // Add jitter: delay * (1 ± jitterFactor)
        double jitter = baseDelay * jitterFactor * (Math.random() * 2 - 1);
        long finalDelay = (long) (baseDelay + jitter);

        return Math.max(0, finalDelay);
    }

    // ========================= Context Management =========================

    /**
     * Gets retry context for a message.
     *
     * @param messageId message identifier
     * @return retry context or null if not found
     */
    public RetryContext getRetryContext(String messageId) {
        return retryContexts.get(messageId);
    }

    /**
     * Checks if a message is being retried.
     *
     * @param messageId message identifier
     * @return true if retry is in progress
     */
    public boolean isRetrying(String messageId) {
        return retryContexts.containsKey(messageId);
    }

    /**
     * Cancels pending retry for a message.
     *
     * @param messageId message identifier
     * @return true if retry was cancelled
     */
    public boolean cancelRetry(String messageId) {
        RetryContext removed = retryContexts.remove(messageId);
        if (removed != null) {
            log.info("Retry cancelled", "messageId", messageId);
            metrics.incrementCounter("retry.cancelled");
            return true;
        }
        return false;
    }

    /**
     * Clears all pending retries.
     */
    public void clearAllRetries() {
        int count = retryContexts.size();
        retryContexts.clear();
        log.info("All retries cleared", "count", count);
    }

    // ========================= Statistics =========================

    /**
     * Gets retry statistics.
     *
     * @return retry statistics
     */
    public RetryStatistics getStatistics() {
        return new RetryStatistics(
                totalRetries.get(),
                successfulRetries.get(),
                failedRetries.get(),
                retryContexts.size()
        );
    }

    /**
     * Resets retry statistics.
     */
    public void resetStatistics() {
        totalRetries.set(0);
        successfulRetries.set(0);
        failedRetries.set(0);
        log.info("Retry statistics reset");
    }

    // ========================= Configuration =========================

    public int getMaxRetries() {
        return maxRetries;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    // ========================= Lifecycle =========================

    @Override
    public void close() {
        log.info("Shutting down RetryManager");

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("RetryManager shutdown complete",
                "pendingRetries", retryContexts.size(),
                "totalRetries", totalRetries.get());
    }

    // ========================= Inner Classes =========================

    /**
     * Retry context for a message.
     */
    public static class RetryContext {
        public final Message message;
        public final Instant firstAttemptTime;
        public volatile int nextAttempt;
        public volatile Instant lastRetryTime;

        public RetryContext(Message message, int currentAttempt) {
            this.message = message;
            this.firstAttemptTime = Instant.now();
            this.nextAttempt = currentAttempt + 1;
            this.lastRetryTime = Instant.now();
        }

        public Duration getTotalRetryDuration() {
            return Duration.between(firstAttemptTime, Instant.now());
        }
    }

    /**
     * Retry statistics.
     */
    public record RetryStatistics(
            int totalRetries,
            int successfulRetries,
            int failedRetries,
            int pendingRetries
    ) {
        public double getSuccessRate() {
            if (totalRetries == 0) return 0.0;
            return (double) successfulRetries / totalRetries * 100.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "RetryStats[total=%d, success=%d (%.1f%%), failed=%d, pending=%d]",
                    totalRetries, successfulRetries, getSuccessRate(),
                    failedRetries, pendingRetries
            );
        }
    }
}