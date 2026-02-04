package com.genesis.p2p.core.handlers.ack;

import com.genesis.p2p.core.Message;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a message pending acknowledgment.
 */
public class PendingMessage {
    private final String messageId;
    private final String peerId;
    private final Message message;
    private final InetSocketAddress destination;
    private final Instant sentAt;
    private final CompletableFuture<AckResult> future;
    private final AtomicInteger attempts;
    private volatile ScheduledFuture<?> timeoutTask;
    private volatile Instant lastSentAt;
    private volatile boolean cancelled;

    public PendingMessage(String messageId, String peerId, Message message,
                          InetSocketAddress destination, CompletableFuture<AckResult> future) {
        this.messageId = messageId;
        this.peerId = peerId;
        this.message = message;
        this.destination = destination;
        this.sentAt = Instant.now();
        this.lastSentAt = this.sentAt;
        this.future = future;
        this.attempts = new AtomicInteger(1);
        this.cancelled = false;
    }

    public String getMessageId() { return messageId; }
    public String getPeerId() { return peerId; }
    public Message getMessage() { return message; }
    public InetSocketAddress getDestination() { return destination; }
    public Instant getSentAt() { return sentAt; }
    public Instant getLastSentAt() { return lastSentAt; }
    public CompletableFuture<AckResult> getFuture() { return future; }
    public int getAttempts() { return attempts.get(); }
    public boolean isCancelled() { return cancelled; }

    public int incrementAttempts() {
        lastSentAt = Instant.now();
        return attempts.incrementAndGet();
    }

    public long getRttMs() {
        return Duration.between(sentAt, Instant.now()).toMillis();
    }

    public void setTimeoutTask(ScheduledFuture<?> task) { this.timeoutTask = task; }

    public void cancelTimeoutTask() {
        ScheduledFuture<?> task = this.timeoutTask;
        if (task != null && !task.isDone()) task.cancel(false);
    }

    public void cancel() {
        this.cancelled = true;
        cancelTimeoutTask();
    }

    public void completeSuccess() {
        cancelTimeoutTask();
        if (!future.isDone()) future.complete(AckResult.acknowledged(messageId, peerId, getRttMs(), attempts.get()));
    }

    public void completeRejected(String reason) {
        cancelTimeoutTask();
        if (!future.isDone()) future.complete(AckResult.rejected(messageId, peerId, reason, attempts.get()));
    }

    public void completeTimeout() {
        cancelTimeoutTask();
        if (!future.isDone()) future.complete(AckResult.timeout(messageId, peerId, attempts.get()));
    }

    public void completeMaxRetries() {
        cancelTimeoutTask();
        if (!future.isDone()) future.complete(AckResult.maxRetriesExceeded(messageId, peerId, attempts.get()));
    }

    public void completeError(String error) {
        cancelTimeoutTask();
        if (!future.isDone()) future.complete(AckResult.error(messageId, peerId, error, attempts.get()));
    }
}

