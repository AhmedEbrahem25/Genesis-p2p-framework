package com.genesis.p2p.core.handlers.circuit;

import com.genesis.p2p.observability.metrics.MetricsRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages circuit breakers for different message types.
 */
public class CircuitBreakerManager {

    private final ConcurrentHashMap<String, CircuitBreaker> breakers;
    private final MetricsRegistry metrics;

    public CircuitBreakerManager(MetricsRegistry metrics) {
        this.breakers = new ConcurrentHashMap<>();
        this.metrics = metrics;
    }

    public boolean isOpen(String messageType) {
        CircuitBreaker breaker = breakers.computeIfAbsent(messageType, k -> new CircuitBreaker());
        boolean open = breaker.isOpen();
        if (open) {
            metrics.incrementCounter("circuitbreaker.open." + messageType);
        }
        return open;
    }

    public void recordSuccess(String messageType) {
        CircuitBreaker breaker = breakers.get(messageType);
        if (breaker != null) {
            breaker.recordSuccess();
            metrics.incrementCounter("circuitbreaker.success." + messageType);
        }
    }

    public void recordFailure(String messageType) {
        CircuitBreaker breaker = breakers.get(messageType);
        if (breaker != null) {
            breaker.recordFailure();
            metrics.incrementCounter("circuitbreaker.failure." + messageType);
        }
    }

    /**
     * Circuit breaker implementation.
     */
    static class CircuitBreaker {
        private static final int FAILURE_THRESHOLD = 5;
        private static final Duration TIMEOUT = Duration.ofSeconds(30);

        private final AtomicInteger failureCount;
        private volatile Instant lastFailureTime;
        private volatile boolean open;

        CircuitBreaker() {
            this.failureCount = new AtomicInteger(0);
            this.lastFailureTime = Instant.now();
            this.open = false;
        }

        boolean isOpen() {
            if (open && Duration.between(lastFailureTime, Instant.now()).compareTo(TIMEOUT) > 0) {
                // Timeout passed, try half-open
                open = false;
                failureCount.set(0);
            }
            return open;
        }

        void recordSuccess() {
            failureCount.set(0);
            open = false;
        }

        void recordFailure() {
            lastFailureTime = Instant.now();
            if (failureCount.incrementAndGet() >= FAILURE_THRESHOLD) {
                open = true;
            }
        }
    }
}