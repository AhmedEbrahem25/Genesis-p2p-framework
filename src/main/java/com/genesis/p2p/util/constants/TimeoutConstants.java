package com.genesis.p2p.util.constants;

import java.time.Duration;

/**
 * Centralized timeout constants used across the framework.
 *
 * <p>Eliminates magic numbers and provides a single source of truth for timeouts.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class TimeoutConstants {

    private TimeoutConstants() {
        // Utility class - prevent instantiation
    }

    // ==================== Node Lifecycle Timeouts ====================

    /** Timeout for node startup */
    public static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    /** Timeout for node shutdown */
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    /** Default shutdown hook timeout */
    public static final Duration SHUTDOWN_HOOK_TIMEOUT = Duration.ofSeconds(30);

    // ==================== Network Timeouts ====================

    /** Default socket SO_TIMEOUT */
    public static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);

    /** TCP connection timeout */
    public static final Duration TCP_CONNECTION_TIMEOUT = Duration.ofSeconds(10);

    /** UDP receive timeout */
    public static final Duration UDP_RECEIVE_TIMEOUT = Duration.ofSeconds(1);

    /** Read timeout for network operations */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /** Write timeout for network operations */
    public static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);

    // ==================== Message Processing Timeouts ====================

    /** Message processing timeout */
    public static final Duration MESSAGE_PROCESSING_TIMEOUT = Duration.ofSeconds(30);

    /** Message handler circuit breaker timeout */
    public static final Duration CIRCUIT_BREAKER_TIMEOUT = Duration.ofSeconds(30);

    /** Retry delay for failed messages */
    public static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    /** Backpressure acquire timeout - how long to wait when system is under load */
    public static final Duration BACKPRESSURE_ACQUIRE_TIMEOUT = Duration.ofMillis(100);

    // ==================== Discovery Timeouts ====================

    /** Multicast discovery timeout */
    public static final Duration MULTICAST_TIMEOUT = Duration.ofSeconds(1);

    /** Broadcast discovery timeout */
    public static final Duration BROADCAST_TIMEOUT = Duration.ofSeconds(1);

    /** Bootstrap connection timeout */
    public static final Duration BOOTSTRAP_CONNECTION_TIMEOUT = Duration.ofSeconds(10);

    /** Peer discovery timeout */
    public static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(10);

    // ==================== Handshake Timeouts ====================

    /** Handshake negotiation timeout */
    public static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    /** Pending handshake expiration */
    public static final Duration PENDING_HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    /** Protocol negotiation timeout */
    public static final Duration PROTOCOL_NEGOTIATION_TIMEOUT = Duration.ofSeconds(30);

    // ==================== Health & Monitoring Timeouts ====================

    /** Health check interval */
    public static final Duration HEALTH_CHECK_INTERVAL = Duration.ofSeconds(30);

    /** Heartbeat timeout */
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(60);

    /** Metrics collection interval */
    public static final Duration METRICS_INTERVAL = Duration.ofSeconds(10);

    /** Peer cleanup interval */
    public static final Duration PEER_CLEANUP_INTERVAL = Duration.ofSeconds(10);

    // ==================== Security Timeouts ====================

    /** Secure channel negotiation timeout */
    public static final Duration SECURE_CHANNEL_TIMEOUT = Duration.ofSeconds(30);

    /** Session key expiration */
    public static final Duration SESSION_KEY_LIFETIME = Duration.ofHours(24);

    /** Authentication timeout */
    public static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);

    // ==================== Executor Shutdown Timeouts ====================

    /** Executor service shutdown timeout */
    public static final Duration EXECUTOR_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    /** Thread pool await termination timeout */
    public static final Duration THREAD_POOL_TERMINATION_TIMEOUT = Duration.ofSeconds(5);

    // ==================== Conversion Utilities ====================

    /**
     * Converts duration to milliseconds.
     */
    public static long toMillis(Duration duration) {
        return duration.toMillis();
    }

    /**
     * Converts duration to seconds.
     */
    public static long toSeconds(Duration duration) {
        return duration.toSeconds();
    }
}
