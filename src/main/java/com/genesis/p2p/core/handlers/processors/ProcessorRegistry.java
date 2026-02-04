package com.genesis.p2p.core.handlers.processors;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.observability.logging.NodeLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry for message processors with metadata.
 */
public class ProcessorRegistry {

    private static final NodeLogger log = NodeLogger.getLogger(ProcessorRegistry.class);
    private final ConcurrentHashMap<String, ProcessorEntry> processors;

    public ProcessorRegistry() {
        this.processors = new ConcurrentHashMap<>();
    }

    /**
     * Processes a message by routing it to the appropriate processor.
     *
     * @param message the message to process
     */
    public void process(Message message) {
        String type = message.type();
        String messageId = message.header().messageId();
        ProcessorEntry entry = processors.get(type);

        if (entry == null) {
            log.warn("No processor registered for message type",
                    "messageId", messageId,
                    "type", type);
            return;
        }

        long startTime = System.currentTimeMillis();
        String handlerName = entry.processor.getClass().getSimpleName();

        // Log processing start
        log.debug("MESSAGE_PROCESSING_START",
                "messageId", messageId,
                "handler", handlerName,
                "threadId", Thread.currentThread().getId());

        try {
            entry.processor.processMessage(message);

            entry.processedCount.incrementAndGet();
            entry.lastProcessed = Instant.now();

            long processingTimeMs = System.currentTimeMillis() - startTime;

            // Log processing complete
            log.debug("MESSAGE_PROCESSING_COMPLETE",
                    "messageId", messageId,
                    "handler", handlerName,
                    "processingTime", processingTimeMs,
                    "result", "success");

        } catch (Exception e) {
            entry.failedCount.incrementAndGet();

            long processingTimeMs = System.currentTimeMillis() - startTime;

            // Log processing failed
            log.error("MESSAGE_PROCESSING_FAILED",
                    "messageId", messageId,
                    "handler", handlerName,
                    "error", e.getMessage(),
                    "errorType", e.getClass().getSimpleName(),
                    "processingTime", processingTimeMs,
                    "stackTrace", getStackTraceAsString(e));
        }
    }

    /**
     * Converts exception stack trace to string for logging.
     */
    private static String getStackTraceAsString(Exception e) {
        if (e == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    public void register(String messageType, MessageProcessor processor,
                         int priority, boolean async, Duration timeout) {
        ProcessorEntry entry = new ProcessorEntry(
                processor, priority, async, timeout,
                new AtomicInteger(0), new AtomicInteger(0), Instant.now()
        );
        processors.put(messageType, entry);
    }

    public void register(String messageType, MessageProcessor processor) {
        register(messageType, processor, 5, false, Duration.ofSeconds(30));
    }

    public ProcessorEntry get(String messageType) {
        return processors.get(messageType);
    }

    public boolean contains(String messageType) {
        return processors.containsKey(messageType);
    }

    public void unregister(String messageType) {
        processors.remove(messageType);
    }

    public Map<String, ProcessorEntry> getAll() {
        return Map.copyOf(processors);
    }

    public static class ProcessorEntry {
        public final MessageProcessor processor;
        public final int priority;
        public final boolean async;
        public final Duration timeout;
        public final AtomicInteger processedCount;
        public final AtomicInteger failedCount;
        public volatile Instant lastProcessed;

        public ProcessorEntry(MessageProcessor processor, int priority, boolean async,
                              Duration timeout, AtomicInteger processedCount,
                              AtomicInteger failedCount, Instant lastProcessed) {
            this.processor = processor;
            this.priority = priority;
            this.async = async;
            this.timeout = timeout;
            this.processedCount = processedCount;
            this.failedCount = failedCount;
            this.lastProcessed = lastProcessed;
        }
    }
}