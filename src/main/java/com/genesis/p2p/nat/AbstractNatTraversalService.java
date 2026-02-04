package com.genesis.p2p.nat;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for NAT detection services.
 * Implements Template Method pattern for common logic.
 */
public abstract class AbstractNatTraversalService implements INatTraversalService {

    protected final NodeLogger log;
    protected final MetricsRegistry metrics;
    protected final ExecutorService executor;
    protected final Duration timeout;
    protected final String nodeId;

    // Statistics tracking
    private final AtomicInteger totalDetections = new AtomicInteger(0);
    private final AtomicInteger successfulDetections = new AtomicInteger(0);
    private final AtomicInteger failedDetections = new AtomicInteger(0);
    private final AtomicInteger stunRequestsSent = new AtomicInteger(0);
    private final AtomicInteger stunResponsesReceived = new AtomicInteger(0);
    private final AtomicLong totalDetectionTimeMs = new AtomicLong(0);

    private volatile NatType lastDetectedType = NatType.UNKNOWN;
    private volatile Instant lastDetectionTime;
    private volatile boolean running = false;

    /**
     * Creates abstract NAT service.
     */
    protected AbstractNatTraversalService(String nodeId, Duration timeout) {
        this.nodeId = nodeId;
        this.timeout = timeout;
        this.log = NodeLogger.getLogger(getClass());
        this.metrics = new MetricsRegistry(nodeId);
        this.executor = ThreadPoolFactory.createCachedPool("NAT", nodeId);
    }

    @Override
    public final void start() {
        if (running) {
            log.warn("NAT service already running");
            return;
        }

        log.info("Starting NAT traversal service", "nodeId", nodeId);
        running = true;
        onStart();
        log.info("NAT service started");
    }

    @Override
    public final void stop() {
        if (!running) {
            return;
        }

        log.info("Stopping NAT service", "nodeId", nodeId);
        running = false;
        onStop();

        executor.shutdownNow();
        log.info("NAT service stopped");
    }

    @Override
    public final boolean isRunning() {
        return running;
    }

    @Override
    public final CompletableFuture<NatType> detectNatType() {
        if (!running) {
            return CompletableFuture.completedFuture(NatType.UNKNOWN);
        }

        totalDetections.incrementAndGet();
        Instant startTime = Instant.now();

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Starting NAT detection");
                stunRequestsSent.incrementAndGet();

                NatType type = performDetection();

                stunResponsesReceived.incrementAndGet();
                successfulDetections.incrementAndGet();
                lastDetectedType = type;
                lastDetectionTime = Instant.now();

                long detectionTime = Duration.between(startTime, Instant.now()).toMillis();
                totalDetectionTimeMs.addAndGet(detectionTime);

                log.info("NAT detected",
                        "type", type.getDescription(),
                        "timeMs", detectionTime);

                metrics.recordTimer("nat.detection.time", detectionTime);
                metrics.incrementCounter("nat.detection.success");

                return type;

            } catch (Exception e) {
                failedDetections.incrementAndGet();
                log.error("NAT detection failed", e);
                metrics.incrementCounter("nat.detection.failure");
                return NatType.UNKNOWN;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> canTraverse(NatType localNat, NatType remoteNat) {
        return CompletableFuture.completedFuture(
                NatType.canHolePunch(localNat, remoteNat)
        );
    }

    @Override
    public NatDetectionStats getStats() {
        int total = totalDetections.get();
        long avgTime = total == 0 ? 0 : totalDetectionTimeMs.get() / total;

        return new NatDetectionStats(
                total,
                successfulDetections.get(),
                failedDetections.get(),
                lastDetectedType,
                lastDetectionTime,
                avgTime,
                stunRequestsSent.get(),
                stunResponsesReceived.get()
        );
    }

    // ==================== Template Methods ====================

    /**
     * Performs actual NAT detection.
     * Subclasses must implement detection logic.
     */
    protected abstract NatType performDetection() throws Exception;

    /**
     * Called when service starts.
     * Subclasses can override for initialization.
     */
    protected void onStart() {
        // Default: do nothing
    }

    /**
     * Called when service stops.
     * Subclasses can override for cleanup.
     */
    protected void onStop() {
        // Default: do nothing
    }

    // ==================== Helper Methods ====================

    protected void recordStunRequest() {
        stunRequestsSent.incrementAndGet();
    }

    protected void recordStunResponse() {
        stunResponsesReceived.incrementAndGet();
    }
}