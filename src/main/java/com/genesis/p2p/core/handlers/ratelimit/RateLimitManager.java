package com.genesis.p2p.core.handlers.ratelimit;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages rate limiting for messages.
 */
public class RateLimitManager implements AutoCloseable {

    private final RateLimiter globalLimiter;
    private final ConcurrentHashMap<String, RateLimiter> peerLimiters;
    private final int perPeerLimit;
    private final MetricsRegistry metrics;

    public RateLimitManager(int globalLimit, int perPeerLimit, MetricsRegistry metrics) {
        this.globalLimiter = new RateLimiter(globalLimit);
        this.peerLimiters = new ConcurrentHashMap<>();
        this.perPeerLimit = perPeerLimit;
        this.metrics = metrics;
    }

    public boolean checkRateLimit(Message message) {
        // Global rate limit
        if (!globalLimiter.tryAcquire()) {
            metrics.incrementCounter("ratelimit.global.exceeded");
            return false;
        }

        // Per-peer rate limit
        String senderId = message.header().from();
        RateLimiter peerLimiter = peerLimiters.computeIfAbsent(
                senderId,
                k -> new RateLimiter(perPeerLimit)
        );

        if (!peerLimiter.tryAcquire()) {
            metrics.incrementCounter("ratelimit.peer.exceeded");
            metrics.incrementCounter("ratelimit.peer." + senderId);
            return false;
        }

        return true;
    }

    @Override
    public void close() {
        peerLimiters.clear();
    }

    /**
     * Token bucket rate limiter.
     */
    static class RateLimiter {
        private final int capacity;
        private final AtomicInteger tokens;
        private volatile long lastRefill;

        RateLimiter(int tokensPerSecond) {
            this.capacity = tokensPerSecond;
            this.tokens = new AtomicInteger(tokensPerSecond);
            this.lastRefill = System.currentTimeMillis();
        }

        boolean tryAcquire() {
            refill();
            return tokens.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;

            if (elapsed >= 1000) {
                tokens.set(capacity);
                lastRefill = now;
            }
        }
    }
}