package com.genesis.p2p.testutil.base;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all unit tests in the Genesis P2P Framework.
 * <p>
 * Provides common functionality:
 * - SLF4J logging automatically configured for each test class
 * - Test lifecycle logging (start/end of each test)
 * - Helper methods for test step logging
 * <p>
 * Note: For Mockito support, use MockitoAnnotations.openMocks(this) in your @BeforeEach method.
 * <p>
 * Design Pattern: Template Method
 * The base class provides the common structure, subclasses implement specific test logic.
 * <p>
 * Usage:
 * <pre>
 * class MyComponentTest extends BaseUnitTest {
 *
 *     @Test
 *     void shouldDoSomething() {
 *         logTestStep("Setting up test data");
 *         // ... test logic ...
 *         logTestStep("Verifying results");
 *         // ... assertions ...
 *     }
 * }
 * </pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public abstract class BaseUnitTest {

    /**
     * Logger instance - automatically configured for the concrete test class
     */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    private String currentTestName;

    /**
     * Set up before each test.
     * Logs the start of the test with its display name.
     *
     * @param testInfo JUnit test metadata
     */
    @BeforeEach
    void baseSetUp(TestInfo testInfo) {
        currentTestName = testInfo.getDisplayName();
        log.info("=== Starting test: {} ===", currentTestName);
    }

    /**
     * Clean up after each test.
     * Logs the completion of the test.
     *
     * @param testInfo JUnit test metadata
     */
    @AfterEach
    void baseTearDown(TestInfo testInfo) {
        log.info("=== Completed test: {} ===", testInfo.getDisplayName());
    }

    /**
     * Logs a test step for better test output readability.
     * Use this to document the major steps in your test logic.
     *
     * @param step description of the current test step
     */
    protected void logTestStep(String step) {
        log.info("STEP: {}", step);
    }

    /**
     * Logs a debug message within a test.
     * Useful for detailed test execution tracing.
     *
     * @param message debug message
     * @param args message arguments (SLF4J style)
     */
    protected void logDebug(String message, Object... args) {
        log.debug(message, args);
    }

    /**
     * Logs a warning message within a test.
     * Useful for documenting expected failures or edge cases.
     *
     * @param message warning message
     * @param args message arguments (SLF4J style)
     */
    protected void logWarning(String message, Object... args) {
        log.warn(message, args);
    }

    /**
     * Gets the name of the currently executing test.
     *
     * @return test display name
     */
    protected String getCurrentTestName() {
        return currentTestName;
    }
}
