package com.genesis.p2p.util.threading;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread factory that creates named threads for easier debugging.
 * Follows best practices for P2P thread management.
 */
public class NamedThreadFactory implements ThreadFactory {

    private final String namePrefix;
    private final AtomicInteger threadNumber;
    private final boolean daemon;
    private final int priority;
    private final ThreadGroup group;

    /**
     * Creates factory with default settings (non-daemon, normal priority).
     */
    public NamedThreadFactory(String namePrefix) {
        this(namePrefix, false, Thread.NORM_PRIORITY);
    }

    /**
     * Creates factory with specified daemon flag.
     */
    public NamedThreadFactory(String namePrefix, boolean daemon) {
        this(namePrefix, daemon, Thread.NORM_PRIORITY);
    }

    /**
     * Creates factory with full configuration.
     */
    public NamedThreadFactory(String namePrefix, boolean daemon, int priority) {
        if (namePrefix == null || namePrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Name prefix cannot be null or empty");
        }
        if (priority < Thread.MIN_PRIORITY || priority > Thread.MAX_PRIORITY) {
            throw new IllegalArgumentException("Invalid thread priority: " + priority);
        }

        this.namePrefix = namePrefix;
        this.daemon = daemon;
        this.priority = priority;
        this.threadNumber = new AtomicInteger(1);

        // Modern approach: Simply use the current thread's thread group
        // SecurityManager is deprecated since Java 17 and removed in later versions
        this.group = Thread.currentThread().getThreadGroup();
    }

    @Override
    public Thread newThread(Runnable r) {
        String threadName = namePrefix + "-" + threadNumber.getAndIncrement();
        Thread thread = new Thread(group, r, threadName, 0);

        thread.setDaemon(daemon);
        thread.setPriority(priority);

        // Set uncaught exception handler
        thread.setUncaughtExceptionHandler((t, e) -> {
            System.err.println("Uncaught exception in thread " + t.getName() + ": " + e);
            e.printStackTrace(System.err);
        });

        return thread;
    }

    /**
     * Gets the name prefix.
     */
    public String getNamePrefix() {
        return namePrefix;
    }

    /**
     * Gets current thread count.
     */
    public int getThreadCount() {
        return threadNumber.get() - 1;
    }

    /**
     * Checks if threads are daemon.
     */
    public boolean isDaemon() {
        return daemon;
    }

    /**
     * Gets thread priority.
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Creates a daemon thread factory.
     */
    public static NamedThreadFactory daemon(String namePrefix) {
        return new NamedThreadFactory(namePrefix, true);
    }

    /**
     * Creates a non-daemon thread factory.
     */
    public static NamedThreadFactory nonDaemon(String namePrefix) {
        return new NamedThreadFactory(namePrefix, false);
    }

    /**
     * Creates a high-priority thread factory.
     */
    public static NamedThreadFactory highPriority(String namePrefix) {
        return new NamedThreadFactory(namePrefix, false, Thread.MAX_PRIORITY);
    }

    /**
     * Creates a low-priority thread factory.
     */
    public static NamedThreadFactory lowPriority(String namePrefix) {
        return new NamedThreadFactory(namePrefix, false, Thread.MIN_PRIORITY);
    }
}