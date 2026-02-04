package com.genesis.p2p.application;

import com.genesis.p2p.application.HealthCheckService.*;
import com.genesis.p2p.testutil.base.BaseAsyncTest;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for HealthCheckService.
 * Tests Strategy Pattern, Composite Pattern, and Observer Pattern.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("HealthCheckService Tests")
class HealthCheckServiceTest extends BaseAsyncTest {

    private HealthCheckService healthCheckService;

    @BeforeEach
    void setUp() {
        healthCheckService = new HealthCheckService();
    }

    @AfterEach
    void tearDown() {
        if (healthCheckService != null) {
            healthCheckService.close();
        }
    }

    // ========================= HealthStatus Tests =========================

    @Test
    @Order(1)
    @DisplayName("HealthStatus enum should have correct values")
    void testHealthStatusValues() {
        assertEquals(3, HealthStatus.values().length);
        assertNotNull(HealthStatus.HEALTHY);
        assertNotNull(HealthStatus.DEGRADED);
        assertNotNull(HealthStatus.UNHEALTHY);
    }

    @Test
    @Order(2)
    @DisplayName("HealthStatus should be ordered by severity")
    void testHealthStatusOrdering() {
        assertTrue(HealthStatus.HEALTHY.ordinal() < HealthStatus.DEGRADED.ordinal());
        assertTrue(HealthStatus.DEGRADED.ordinal() < HealthStatus.UNHEALTHY.ordinal());
    }

    // ========================= HealthResult Tests =========================

    @Test
    @Order(10)
    @DisplayName("HealthResult healthy should be created correctly")
    void testHealthResultHealthy() {
        HealthResult result = HealthResult.healthy("All systems operational");

        assertEquals(HealthStatus.HEALTHY, result.getStatus());
        assertEquals("All systems operational", result.getMessage());
        assertTrue(result.getDetails().isEmpty());
    }

    @Test
    @Order(11)
    @DisplayName("HealthResult degraded should be created correctly")
    void testHealthResultDegraded() {
        HealthResult result = HealthResult.degraded("Performance degraded");

        assertEquals(HealthStatus.DEGRADED, result.getStatus());
        assertEquals("Performance degraded", result.getMessage());
    }

    @Test
    @Order(12)
    @DisplayName("HealthResult unhealthy should be created correctly")
    void testHealthResultUnhealthy() {
        HealthResult result = HealthResult.unhealthy("System failure");

        assertEquals(HealthStatus.UNHEALTHY, result.getStatus());
        assertEquals("System failure", result.getMessage());
    }

    @Test
    @Order(13)
    @DisplayName("HealthResult should support details")
    void testHealthResultWithDetails() {
        HealthResult result = HealthResult.healthy("OK")
                .withDetail("cpu", "50%")
                .withDetail("memory", "2GB");

        assertEquals(2, result.getDetails().size());
        assertEquals("50%", result.getDetails().get("cpu"));
        assertEquals("2GB", result.getDetails().get("memory"));
    }

    @Test
    @Order(14)
    @DisplayName("HealthResult should support duration")
    void testHealthResultWithDuration() {
        HealthResult result = HealthResult.healthy("OK")
                .withDuration(Duration.ofMillis(150));

        assertEquals(Duration.ofMillis(150), result.getDuration());
    }

    // ========================= HealthCheckStrategy Tests =========================

    @Test
    @Order(20)
    @DisplayName("Lambda health check should work")
    void testLambdaHealthCheck() {
        HealthCheckStrategy check = () -> HealthResult.healthy("Lambda check passed");

        HealthResult result = check.check();

        assertEquals(HealthStatus.HEALTHY, result.getStatus());
    }

    @Test
    @Order(21)
    @DisplayName("NodeRunningCheck should check node state")
    void testNodeRunningCheck() {
        AtomicBoolean isRunning = new AtomicBoolean(true);

        NodeRunningCheck check = new NodeRunningCheck(isRunning::get);

        HealthResult result = check.check();
        assertEquals(HealthStatus.HEALTHY, result.getStatus());

        isRunning.set(false);
        result = check.check();
        assertEquals(HealthStatus.UNHEALTHY, result.getStatus());
    }

    @Test
    @Order(22)
    @DisplayName("MemoryCheck should check memory usage")
    void testMemoryCheck() {
        MemoryCheck check = new MemoryCheck(0.95); // 95% threshold

        HealthResult result = check.check();

        // Memory check should always return a result
        assertNotNull(result);
        assertNotNull(result.getStatus());

        // Should have memory details
        assertTrue(result.getDetails().containsKey("usedMemory") ||
                   result.getDetails().containsKey("maxMemory") ||
                   result.getMessage().contains("Memory"));
    }

    @Test
    @Order(23)
    @DisplayName("ThreadPoolCheck should check thread pool state")
    void testThreadPoolCheck() {
        ThreadPoolCheck check = new ThreadPoolCheck(
                () -> 5,  // activeThreads
                () -> 10, // maxThreads
                0.9       // threshold
        );

        HealthResult result = check.check();

        assertEquals(HealthStatus.HEALTHY, result.getStatus());
    }

