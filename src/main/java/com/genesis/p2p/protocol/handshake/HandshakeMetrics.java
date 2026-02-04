package com.genesis.p2p.protocol.handshake;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Prometheus-style metrics for handshake operations.
 *
 * Provides counters, gauges, and histograms for monitoring:
 * - Handshake success/failure rates
 * - Timeout occurrences
 * - Retry counts
 * - Deduplication events
 * - Latency distributions
 *
 * Thread-safe with minimal contention using LongAdder for high-frequency counters.
 *
 * Metric naming follows Prometheus conventions:
 * - handshake_total (counter)
 * - handshake_success_total (counter)
 * - handshake_failed_total (counter)
 * - handshake_timeout_total (counter)
 * - handshake_deduplicated_total (counter)
 * - handshake_duration_seconds (histogram)
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class HandshakeMetrics {

    private static final NodeLogger log = NodeLogger.getLogger(HandshakeMetrics.class);

    // ========================= Counters (Prometheus-style) =========================

    // Total handshakes initiated/received
    private final LongAdder handshakeTotal = new LongAdder();
    private final LongAdder handshakeInitiatedTotal = new LongAdder();
    private final LongAdder handshakeReceivedTotal = new LongAdder();

    // Success/failure counters
    private final LongAdder handshakeSuccessTotal = new LongAdder();
    private final LongAdder handshakeFailedTotal = new LongAdder();

    // Timeout counters by type
    private final LongAdder handshakeTimeoutTotal = new LongAdder();
    private final LongAdder connectionTimeoutTotal = new LongAdder();
    private final LongAdder responseTimeoutTotal = new LongAdder();

    // Deduplication counters
    private final LongAdder handshakeDedupTotal = new LongAdder();
    private final LongAdder bidirectionalResolvedTotal = new LongAdder();

    // Retry counters
    private final LongAdder retryAttemptTotal = new LongAdder();
    private final LongAdder retryExhaustedTotal = new LongAdder();
    private final LongAdder retrySuccessTotal = new LongAdder();

    // Security session counters
    private final LongAdder sessionEstablishedTotal = new LongAdder();
    private final LongAdder sessionFailedTotal = new LongAdder();

    // Rate limiting
    private final LongAdder rateLimitedTotal = new LongAdder();

    // Protocol errors
    private final LongAdder protocolMismatchTotal = new LongAdder();
    private final LongAdder validationFailedTotal = new LongAdder();
    private final LongAdder rejectedTotal = new LongAdder();

    // ========================= Gauges =========================

    private final AtomicLong pendingHandshakes = new AtomicLong(0);
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong establishedSessions = new AtomicLong(0);

    // ========================= Histograms (Latency Distribution) =========================

    // Latency buckets in milliseconds: [10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, +Inf]
    private static final long[] LATENCY_BUCKETS_MS = {10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};
    private final LongAdder[] handshakeDurationBuckets;
    private final LongAdder handshakeDurationSum = new LongAdder();
    private final LongAdder handshakeDurationCount = new LongAdder();

    // Retry delay histogram
    private final LongAdder[] retryDelayBuckets;
    private final LongAdder retryDelaySum = new LongAdder();
    private final LongAdder retryDelayCount = new LongAdder();

    // ========================= Labels (for per-peer/per-reason breakdown) =========================

    // Per-peer success/failure (limited to prevent unbounded growth)
    private final Map<String, PeerMetrics> perPeerMetrics = new ConcurrentHashMap<>();
    private static final int MAX_PEER_METRICS = 1000;

    // Failure reasons
    private final Map<String, LongAdder> failureReasons = new ConcurrentHashMap<>();

    // ========================= Rejection Tracking (HANDSHAKE_REJECT) =========================

    // Rejection counters
    private final LongAdder rejectionSentTotal = new LongAdder();
    private final LongAdder rejectionReceivedTotal = new LongAdder();

    // Rejection counts by reason
    private final Map<HandshakeReject.RejectionReason, LongAdder> rejectionByReason =
            new ConcurrentHashMap<>();

    // ========================= Timestamps =========================

    private final Instant startTime;
    private volatile Instant lastHandshakeTime;
    private volatile Instant lastSuccessTime;
    private volatile Instant lastFailureTime;

    public HandshakeMetrics() {
        this.startTime = Instant.now();
        this.handshakeDurationBuckets = new LongAdder[LATENCY_BUCKETS_MS.length + 1];
        this.retryDelayBuckets = new LongAdder[LATENCY_BUCKETS_MS.length + 1];

        for (int i = 0; i <= LATENCY_BUCKETS_MS.length; i++) {
            handshakeDurationBuckets[i] = new LongAdder();
            retryDelayBuckets[i] = new LongAdder();
        }

        log.info("HandshakeMetrics initialized");
    }

    // ========================= Recording Methods =========================

    /**
     * Records a handshake initiation.
     */
    public void recordHandshakeInitiated(String peerId) {
        handshakeTotal.increment();
        handshakeInitiatedTotal.increment();
        lastHandshakeTime = Instant.now();
        pendingHandshakes.incrementAndGet();
        getPeerMetrics(peerId).initiated.increment();
    }

    /**
     * Records a handshake request received.
     */
    public void recordHandshakeReceived(String peerId) {
        handshakeTotal.increment();
        handshakeReceivedTotal.increment();
        lastHandshakeTime = Instant.now();
        getPeerMetrics(peerId).received.increment();
    }

    /**
     * Records a successful handshake.
     */
    public void recordHandshakeSuccess(String peerId, Duration duration) {
        handshakeSuccessTotal.increment();
        pendingHandshakes.decrementAndGet();
        lastSuccessTime = Instant.now();
        recordDuration(duration);
        getPeerMetrics(peerId).success.increment();

        log.debug("HANDSHAKE_SUCCESS",
                "peerId", peerId,
                "durationMs", duration.toMillis());
    }

    /**
     * Records a failed handshake.
     */
    public void recordHandshakeFailed(String peerId, String reason, Duration duration) {
        handshakeFailedTotal.increment();
        pendingHandshakes.decrementAndGet();
        lastFailureTime = Instant.now();

        if (duration != null) {
            recordDuration(duration);
        }

        // Track failure reason
        failureReasons.computeIfAbsent(reason, k -> new LongAdder()).increment();

        getPeerMetrics(peerId).failed.increment();

        log.debug("HANDSHAKE_FAILED",
                "peerId", peerId,
                "reason", reason,
                "durationMs", duration != null ? duration.toMillis() : -1);
    }

    /**
     * Records a connection timeout.
     */
    public void recordConnectionTimeout(String peerId) {
        handshakeTimeoutTotal.increment();
        connectionTimeoutTotal.increment();
        handshakeFailedTotal.increment();
        pendingHandshakes.decrementAndGet();
        lastFailureTime = Instant.now();
        getPeerMetrics(peerId).timeouts.increment();

        log.debug("HANDSHAKE_CONNECTION_TIMEOUT", "peerId", peerId);
    }

    /**
     * Records a response timeout.
     */
    public void recordResponseTimeout(String peerId) {
        handshakeTimeoutTotal.increment();
        responseTimeoutTotal.increment();
        handshakeFailedTotal.increment();
        pendingHandshakes.decrementAndGet();
        lastFailureTime = Instant.now();
        getPeerMetrics(peerId).timeouts.increment();

        log.debug("HANDSHAKE_RESPONSE_TIMEOUT", "peerId", peerId);
    }

    /**
     * Records a deduplicated handshake.
     */
    public void recordDeduplicated(String peerId) {
        handshakeDedupTotal.increment();
        getPeerMetrics(peerId).deduplicated.increment();

        log.debug("HANDSHAKE_DEDUPLICATED", "peerId", peerId);
    }

    /**
     * Records a bidirectional handshake resolution.
     */
    public void recordBidirectionalResolved(String peerId, boolean becameResponder) {
        bidirectionalResolvedTotal.increment();

        log.debug("HANDSHAKE_BIDIRECTIONAL_RESOLVED",
                "peerId", peerId,
                "role", becameResponder ? "responder" : "initiator");
    }

    /**
     * Records a retry attempt.
     */
    public void recordRetryAttempt(String peerId, int attemptNumber, Duration delay) {
        retryAttemptTotal.increment();
        recordRetryDelay(delay);
        getPeerMetrics(peerId).retries.increment();

        log.debug("HANDSHAKE_RETRY",
                "peerId", peerId,
                "attempt", attemptNumber,
                "delayMs", delay.toMillis());
    }

    /**
     * Records retry exhaustion (all retries failed).
     */
    public void recordRetryExhausted(String peerId, int totalAttempts) {
        retryExhaustedTotal.increment();

        log.debug("HANDSHAKE_RETRY_EXHAUSTED",
                "peerId", peerId,
                "totalAttempts", totalAttempts);
    }

    /**
     * Records a successful retry (handshake succeeded after retry).
     */
    public void recordRetrySuccess(String peerId, int attemptNumber) {
        retrySuccessTotal.increment();

        log.debug("HANDSHAKE_RETRY_SUCCESS",
                "peerId", peerId,
                "successfulAttempt", attemptNumber);
    }

    /**
     * Records a security session establishment.
     */
    public void recordSessionEstablished(String peerId, boolean isInitiator) {
        sessionEstablishedTotal.increment();
        establishedSessions.incrementAndGet();

        log.debug("HANDSHAKE_SESSION_ESTABLISHED",
                "peerId", peerId,
                "role", isInitiator ? "initiator" : "responder");
    }

    /**
     * Records a security session establishment failure.
     */
    public void recordSessionFailed(String peerId, String reason) {
        sessionFailedTotal.increment();

        log.debug("HANDSHAKE_SESSION_FAILED",
                "peerId", peerId,
                "reason", reason);
    }

    /**
     * Records a rate-limited handshake.
     */
    public void recordRateLimited(String peerId) {
        rateLimitedTotal.increment();
        getPeerMetrics(peerId).rateLimited.increment();

        log.debug("HANDSHAKE_RATE_LIMITED", "peerId", peerId);
    }

    /**
     * Records a protocol mismatch.
     */
    public void recordProtocolMismatch(String peerId) {
        protocolMismatchTotal.increment();
        handshakeFailedTotal.increment();

        log.debug("HANDSHAKE_PROTOCOL_MISMATCH", "peerId", peerId);
    }

    /**
     * Records a validation failure.
     */
    public void recordValidationFailed(String peerId, String reason) {
        validationFailedTotal.increment();
        handshakeFailedTotal.increment();

        log.debug("HANDSHAKE_VALIDATION_FAILED",
                "peerId", peerId,
                "reason", reason);
    }

    /**
     * Records a rejected handshake.
     */
    public void recordRejected(String peerId, String reason) {
        rejectedTotal.increment();
        handshakeFailedTotal.increment();

        log.debug("HANDSHAKE_REJECTED",
                "peerId", peerId,
                "reason", reason);
    }

    // ========================= Rejection Recording (HANDSHAKE_REJECT) =========================

    /**
     * Records sending a HANDSHAKE_REJECT message.
     *
     * @param peerId the peer being rejected
     * @param reason the rejection reason
     */
    public void recordRejectionSent(String peerId, HandshakeReject.RejectionReason reason) {
        rejectionSentTotal.increment();
        rejectionByReason.computeIfAbsent(reason, k -> new LongAdder()).increment();
        pendingHandshakes.decrementAndGet();

        log.debug("HANDSHAKE_REJECTION_SENT",
                "peerId", peerId,
                "reason", reason.name());
    }

    /**
     * Records receiving a HANDSHAKE_REJECT message.
     *
     * @param peerId the peer that sent the rejection
     * @param reason the rejection reason
     * @param duration how long the handshake was in progress before rejection
     */
    public void recordRejectionReceived(String peerId, HandshakeReject.RejectionReason reason, Duration duration) {
        rejectionReceivedTotal.increment();
        handshakeFailedTotal.increment();
        rejectionByReason.computeIfAbsent(reason, k -> new LongAdder()).increment();
        pendingHandshakes.decrementAndGet();
        lastFailureTime = Instant.now();

        if (duration != null) {
            recordDuration(duration);
        }

        // Track as failure reason too
        failureReasons.computeIfAbsent("REJECTED_" + reason.name(), k -> new LongAdder()).increment();

        getPeerMetrics(peerId).failed.increment();

        log.debug("HANDSHAKE_REJECTION_RECEIVED",
                "peerId", peerId,
                "reason", reason.name(),
                "durationMs", duration != null ? duration.toMillis() : -1);
    }

    /**
     * Gets rejection sent count.
     */
    public long getRejectionSentTotal() {
        return rejectionSentTotal.sum();
    }

    /**
     * Gets rejection received count.
     */
    public long getRejectionReceivedTotal() {
        return rejectionReceivedTotal.sum();
    }

    /**
     * Gets rejection counts broken down by reason.
     *
     * @return map of reason name to count
     */
    public Map<String, Long> getRejectionByReasonCounts() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        rejectionByReason.forEach((reason, counter) -> result.put(reason.name(), counter.sum()));
        return result;
    }

    // ========================= Histogram Recording =========================

    private void recordDuration(Duration duration) {
        long durationMs = duration.toMillis();
        handshakeDurationSum.add(durationMs);
        handshakeDurationCount.increment();

        int bucket = findBucket(durationMs);
        handshakeDurationBuckets[bucket].increment();
    }

    private void recordRetryDelay(Duration delay) {
        long delayMs = delay.toMillis();
        retryDelaySum.add(delayMs);
        retryDelayCount.increment();

        int bucket = findBucket(delayMs);
        retryDelayBuckets[bucket].increment();
    }

    private int findBucket(long valueMs) {
        for (int i = 0; i < LATENCY_BUCKETS_MS.length; i++) {
            if (valueMs <= LATENCY_BUCKETS_MS[i]) {
                return i;
            }
        }
        return LATENCY_BUCKETS_MS.length; // +Inf bucket
    }

    // ========================= Query Methods =========================

    public long getHandshakeTotal() {
        return handshakeTotal.sum();
    }

    public long getHandshakeSuccess() {
        return handshakeSuccessTotal.sum();
    }

    public long getHandshakeFailed() {
        return handshakeFailedTotal.sum();
    }

    public long getHandshakeDeduplicated() {
        return handshakeDedupTotal.sum();
    }

    public long getHandshakeTimeout() {
        return handshakeTimeoutTotal.sum();
    }

    public long getRetryTotal() {
        return retryAttemptTotal.sum();
    }

    public long getPendingCount() {
        return pendingHandshakes.get();
    }

    public long getEstablishedSessions() {
        return establishedSessions.get();
    }

    /**
     * Gets the success rate as a percentage.
     */
    public double getSuccessRate() {
        long total = handshakeTotal.sum();
        if (total == 0) return 0.0;
        return (double) handshakeSuccessTotal.sum() / total * 100.0;
    }

    /**
     * Gets the average handshake duration in milliseconds.
     */
    public double getAverageDurationMs() {
        long count = handshakeDurationCount.sum();
        if (count == 0) return 0.0;
        return (double) handshakeDurationSum.sum() / count;
    }

    /**
     * Gets the p50 (median) latency estimate in milliseconds.
     */
    public long getP50LatencyMs() {
        return getPercentileLatency(50);
    }

    /**
     * Gets the p95 latency estimate in milliseconds.
     */
    public long getP95LatencyMs() {
        return getPercentileLatency(95);
    }

    /**
     * Gets the p99 latency estimate in milliseconds.
     */
    public long getP99LatencyMs() {
        return getPercentileLatency(99);
    }

    private long getPercentileLatency(int percentile) {
        long total = handshakeDurationCount.sum();
        if (total == 0) return 0;

        long target = (long) (total * percentile / 100.0);
        long cumulative = 0;

        for (int i = 0; i < handshakeDurationBuckets.length; i++) {
            cumulative += handshakeDurationBuckets[i].sum();
            if (cumulative >= target) {
                return i < LATENCY_BUCKETS_MS.length ? LATENCY_BUCKETS_MS[i] : Long.MAX_VALUE;
            }
        }
        return Long.MAX_VALUE;
    }

    // ========================= Per-Peer Metrics =========================

    private PeerMetrics getPeerMetrics(String peerId) {
        // Limit size to prevent unbounded growth
        if (perPeerMetrics.size() >= MAX_PEER_METRICS && !perPeerMetrics.containsKey(peerId)) {
            return new PeerMetrics(); // Return throwaway metrics
        }
        return perPeerMetrics.computeIfAbsent(peerId, k -> new PeerMetrics());
    }

    public Map<String, PeerMetrics> getPerPeerMetrics() {
        return Map.copyOf(perPeerMetrics);
    }

    public Map<String, Long> getFailureReasonCounts() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        failureReasons.forEach((reason, counter) -> result.put(reason, counter.sum()));
        return result;
    }

    // ========================= Prometheus Export =========================

    /**
     * Exports metrics in Prometheus text format.
     */
    public String toPrometheusFormat() {
        StringBuilder sb = new StringBuilder();

        // Counters
        appendCounter(sb, "genesis_handshake_total", "Total handshake attempts", handshakeTotal.sum());
        appendCounter(sb, "genesis_handshake_initiated_total", "Handshakes initiated", handshakeInitiatedTotal.sum());
        appendCounter(sb, "genesis_handshake_received_total", "Handshakes received", handshakeReceivedTotal.sum());
        appendCounter(sb, "genesis_handshake_success_total", "Successful handshakes", handshakeSuccessTotal.sum());
        appendCounter(sb, "genesis_handshake_failed_total", "Failed handshakes", handshakeFailedTotal.sum());
        appendCounter(sb, "genesis_handshake_timeout_total", "Handshake timeouts", handshakeTimeoutTotal.sum());
        appendCounter(sb, "genesis_handshake_deduplicated_total", "Deduplicated handshakes", handshakeDedupTotal.sum());
        appendCounter(sb, "genesis_handshake_retry_total", "Retry attempts", retryAttemptTotal.sum());
        appendCounter(sb, "genesis_handshake_session_established_total", "Sessions established", sessionEstablishedTotal.sum());
        appendCounter(sb, "genesis_handshake_rate_limited_total", "Rate limited attempts", rateLimitedTotal.sum());

        // Rejection counters
        appendCounter(sb, "genesis_handshake_rejection_sent_total", "Rejections sent", rejectionSentTotal.sum());
        appendCounter(sb, "genesis_handshake_rejection_received_total", "Rejections received", rejectionReceivedTotal.sum());

        // Per-reason rejection metrics
        sb.append("# HELP genesis_handshake_rejection_total Total rejections by reason\n");
        sb.append("# TYPE genesis_handshake_rejection_total counter\n");
        for (HandshakeReject.RejectionReason reason : HandshakeReject.RejectionReason.values()) {
            LongAdder counter = rejectionByReason.get(reason);
            long value = counter != null ? counter.sum() : 0;
            sb.append("genesis_handshake_rejection_total{reason=\"")
                    .append(reason.name().toLowerCase())
                    .append("\"} ")
                    .append(value)
                    .append("\n");
        }

        // Gauges
        appendGauge(sb, "genesis_handshake_pending", "Pending handshakes", pendingHandshakes.get());
        appendGauge(sb, "genesis_handshake_sessions_active", "Active sessions", establishedSessions.get());

        // Histogram
        appendHistogram(sb, "genesis_handshake_duration_seconds", "Handshake duration",
                handshakeDurationBuckets, handshakeDurationSum.sum(), handshakeDurationCount.sum());

        return sb.toString();
    }

    private void appendCounter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(" ").append(help).append("\n");
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(" ").append(value).append("\n");
    }

    private void appendGauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(" ").append(help).append("\n");
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(" ").append(value).append("\n");
    }

    private void appendHistogram(StringBuilder sb, String name, String help,
                                  LongAdder[] buckets, long sum, long count) {
        sb.append("# HELP ").append(name).append(" ").append(help).append("\n");
        sb.append("# TYPE ").append(name).append(" histogram\n");

        long cumulative = 0;
        for (int i = 0; i < LATENCY_BUCKETS_MS.length; i++) {
            cumulative += buckets[i].sum();
            sb.append(name).append("_bucket{le=\"")
                    .append(LATENCY_BUCKETS_MS[i] / 1000.0).append("\"} ")
                    .append(cumulative).append("\n");
        }
        cumulative += buckets[LATENCY_BUCKETS_MS.length].sum();
        sb.append(name).append("_bucket{le=\"+Inf\"} ").append(cumulative).append("\n");
        sb.append(name).append("_sum ").append(sum / 1000.0).append("\n");
        sb.append(name).append("_count ").append(count).append("\n");
    }

    // ========================= Summary =========================

    /**
     * Gets a summary snapshot of key metrics.
     */
    public MetricsSummary getSummary() {
        return new MetricsSummary(
                handshakeTotal.sum(),
                handshakeSuccessTotal.sum(),
                handshakeFailedTotal.sum(),
                handshakeTimeoutTotal.sum(),
                handshakeDedupTotal.sum(),
                retryAttemptTotal.sum(),
                pendingHandshakes.get(),
                establishedSessions.get(),
                getSuccessRate(),
                getAverageDurationMs(),
                getP50LatencyMs(),
                getP95LatencyMs(),
                getP99LatencyMs(),
                Duration.between(startTime, Instant.now())
        );
    }

    /**
     * Resets all metrics (for testing).
     */
    public void reset() {
        handshakeTotal.reset();
        handshakeInitiatedTotal.reset();
        handshakeReceivedTotal.reset();
        handshakeSuccessTotal.reset();
        handshakeFailedTotal.reset();
        handshakeTimeoutTotal.reset();
        handshakeDedupTotal.reset();
        retryAttemptTotal.reset();
        sessionEstablishedTotal.reset();
        rateLimitedTotal.reset();
        pendingHandshakes.set(0);
        establishedSessions.set(0);
        perPeerMetrics.clear();
        failureReasons.clear();
        rejectionSentTotal.reset();
        rejectionReceivedTotal.reset();
        rejectionByReason.clear();
        log.info("HandshakeMetrics reset");
    }

    // ========================= Inner Classes =========================

    /**
     * Per-peer metrics tracking.
     */
    public static class PeerMetrics {
        public final LongAdder initiated = new LongAdder();
        public final LongAdder received = new LongAdder();
        public final LongAdder success = new LongAdder();
        public final LongAdder failed = new LongAdder();
        public final LongAdder timeouts = new LongAdder();
        public final LongAdder retries = new LongAdder();
        public final LongAdder deduplicated = new LongAdder();
        public final LongAdder rateLimited = new LongAdder();

        @Override
        public String toString() {
            return String.format(
                    "PeerMetrics[initiated=%d, received=%d, success=%d, failed=%d, timeouts=%d]",
                    initiated.sum(), received.sum(), success.sum(), failed.sum(), timeouts.sum()
            );
        }
    }

    /**
     * Summary snapshot of metrics.
     */
    public record MetricsSummary(
            long total,
            long success,
            long failed,
            long timeouts,
            long deduplicated,
            long retries,
            long pending,
            long sessions,
            double successRate,
            double avgDurationMs,
            long p50LatencyMs,
            long p95LatencyMs,
            long p99LatencyMs,
            Duration uptime
    ) {
        @Override
        public String toString() {
            return String.format(
                    "HandshakeMetrics[total=%d, success=%d (%.1f%%), failed=%d, timeouts=%d, " +
                    "dedup=%d, retries=%d, pending=%d, sessions=%d, " +
                    "avgMs=%.1f, p50=%dms, p95=%dms, p99=%dms, uptime=%s]",
                    total, success, successRate, failed, timeouts,
                    deduplicated, retries, pending, sessions,
                    avgDurationMs, p50LatencyMs, p95LatencyMs, p99LatencyMs, uptime
            );
        }
    }
}
