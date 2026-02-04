package com.genesis.p2p.testutil.base;

import com.genesis.p2p.testutil.AsyncTestUtils;
import com.genesis.p2p.testutil.ResourceCleanup;
import com.genesis.p2p.testutil.TestConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Base class for integration tests.
 * <p>
 * Extends BaseUnitTest with additional functionality for integration testing:
 * - Automatic resource cleanup management
 * - Longer default timeouts suitable for integration tests
 * - Helper methods for waiting on async conditions
 * - Network and I/O test utilities
 * <p>
 * Integration tests typically:
 * - Test multiple components working together
 * - Involve real network operations or I/O
 * - Take longer to execute than unit tests
 * - May require special test environment setup
 * <p>
 * Usage:
 * <pre>
 * class MyIntegrationTest extends BaseIntegrationTest {
 *
 *     @Test
 *     void shouldIntegrateComponents() {
 *         Node node = cleanup.register(new Node(...));
 *         Transport transport = cleanup.register(createTransport());
 *
 *         // Test integration
 *         waitForCondition(() -> node.isConnected(), TestConstants.LONG_TIMEOUT);
 *
 *         // cleanup.cleanupAll() called automatically in @AfterEach
 *     }
 * }
 * </pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public abstract class BaseIntegrationTest extends BaseUnitTest {

    /**
     * Resource cleanup manager - automatically cleans up in @AfterEach
     */
    protected ResourceCleanup cleanup;

    /**
     * Default timeout for integration tests (longer than unit tests)
     */
    protected Duration defaultTimeout = TestConstants.LONG_TIMEOUT;

    /**
     * Set up before each integration test.
     * Creates a new ResourceCleanup instance for the test.
     */
    @BeforeEach
    void integrationSetUp() {
        cleanup = new ResourceCleanup();
        log.info("Integration test setup - resource cleanup initialized");
    }

    /**
     * Clean up after each integration test.
     * Automatically cleans up all registered resources.
     */
    @AfterEach
    void integrationTearDown() {
        log.info("Integration test teardown - cleaning up {} resources", cleanup.size());
        cleanup.cleanupAll();
    }

    /**
     * Waits for a condition to become true using the default integration test timeout.
     * <p>
     * This is a convenience wrapper around AsyncTestUtils.waitForCondition()
     * with integration-appropriate timeout.
     *
     * @param condition the condition to wait for
     * @throws AssertionError if condition doesn't become true within timeout
     */
    protected void waitForCondition(BooleanSupplier condition) {
        AsyncTestUtils.waitForCondition(condition, defaultTimeout);
    }

    /**
     * Waits for a condition to become true with a custom timeout.
     *
     * @param condition the condition to wait for
     * @param timeout maximum wait time
     * @throws AssertionError if condition doesn't become true within timeout
     */
    protected void waitForCondition(BooleanSupplier condition, Duration timeout) {
        AsyncTestUtils.waitForCondition(condition, timeout);
    }

    /**
     * Waits for a condition with custom timeout and error message.
     *
     * @param condition the condition to wait for
     * @param timeout maximum wait time
     * @param message error message if timeout occurs
     * @throws AssertionError if condition doesn't become true within timeout
     */
    protected void waitForCondition(BooleanSupplier condition, Duration timeout, String message) {
        AsyncTestUtils.waitForCondition(condition, timeout, message);
    }

    /**
     * Sets the default timeout for this integration test.
     * Useful when a specific test needs different timing than the default.
     *
     * @param timeout the timeout to use as default
     */
    protected void setDefaultTimeout(Duration timeout) {
        this.defaultTimeout = timeout;
        log.debug("Default timeout changed to {}", timeout);
    }
}
