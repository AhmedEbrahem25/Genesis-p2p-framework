package com.genesis.p2p.util.threading;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread pool manager facade for P2P framework.
 * Provides named pools for different operations with lifecycle management.
 *
 * Pattern: Facade pattern over Java Executors
 */
public class ThreadPoolManager {

    private final ScheduledExecutorService scheduler;
    private final ExecutorService ioPool;
    private final ExecutorService workerPool;
    private final AtomicBoolean shutdown;

    /**
     * Creates thread pool manager with specified thread counts.
     */
    public ThreadPoolManager(int coreThreads, int ioThreads, int workerThreads) {
        if (coreThreads <= 0 || ioThreads <= 0 || workerThreads <= 0) {
            throw new IllegalArgumentException("Thread counts must be positive");
        }

        this.scheduler = Executors.newScheduledThreadPool(
                coreThreads,
                new NamedThreadFactory("scheduler", true)
        );

        this.ioPool = Executors.newFixedThreadPool(
                ioThreads,
                new NamedThreadFactory("io", false)
        );

        this.workerPool = Executors.newFixedThreadPool(
                workerThreads,
                new NamedThreadFactory("worker", false)
        );

        this.shutdown = new AtomicBoolean(false);
    }

    /**
     * Creates default thread pool manager with CPU-based sizing.
     */
    public static ThreadPoolManager createDefault() {
        int processors = Runtime.getRuntime().availableProcessors();
        int coreThreads = Math.max(2, processors / 2);
        int ioThreads = Math.max(4, processors);
        int workerThreads = Math.max(4, processors * 2);

        return new ThreadPoolManager(coreThreads, ioThreads, workerThreads);
    }

    /**
     * Creates minimal thread pool manager for testing/embedded use.
     */
    public static ThreadPoolManager createMinimal() {
        return new ThreadPoolManager(1, 2, 2);
    }

    /**
     * Gets scheduler for periodic/delayed tasks.
     */
    public ScheduledExecutorService scheduler() {
        checkNotShutdown();
        return scheduler;
    }

    /**
     * Gets I/O pool for network operations.
     */
    public ExecutorService ioPool() {
        checkNotShutdown();
        return ioPool;
    }

    /**
     * Gets worker pool for CPU-bound tasks.
     */
    public ExecutorService workerPool() {
        checkNotShutdown();
        return workerPool;
    }

    /**
     * Submits task to I/O pool.
     */
    public Future<?> submitIo(Runnable task) {
        return ioPool.submit(task);
    }

    /**
     * Submits callable to I/O pool.
     */
    public <T> Future<T> submitIo(Callable<T> task) {
        return ioPool.submit(task);
    }

    /**
     * Submits task to worker pool.
     */
    public Future<?> submitWorker(Runnable task) {
        return workerPool.submit(task);
    }

    /**
     * Submits callable to worker pool.
     */
    public <T> Future<T> submitWorker(Callable<T> task) {
        return workerPool.submit(task);
    }

    /**
     * Schedules task with fixed delay.
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay,
                                                     long delay, TimeUnit unit) {
        return scheduler.scheduleWithFixedDelay(task, initialDelay, delay, unit);
    }

    /**
     * Schedules task at fixed rate.
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay,
                                                  long period, TimeUnit unit) {
        return scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    /**
     * Schedules one-time task.
     */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return scheduler.schedule(task, delay, unit);
    }

    /**
     * Schedules one-time callable.
     */
    public <T> ScheduledFuture<T> schedule(Callable<T> task, long delay, TimeUnit unit) {
        return scheduler.schedule(task, delay, unit);
    }

    /**
     * Initiates graceful shutdown of all pools.
     */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            scheduler.shutdown();
            ioPool.shutdown();
            workerPool.shutdown();
        }
    }

    /**
     * Immediately shuts down all pools.
     */
    public void shutdownNow() {
        if (shutdown.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            ioPool.shutdownNow();
            workerPool.shutdownNow();
        }
    }

    /**
     * Waits for all pools to terminate.
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        if (!scheduler.awaitTermination(calculateRemaining(deadline), TimeUnit.NANOSECONDS)) {
            return false;
        }
        if (!ioPool.awaitTermination(calculateRemaining(deadline), TimeUnit.NANOSECONDS)) {
            return false;
        }
        return workerPool.awaitTermination(calculateRemaining(deadline), TimeUnit.NANOSECONDS);
    }

    /**
     * Gracefully shuts down and waits for termination.
     */
    public boolean shutdownAndAwait(long timeout, TimeUnit unit) throws InterruptedException {
        shutdown();
        return awaitTermination(timeout, unit);
    }

    /**
     * Checks if manager is shut down.
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * Checks if all pools are terminated.
     */
    public boolean isTerminated() {
        return scheduler.isTerminated() &&
                ioPool.isTerminated() &&
                workerPool.isTerminated();
    }

    /**
     * Gets pool statistics.
     */
    public PoolStats getStats() {
        return new PoolStats(
                getPoolSize(scheduler),
                getPoolSize(ioPool),
                getPoolSize(workerPool)
        );
    }

    private void checkNotShutdown() {
        if (shutdown.get()) {
            throw new IllegalStateException("ThreadPoolManager is shut down");
        }
    }

    private long calculateRemaining(long deadlineNanos) {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    private int getPoolSize(ExecutorService executor) {
        if (executor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) executor).getPoolSize();
        }
        return -1;
    }

    /**
     * Pool statistics holder.
     */
    public static class PoolStats {
        private final int schedulerSize;
        private final int ioPoolSize;
        private final int workerPoolSize;

        public PoolStats(int schedulerSize, int ioPoolSize, int workerPoolSize) {
            this.schedulerSize = schedulerSize;
            this.ioPoolSize = ioPoolSize;
            this.workerPoolSize = workerPoolSize;
        }

        public int getSchedulerSize() {
            return schedulerSize;
        }

        public int getIoPoolSize() {
            return ioPoolSize;
        }

        public int getWorkerPoolSize() {
            return workerPoolSize;
        }

        public int getTotalThreads() {
            return schedulerSize + ioPoolSize + workerPoolSize;
        }

        @Override
        public String toString() {
            return String.format(
                    "PoolStats{scheduler=%d, io=%d, worker=%d, total=%d}",
                    schedulerSize, ioPoolSize, workerPoolSize, getTotalThreads()
            );
        }
    }
}