package com.genesis.p2p.testutil.base;

import com.genesis.p2p.testutil.AsyncTestUtils;
import com.genesis.p2p.testutil.TestConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Base class for tests involving asynchronous operations and concurrency.
 * <p>
 * Extends BaseUnitTest with additional functionality for async testing:
 * - Managed thread pool for concurrent operations
 * - Latch and concurrent test utilities
 * - Safe executor shutdown
 * - Async assertion helpers
 * <p>
 * Async tests typically:
 * - Test concurrent operations
 * - Use CountDownLatch for synchronization
 * - Require thread-safe assertions
 * - Need careful resource cleanup
 * <p>
 * Usage:
 * <pre>
 * class MyAsyncTest extends BaseAsyncTest {
 *
 *     @Test
 *     void shouldHandleConcurrentOperations() {
 *         CountDownLatch latch = new CountDownLatch(10);
 *
 *         // Submit concurrent tasks
 *         for (int i = 0; i < 10; i++) {
 *             testExecutor.submit(() -> {
 *                 // ... async operation ...
 *                 latch.countDown();
 *             });
 *         }
 *
 *         awaitLatch(latch, "All tasks should complete");
 *     }
 * }
 * </pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public abstract class BaseAsyncTest extends BaseUnitTest {

    /**
     * Test executor service for concurrent operations
     * Automatically created before each test and shut down after
     */
    protected ExecutorService testExecutor;

    /**
     * Default timeout for async operations
     */
    protected Duration asyncTimeout = TestConstants.DEFAULT_TIMEOUT;

    /**
     * Set up before each async test.
     * Creates a new thread pool for the test.
     */
    @BeforeEach
    void asyncSetUp() {
        testExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r);
            thread.setName("test-executor-" + thread.getId());
            thread.setDaemon(true); // Don't prevent JVM shutdown
            return thread;
        });
        log.info("Async test setup - executor service created");
    }

    /**
     * Clean up after each async test.
     * Shuts down the executor service safely.
     */
    @AfterEach
    void asyncTearDown() {
        if (testExecutor != null) {
            shutdownExecutor(testExecutor, TestConstants.DEFAULT_TIMEOUT);
        }
        log.info("Async test teardown - executor service shut down");
    }

    /**
     * Safely shuts down an executor service with a timeout.
     * First attempts graceful shutdown, then forces shutdown if needed.
     *
     * @param executor the executor to shut down
     * @param timeout maximum wait time for graceful shutdown
     */
    protected void shutdownExecutor(ExecutorService executor, Duration timeout) {
        log.debug("Shutting down executor service");
        executor.shutdown();

        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Executor did not terminate gracefully, forcing shutdown");
                executor.shutdownNow();

                if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.error("Executor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for executor shutdown");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits for a CountDownLatch with the default async timeout.
     *
     * @param latch the latch to await
     * @param message error message if timeout occurs
     * @throws AssertionError if latch doesn't count down within timeout
     */
    protected void awaitLatch(CountDownLatch latch, String message) {
        AsyncTestUtils.awaitLatch(latch, asyncTimeout, message);
    }

    /**
     * Waits for a CountDownLatch with a custom timeout.
     *
     * @param latch the latch to await
     * @param timeout maximum wait time
     * @param message error message if timeout occurs
     * @throws AssertionError if latch doesn't count down within timeout
     */
    protected void awaitLatch(CountDownLatch latch, Duration timeout, String message) {
        AsyncTestUtils.awaitLatch(latch, timeout, message);
    }

    /**
     * Waits for a condition to become true using async timeout.
     *
     * @param condition the condition to wait for
     * @throws AssertionError if condition doesn't become true within timeout
     */
    protected void waitForCondition(BooleanSupplier condition) {
        AsyncTestUtils.waitForCondition(condition, asyncTimeout);
    }

    /**
     * Waits for a condition with custom timeout and message.
     *
     * @param condition the condition to wait for
     * @param timeout maximum wait time
     * @param message error message if timeout occurs
     * @throws AssertionError if condition doesn't become true within timeout
     */
    protected void waitForCondition(BooleanSupplier condition, Duration timeout, String message) {
        AsyncTestUtils.waitForCondition(condition, timeout, message);
    }

    /**
     * Submits a task to the test executor and returns a Future.
     *
     * @param task the task to execute
     * @return Future representing the task
     */
    protected Future<?> submitTask(Runnable task) {
        return testExecutor.submit(task);
    }

    /**
     * Submits multiple identical tasks concurrently.
     * Useful for stress testing and race condition detection.
     *
     * @param task the task to execute
     * @param count number of concurrent executions
     * @return list of Futures representing the tasks
     */
    protected List<Future<?>> submitConcurrentTasks(Runnable task, int count) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(testExecutor.submit(task));
        }
        return futures;
    }

    /**
     * Waits for all futures to complete with a timeout.
     *
     * @param futures list of futures to wait for
     * @param timeout maximum wait time for all futures
     * @throws AssertionError if any future doesn't complete within timeout
     */
    protected void awaitAllFutures(List<Future<?>> futures, Duration timeout) {
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();

        for (Future<?> future : futures) {
            long remainingMillis = deadlineMillis - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                throw new AssertionError("Timeout waiting for futures to complete");
            }

            try {
                future.get(remainingMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for future", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Future execution failed", e.getCause());
            } catch (TimeoutException e) {
                throw new AssertionError("Future did not complete within timeout");
            }
        }
    }
}
