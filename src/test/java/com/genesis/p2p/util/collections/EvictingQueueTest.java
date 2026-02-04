package com.genesis.p2p.util.collections;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EvictingQueue Tests")
class EvictingQueueTest extends BaseUnitTest {

    private EvictingQueue<String> queue;

    @BeforeEach
    void setUp() {
        queue = new EvictingQueue<>(3);
    }

    @Test
    @DisplayName("Should add elements")
    void testAdd() {
        assertTrue(queue.add("item1"));
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("Should evict oldest when full")
    void testEviction() {
        queue.add("item1");
        queue.add("item2");
        queue.add("item3");
        queue.add("item4");

        assertEquals(3, queue.size());
        assertFalse(queue.contains("item1"));
        assertTrue(queue.contains("item4"));
    }

    @Test
    @DisplayName("Should offer elements")
    void testOffer() {
        assertTrue(queue.offer("item1"));
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("Should poll elements")
    void testPoll() {
        queue.add("item1");
        queue.add("item2");

        assertEquals("item1", queue.poll());
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("Should peek at head")
    void testPeek() {
        queue.add("item1");
        queue.add("item2");

        assertEquals("item1", queue.peek());
        assertEquals(2, queue.size());
    }

    @Test
    @DisplayName("Should clear queue")
    void testClear() {
        queue.add("item1");
        queue.add("item2");
        queue.clear();

        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Should check if contains element")
    void testContains() {
        queue.add("item1");
        assertTrue(queue.contains("item1"));
        assertFalse(queue.contains("item2"));
    }
}

