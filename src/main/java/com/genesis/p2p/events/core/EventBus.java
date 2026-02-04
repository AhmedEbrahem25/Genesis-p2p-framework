package com.genesis.p2p.events.core;

import com.genesis.p2p.events.exceptions.*;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Central event bus implementation using Observer pattern.
 * Thread-safe and supports both sync and async event publishing.
 *
 * @since 2.0.0
 */
public class EventBus implements IEventBus, AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(EventBus.class);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<IEventListener>> listeners;
    private final CopyOnWriteArrayList<IEventListener> wildcardListeners;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<IEventListener>> patternListeners;
    private final ExecutorService asyncExecutor;
    private final MetricsRegistry metrics;
    private final AtomicBoolean closed;

    // Filter and Transformer support
    private final CopyOnWriteArrayList<IEventFilter> filters;
    private final CopyOnWriteArrayList<IEventTransformer> transformers;

    public EventBus() {
        this(new MetricsRegistry());
    }

    public EventBus(MetricsRegistry metrics) {
        this.listeners = new ConcurrentHashMap<>();
        this.wildcardListeners = new CopyOnWriteArrayList<>();
        this.patternListeners = new ConcurrentHashMap<>();
        this.filters = new CopyOnWriteArrayList<>();
        this.transformers = new CopyOnWriteArrayList<>();
        this.metrics = metrics;
        this.closed = new AtomicBoolean(false);

        this.asyncExecutor = ThreadPoolFactory.createCachedPool("EventBus", "event");

        log.info("EventBus initialized");
    }

    @Override
    public Subscription subscribe(String eventType, IEventListener listener) {
        if (closed.get()) {
            throw new EventBusClosedException();
        }

        if (eventType == null || listener == null) {
            throw new EventValidationException("Event type and listener cannot be null");
        }

        if ("*".equals(eventType)) {
            wildcardListeners.add(listener);
            log.debug("Wildcard listener registered");
        } else if (eventType.contains("*")) {
            patternListeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                    .add(listener);
            log.debug("Pattern listener registered", "pattern", eventType);
        } else {
            listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                    .add(listener);
            log.debug("Listener registered", "eventType", eventType);
        }

        metrics.incrementCounter("eventbus.subscriptions");
        return new EventSubscription(this, eventType, listener);
    }

    @Override
    public <T extends IEvent> Subscription subscribe(Class<T> eventClass, IEventListener listener) {
        String eventType = eventClass.getSimpleName();
        return subscribe(eventType, listener);
    }

    @Override
    public void unsubscribe(String eventType, IEventListener listener) {
        if ("*".equals(eventType)) {
            wildcardListeners.remove(listener);
        } else if (eventType.contains("*")) {
            CopyOnWriteArrayList<IEventListener> list = patternListeners.get(eventType);
            if (list != null) {
                list.remove(listener);
            }
        } else {
            CopyOnWriteArrayList<IEventListener> list = listeners.get(eventType);
            if (list != null) {
                list.remove(listener);
            }
        }

        metrics.incrementCounter("eventbus.unsubscriptions");
        log.debug("Listener unsubscribed", "eventType", eventType);
    }

    @Override
    public void publish(IEvent event) {
        if (closed.get()) {
            log.warn("Cannot publish - EventBus is closed");
            throw new EventBusClosedException();
        }

        if (event == null) {
            throw new NullEventException();
        }

        long startTime = System.currentTimeMillis();

        try {
            // Apply filters
            IEvent processedEvent = applyFiltersAndTransformers(event);
            if (processedEvent == null) {
                metrics.incrementCounter("eventbus.events.filtered");
                return; // Event was filtered out
            }

            notifyListeners(processedEvent);

            metrics.incrementCounter("eventbus.events.published");
            metrics.recordTimer("eventbus.publish.time",
                    System.currentTimeMillis() - startTime);

            log.debug("Event published", "type", event.getType());

        } catch (EventException e) {
            throw e; // Rethrow event-specific exceptions
        } catch (Exception e) {
            metrics.incrementCounter("eventbus.publish.errors");
            log.error("Error publishing event", e, "type", event.getType());
            throw new EventPublishException("Failed to publish event", event.getType(), e);
        }
    }

    @Override
    public CompletableFuture<Void> publishAsync(IEvent event) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new EventBusClosedException());
        }

        return CompletableFuture.runAsync(() -> publish(event), asyncExecutor);
    }

    @Override
    public int getListenerCount(String eventType) {
        CopyOnWriteArrayList<IEventListener> list = listeners.get(eventType);
        return list != null ? list.size() : 0;
    }

    // Filter and Transformer methods
    public void addFilter(IEventFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("Filter cannot be null");
        }
        filters.add(filter);
        filters.sort(Comparator.comparingInt(IEventFilter::getOrder));
        log.debug("Event filter added");
    }

    public void removeFilter(IEventFilter filter) {
        filters.remove(filter);
        log.debug("Event filter removed");
    }

    public void addTransformer(IEventTransformer transformer) {
        if (transformer == null) {
            throw new IllegalArgumentException("Transformer cannot be null");
        }
        transformers.add(transformer);
        transformers.sort(Comparator.comparingInt(IEventTransformer::getOrder));
        log.debug("Event transformer added");
    }

    public void removeTransformer(IEventTransformer transformer) {
        transformers.remove(transformer);
        log.debug("Event transformer removed");
    }

    // Private methods
    private IEvent applyFiltersAndTransformers(IEvent event) {
        // Apply filters first
        for (IEventFilter filter : filters) {
            try {
                if (!filter.accept(event)) {
                    log.debug("Event filtered out", "type", event.getType());
                    return null; // Event filtered out
                }
            } catch (Exception e) {
                log.warn("Filter threw exception", "error", e.getMessage());
                metrics.incrementCounter("eventbus.filter.errors");
            }
        }

        // Apply transformers
        IEvent transformedEvent = event;
        for (IEventTransformer transformer : transformers) {
            try {
                transformedEvent = transformer.transform(transformedEvent);
                if (transformedEvent == null) {
                    log.warn("Transformer returned null, using original event");
                    transformedEvent = event;
                }
            } catch (Exception e) {
                log.warn("Transformer threw exception", "error", e.getMessage());
                metrics.incrementCounter("eventbus.transformer.errors");
            }
        }

        metrics.incrementCounter("eventbus.events.transformed");
        return transformedEvent;
    }

    private void notifyListeners(IEvent event) {
        String eventType = event.getType();

        List<IEventListener> allListeners = new ArrayList<>();

        // 1. Exact type match
        CopyOnWriteArrayList<IEventListener> exactMatch = listeners.get(eventType);
        if (exactMatch != null) {
            allListeners.addAll(exactMatch);
        }

        // 2. Pattern match
        for (Map.Entry<String, CopyOnWriteArrayList<IEventListener>> entry :
                patternListeners.entrySet()) {
            if (matchesPattern(eventType, entry.getKey())) {
                allListeners.addAll(entry.getValue());
            }
        }

        // 3. Wildcard listeners
        allListeners.addAll(wildcardListeners);

        // Sort by order (priority)
        List<IEventListener> sorted = sortByOrder(allListeners);

        // Notify all listeners
        int notified = 0;
        int errors = 0;

        for (IEventListener listener : sorted) {
            try {
                listener.onEvent(event);
                notified++;
            } catch (Exception e) {
                errors++;
                metrics.incrementCounter("eventbus.listener.errors");
                log.warn("Listener threw exception",
                        "eventType", eventType,
                        "error", e.getMessage());
            }
        }

        log.debug("Notified listeners",
                "eventType", eventType,
                "notified", notified,
                "errors", errors);
    }

    private List<IEventListener> sortByOrder(List<IEventListener> listeners) {
        return listeners.stream()
                .sorted(Comparator.comparingInt(IEventListener::getOrder))
                .collect(Collectors.toList());
    }

    private boolean matchesPattern(String eventType, String pattern) {
        String regex = pattern.replace("*", ".*");
        return eventType.matches(regex);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Closing EventBus");

            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            listeners.clear();
            wildcardListeners.clear();
            patternListeners.clear();
            filters.clear();
            transformers.clear();

            log.info("EventBus closed");
        }
    }
}