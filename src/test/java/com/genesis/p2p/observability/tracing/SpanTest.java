package com.genesis.p2p.observability.tracing;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Span Tests")
class SpanTest extends BaseUnitTest {

    @Test
    @DisplayName("Should create span with trace ID and operation name")
    void testCreate() {
        String traceId = TraceIdGenerator.generate();
        Span span = new Span(traceId, "test.operation");

        assertNotNull(span);
        assertEquals(traceId, span.getTraceId());
        assertEquals("test.operation", span.getOperationName());
        assertNotNull(span.getSpanId());
    }

    @Test
    @DisplayName("Should generate unique span ID")
    void testUniqueSpanId() {
        String traceId = TraceIdGenerator.generate();
        Span span1 = new Span(traceId, "op1");
        Span span2 = new Span(traceId, "op2");

        assertNotEquals(span1.getSpanId(), span2.getSpanId());
    }

    @Test
    @DisplayName("Should set and get tags")
    void testTags() {
        String traceId = TraceIdGenerator.generate();
        Span span = new Span(traceId, "test.operation");

        span.setTag("key1", "value1");
        span.setTag("key2", "value2");

        Map<String, String> tags = span.getTags();
        assertEquals("value1", tags.get("key1"));
        assertEquals("value2", tags.get("key2"));
    }

    @Test
    @DisplayName("Should support fluent tag setting")
    void testFluentTags() {
        String traceId = TraceIdGenerator.generate();
        Span span = new Span(traceId, "test.operation")
            .setTag("key1", "value1")
            .setTag("key2", "value2");

        Map<String, String> tags = span.getTags();
        assertEquals(2, tags.size());
    }

    @Test
    @DisplayName("Should finish span")
    void testFinish() throws InterruptedException {
        String traceId = TraceIdGenerator.generate();
        Span span = new Span(traceId, "test.operation");

        Thread.sleep(10);
        span.finish();

        Duration duration = span.getDuration();
        assertTrue(duration.toMillis() >= 10);
    }

    @Test
    @DisplayName("Should only finish once")
    void testFinishOnce() throws InterruptedException {
        String traceId = TraceIdGenerator.generate();
        Span span = new Span(traceId, "test.operation");

        Thread.sleep(10);
        span.finish();
        Duration firstDuration = span.getDuration();

        Thread.sleep(50);
        span.finish(); // Should not change end time
        Duration secondDuration = span.getDuration();

        assertEquals(firstDuration, secondDuration);
    }

    @Test
    @DisplayName("Should calculate duration for unfinished span")
    void testUnfinishedDuration() throws InterruptedException {
        String traceId = TraceIdGenerator.generate();
        Span span = new Span(traceId, "test.operation");

        Thread.sleep(10);
        Duration duration = span.getDuration();

        assertTrue(duration.toMillis() >= 10);
    }

    @Test
    @DisplayName("Should return copy of tags")
    void testTagsCopy() {
        String traceId = TraceIdGenerator.generate();
        Span span = new Span(traceId, "test.operation");
        span.setTag("key", "value");

        Map<String, String> tags = span.getTags();
        tags.put("new", "tag");

        // Original should not be affected
        assertFalse(span.getTags().containsKey("new"));
    }
}

