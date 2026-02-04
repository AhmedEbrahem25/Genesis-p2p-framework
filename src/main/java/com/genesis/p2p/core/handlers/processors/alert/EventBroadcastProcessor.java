package com.genesis.p2p.core.handlers.processors.alert;

import com.genesis.p2p.events.core.EventBus;
import com.genesis.p2p.events.core.GenericEvent;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.util.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class EventBroadcastProcessor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventBroadcastProcessor.class);
    private static final int MAX_HISTORY_SIZE = 1000;

    private final EventBus eventBus;
    private final MetricsRegistry metricsRegistry;
    private final List<BroadcastEvent> broadcastHistory;

    public EventBroadcastProcessor(EventBus eventBus, MetricsRegistry metricsRegistry) {
        this.eventBus = eventBus;
        this.metricsRegistry = metricsRegistry;
        this.broadcastHistory = Collections.synchronizedList(new ArrayList<>());
        setupEventListeners();
        log.info("EventBroadcastProcessor initialized");
    }

    private void setupEventListeners() {
        eventBus.subscribe("broadcast:request", event -> {
            if (event.getPayload() instanceof BroadcastEvent) {
                processBroadcast((BroadcastEvent) event.getPayload());
            }
        });
    }

    public void processBroadcast(BroadcastEvent event) {
        if (!validateEvent(event)) {
            log.warn("Invalid broadcast event received: {}", event);
            return;
        }

        recordBroadcast(event);

        metricsRegistry.incrementCounter("broadcasts.total");
        metricsRegistry.incrementCounter("broadcasts.type." + event.type);
        metricsRegistry.incrementCounter("broadcasts.priority." + event.priority.name().toLowerCase());

        executeBroadcast(event);

        eventBus.publish(new GenericEvent("broadcast:completed", Map.of(
                "eventId", event.id,
                "timestamp", Time.currentMillis()
        )));

        log.debug("Broadcast completed: {} [type={}, priority={}]",
                event.id, event.type, event.priority);
    }

    private boolean validateEvent(BroadcastEvent event) {
        return event != null && event.id != null && event.type != null && event.payload != null;
    }

    private void recordBroadcast(BroadcastEvent event) {
        broadcastHistory.add(event);
        if (broadcastHistory.size() > MAX_HISTORY_SIZE) {
            broadcastHistory.remove(0);
        }
    }

    private void executeBroadcast(BroadcastEvent event) {
        String eventName = "cluster:" + event.type;

        if (event.targetNodes != null && !event.targetNodes.isEmpty()) {
            for (String nodeId : event.targetNodes) {
                eventBus.publish(new GenericEvent(eventName + ":" + nodeId, event.payload));
            }
            metricsRegistry.incrementCounter("broadcasts.targeted");
        } else {
            eventBus.publish(new GenericEvent(eventName, event.payload));
            metricsRegistry.incrementCounter("broadcasts.cluster_wide");
        }

        if (event.priority == Priority.HIGH) {
            eventBus.publish(new GenericEvent("broadcast:high_priority", event));
        }
    }

    public List<BroadcastEvent> getBroadcastHistory(String type, int limit) {
        synchronized (broadcastHistory) {
            List<BroadcastEvent> filtered = new ArrayList<>(broadcastHistory);

            if (type != null) {
                filtered.removeIf(e -> !e.type.equals(type));
            }

            int size = filtered.size();
            int fromIndex = Math.max(0, size - limit);
            return new ArrayList<>(filtered.subList(fromIndex, size));
        }
    }

    @Override
    public void close() {
        broadcastHistory.clear();
        log.info("EventBroadcastProcessor closed");
    }

    public static class BroadcastEvent {
        public final String id;
        public final String type;
        public final Object payload;
        public final long timestamp;
        public final Priority priority;
        public final List<String> targetNodes;

        public BroadcastEvent(String id, String type, Object payload, long timestamp,
                              Priority priority, List<String> targetNodes) {
            this.id = id;
            this.type = type;
            this.payload = payload;
            this.timestamp = timestamp;
            this.priority = priority != null ? priority : Priority.NORMAL;
            this.targetNodes = targetNodes;
        }
    }

    public enum Priority {
        HIGH, NORMAL, LOW
    }
}