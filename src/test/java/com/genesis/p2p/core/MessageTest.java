package com.genesis.p2p.core;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Message Tests")
class MessageTest extends BaseUnitTest {

    @Test
    @DisplayName("Should create message with header and body")
    void testCreateMessage() {
        MessageHeader header = createTestHeader();
        MessageBody body = createTestBody();

        Message message = new Message(header, body);

        assertNotNull(message);
        assertNotNull(message.header());
        assertNotNull(message.body());
    }

    @Test
    @DisplayName("Should get message id from header")
    void testGetMessageId() {
        MessageHeader header = createTestHeader();
        MessageBody body = createTestBody();

        Message message = new Message(header, body);

        assertNotNull(message.messageId());
    }

    @Test
    @DisplayName("Should get message type from header")
    void testGetMessageType() {
        MessageHeader header = createTestHeader();
        MessageBody body = createTestBody();

        Message message = new Message(header, body);

        assertNotNull(message.type());
        assertEquals("HELLO", message.type());
    }

    @Test
    @DisplayName("Should get sender from header")
    void testGetSender() {
        MessageHeader header = createTestHeader();
        MessageBody body = createTestBody();

        Message message = new Message(header, body);

        assertEquals("sender-123", message.from());
    }

    @Test
    @DisplayName("Should get receiver from header")
    void testGetReceiver() {
        MessageHeader header = createTestHeader();
        MessageBody body = createTestBody();

        Message message = new Message(header, body);

        assertEquals("receiver-456", message.to());
    }

    @Test
    @DisplayName("Should throw on null header")
    void testNullHeader() {
        MessageBody body = createTestBody();
        assertThrows(IllegalArgumentException.class, () -> new Message(null, body));
    }

    @Test
    @DisplayName("Should throw on null body")
    void testNullBody() {
        MessageHeader header = createTestHeader();
        assertThrows(IllegalArgumentException.class, () -> new Message(header, null));
    }

    private MessageHeader createTestHeader() {
        return new MessageHeader(
            "msg-123", "corr-456", "1.0", "1.0", 10, 0, "HELLO", "sender-123", "receiver-456",
            System.currentTimeMillis(), false, "text/plain", false, null, false
        );
    }

    private MessageBody createTestBody() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("test", "value");
        return new MessageBody("Test payload", metadata);
    }
}
