package com.genesis.p2p.observability.tracing;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TraceIdGenerator Tests")
class TraceIdGeneratorTest extends BaseUnitTest {

    @Test
    @DisplayName("Should generate non-null trace ID")
    void testGenerate() {
        String traceId = TraceIdGenerator.generate();
        assertNotNull(traceId);
        assertFalse(traceId.isEmpty());
    }

    @Test
    @DisplayName("Should generate unique trace IDs")
    void testUniqueness() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = TraceIdGenerator.generate();
            assertFalse(ids.contains(id), "Duplicate ID generated: " + id);
            ids.add(id);
        }
    }

    @Test
    @DisplayName("Should generate full UUID trace ID")
    void testGenerateFull() {
        String fullId = TraceIdGenerator.generateFull();

        assertNotNull(fullId);
        // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        assertTrue(fullId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    @DisplayName("Should generate unique full UUIDs")
    void testFullUniqueness() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = TraceIdGenerator.generateFull();
            assertFalse(ids.contains(id), "Duplicate full ID generated: " + id);
            ids.add(id);
        }
    }

    @Test
    @DisplayName("Should be thread-safe")
    void testThreadSafety() throws InterruptedException {
        int threads = 10;
        int idsPerThread = 1000;
        Set<String> allIds = java.util.Collections.synchronizedSet(new HashSet<>());

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < idsPerThread; j++) {
                    allIds.add(TraceIdGenerator.generate());
                }
            });
            workers[i].start();
        }

        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(threads * idsPerThread, allIds.size());
    }

    @Test
    @DisplayName("Short ID should contain node prefix")
    void testShortIdFormat() {
        String id = TraceIdGenerator.generate();
        // Format: prefix-counter (e.g., "abcd1234-0000000000000001")
        assertTrue(id.contains("-"));
    }
}

