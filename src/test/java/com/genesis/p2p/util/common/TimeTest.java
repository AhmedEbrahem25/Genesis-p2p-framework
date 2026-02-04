package com.genesis.p2p.util.common;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Time Utility Tests")
class TimeTest extends BaseUnitTest {

    @Test
    @DisplayName("Should get current timestamp in millis")
    void testCurrentMillis() {
        long timestamp = Time.currentMillis();
        assertTrue(timestamp > 0);
    }

    @Test
    @DisplayName("Should get current nano time")
    void testCurrentNanos() {
        long nanos = Time.currentNanos();
        assertTrue(nanos > 0);
    }

    @Test
    @DisplayName("Should get current seconds")
    void testCurrentSeconds() {
        long seconds = Time.currentSeconds();
        assertTrue(seconds > 0);
    }

    @Test
    @DisplayName("Should get current Instant")
    void testNow() {
        Instant now = Time.now();
        assertNotNull(now);
    }

    @Test
    @DisplayName("Should measure elapsed time")
    void testElapsedTime() throws InterruptedException {
        long start = Time.currentNanos();
        Thread.sleep(10);
        long elapsed = Time.currentNanos() - start;
        assertTrue(elapsed > 0);
    }

    @Test
    @DisplayName("Should convert seconds to millis")
    void testToMillis() {
        long seconds = 5;
        long millis = Time.toMillis(seconds);
        assertEquals(5000, millis);
    }

    @Test
    @DisplayName("Should convert millis to seconds")
    void testToSeconds() {
        long millis = 5000;
        long seconds = Time.toSeconds(millis);
        assertEquals(5, seconds);
    }
}