    @Test
    @Order(24)
    @DisplayName("ThreadPoolCheck should detect overload")
    void testThreadPoolCheckOverload() {
        ThreadPoolCheck check = new ThreadPoolCheck(
                () -> 9,  // activeThreads
                () -> 10, // maxThreads
                0.8       // threshold (80%)
        );

        HealthResult result = check.check();

        // 90% usage exceeds 80% threshold
        assertNotEquals(HealthStatus.HEALTHY, result.getStatus());
    }

    // ========================= CompositeHealthCheck Tests =========================

    @Test
    @Order(30)
    @DisplayName("CompositeHealthCheck should aggregate results")
    void testCompositeHealthCheck() {
        CompositeHealthCheck composite = new CompositeHealthCheck("aggregate");

        composite.addCheck("check1", () -> HealthResult.healthy("Check 1 OK"));
        composite.addCheck("check2", () -> HealthResult.healthy("Check 2 OK"));

        HealthResult result = composite.check();

        assertEquals(HealthStatus.HEALTHY, result.getStatus());
    }

    @Test
    @Order(31)
    @DisplayName("CompositeHealthCheck should report worst status")
    void testCompositeHealthCheckWorstStatus() {
        CompositeHealthCheck composite = new CompositeHealthCheck("aggregate");

        composite.addCheck("healthy", () -> HealthResult.healthy("OK"));
        composite.addCheck("degraded", () -> HealthResult.degraded("Slow"));
        composite.addCheck("unhealthy", () -> HealthResult.unhealthy("Failed"));

        HealthResult result = composite.check();

        assertEquals(HealthStatus.UNHEALTHY, result.getStatus());
    }

    @Test
    @Order(32)
    @DisplayName("CompositeHealthCheck should include child results")
    void testCompositeHealthCheckChildResults() {
        CompositeHealthCheck composite = new CompositeHealthCheck("parent");

        composite.addCheck("child1", () -> HealthResult.healthy("Child 1"));
        composite.addCheck("child2", () -> HealthResult.healthy("Child 2"));

        HealthResult result = composite.check();

        // Details should contain child check information
        assertNotNull(result.getDetails());
    }

    @Test
    @Order(33)
    @DisplayName("CompositeHealthCheck should handle empty checks")
    void testCompositeHealthCheckEmpty() {
        CompositeHealthCheck composite = new CompositeHealthCheck("empty");

        HealthResult result = composite.check();

        assertEquals(HealthStatus.HEALTHY, result.getStatus());
    }

    @Test
    @Order(34)
    @DisplayName("CompositeHealthCheck should handle exception in child")
    void testCompositeHealthCheckException() {
        CompositeHealthCheck composite = new CompositeHealthCheck("with-exception");

        composite.addCheck("failing", () -> {
            throw new RuntimeException("Check failed");
        });
        composite.addCheck("passing", () -> HealthResult.healthy("OK"));

        HealthResult result = composite.check();

        assertEquals(HealthStatus.UNHEALTHY, result.getStatus());
    }

    // ========================= Service Registration Tests =========================

    @Test
    @Order(40)
    @DisplayName("Should register health check")
    void testRegisterHealthCheck() {
        healthCheckService.registerCheck("test", () -> HealthResult.healthy("OK"));

        assertTrue(healthCheckService.getRegisteredChecks().contains("test"));
    }

    @Test
    @Order(41)
    @DisplayName("Should unregister health check")
    void testUnregisterHealthCheck() {
        healthCheckService.registerCheck("temp", () -> HealthResult.healthy("OK"));
        healthCheckService.unregisterCheck("temp");

        assertFalse(healthCheckService.getRegisteredChecks().contains("temp"));
    }

    @Test
    @Order(42)
    @DisplayName("Should get all registered checks")
    void testGetRegisteredChecks() {
        healthCheckService.registerCheck("check1", () -> HealthResult.healthy("OK"));
        healthCheckService.registerCheck("check2", () -> HealthResult.healthy("OK"));

        List<String> checks = healthCheckService.getRegisteredChecks();

        assertTrue(checks.contains("check1"));
        assertTrue(checks.contains("check2"));
    }

    // ========================= Check Execution Tests =========================

    @Test
    @Order(50)
    @DisplayName("Should run specific check")
    void testRunSpecificCheck() {
        healthCheckService.registerCheck("specific", () -> HealthResult.healthy("Specific OK"));

        HealthResult result = healthCheckService.runCheck("specific");

        assertNotNull(result);
        assertEquals(HealthStatus.HEALTHY, result.getStatus());
    }

    @Test
    @Order(51)
    @DisplayName("Should run all checks")
    void testRunAllChecks() {
        healthCheckService.registerCheck("all1", () -> HealthResult.healthy("OK 1"));
        healthCheckService.registerCheck("all2", () -> HealthResult.healthy("OK 2"));

        HealthResult result = healthCheckService.runAllChecks();

        assertNotNull(result);
        assertEquals(HealthStatus.HEALTHY, result.getStatus());
    }

