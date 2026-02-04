package com.genesis.p2p.core.handlers.async;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.*;
import com.genesis.p2p.core.handlers.circuit.CircuitBreakerManager;
import com.genesis.p2p.core.handlers.dlq.DeadLetterQueue;
import com.genesis.p2p.core.handlers.processors.ProcessorRegistry;
import com.genesis.p2p.core.handlers.processors.ProcessorRegistry.ProcessorEntry;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.tracing.MessageLifecycleTracker;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Handles async message processing with priority queue and worker threads.
 */
public class AsyncMessageProcessor implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(AsyncMessageProcessor.class);

    private final ExecutorService processingExecutor;
    private final ExecutorService callbackExecutor;
    private final BlockingQueue<MessageTask> messageQueue;
    private final ProcessorRegistry processorRegistry;
    private final DeadLetterQueue deadLetterQueue;
    private final MetricsRegistry metrics;
    private final MessageLifecycleTracker tracker;
    private final int maxRetries;

    private final AtomicBoolean isRunning;
    private final CountDownLatch shutdownLatch;

    public AsyncMessageProcessor(int threadPoolSize, int queueCapacity,
                                 Duration processingTimeout, int maxRetries,
                                 ProcessorRegistry processorRegistry,
                                 DeadLetterQueue deadLetterQueue,
                                 MetricsRegistry metrics,
                                 MessageLifecycleTracker tracker) {

        this.messageQueue = new PriorityBlockingQueue<>(queueCapacity);
        this.processorRegistry = processorRegistry;
        this.deadLetterQueue = deadLetterQueue;
        this.metrics = metrics;
        this.tracker = tracker;
        this.maxRetries = maxRetries;

        this.processingExecutor = new ThreadPoolExecutor(
                threadPoolSize,
                threadPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger threadNumber = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "MessageProcessor-" + threadNumber.getAndIncrement());
                        t.setDaemon(false);
                        return t;
                    }
                }
        );

        this.callbackExecutor = ThreadPoolFactory.createFixedPool("Callback", "handler", 2);

        this.isRunning = new AtomicBoolean(true);
        this.shutdownLatch = new CountDownLatch(threadPoolSize);

        startWorkers(threadPoolSize);
    }

    private void startWorkers(int count) {
        for (int i = 0; i < count; i++) {
            processingExecutor.submit(new MessageWorker());
        }
    }

    /**
     * Process a message with full context for response routing and persistence.
     *
     * @param message the message to process
     * @param traceId trace correlation ID
     * @param callback completion callback
     * @param circuitBreaker circuit breaker manager
     * @param context processing context with source address and persistence info
     * @return processing result
     */
    public ProcessingResult process(Message message, String traceId,
                                    Consumer<ProcessingResult> callback,
                                    CircuitBreakerManager circuitBreaker,
                                    ProcessingContext context) {

        ProcessorEntry entry = processorRegistry.get(message.header().type());
        if (entry == null) {
            return ProcessingResult.failure(
                    message.header().messageId(),
                    Duration.ZERO,
                    "No processor found"
            );
        }

        String persistenceId = (context != null) ? context.persistenceId() : null;

        if (entry.async) {
            // Queue for async processing with persistence tracking and context
            MessageTask task = new MessageTask(message, entry, traceId, 0, callback, circuitBreaker, persistenceId, context);
            boolean queued = messageQueue.offer(task);

            if (!queued) {
                metrics.incrementCounter("messages.queue.full");
                return ProcessingResult.failure(
                        message.header().messageId(),
                        Duration.ZERO,
                        "Queue full"
                );
            }

            metrics.incrementCounter("messages.queued");
            metrics.setGauge("queue.size", messageQueue.size());
            return ProcessingResult.success(message.header().messageId(), Duration.ZERO);

        } else {
            // Sync processing with context
            return processSync(message, entry, traceId, circuitBreaker, context);
        }
    }

    /**
     * Process a message with persistence tracking (legacy method).
     */
    public ProcessingResult process(Message message, String traceId,
                                    Consumer<ProcessingResult> callback,
                                    CircuitBreakerManager circuitBreaker,
                                    String persistenceId) {
        ProcessingContext context = (persistenceId != null)
                ? ProcessingContext.create(persistenceId, traceId)
                : null;
        return process(message, traceId, callback, circuitBreaker, context);
    }

    /**
     * Process a message (legacy method for backwards compatibility).
     */
    public ProcessingResult process(Message message, String traceId,
                                    Consumer<ProcessingResult> callback,
                                    CircuitBreakerManager circuitBreaker) {
        return process(message, traceId, callback, circuitBreaker, (ProcessingContext) null);
    }

    private ProcessingResult processSync(Message message, ProcessorEntry entry,
                                         String traceId, CircuitBreakerManager circuitBreaker,
                                         ProcessingContext context) {
        long startTime = System.currentTimeMillis();
        String messageId = message.header().messageId();

        try {
            tracker.recordStage(traceId, "processing.sync.start");

            Future<?> future = processingExecutor.submit(() -> {
                // Use context-aware processing if processor supports it
                if (entry.processor.supportsContext() && context != null) {
                    entry.processor.processMessage(message, context);
                } else {
                    entry.processor.processMessage(message);
                }
            });

            future.get(entry.timeout.toMillis(), TimeUnit.MILLISECONDS);

            entry.processedCount.incrementAndGet();
            entry.lastProcessed = Instant.now();
            metrics.incrementCounter("messages.processed");

            if (circuitBreaker != null) {
                circuitBreaker.recordSuccess(message.header().type());
            }

            Duration time = Duration.ofMillis(System.currentTimeMillis() - startTime);
            tracker.recordStage(traceId, "processing.sync.success");
            return ProcessingResult.success(messageId, time);

        } catch (TimeoutException e) {
            metrics.incrementCounter("messages.timeouts");
            entry.failedCount.incrementAndGet();
            tracker.recordStage(traceId, "processing.sync.timeout");

            if (circuitBreaker != null) {
                circuitBreaker.recordFailure(message.header().type());
            }

            return ProcessingResult.failure(messageId,
                    Duration.ofMillis(System.currentTimeMillis() - startTime),
                    "Processing timeout");

        } catch (Exception e) {
            metrics.incrementCounter("messages.errors");
            entry.failedCount.incrementAndGet();
            tracker.recordStage(traceId, "processing.sync.error");

            if (circuitBreaker != null) {
                circuitBreaker.recordFailure(message.header().type());
            }

            return ProcessingResult.failure(messageId,
                    Duration.ofMillis(System.currentTimeMillis() - startTime),
                    e.getMessage());
        }
    }

    // Overload for legacy callers
    private ProcessingResult processSync(Message message, ProcessorEntry entry,
                                         String traceId, CircuitBreakerManager circuitBreaker) {
        return processSync(message, entry, traceId, circuitBreaker, null);
    }

    public void pause() {
        isRunning.set(false);
    }

    public void resume() {
        isRunning.set(true);
    }

    public QueueStatus getQueueStatus() {
        int size = messageQueue.size();
        int capacity = 10000; // From config
        return new QueueStatus(size, capacity, capacity - size);
    }

    @Override
    public void close() {
        log.info("Shutting down async processor");
        isRunning.set(false);

        try {
            shutdownLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        shutdownExecutor(processingExecutor, "Processing");
        shutdownExecutor(callbackExecutor, "Callback");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Worker thread for async processing.
     */
    private class MessageWorker implements Runnable {
        @Override
        public void run() {
            log.info("Worker started", "thread", Thread.currentThread().getName());

            try {
                while (isRunning.get()) {
                    try {
                        MessageTask task = messageQueue.poll(1, TimeUnit.SECONDS);
                        if (task == null) continue;

                        processTask(task);

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("Worker error", e);
                    }
                }
            } finally {
                shutdownLatch.countDown();
                log.info("Worker stopped", "thread", Thread.currentThread().getName());
            }
        }

        private void processTask(MessageTask task) {
            long startTime = System.currentTimeMillis();
            Message message = task.message;
            String messageId = message.header().messageId();

            try {
                tracker.recordStage(task.traceId, "processing.async.start");

                // Use context-aware processing if processor supports it
                if (task.entry.processor.supportsContext() && task.context != null) {
                    task.entry.processor.processMessage(message, task.context);
                } else {
                    task.entry.processor.processMessage(message);
                }

                task.entry.processedCount.incrementAndGet();
                task.entry.lastProcessed = Instant.now();
                metrics.incrementCounter("messages.processed");

                if (task.circuitBreaker != null) {
                    task.circuitBreaker.recordSuccess(message.header().type());
                }

                Duration time = Duration.ofMillis(System.currentTimeMillis() - startTime);
                metrics.recordTimer("messages.processing.time", time.toMillis());
                tracker.recordStage(task.traceId, "processing.async.success");

                if (task.callback != null) {
                    notifyCallback(task.callback, ProcessingResult.success(messageId, time));
                }

            } catch (Exception e) {
                task.entry.failedCount.incrementAndGet();
                metrics.incrementCounter("messages.errors");
                tracker.recordStage(task.traceId, "processing.async.error");

                if (task.circuitBreaker != null) {
                    task.circuitBreaker.recordFailure(message.header().type());
                }

                handleFailure(task, e, startTime);
            }
        }

        private void handleFailure(MessageTask task, Exception e, long startTime) {
            String messageId = task.message.header().messageId();

            if (task.retryCount < maxRetries) {
                // Retry with persistenceId and context preserved
                MessageTask retryTask = new MessageTask(
                        task.message,
                        task.entry,
                        task.traceId,
                        task.retryCount + 1,
                        task.callback,
                        task.circuitBreaker,
                        task.persistenceId,
                        task.context
                );
                messageQueue.offer(retryTask);
                metrics.incrementCounter("messages.retries");

                log.info("Retrying message",
                        "messageId", messageId,
                        "attempt", task.retryCount + 1,
                        "maxRetries", maxRetries,
                        "persistenceId", task.persistenceId);

            } else {
                // Move to DLQ
                FailedMessage failed = new FailedMessage(
                        task.message,
                        "Max retries exceeded",
                        e,
                        Instant.now(),
                        task.retryCount
                );
                deadLetterQueue.add(failed);
                metrics.incrementCounter("messages.deadlettered");

                log.error("Message moved to DLQ",
                        "messageId", messageId,
                        "retries", task.retryCount,
                        "persistenceId", task.persistenceId);

                if (task.callback != null) {
                    notifyCallback(task.callback, ProcessingResult.failure(
                            messageId,
                            Duration.ofMillis(System.currentTimeMillis() - startTime),
                            "Max retries exceeded: " + e.getMessage()
                    ));
                }
            }
        }

        private void notifyCallback(Consumer<ProcessingResult> callback, ProcessingResult result) {
            callbackExecutor.submit(() -> {
                try {
                    callback.accept(result);
                } catch (Exception e) {
                    log.error("Callback error", e);
                }
            });
        }
    }

    /**
     * Message task for queue.
     * Includes persistenceId and ProcessingContext for end-to-end message tracking.
     */
    private static class MessageTask implements Comparable<MessageTask> {
        final Message message;
        final ProcessorEntry entry;
        final String traceId;
        final int retryCount;
        final Instant submittedAt;
        final Consumer<ProcessingResult> callback;
        final CircuitBreakerManager circuitBreaker;
        final String persistenceId; // For MessagePersistenceStore tracking
        final ProcessingContext context; // For response routing (source address)

        MessageTask(Message message, ProcessorEntry entry, String traceId, int retryCount,
                    Consumer<ProcessingResult> callback, CircuitBreakerManager circuitBreaker,
                    String persistenceId, ProcessingContext context) {
            this.message = message;
            this.entry = entry;
            this.traceId = traceId;
            this.retryCount = retryCount;
            this.submittedAt = Instant.now();
            this.callback = callback;
            this.circuitBreaker = circuitBreaker;
            this.persistenceId = persistenceId;
            this.context = context;
        }

        @Override
        public int compareTo(MessageTask other) {
            return Integer.compare(other.entry.priority, this.entry.priority);
        }
    }
}