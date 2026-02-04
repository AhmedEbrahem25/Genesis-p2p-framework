package com.genesis.p2p.testutil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Utilities for async and concurrent testing.
 * <p>
 * Provides helper methods for:
 * - Waiting for conditions with timeouts
 * - Latch management with proper error handling
 * - Polling-based assertion helpers
 * - Safe async operations
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class AsyncTestUtils {

    private static final Logger log = LoggerFactory.getLogger(AsyncTestUtils.class);

    /**
     * Waits for a condition to become true within a timeout.
     * Polls the condition at regular intervals (50ms by default).
     *
     * @param condition the condition to wait for
     * @param timeout maximum wait time
     * @throws AssertionError if condition doesn't become true within timeout
     */
    public static void waitForCondition(BooleanSupplier condition, Duration timeout) {
        waitForCondition(condition, timeout, "Condition not met");
    }

    /**
     * Waits for a condition to become true within a timeout with custom error message.
     *
     * @param condition the condition to wait for
     * @param timeout maximum wait time
     * @param message error message if timeout occurs
     * @throws AssertionError if condition doesn't become true within timeout
     */
    public static void waitForCondition(BooleanSupplier condition, Duration timeout, String message) {
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        long pollIntervalMillis = TestConstants.POLL_INTERVAL.toMillis();

        while (System.currentTimeMillis() < deadlineMillis) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (Exception e) {
                log.debug("Condition check threw exception, will retry", e);
            }

            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for condition", e);
            }
        }

        throw new AssertionError(message + " (timeout after " + timeout + ")");
    }

    /**
     * Waits for a latch to count down with proper error handling.
     *
     * @param latch the latch to await
     * @param timeout maximum wait time
     * @param message error message if timeout occurs
     * @throws AssertionError if latch doesn't count down within timeout
     */
    public static void awaitLatch(CountDownLatch latch, Duration timeout, String message) {
        try {
            boolean completed = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new AssertionError(message + " (timeout after " + timeout +
                    ", remaining count: " + latch.getCount() + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for latch: " + message, e);
        }
    }

    /**
     * Waits for a latch to count down with default error message.
     *
     * @param latch the latch to await
     * @param timeout maximum wait time
     * @throws AssertionError if latch doesn't count down within timeout
     */
    public static void awaitLatch(CountDownLatch latch, Duration timeout) {
        awaitLatch(latch, timeout, "Latch did not count down");
    }

    /**
     * Asserts that a value eventually meets a predicate condition.
     * Useful for polling async state changes.
     *
     * @param supplier supplies the value to check
     * @param predicate the condition to meet
     * @param timeout maximum wait time
     * @param <T> type of value being checked
     * @throws AssertionError if condition not met within timeout
     */
    public static <T> void assertEventually(Supplier<T> supplier, Predicate<T> predicate, Duration timeout) {
        assertEventually(supplier, predicate, timeout, "Value did not meet predicate");
    }

    /**
     * Asserts that a value eventually meets a predicate condition with custom message.
     *
     * @param supplier supplies the value to check
     * @param predicate the condition to meet
     * @param timeout maximum wait time
     * @param message error message if timeout occurs
     * @param <T> type of value being checked
     * @throws AssertionError if condition not met within timeout
     */
    public static <T> void assertEventually(Supplier<T> supplier, Predicate<T> predicate,
                                           Duration timeout, String message) {
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        long pollIntervalMillis = TestConstants.POLL_INTERVAL.toMillis();

        T lastValue = null;
        while (System.currentTimeMillis() < deadlineMillis) {
            try {
                lastValue = supplier.get();
                if (predicate.test(lastValue)) {
                    return;
                }
            } catch (Exception e) {
                log.debug("Supplier threw exception, will retry", e);
            }

            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting", e);
            }
        }

        throw new AssertionError(message + " (timeout after " + timeout +
            ", last value: " + lastValue + ")");
    }

    /**
     * Sleeps for a specified duration, converting InterruptedException to RuntimeException.
     * Use sparingly - prefer condition-based waiting over fixed sleeps.
     *
     * @param duration time to sleep
     */
    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sleep interrupted", e);
        }
    }

    /**
     * Private constructor to prevent instantiation
     */
    private AsyncTestUtils() {
        throw new AssertionError("Utility class - do not instantiate");
    }
}