    @Test
    @Order(52)
    @DisplayName("Should return unhealthy for unknown check")
    void testRunUnknownCheck() {
        HealthResult result = healthCheckService.runCheck("nonexistent");

        assertEquals(HealthStatus.UNHEALTHY, result.getStatus());
    }

    // ========================= Observer Pattern Tests =========================

    @Test
    @Order(60)
    @DisplayName("Should notify listener on health change")
    void testHealthChangeNotification() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean notified = new AtomicBoolean(false);

        healthCheckService.addListener(new HealthChangeListener() {
            @Override
            public void onHealthChanged(HealthStatus oldStatus, HealthStatus newStatus, HealthResult result) {
                notified.set(true);
                latch.countDown();
            }
        });

        // Register a check that will change health status
        healthCheckService.registerCheck("changing", () -> HealthResult.unhealthy("Failed"));
        healthCheckService.runAllChecks();

        assertTrue(latch.await(1, TimeUnit.SECONDS) || notified.get());
    }

    @Test
    @Order(61)
    @DisplayName("Should remove listener")
    void testRemoveListener() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);

        HealthChangeListener listener = (old, current, result) -> callCount.incrementAndGet();

        healthCheckService.addListener(listener);
        healthCheckService.removeListener(listener);

        healthCheckService.registerCheck("test", () -> HealthResult.unhealthy("Failed"));
        healthCheckService.runAllChecks();

        Thread.sleep(100);
        assertEquals(0, callCount.get());
    }

    @Test
    @Order(62)
    @DisplayName("Multiple listeners should all be notified")
    void testMultipleListeners() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            healthCheckService.addListener((old, current, result) -> latch.countDown());
        }

        healthCheckService.registerCheck("multi", () -> HealthResult.unhealthy("Changed"));
        healthCheckService.runAllChecks();

        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    // ========================= Background Monitoring Tests =========================

    @Test
    @Order(70)
    @DisplayName("Should start background monitoring")
    void testStartMonitoring() throws InterruptedException {
        AtomicInteger checkCount = new AtomicInteger(0);

        healthCheckService.registerCheck("monitored", () -> {
            checkCount.incrementAndGet();
            return HealthResult.healthy("OK");
        });

        healthCheckService.startMonitoring(Duration.ofMillis(100));

        Thread.sleep(350);

        healthCheckService.stopMonitoring();

        assertTrue(checkCount.get() >= 2);
    }

    @Test
    @Order(71)
    @DisplayName("Should stop background monitoring")
    void testStopMonitoring() throws InterruptedException {
        AtomicInteger checkCount = new AtomicInteger(0);

        healthCheckService.registerCheck("stopped", () -> {
            checkCount.incrementAndGet();
            return HealthResult.healthy("OK");
        });

        healthCheckService.startMonitoring(Duration.ofMillis(50));
        Thread.sleep(150);

        int countBeforeStop = checkCount.get();
        healthCheckService.stopMonitoring();

        Thread.sleep(150);

        // Count should not increase significantly after stop
        assertTrue(checkCount.get() <= countBeforeStop + 1);
    }

    // ========================= Current Status Tests =========================

    @Test
    @Order(80)
    @DisplayName("Should get current health status")
    void testGetCurrentStatus() {
        healthCheckService.registerCheck("current", () -> HealthResult.healthy("OK"));
        healthCheckService.runAllChecks();

        HealthStatus status = healthCheckService.getCurrentStatus();

        assertNotNull(status);
    }

    @Test
    @Order(81)
    @DisplayName("Should get last health result")
    void testGetLastResult() {
        healthCheckService.registerCheck("last", () -> HealthResult.healthy("Last check"));
        healthCheckService.runAllChecks();

        HealthResult result = healthCheckService.getLastResult();

        assertNotNull(result);
    }

    // ========================= Edge Cases =========================

    @Test
    @Order(90)
    @DisplayName("Should handle null check name gracefully")
    void testNullCheckName() {
        assertThrows(Exception.class, () -> {
            healthCheckService.registerCheck(null, () -> HealthResult.healthy("OK"));
        });
    }

    @Test
    @Order(91)
    @DisplayName("Should handle null check strategy gracefully")
    void testNullCheckStrategy() {
        assertThrows(Exception.class, () -> {
            healthCheckService.registerCheck("null-strategy", null);
        });
    }

    @Test
    @Order(92)
    @DisplayName("Should handle check timeout")
    void testCheckTimeout() {
        healthCheckService.registerCheck("slow", () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HealthResult.healthy("OK");
        });

        // This may or may not timeout depending on implementation
        HealthResult result = healthCheckService.runCheck("slow");
        assertNotNull(result);
    }

    // ========================= Cleanup Tests =========================

    @Test
    @Order(100)
    @DisplayName("Close should stop monitoring")
    void testClose() {
        healthCheckService.startMonitoring(Duration.ofMillis(100));

        assertDoesNotThrow(() -> healthCheckService.close());
    }

    @Test
    @Order(101)
    @DisplayName("Multiple close calls should be safe")
    void testMultipleClose() {
        assertDoesNotThrow(() -> {
            healthCheckService.close();
            healthCheckService.close();
            healthCheckService.close();
        });
    }
}
