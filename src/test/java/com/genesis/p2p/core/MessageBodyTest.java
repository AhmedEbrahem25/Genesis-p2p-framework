package com.genesis.p2p.core;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MessageBody Tests")
class MessageBodyTest extends BaseUnitTest {

    @Test
    @DisplayName("Should create body with content")
    void testCreateWithContent() {
        String content = "Test payload";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");

        MessageBody body = new MessageBody(content, metadata);

        assertNotNull(body);
        assertEquals(content, body.content());
    }

    @Test
    @DisplayName("Should create body with metadata")
    void testCreateWithMetadata() {
        String content = "Test payload";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", 42);

        MessageBody body = new MessageBody(content, metadata);

        assertNotNull(body);
        assertNotNull(body.metadata());
        assertEquals("value1", body.metadata().get("key1"));
        assertEquals(42, body.metadata().get("key2"));
    }

    @Test
    @DisplayName("Should throw on null content")
    void testNullContent() {
        Map<String, Object> metadata = new HashMap<>();
        assertThrows(IllegalArgumentException.class, () -> new MessageBody(null, metadata));
    }

    @Test
    @DisplayName("Should throw on empty content")
    void testEmptyContent() {
        Map<String, Object> metadata = new HashMap<>();
        assertThrows(IllegalArgumentException.class, () -> new MessageBody("", metadata));
    }

    @Test
    @DisplayName("Should throw on null metadata")
    void testNullMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new MessageBody("content", null));
    }
}
