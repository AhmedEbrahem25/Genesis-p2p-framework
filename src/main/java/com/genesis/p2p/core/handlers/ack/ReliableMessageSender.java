package com.genesis.p2p.core.handlers.ack;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.transport.core.ITransport;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;

/**
 * Reliable message sender with acknowledgment support.
 *
 * Features:
 * - Send message with ACK tracking
 * - Automatic retries with exponential backoff
 * - Configurable timeout
 * - Completion callback
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class ReliableMessageSender implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(ReliableMessageSender.class);

    private final ITransport transport;
    private final AckTracker tracker;
    private final AckConfig config;
    private final MetricsRegistry metrics;
    private final String localNodeId;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates reliable message sender.
     */
    public ReliableMessageSender(ITransport transport, AckTracker tracker,
                                  AckConfig config, MetricsRegistry metrics, String localNodeId) {
        this.transport = transport;
        this.tracker = tracker;
        this.config = config;
        this.metrics = metrics;
        this.localNodeId = localNodeId;
        this.scheduler = ThreadPoolFactory.createNamedScheduler("ReliableSender", "ack");

        log.info("ReliableMessageSender created",
                "timeout", config.defaultTimeout(),
                "maxRetries", config.maxRetries());
    }

    /**
     * Sends a message with acknowledgment tracking.
     *
     * @param message the message to send
     * @param peer target peer
     * @return future that completes when ACK received or timeout
     */
    public CompletableFuture<AckResult> sendWithAck(Message message, Peer peer) {
        return sendWithAck(message, peer.getConnectionAddress(), peer.id(),
                config.defaultTimeout(), config.maxRetries());
    }

    /**
     * Sends a message with custom timeout.
     */
    public CompletableFuture<AckResult> sendWithAck(Message message, Peer peer, Duration timeout) {
        return sendWithAck(message, peer.getConnectionAddress(), peer.id(),
                timeout, config.maxRetries());
    }

    /**
     * Sends a message with custom timeout and retries.
     */
    public CompletableFuture<AckResult> sendWithAck(Message message, Peer peer,
                                                     Duration timeout, int maxRetries) {
        return sendWithAck(message, peer.getConnectionAddress(), peer.id(), timeout, maxRetries);
    }

    /**
     * Sends a message to a specific address with ACK tracking.
     */
    public CompletableFuture<AckResult> sendWithAck(Message message, InetSocketAddress destination,
                                                     String peerId, Duration timeout, int maxRetries) {

        String messageId = message.header().messageId();
        CompletableFuture<AckResult> future = new CompletableFuture<>();

        // Track the message
        PendingMessage pending = tracker.track(messageId, peerId, message, destination, future);

        // Send the message
        sendAndScheduleTimeout(pending, timeout, maxRetries);

        return future;
    }

    /**
     * Sends message and schedules timeout.
     */
    private void sendAndScheduleTimeout(PendingMessage pending, Duration timeout, int maxRetries) {
        String messageId = pending.getMessageId();

        // Send via transport
        transport.send(pending.getMessage(), pending.getDestination())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("Failed to send message",
                                "messageId", messageId,
                                "error", error.getMessage());

                        // Check if we should retry
                        if (pending.getAttempts() < maxRetries) {
                            scheduleRetry(pending, timeout, maxRetries);
                        } else {
                            tracker.remove(messageId);
                            pending.getFuture().complete(
                                    AckResult.sendFailed(messageId, pending.getPeerId(), error.getMessage()));
                        }
                    } else {
                        // Schedule timeout for ACK
                        scheduleTimeout(pending, timeout, maxRetries);

                        log.debug("Message sent, waiting for ACK",
                                "messageId", messageId,
                                "peerId", pending.getPeerId(),
                                "attempt", pending.getAttempts());
                    }
                });
    }

    /**
     * Schedules timeout handler.
     */
    private void scheduleTimeout(PendingMessage pending, Duration timeout, int maxRetries) {
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            handleTimeout(pending, timeout, maxRetries);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        pending.setTimeoutTask(timeoutTask);
    }

    /**
     * Handles timeout.
     */
    private void handleTimeout(PendingMessage pending, Duration timeout, int maxRetries) {
        if (pending.isCancelled() || pending.getFuture().isDone()) {
            return;
        }

        PendingMessage tracked = tracker.handleTimeout(pending.getMessageId());
        if (tracked == null) {
            return; // Already completed
        }

        int attempts = pending.getAttempts();

        if (attempts < maxRetries) {
            // Retry
            scheduleRetry(pending, timeout, maxRetries);
        } else {
            // Max retries exceeded
            tracker.remove(pending.getMessageId());
            pending.completeMaxRetries();

            log.warn("Message ACK timeout - max retries exceeded",
                    "messageId", pending.getMessageId(),
                    "peerId", pending.getPeerId(),
                    "attempts", attempts);
        }
    }

    /**
     * Schedules a retry with exponential backoff.
     */
    private void scheduleRetry(PendingMessage pending, Duration timeout, int maxRetries) {
        int attempt = pending.getAttempts();

        // Calculate delay with exponential backoff
        long delayMs = (long) (config.retryDelay().toMillis() *
                Math.pow(config.retryMultiplier(), attempt - 1));

        // Cap at max delay
        delayMs = Math.min(delayMs, config.maxRetryDelay().toMillis());

        log.debug("Scheduling retry",
                "messageId", pending.getMessageId(),
                "attempt", attempt + 1,
                "delayMs", delayMs);

        scheduler.schedule(() -> {
            if (pending.isCancelled() || pending.getFuture().isDone()) {
                return;
            }

            pending.incrementAttempts();
            metrics.incrementCounter("ack.retries");

            sendAndScheduleTimeout(pending, timeout, maxRetries);

        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Sends a fire-and-forget message (no ACK).
     */
    public CompletableFuture<Void> send(Message message, Peer peer) {
        return transport.send(message, peer.getConnectionAddress());
    }

    /**
     * Sends a fire-and-forget message to an address.
     */
    public CompletableFuture<Void> send(Message message, InetSocketAddress destination) {
        return transport.send(message, destination);
    }

    /**
     * Cancels a pending message.
     */
    public boolean cancel(String messageId) {
        return tracker.cancel(messageId);
    }

    /**
     * Gets pending count.
     */
    public int getPendingCount() {
        return tracker.getPendingCount();
    }

    @Override
    public void close() {
        ThreadPoolFactory.shutdownGracefully(scheduler, "ReliableSender", 5);
    }
}

