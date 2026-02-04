package com.genesis.p2p.util.resource;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resource Leak Detector for tracking unclosed resources.
 *
 * Detects resources that are garbage collected without being properly closed.
 * Uses PhantomReferences to track object lifecycles.
 *
 * Features:
 * - Automatic leak detection
 * - Stack trace capture (allocation site)
 * - Periodic leak reporting
 * - Statistics tracking
 * - Configurable sampling rate
 *
 * Usage:
 * <pre>{@code
 * // Enable leak detection
 * ResourceLeakDetector.enable();
 *
 * // Track a resource
 * MyResource resource = new MyResource();
 * ResourceLeakDetector.track(resource, "MyResource");
 *
 * // Mark as closed
 * ResourceLeakDetector.untrack(resource);
 *
 * // Or use try-with-resources
 * try (TrackedResource tracked = ResourceLeakDetector.trackAutoClose(resource)) {
 *     // Use resource...
 * } // Automatically untracked
 * }</pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class ResourceLeakDetector {

    private static final NodeLogger log = NodeLogger.getLogger(ResourceLeakDetector.class);

    private static volatile boolean enabled = false;
    private static volatile int samplingRate = 100; // Track 1 in 100 by default

    private static final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
    private static final Map<PhantomReference<Object>, LeakInfo> trackedResources =
            new ConcurrentHashMap<>();

    private static final AtomicLong trackedCount = new AtomicLong(0);
    private static final AtomicLong leakCount = new AtomicLong(0);
    private static final AtomicLong sampledCount = new AtomicLong(0);

    private static ScheduledExecutorService cleanupExecutor;

    static {
        // Start cleanup thread
        startCleanupThread();
    }

    private ResourceLeakDetector() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    // ==================== Configuration ====================

    /**
     * Enables leak detection.
     */
    public static void enable() {
        enabled = true;
        log.info("Resource leak detection enabled", "samplingRate", samplingRate);
    }

    /**
     * Disables leak detection.
     */
    public static void disable() {
        enabled = false;
        log.info("Resource leak detection disabled");
    }

    /**
     * Checks if leak detection is enabled.
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets sampling rate (1 = track all, 100 = track 1 in 100).
     */
    public static void setSamplingRate(int rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Sampling rate must be positive");
        }
        samplingRate = rate;
        log.info("Sampling rate changed", "rate", rate);
    }

    /**
     * Gets current sampling rate.
     */
    public static int getSamplingRate() {
        return samplingRate;
    }

    // ==================== Tracking ====================

    /**
     * Tracks a resource for leak detection.
     *
     * @param resource the resource to track
     * @param resourceType description of resource type
     * @return tracker object (for manual untracking)
     */
    public static <T> ResourceTracker track(T resource, String resourceType) {
        if (!enabled || resource == null) {
            return ResourceTracker.NOOP;
        }

        // Apply sampling
        long count = sampledCount.incrementAndGet();
        if (samplingRate > 1 && count % samplingRate != 0) {
            return ResourceTracker.NOOP;
        }

        // Capture allocation stack trace
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        // Create phantom reference
        PhantomReference<Object> ref = new PhantomReference<>(resource, referenceQueue);
        LeakInfo info = new LeakInfo(resourceType, stackTrace, System.currentTimeMillis());

        trackedResources.put(ref, info);
        long totalTracked = trackedCount.incrementAndGet();

        // Enhanced logging for resource tracking
        log.debug("RESOURCE_TRACKED",
                "resourceType", resourceType,
                "resourceId", System.identityHashCode(resource),
                "allocationSite", getAllocationSite(stackTrace),
                "threadId", Thread.currentThread().getId(),
                "threadName", Thread.currentThread().getName(),
                "timestamp", System.currentTimeMillis(),
                "totalTracked", totalTracked);

        return new ResourceTracker(ref);
    }

    /**
     * Tracks a resource with auto-close support.
     */
    public static <T> TrackedResource trackAutoClose(T resource, String resourceType) {
        ResourceTracker tracker = track(resource, resourceType);
        return new TrackedResource(tracker);
    }

    /**
     * Untracks a resource (marks as properly closed).
     */
    public static void untrack(ResourceTracker tracker) {
        if (tracker == null || tracker == ResourceTracker.NOOP) {
            return;
        }

        PhantomReference<Object> ref = tracker.reference;
        if (ref != null) {
            LeakInfo info = trackedResources.remove(ref);
            if (info != null) {
                long lifetime = System.currentTimeMillis() - info.createdAt;

                // Enhanced logging for resource release
                log.debug("RESOURCE_RELEASED",
                        "resourceType", info.resourceType,
                        "lifetime", lifetime,
                        "timestamp", System.currentTimeMillis(),
                        "currentlyTracked", trackedResources.size());
            }
        }
    }

    // ==================== Leak Detection ====================

    /**
     * Checks for leaked resources (called by cleanup thread).
     */
    private static void checkForLeaks() {
        PhantomReference<?> ref;
        while ((ref = (PhantomReference<?>) referenceQueue.poll()) != null) {
            LeakInfo info = trackedResources.remove(ref);
            if (info != null) {
                // Resource was GC'd without being untracked = LEAK!
                leakCount.incrementAndGet();
                reportLeak(info);
            }
        }
    }

    /**
     * Reports a detected leak.
     */
    private static void reportLeak(LeakInfo info) {
        long lifetime = System.currentTimeMillis() - info.createdAt;
        String allocationSite = getAllocationSite(info.allocationStack);
        String allocationStackTrace = formatStackTrace(info.allocationStack, 15);

        // Enhanced logging for leak detection
        log.error("RESOURCE_LEAK_DETECTED",
                "resourceType", info.resourceType,
                "allocationSite", allocationSite,
                "allocationTime", info.createdAt,
                "leakDetectionTime", System.currentTimeMillis(),
                "lifetime", lifetime,
                "totalLeaksDetected", leakCount.get(),
                "currentlyTracked", trackedResources.size(),
                "stackTrace", allocationStackTrace);
    }

    /**
     * Manually triggers leak check.
     */
    public static void checkLeaks() {
        if (!enabled) {
            return;
        }
        checkForLeaks();
    }

    /**
     * Forces garbage collection and checks for leaks.
     */
    public static void forceCheckLeaks() {
        if (!enabled) {
            return;
        }
        System.gc();
        try {
            Thread.sleep(100); // Give GC time to run
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        checkForLeaks();
    }

    // ==================== Statistics ====================

    /**
     * Gets number of currently tracked resources.
     */
    public static int getTrackedResourceCount() {
        return trackedResources.size();
    }

    /**
     * Gets total number of resources ever tracked.
     */
    public static long getTotalTrackedCount() {
        return trackedCount.get();
    }

    /**
     * Gets number of detected leaks.
     */
    public static long getLeakCount() {
        return leakCount.get();
    }

    /**
     * Gets leak detection statistics.
     */
    public static LeakStats getStats() {
        return new LeakStats(
                trackedResources.size(),
                trackedCount.get(),
                leakCount.get(),
                sampledCount.get(),
                samplingRate
        );
    }

    /**
     * Resets statistics.
     */
    public static void resetStats() {
        leakCount.set(0);
        trackedCount.set(0);
        sampledCount.set(0);
        log.info("Leak detection statistics reset");
    }

    /**
     * Clears all tracked resources (for testing).
     */
    public static void clear() {
        trackedResources.clear();
        log.info("All tracked resources cleared");
    }

    // ==================== Cleanup Thread ====================

    /**
     * Starts background cleanup thread.
     */
    private static synchronized void startCleanupThread() {
        if (cleanupExecutor == null) {
            cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ResourceLeakDetector-Cleanup");
                t.setDaemon(true);
                return t;
            });

            // Check for leaks every 10 seconds
            cleanupExecutor.scheduleWithFixedDelay(
                    ResourceLeakDetector::checkForLeaks,
                    10, 10, TimeUnit.SECONDS
            );

            // Log leak summary every 60 seconds
            cleanupExecutor.scheduleWithFixedDelay(
                    ResourceLeakDetector::logLeakSummary,
                    60, 60, TimeUnit.SECONDS
            );
        }
    }

    /**
     * Stops cleanup thread (for shutdown).
     */
    public static synchronized void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            cleanupExecutor = null;
        }
        log.info("Resource leak detector shutdown");
    }

    // ==================== Helper Methods ====================

    /**
     * Extracts allocation site from stack trace.
     */
    private static String getAllocationSite(StackTraceElement[] stackTrace) {
        if (stackTrace == null || stackTrace.length == 0) {
            return "unknown";
        }

        // Find first non-ResourceLeakDetector stack frame
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (!className.contains("ResourceLeakDetector") &&
                !className.contains("Thread") &&
                !className.contains("java.lang.reflect")) {
                return element.toString();
            }
        }

        return stackTrace[0].toString();
    }

    /**
     * Formats stack trace for logging.
     */
    private static String formatStackTrace(StackTraceElement[] stackTrace, int maxFrames) {
        if (stackTrace == null || stackTrace.length == 0) {
            return "no stack trace available";
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(maxFrames, stackTrace.length);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(stackTrace[i].toString());
        }
        if (stackTrace.length > limit) {
            sb.append(" | ... ").append(stackTrace.length - limit).append(" more");
        }
        return sb.toString();
    }

    /**
     * Logs summary of leak detection statistics.
     * Only logs at WARN level when actual leaks are detected.
     * Otherwise logs at DEBUG level.
     */
    public static void logLeakSummary() {
        if (!enabled) {
            return;
        }

        LeakStats stats = getStats();
        Map<String, Integer> leaksByType = getLeaksByType();

        // Only log at WARN level when there are actual leaks detected
        if (stats.getLeaksDetected() > 0) {
            log.warn("RESOURCE_LEAK_SUMMARY",
                    "totalTracked", stats.getTotalTracked(),
                    "totalReleased", stats.getTotalTracked() - stats.getLeaksDetected(),
                    "totalLeaked", stats.getLeaksDetected(),
                    "leakRate", String.format("%.2f%%", stats.getLeakRate() * 100),
                    "currentlyTracked", stats.getCurrentlyTracked(),
                    "byType", leaksByType.toString());
        } else {
            // No leaks - log at DEBUG level to reduce noise
            log.debug("RESOURCE_LEAK_SUMMARY",
                    "totalTracked", stats.getTotalTracked(),
                    "totalReleased", stats.getTotalTracked(),
                    "totalLeaked", 0,
                    "leakRate", "0.00%",
                    "currentlyTracked", stats.getCurrentlyTracked(),
                    "status", "healthy");
        }
    }

    /**
     * Gets leak count grouped by resource type.
     */
    private static Map<String, Integer> getLeaksByType() {
        Map<String, Integer> byType = new ConcurrentHashMap<>();
        for (LeakInfo info : trackedResources.values()) {
            byType.merge(info.resourceType, 1, Integer::sum);
        }
        return byType;
    }

    // ==================== Data Classes ====================

    /**
     * Information about a tracked resource.
     */
    private static class LeakInfo {
        final String resourceType;
        final StackTraceElement[] allocationStack;
        final long createdAt;

        LeakInfo(String resourceType, StackTraceElement[] allocationStack, long createdAt) {
            this.resourceType = resourceType;
            this.allocationStack = allocationStack;
            this.createdAt = createdAt;
        }
    }

    /**
     * Tracker for a resource.
     */
    public static class ResourceTracker {
        static final ResourceTracker NOOP = new ResourceTracker(null);

        private final PhantomReference<Object> reference;

        private ResourceTracker(PhantomReference<Object> reference) {
            this.reference = reference;
        }

        /**
         * Marks resource as closed.
         */
        public void close() {
            untrack(this);
        }
    }

    /**
     * AutoCloseable wrapper for tracked resources.
     */
    public static class TrackedResource implements AutoCloseable {
        private final ResourceTracker tracker;

        private TrackedResource(ResourceTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public void close() {
            tracker.close();
        }
    }

    /**
     * Leak detection statistics.
     */
    public static class LeakStats {
        private final int currentlyTracked;
        private final long totalTracked;
        private final long leaksDetected;
        private final long totalSampled;
        private final int samplingRate;

        public LeakStats(int currentlyTracked, long totalTracked, long leaksDetected,
                        long totalSampled, int samplingRate) {
            this.currentlyTracked = currentlyTracked;
            this.totalTracked = totalTracked;
            this.leaksDetected = leaksDetected;
            this.totalSampled = totalSampled;
            this.samplingRate = samplingRate;
        }

        public int getCurrentlyTracked() { return currentlyTracked; }
        public long getTotalTracked() { return totalTracked; }
        public long getLeaksDetected() { return leaksDetected; }
        public long getTotalSampled() { return totalSampled; }
        public int getSamplingRate() { return samplingRate; }

        public double getLeakRate() {
            return totalTracked == 0 ? 0.0 : (double) leaksDetected / totalTracked;
        }

        @Override
        public String toString() {
            return String.format("LeakStats[tracked=%d, total=%d, leaks=%d, rate=%.2f%%]",
                    currentlyTracked, totalTracked, leaksDetected, getLeakRate() * 100);
        }
    }
}

