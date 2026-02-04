package com.genesis.p2p.events.core;

import java.time.Instant;
import java.util.UUID;

public abstract class AbstractEvent implements IEvent {
    protected final String type;
    protected final Instant timestamp;
    protected final String source;
    protected final Object payload;
    protected String correlationId;

    protected AbstractEvent(String type, String source) {
        this(type, source, null);
    }

    protected AbstractEvent(String type, String source, Object payload) {
        this.type = type;
        this.source = source;
        this.payload = payload;
        this.timestamp = Instant.now();
        this.correlationId = UUID.randomUUID().toString();
    }

    @Override public String getType() { return type; }
    @Override public Instant getTimestamp() { return timestamp; }
    @Override public String getSource() { return source; }
    @Override public Object getPayload() { return payload; }
    @Override public String getCorrelationId() { return correlationId; }
    @Override public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    @Override
    public String toString() {
        return String.format("%s{type='%s', source='%s', timestamp=%s}",
                getClass().getSimpleName(), type, source, timestamp);
    }
}