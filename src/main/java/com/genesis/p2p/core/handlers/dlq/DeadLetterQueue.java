package com.genesis.p2p.core.handlers.dlq;

import com.genesis.p2p.core.handlers.FailedMessage;
import com.genesis.p2p.observability.message.MessageLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.logging.NodeLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dead letter queue for failed messages with eviction policy.
 *
 * Supports optional MessageLogger integration for unified persistence tracking.
 * When MessageLogger is set, DLQ entries are also logged to MessagePersistenceStore
 * with FAILED state for comprehensive observability.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class DeadLetterQueue {

    private static final NodeLogger log = NodeLogger.getLogger(DeadLetterQueue.class);
    private final BlockingQueue<FailedMessage> queue;
    private final MetricsRegistry metrics;
    private final int capacity;

    // Optional MessageLogger for unified persistence tracking
    private final AtomicReference<MessageLogger> messageLoggerRef = new AtomicReference<>();

    public DeadLetterQueue(int capacity, MetricsRegistry metrics) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.metrics = metrics;
        this.capacity = capacity;
    }

    /**
     * Sets the MessageLogger for unified persistence tracking.
     *
     * When set, all messages added to the DLQ will also be logged
     * to the MessagePersistenceStore with FAILED state.
     *
     * @param messageLogger the message logger (can be null to disable)
     */
    public void setMessageLogger(MessageLogger messageLogger) {
        this.messageLoggerRef.set(messageLogger);
        if (messageLogger != null) {
            log.info("DLQ unified persistence enabled");
        }
    }

    /**
     * Gets the current MessageLogger (for testing purposes).
     */
    public MessageLogger getMessageLogger() {
        return messageLoggerRef.get();
    }

    /**
     * Adds a failed message to the DLQ.
     *
     * If MessageLogger is configured, also logs the message with FAILED state
     * to the MessagePersistenceStore for unified tracking.
     *
     * @param message the failed message
     * @return true if successfully added
     */
    public boolean add(FailedMessage message) {
        return add(message, null);
    }

    /**
     * Adds a failed message to the DLQ with persistence tracking.
     *
     * @param message the failed message
     * @param persistenceId the persistence store ID for tracking (can be null)
     * @return true if successfully added
     */
    public boolean add(FailedMessage message, String persistenceId) {
        boolean added = queue.offer(message);
        if (added) {
            metrics.incrementCounter("dlq.added");
            metrics.setGauge("dlq.size", queue.size());

            // Log to unified persistence store if available
            MessageLogger logger = messageLoggerRef.get();
            if (logger != null && persistenceId != null) {
                try {
                    logger.logMessageError(persistenceId, message.reason(),
                            message.exception());
                    log.debug("DLQ message logged to persistence store",
                            "messageId", message.message().messageId(),
                            "persistenceId", persistenceId);
                } catch (Exception e) {
                    log.warn("Failed to log DLQ message to persistence",
                            "messageId", message.message().messageId(),
                            "error", e.getMessage());
                }
            }
        } else {
            // Queue is full - evict oldest message to make room
            FailedMessage evicted = queue.poll();
            if (evicted != null) {
                log.warn("DLQ capacity reached - evicting oldest message",
                        "messageId", evicted.message().messageId(),
                        "capacity", capacity);
                metrics.incrementCounter("dlq.evicted");

                // Now add the new message
                added = queue.offer(message);
                if (added) {
                    metrics.incrementCounter("dlq.added");
                    metrics.setGauge("dlq.size", queue.size());

                    // Log to unified persistence store if available
                    MessageLogger logger = messageLoggerRef.get();
                    if (logger != null && persistenceId != null) {
                        try {
                            logger.logMessageError(persistenceId, message.reason(),
                                    message.exception());
                        } catch (Exception e) {
                            log.warn("Failed to log DLQ message to persistence",
                                    "messageId", message.message().messageId(),
                                    "error", e.getMessage());
                        }
                    }
                }
            }
            metrics.incrementCounter("dlq.overflow");
        }
        return added;
    }

    public List<FailedMessage> getMessages(int limit) {
        // Limit to reasonable batch size to prevent memory issues
        int safeLimit = Math.min(limit, 1000);
        List<FailedMessage> messages = new ArrayList<>(safeLimit);
        queue.drainTo(messages, safeLimit);
        metrics.setGauge("dlq.size", queue.size());

        if (limit > safeLimit) {
            log.warn("DLQ batch size limited for safety",
                    "requested", limit,
                    "actual", safeLimit);
        }

        return Collections.unmodifiableList(messages);
    }

    public int clear() {
        int size = queue.size();
        if (size > 10000) {
            log.warn("Clearing large DLQ - this may take time",
                    "size", size);
        }
        queue.clear();
        metrics.setGauge("dlq.size", 0);
        log.info("DLQ cleared", "messagesRemoved", size);
        return size;
    }

    public int size() {
        return queue.size();
    }

    /**
     * Safely drains messages in batches during shutdown.
     * Prevents OutOfMemoryError by processing in chunks.
     */
    public void safeShutdown() {
        int totalSize = queue.size();
        if (totalSize == 0) {
            return;
        }

        log.info("DLQ shutdown - draining messages in batches",
                "totalMessages", totalSize);

        int processed = 0;
        while (!queue.isEmpty() && processed < 50000) {
            List<FailedMessage> batch = new ArrayList<>(1000);
            queue.drainTo(batch, 1000);
            processed += batch.size();

            // Process or discard batch
            log.debug("DLQ shutdown batch processed",
                    "batchSize", batch.size(),
                    "totalProcessed", processed);
        }

        // If still has messages, just clear
        if (!queue.isEmpty()) {
            int remaining = queue.size();
            queue.clear();
            log.warn("DLQ shutdown - cleared remaining messages",
                    "remaining", remaining);
        }

        metrics.setGauge("dlq.size", 0);
        log.info("DLQ shutdown complete",
                "messagesProcessed", processed);
    }
}