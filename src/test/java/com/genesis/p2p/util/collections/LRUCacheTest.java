package com.genesis.p2p.util.collections;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LRUCache Tests")
class LRUCacheTest extends BaseUnitTest {

    private LRUCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = new LRUCache<>(3);
    }

    @Test
    @DisplayName("Should put and get values")
    void testPutAndGet() {
        cache.put("key1", "value1");
        assertEquals("value1", cache.get("key1"));
    }

    @Test
    @DisplayName("Should return null for missing keys")
    void testGetMissingKey() {
        assertNull(cache.get("missing"));
    }

    @Test
    @DisplayName("Should evict least recently used item")
    void testEviction() {
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        cache.put("key4", "value4");

        assertNull(cache.get("key1"));
        assertEquals("value4", cache.get("key4"));
    }

    @Test
    @DisplayName("Should update access order on get")
    void testAccessOrder() {
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");

        cache.get("key1"); // Access key1
        cache.put("key4", "value4");

        assertNotNull(cache.get("key1"));
        assertNull(cache.get("key2"));
    }

    @Test
    @DisplayName("Should remove values")
    void testRemove() {
        cache.put("key1", "value1");
        assertEquals("value1", cache.remove("key1"));
        assertNull(cache.get("key1"));
    }

    @Test
    @DisplayName("Should clear cache")
    void testClear() {
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.clear();

        assertNull(cache.get("key1"));
        assertNull(cache.get("key2"));
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("Should check if contains key")
    void testContainsKey() {
        cache.put("key1", "value1");
        assertTrue(cache.containsKey("key1"));
        assertFalse(cache.containsKey("key2"));
    }

    @Test
    @DisplayName("Should get correct size")
    void testSize() {
        assertEquals(0, cache.size());
        cache.put("key1", "value1");
        assertEquals(1, cache.size());
        cache.put("key2", "value2");
        assertEquals(2, cache.size());
    }
}

