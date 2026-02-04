package com.genesis.p2p.util.threading;

import java.util.concurrent.*;

/**
 * Factory for creating thread pools with consistent naming and daemon settings.
 *
 * <p>Eliminates code duplication in executor service creation.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ThreadPoolFactory {

    /**
     * Creates a single-threaded scheduled executor with daemon threads.
     *
     * @param name thread name prefix
     * @param nodeId node identifier for thread naming
     * @return configured scheduled executor
     */
    public static ScheduledExecutorService createNamedScheduler(String name, String nodeId) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name + "-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Creates a single-threaded executor with daemon threads.
     *
     * @param name thread name prefix
     * @param nodeId node identifier for thread naming
     * @return configured executor
     */
    public static ExecutorService createNamedExecutor(String name, String nodeId) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name + "-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Creates a fixed-size thread pool with daemon threads.
     *
     * @param name thread name prefix
     * @param nodeId node identifier for thread naming
     * @param size number of threads in the pool
     * @return configured executor
     */
    public static ExecutorService createFixedPool(String name, String nodeId, int size) {
        return Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, name + "-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Creates a cached thread pool with daemon threads.
     *
     * @param name thread name prefix
     * @param nodeId node identifier for thread naming
     * @return configured executor
     */
    public static ExecutorService createCachedPool(String name, String nodeId) {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, name + "-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Creates a custom thread pool executor with full configuration.
     *
     * @param name thread name prefix
     * @param nodeId node identifier for thread naming
     * @param corePoolSize minimum number of threads
     * @param maximumPoolSize maximum number of threads
     * @param keepAliveTime how long to keep idle threads alive
     * @param queueCapacity task queue capacity
     * @return configured thread pool executor
     */
    public static ThreadPoolExecutor createCustomPool(
            String name,
            String nodeId,
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            int queueCapacity
    ) {
        return new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, name + "-" + nodeId);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Shuts down an executor gracefully with timeout.
     *
     * @param executor the executor to shut down
     * @param name executor name for logging
     * @param timeoutSeconds maximum wait time for shutdown
     */
    public static void shutdownGracefully(ExecutorService executor, String name, long timeoutSeconds) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    System.err.println("WARNING: " + name + " executor forcibly shutdown");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
