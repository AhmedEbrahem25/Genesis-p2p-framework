package com.genesis.p2p.core;

import com.genesis.p2p.core.handlers.*;
import com.genesis.p2p.core.handlers.async.AsyncMessageProcessor;
import com.genesis.p2p.core.handlers.backpressure.BackpressureManager;
import com.genesis.p2p.core.handlers.circuit.CircuitBreakerManager;
import com.genesis.p2p.core.handlers.dedup.DeduplicationService;
import com.genesis.p2p.core.handlers.dlq.DeadLetterQueue;
import com.genesis.p2p.core.handlers.processors.ProcessorRegistry;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.core.handlers.retry.RetryManager;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.message.MessageLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.tracing.MessageLifecycleTracker;
import com.genesis.p2p.storage.PersistenceFacade;
import com.genesis.p2p.storage.DeadLetterQueuePersistent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central message handler that orchestrates all message processing operations.
 *
 * This is the main entry point for processing incoming messages in the P2P network.
 * It integrates all handlers and services:
 * - Message validation
 * - Deduplication
 * - Backpressure control
 * - Circuit breaker
 * - Async processing
 * - Retry management
 * - Peer management
 *
 * @author Genesis P2P Framework
 * @version 3.0
 */
public class MessageHandler implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(MessageHandler.class);

    // Configuration
    private final MessageHandlerConfig config;
    private final String nodeId;

    // Core services
    private final PeerManager peerManager;
    private final ProcessorRegistry processorRegistry;
    private final MetricsRegistry metrics;
    private final MessageLifecycleTracker tracker;

    // Observability - Message-level logging and persistence
    private final MessageLogger messageLogger;

    // Persistence
    private final PersistenceFacade persistence;

    // Handler components
    private final DeduplicationService deduplicationService;
    private final BackpressureManager backpressureManager;
    private final CircuitBreakerManager circuitBreakerManager;
    private final DeadLetterQueue deadLetterQueue;
    private final RetryManager retryManager;
    private final AsyncMessageProcessor asyncProcessor;

    // State
    private final AtomicBoolean isRunning;
    private final AtomicLong totalMessagesReceived;
    private final AtomicLong totalMessagesProcessed;
    private final AtomicLong totalMessagesFailed;
    private final AtomicLong totalMessagesRejected;

    /**
     * Creates a MessageHandler with default configuration (no persistence).
     *
     * @param nodeId this node's identifier
     * @param peerManager peer management service
     */
    public MessageHandler(String nodeId, PeerManager peerManager) {
        this(nodeId, peerManager, null, null, null, MessageHandlerConfig.defaults());
    }

    /**
     * Creates a MessageHandler with persistence support.
     *
     * @param nodeId this node's identifier
     * @param peerManager peer management service
     * @param persistence persistence facade (can be null)
     */
    public MessageHandler(String nodeId, PeerManager peerManager, PersistenceFacade persistence) {
        this(nodeId, peerManager, persistence, null, null, MessageHandlerConfig.defaults());
    }

    /**
     * Creates a MessageHandler with custom configuration and optional persistence.
     *
     * @param nodeId this node's identifier
     * @param peerManager peer management service
     * @param persistence persistence facade (can be null)
     * @param config handler configuration
     */
    public MessageHandler(String nodeId, PeerManager peerManager, PersistenceFacade persistence, MessageHandlerConfig config) {
        this(nodeId, peerManager, persistence, null, null, config);
    }

    /**
     * Creates a MessageHandler with full observability support.
     *
     * This is the recommended constructor for production use as it enables
     * full message-level logging and persistence tracking.
     *
     * @param nodeId this node's identifier
     * @param peerManager peer management service
     * @param persistence persistence facade (can be null)
     * @param messageLogger message logger for observability (can be null)
     * @param config handler configuration
     */
    public MessageHandler(String nodeId, PeerManager peerManager, PersistenceFacade persistence,
                          MessageLogger messageLogger, MessageHandlerConfig config) {
        this(nodeId, peerManager, persistence, null, messageLogger, config);
    }

    /**
     * Creates a MessageHandler with full observability and shared MetricsRegistry.
     *
     * This is the PREFERRED constructor for production use as it:
     * - Shares MetricsRegistry with other components for unified metrics
     * - Enables full message-level logging and persistence tracking
     * - Properly integrates with Node's observability infrastructure
     *
     * @param nodeId this node's identifier
     * @param peerManager peer management service
     * @param persistence persistence facade (can be null)
     * @param metrics shared metrics registry (can be null - will create internal one)
     * @param messageLogger message logger for observability (can be null)
     * @param config handler configuration
     */
    public MessageHandler(String nodeId, PeerManager peerManager, PersistenceFacade persistence,
                          MetricsRegistry metrics, MessageLogger messageLogger, MessageHandlerConfig config) {
        this.nodeId = nodeId;
        this.peerManager = peerManager;
        this.persistence = persistence;
        this.messageLogger = messageLogger;
        this.config = config;

        // Initialize metrics - use provided or create internal (backward compatibility)
        this.metrics = metrics != null ? metrics : new MetricsRegistry(nodeId);
        this.tracker = new MessageLifecycleTracker();

        // Initialize processor registry
        this.processorRegistry = new ProcessorRegistry();

        // Initialize handler components
        this.deduplicationService = config.enableDeduplication() ?
                new DeduplicationService(config.dedupWindow(), metrics) : null;

        this.backpressureManager = new BackpressureManager(
                config.threadPoolSize() * 2,  // max concurrent
                config.queueCapacity(),
                config.globalRateLimit(),
                config.globalRateLimit() * 2,
                metrics
        );

        this.circuitBreakerManager = config.enableCircuitBreaker() ?
                new CircuitBreakerManager(metrics) : null;

        this.deadLetterQueue = new DeadLetterQueue(config.dlqCapacity(), metrics);

        this.retryManager = new RetryManager(
                config.maxRetries(),
                com.genesis.p2p.util.constants.TimeoutConstants.RETRY_DELAY,
                com.genesis.p2p.util.constants.TimeoutConstants.MESSAGE_PROCESSING_TIMEOUT,
                2.0,
                0.1,
                metrics
        );

        this.asyncProcessor = new AsyncMessageProcessor(
                config.threadPoolSize(),
                config.queueCapacity(),
                config.processingTimeout(),
                config.maxRetries(),
                processorRegistry,
                deadLetterQueue,
                metrics,
                tracker
        );

        // Restore failed messages from persistent DLQ on startup
        if (persistence != null && persistence.isAvailable()) {
            try {
                List<DeadLetterQueuePersistent.DLQEntry> restored = persistence.getAllDeadLetters();
                for (DeadLetterQueuePersistent.DLQEntry entry : restored) {
                    FailedMessage failed = new FailedMessage(
                        entry.getMessage(),
                        entry.getReason(),
                        null, // exception not persisted
                        java.time.Instant.ofEpochMilli(entry.getTimestamp()),
                        entry.getRetryCount()
                    );
                    deadLetterQueue.add(failed);
                }
                log.info("✓ Restored {} failed messages from persistent DLQ", restored.size());
            } catch (Exception e) {
                log.error("Failed to restore DLQ from persistence", e);
            }
        }

        // Initialize state
        this.isRunning = new AtomicBoolean(true);
        this.totalMessagesReceived = new AtomicLong(0);
        this.totalMessagesProcessed = new AtomicLong(0);
        this.totalMessagesFailed = new AtomicLong(0);
        this.totalMessagesRejected = new AtomicLong(0);

        log.info("MessageHandler initialized",
                "nodeId", nodeId,
                "config", config,
                "persistence", persistence != null);
    }

    // ========================= Message Processing =========================

    /**
     * Main entry point for processing incoming messages with full observability.
     *
     * This method orchestrates the entire message processing pipeline:
     * 1. Validate message
     * 2. Check deduplication
     * 3. Apply backpressure
     * 4. Check circuit breaker
     * 5. Process message (sync or async)
     * 6. Handle errors and retries
     * 7. Update peer information
     * 8. Log delivery/failure to MessagePersistenceStore
     *
     * @param message the message to process
     * @param context the processing context with persistenceId for tracking
     * @return processing result
     */
    public ProcessingResult handleMessage(Message message, ProcessingContext context) {
        if (!isRunning.get()) {
            // Log failure if we have persistence context
            if (messageLogger != null && context != null && context.hasPersistenceId()) {
                messageLogger.logMessageError(context.persistenceId(), "Handler is shutdown", null);
            }
            return ProcessingResult.failure(
                    message.header().messageId(),
                    Duration.ZERO,
                    "Handler is shutdown"
            );
        }

        totalMessagesReceived.incrementAndGet();
        // Use traceId from context if available, otherwise generate new one
        String traceId = (context != null && context.hasTraceId())
                ? context.traceId()
                : UUID.randomUUID().toString();
        String persistenceId = (context != null) ? context.persistenceId() : null;
        long startTime = System.currentTimeMillis();

        // Log message reception with persistence tracking
        log.debug("MESSAGE_RECEIVED",
                "nodeId", nodeId,
                "messageId", message.header().messageId(),
                "messageType", message.header().type(),
                "source", message.header().from(),
                "peerId", message.header().from(),
                "messageSize", message.body().content().length(),
                "timestamp", message.header().timestamp(),
                "persistenceId", persistenceId,
                "traceId", traceId);

        try {
            tracker.recordStage(traceId, "received");

            // 1. Validate message
            ValidationResult validation = validateMessage(message);
            if (!validation.isValid()) {
                log.warn("Message validation failed",
                        "messageId", message.header().messageId(),
                        "reason", validation.errorMessage());
                metrics.incrementCounter("messages.validation.failed");
                totalMessagesRejected.incrementAndGet();
                return ProcessingResult.failure(
                        message.header().messageId(),
                        Duration.ofMillis(System.currentTimeMillis() - startTime),
                        "Validation failed: " + validation.errorMessage()
                );
            }
            tracker.recordStage(traceId, "validated");

            // 2. Check deduplication
            if (config.enableDeduplication()) {
                boolean isDuplicate = !deduplicationService.tryAdd(message.header().messageId());

                // Log dedup check
                log.debug("MESSAGE_DEDUP_CHECK",
                        "nodeId", nodeId,
                        "messageId", message.header().messageId(),
                        "isDuplicate", isDuplicate,
                        "cacheSize", deduplicationService.size());

                if (isDuplicate) {
                    // Log duplicate detected
                    log.warn("MESSAGE_DUPLICATE_DETECTED",
                            "nodeId", nodeId,
                            "messageId", message.header().messageId(),
                            "messageType", message.header().type(),
                            "peerId", message.header().from());

                    metrics.incrementCounter("messages.duplicates");
                    totalMessagesRejected.incrementAndGet();
                    return ProcessingResult.success(
                            message.header().messageId(),
                            Duration.ofMillis(System.currentTimeMillis() - startTime)
                    );
                }
            }
            tracker.recordStage(traceId, "deduplication.checked");

            // 3. Apply backpressure
            if (!backpressureManager.tryAcquire(com.genesis.p2p.util.constants.TimeoutConstants.BACKPRESSURE_ACQUIRE_TIMEOUT)) {
                log.warn("Message rejected due to backpressure",
                        "messageId", message.header().messageId(),
                        "level", backpressureManager.getBackpressureLevel());
                metrics.incrementCounter("messages.backpressure.rejected");
                totalMessagesRejected.incrementAndGet();
                return ProcessingResult.failure(
                        message.header().messageId(),
                        Duration.ofMillis(System.currentTimeMillis() - startTime),
                        "Backpressure - system overloaded"
                );
            }
            tracker.recordStage(traceId, "backpressure.acquired");

            try {
                // 4. Check circuit breaker
                String messageType = message.header().type();
                if (config.enableCircuitBreaker() &&
                        circuitBreakerManager.isOpen(messageType)) {
                    log.warn("Circuit breaker open for message type",
                            "type", messageType);
                    metrics.incrementCounter("messages.circuit.open");
                    totalMessagesRejected.incrementAndGet();
                    return ProcessingResult.failure(
                            message.header().messageId(),
                            Duration.ofMillis(System.currentTimeMillis() - startTime),
                            "Circuit breaker open for: " + messageType
                    );
                }
                tracker.recordStage(traceId, "circuit.checked");

                // 5. Update peer info
                updatePeerInfo(message);
                tracker.recordStage(traceId, "peer.updated");

                // 6. Log routing decision
                boolean hasProcessor = processorRegistry.contains(messageType);

                log.debug("MESSAGE_ROUTING",
                        "nodeId", nodeId,
                        "messageId", message.header().messageId(),
                        "messageType", messageType,
                        "handler", hasProcessor ? "registered" : "none",
                        "async", true);

                // 7. Process message with full context (source address for response routing)
                // Create a callback that includes persistence tracking
                final String finalPersistenceId = persistenceId;
                ProcessingResult result = asyncProcessor.process(
                        message,
                        traceId,
                        (ProcessingResult r) -> {
                            handleProcessingResult(r);
                            // Log to persistence store after processing complete
                            if (messageLogger != null && finalPersistenceId != null) {
                                if (r.success()) {
                                    messageLogger.logMessageDelivered(finalPersistenceId);
                                } else {
                                    messageLogger.logMessageError(finalPersistenceId, r.error(), null);
                                }
                            }
                        },
                        circuitBreakerManager,
                        context  // Pass full context including source address for response routing
                );

                if (result.success()) {
                    totalMessagesProcessed.incrementAndGet();
                    metrics.incrementCounter("messages.processed.success");
                    // For sync processing, log delivery immediately
                    // (async processing logs via callback above)
                } else {
                    totalMessagesFailed.incrementAndGet();
                    metrics.incrementCounter("messages.processed.failed");
                }

                tracker.recordStage(traceId, "processing.complete");
                return result;

            } finally {
                backpressureManager.release();
            }

        } catch (Exception e) {
            log.error("Error handling message", e,
                    "messageId", message.header().messageId());
            metrics.incrementCounter("messages.errors");
            totalMessagesFailed.incrementAndGet();
            tracker.recordStage(traceId, "error");

            // Log error to persistence store
            if (messageLogger != null && persistenceId != null) {
                messageLogger.logMessageError(persistenceId, e.getMessage(), e);
            }

            return ProcessingResult.failure(
                    message.header().messageId(),
                    Duration.ofMillis(System.currentTimeMillis() - startTime),
                    "Exception: " + e.getMessage()
            );
        }
    }

    /**
     * Main entry point for processing incoming messages (legacy method).
     *
     * This method provides backwards compatibility for callers that don't
     * have a ProcessingContext. For full observability, use
     * {@link #handleMessage(Message, ProcessingContext)} instead.
     *
     * @param message the message to process
     * @return processing result
     */
    public ProcessingResult handleMessage(Message message) {
        return handleMessage(message, ProcessingContext.empty());
    }

    /**
     * Validates incoming message.
     */
    private ValidationResult validateMessage(Message message) {
        try {
            validateMessageHeader(message.header());
            validateMessageBody(message.body());
            return ValidationResult.valid();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }

    /**
     * Validates message header.
     */
    private void validateMessageHeader(MessageHeader header) {
        if (header == null) {
            throw new IllegalArgumentException("Message header cannot be null");
        }
        if (header.type() == null || header.type().isEmpty()) {
            throw new IllegalArgumentException("Message type cannot be null or empty");
        }
        if (header.from() == null || header.from().isEmpty()) {
            throw new IllegalArgumentException("Message sender cannot be null or empty");
        }
        if (header.timestamp() <= 0) {
            throw new IllegalArgumentException("Invalid timestamp in message header");
        }
        if (header.ttl() <= 0) {
            throw new IllegalArgumentException("Invalid TTL in message header");
        }
    }

    /**
     * Validates message body.
     */
    private void validateMessageBody(MessageBody body) {
        if (body == null) {
            throw new IllegalArgumentException("Message body cannot be null");
        }
        if (body.content() == null || body.content().isEmpty()) {
            throw new IllegalArgumentException("Message content cannot be null or empty");
        }
    }

    /**
     * Updates peer information from message.
     */
    private void updatePeerInfo(Message message) {
        String peerId = message.header().from();

        if (peerManager.containsPeer(peerId)) {
            // Update last seen
            peerManager.refreshLastSeen(peerId);

            // Record message
            peerManager.recordMessage(peerId);
        } else {
            log.debug("Message from unknown peer",
                    "peerId", peerId,
                    "type", message.header().type());
        }
    }

    /**
     * Handles processing result callback.
     */
    private void handleProcessingResult(ProcessingResult result) {
        if (result.success()) {
            log.debug("Message processed successfully",
                    "messageId", result.messageId(),
                    "duration", result.processingTime().toMillis() + "ms");
        } else {
            log.warn("Message processing failed",
                    "messageId", result.messageId(),
                    "error", result.error());
        }
    }

    // ========================= Processor Registration =========================

    /**
     * Registers a message processor.
     *
     * @param messageType the message type to handle
     * @param processor the processor implementation
     */
    public void registerProcessor(String messageType, MessageProcessor processor) {
        processorRegistry.register(messageType, processor);
        log.info("Processor registered", "type", messageType);
    }

    /**
     * Registers a processor with configuration.
     *
     * @param messageType the message type to handle
     * @param processor the processor implementation
     * @param priority processing priority (higher = more important)
     * @param async whether to process asynchronously
     * @param timeout processing timeout
     */
    public void registerProcessor(String messageType, MessageProcessor processor,
                                  int priority, boolean async, Duration timeout) {
        processorRegistry.register(messageType, processor, priority, async, timeout);
        log.info("Processor registered",
                "type", messageType,
                "priority", priority,
                "async", async);
    }

    /**
     * Unregisters a processor.
     *
     * @param messageType the message type
     */
    public void unregisterProcessor(String messageType) {
        processorRegistry.unregister(messageType);
        log.info("Processor unregistered", "type", messageType);
    }

    /**
     * Checks if a processor is registered.
     *
     * @param messageType the message type
     * @return true if processor exists
     */
    public boolean hasProcessor(String messageType) {
        return processorRegistry.contains(messageType);
    }

    // ========================= Control Operations =========================

    /**
     * Pauses message processing.
     */
    public void pause() {
        asyncProcessor.pause();
        log.info("Message processing paused");
    }

    /**
     * Resumes message processing.
     */
    public void resume() {
        asyncProcessor.resume();
        log.info("Message processing resumed");
    }

    /**
     * Checks if handler is running.
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    // ========================= Statistics and Monitoring =========================

    /**
     * Gets handler statistics.
     */
    public MessageHandlerStatistics getStatistics() {
        return new MessageHandlerStatistics(
                totalMessagesReceived.get(),
                totalMessagesProcessed.get(),
                totalMessagesFailed.get(),
                totalMessagesRejected.get(),
                asyncProcessor.getQueueStatus(),
                backpressureManager.getStatistics(),
                retryManager.getStatistics(),
                deadLetterQueue.size()
        );
    }

    /**
     * Gets queue status.
     */
    public QueueStatus getQueueStatus() {
        return asyncProcessor.getQueueStatus();
    }

    /**
     * Gets backpressure statistics.
     */
    public BackpressureManager.BackpressureStatistics getBackpressureStats() {
        return backpressureManager.getStatistics();
    }

    /**
     * Gets dead letter queue messages.
     */
    public java.util.List<FailedMessage> getDeadLetterMessages(int limit) {
        return deadLetterQueue.getMessages(limit);
    }

    /**
     * Clears dead letter queue.
     */
    public int clearDeadLetterQueue() {
        return deadLetterQueue.clear();
    }

    // ========================= Configuration Access =========================

    public MessageHandlerConfig getConfig() {
        return config;
    }

    public String getNodeId() {
        return nodeId;
    }

    public ProcessorRegistry getProcessorRegistry() {
        return processorRegistry;
    }

    public MetricsRegistry getMetrics() {
        return metrics;
    }

    public PeerManager getPeerManager() {
        return peerManager;
    }

    // ========================= Lifecycle =========================

    /**
     * Shuts down the message handler.
     */
    @Override
    public void close() {
        if (!isRunning.compareAndSet(true, false)) {
            return; // Already closed
        }

        log.info("Shutting down MessageHandler");

        // Close components in order
        if (asyncProcessor != null) {
            asyncProcessor.close();
        }

        if (retryManager != null) {
            retryManager.close();
        }

        if (deduplicationService != null) {
            deduplicationService.close();
        }

        // Safely drain DLQ to prevent OutOfMemoryError
        if (deadLetterQueue != null) {
            log.info("Shutting down DLQ", "size", deadLetterQueue.size());
            deadLetterQueue.safeShutdown();
        }

        // Persist final DLQ state if persistence available
        if (persistence != null && persistence.isAvailable()) {
            try {
                // Get a safe batch of messages to persist
                java.util.List<FailedMessage> remaining = deadLetterQueue.getMessages(1000);
                for (FailedMessage failed : remaining) {
                    persistence.addToDeadLetterQueue(
                        failed.message(),
                        failed.reason(),
                        failed.retryCount()
                    );
                }
                log.info("Persisted DLQ messages on shutdown", "count", remaining.size());
            } catch (Exception e) {
                log.error("Failed to persist DLQ on shutdown", e);
            }
        }

        log.info("MessageHandler shutdown complete",
                "received", totalMessagesReceived.get(),
                "processed", totalMessagesProcessed.get(),
                "failed", totalMessagesFailed.get(),
                "rejected", totalMessagesRejected.get());
    }

    // ========================= Inner Classes =========================

    /**
     * Validation result.
     */
    private static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }

        boolean isValid() {
            return valid;
        }

        String errorMessage() {
            return errorMessage;
        }
    }

    /**
     * Message handler statistics.
     */
    public record MessageHandlerStatistics(
            long totalReceived,
            long totalProcessed,
            long totalFailed,
            long totalRejected,
            QueueStatus queueStatus,
            BackpressureManager.BackpressureStatistics backpressureStats,
            RetryManager.RetryStatistics retryStats,
            int deadLetterQueueSize
    ) {
        public double getSuccessRate() {
            if (totalReceived == 0) return 0.0;
            return (double) totalProcessed / totalReceived * 100.0;
        }

        public double getFailureRate() {
            if (totalReceived == 0) return 0.0;
            return (double) totalFailed / totalReceived * 100.0;
        }

        public double getRejectionRate() {
            if (totalReceived == 0) return 0.0;
            return (double) totalRejected / totalReceived * 100.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "MessageHandlerStats[received=%d, processed=%d (%.1f%%), " +
                            "failed=%d (%.1f%%), rejected=%d (%.1f%%), dlq=%d]",
                    totalReceived,
                    totalProcessed, getSuccessRate(),
                    totalFailed, getFailureRate(),
                    totalRejected, getRejectionRate(),
                    deadLetterQueueSize
            );
        }
    }
}