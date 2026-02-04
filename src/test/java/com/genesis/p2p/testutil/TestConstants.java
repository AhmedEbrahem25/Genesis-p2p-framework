package com.genesis.p2p.testutil;

import java.time.Duration;

/**
 * Common test constants to eliminate magic numbers and ensure consistency across all tests.
 * <p>
 * Design Pattern: Constants Class
 * Purpose: Centralize test configuration values to improve maintainability and consistency
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class TestConstants {

    // ==================== Timeout Constants ====================

    /**
     * Default timeout for most async operations (5 seconds)
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Short timeout for fast operations (1 second)
     */
    public static final Duration SHORT_TIMEOUT = Duration.ofSeconds(1);

    /**
     * Long timeout for integration/network tests (30 seconds)
     */
    public static final Duration LONG_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Very long timeout for multi-node integration tests (60 seconds)
     */
    public static final Duration VERY_LONG_TIMEOUT = Duration.ofSeconds(60);

    // ==================== Network Constants ====================

    /**
     * Test multicast group address (non-routable, test-only)
     */
    public static final String TEST_MULTICAST_GROUP = "239.255.0.1";

    /**
     * Test multicast port
     */
    public static final int TEST_MULTICAST_PORT = 5000;

    /**
     * Test broadcast port
     */
    public static final int TEST_BROADCAST_PORT = 5001;

    /**
     * Base TCP port for tests (use port 0 for random assignment to avoid conflicts)
     */
    public static final int TEST_TCP_BASE_PORT = 0;

    /**
     * Base listen port for tests (use port 0 for random assignment to avoid conflicts)
     */
    public static final int TEST_LISTEN_BASE_PORT = 0;

    /**
     * Localhost IP address for testing
     */
    public static final String TEST_LOCALHOST = "127.0.0.1";

    // ==================== Test Data Constants ====================

    /**
     * Default test node ID prefix
     */
    public static final String TEST_NODE_ID_PREFIX = "test-node-";

    /**
     * Default test peer ID prefix
     */
    public static final String TEST_PEER_ID_PREFIX = "test-peer-";

    /**
     * Default test peer IP prefix
     */
    public static final String TEST_PEER_IP_PREFIX = "192.168.1.";

    /**
     * Default peer reputation for testing (neutral)
     */
    public static final int DEFAULT_TEST_REPUTATION = 50;

    /**
     * Trusted peer reputation threshold
     */
    public static final int TRUSTED_REPUTATION = 80;

    /**
     * Untrusted peer reputation value
     */
    public static final int UNTRUSTED_REPUTATION = 20;

    // ==================== Concurrency Constants ====================

    /**
     * Default thread pool size for concurrent tests
     */
    public static final int DEFAULT_THREAD_POOL_SIZE = 4;

    /**
     * Sleep interval for polling operations (50ms)
     */
    public static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    // ==================== Performance Test Constants ====================

    /**
     * Target throughput for performance tests (messages per second)
     */
    public static final int TARGET_THROUGHPUT_MSG_PER_SEC = 1000;

    /**
     * Message count for throughput tests
     */
    public static final int THROUGHPUT_TEST_MESSAGE_COUNT = 10_000;

    /**
     * Number of nodes for cluster scalability tests
     */
    public static final int SMALL_CLUSTER_SIZE = 5;
    public static final int MEDIUM_CLUSTER_SIZE = 10;
    public static final int LARGE_CLUSTER_SIZE = 20;

    /**
     * Private constructor to prevent instantiation
     */
    private TestConstants() {
        throw new AssertionError("Utility class - do not instantiate");
    }
}
