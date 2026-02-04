package com.genesis.p2p.events.core;

import java.time.Instant;

/**
 * Core event interface that all events must implement.
 *
 * @since 2.0.0
 */
public interface IEvent {
    String getType();
    Instant getTimestamp();
    String getSource();
    Object getPayload();
    String getCorrelationId();
    void setCorrelationId(String correlationId);
}