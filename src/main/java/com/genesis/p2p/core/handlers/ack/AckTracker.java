package com.genesis.p2p.core.handlers.ack;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks pending messages awaiting acknowledgment.
 */
public class AckTracker implements AutoCloseable {
    
    private static final NodeLogger log = NodeLogger.getLogger(AckTracker.class);
    
    private final ConcurrentHashMap<String, PendingMessage> pendingMessages;
    private final AckConfig config;
    private final MetricsRegistry metrics;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;
    private ScheduledFuture<?> cleanupTask;
    
    private final AtomicLong totalTracked = new AtomicLong(0);
    private final AtomicLong totalAcked = new AtomicLong(0);
    private final AtomicLong totalNacked = new AtomicLong(0);
    private final AtomicLong totalTimedOut = new AtomicLong(0);
    private final AtomicLong totalCancelled = new AtomicLong(0);
    
    public AckTracker(AckConfig config, MetricsRegistry metrics) {
        this.pendingMessages = new ConcurrentHashMap<>();
        this.config = config;
        this.metrics = metrics;
        this.scheduler = ThreadPoolFactory.createNamedScheduler("AckTracker", "ack");
        this.running = new AtomicBoolean(false);
    }
    
    public void start() {
        if (running.compareAndSet(false, true)) {
            long intervalMs = config.cleanupInterval().toMillis();
            cleanupTask = scheduler.scheduleAtFixedRate(
                    this::cleanupStaleEntries, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            log.info("AckTracker started");
        }
    }
    
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (cleanupTask != null) cleanupTask.cancel(false);
            pendingMessages.values().forEach(PendingMessage::cancel);
            pendingMessages.clear();
            log.info("AckTracker stopped");
        }
    }
    
    @Override
    public void close() {
        stop();
        ThreadPoolFactory.shutdownGracefully(scheduler, "AckTracker", 5);
    }
    
    public PendingMessage track(String messageId, String peerId, Message message,
                                InetSocketAddress destination, CompletableFuture<AckResult> future) {
        PendingMessage pending = new PendingMessage(messageId, peerId, message, destination, future);
        pendingMessages.put(messageId, pending);
        totalTracked.incrementAndGet();
        metrics.incrementCounter("ack.tracked");
        return pending;
    }
    
    public PendingMessage get(String messageId) { return pendingMessages.get(messageId); }
    public boolean isPending(String messageId) { return pendingMessages.containsKey(messageId); }
    public PendingMessage remove(String messageId) { return pendingMessages.remove(messageId); }
    
    public boolean handleAck(String messageId) {
        PendingMessage pending = pendingMessages.remove(messageId);
        if (pending == null) return false;
        pending.completeSuccess();
        totalAcked.incrementAndGet();
        metrics.incrementCounter("ack.received");
        metrics.recordTimer("ack.rtt", pending.getRttMs());
        return true;
    }
    
    public boolean handleNack(String messageId, String reason) {
        PendingMessage pending = pendingMessages.remove(messageId);
        if (pending == null) return false;
        pending.completeRejected(reason);
        totalNacked.incrementAndGet();
        metrics.incrementCounter("ack.nacked");
        return true;
    }
    
    public PendingMessage handleTimeout(String messageId) {
        PendingMessage pending = pendingMessages.get(messageId);
        if (pending != null) {
            totalTimedOut.incrementAndGet();
            metrics.incrementCounter("ack.timeout");
        }
        return pending;
    }
    
    public boolean cancel(String messageId) {
        PendingMessage pending = pendingMessages.remove(messageId);
        if (pending == null) return false;
        pending.cancel();
        totalCancelled.incrementAndGet();
        return true;
    }
    
    private void cleanupStaleEntries() {
        if (!running.get()) return;
        Instant threshold = Instant.now().minus(config.defaultTimeout().multipliedBy(config.maxRetries() + 2));
        pendingMessages.entrySet().removeIf(e -> {
            if (e.getValue().getSentAt().isBefore(threshold)) {
                e.getValue().completeTimeout();
                return true;
            }
            return false;
        });
    }
    
    public int getPendingCount() { return pendingMessages.size(); }
    public Collection<PendingMessage> getAllPending() { return pendingMessages.values(); }
    
    public record TrackerStats(int currentPending, long totalTracked, long totalAcked,
                               long totalNacked, long totalTimedOut, long totalCancelled, boolean isRunning) {}
    
    public TrackerStats getStats() {
        return new TrackerStats(pendingMessages.size(), totalTracked.get(), totalAcked.get(),
                totalNacked.get(), totalTimedOut.get(), totalCancelled.get(), running.get());
    }
}

