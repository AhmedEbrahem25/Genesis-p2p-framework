package com.genesis.p2p.integration;

import com.genesis.p2p.testutil.base.BaseIntegrationTest;
import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.MessageBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@DisplayName("Message Exchange Integration Tests")
class MessageExchangeIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should create valid message for exchange")
    void testCreateValidMessage() {
        MessageHeader header = createTestHeader("HELLO", "sender-1", "receiver-1");
        MessageBody body = createTestBody("Hello from sender");

        Message message = new Message(header, body);

        assertNotNull(message);
        assertEquals("HELLO", message.type());
        assertEquals("sender-1", message.from());
        assertEquals("receiver-1", message.to());
    }

    @Test
    @DisplayName("Should create valid request-response message pair")
    void testRequestResponsePair() {
        // Request message
        MessageHeader requestHeader = createTestHeader("REQUEST", "client", "server");
        MessageBody requestBody = createTestBody("Get data");
        Message request = new Message(requestHeader, requestBody);

        // Response message
        MessageHeader responseHeader = createTestHeader("RESPONSE", "server", "client");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("correlationId", request.messageId());
        MessageBody responseBody = new MessageBody("Data response", metadata);
        Message response = new Message(responseHeader, responseBody);

        assertNotNull(request);
        assertNotNull(response);
        assertEquals("REQUEST", request.type());
        assertEquals("RESPONSE", response.type());
        assertEquals(request.messageId(), response.body().metadata().get("correlationId"));
    }

    @Test
    @DisplayName("Should handle broadcast message")
    void testBroadcastMessage() {
        MessageHeader header = createTestHeader("BROADCAST", "node-1", "*");
        MessageBody body = createTestBody("Broadcast announcement");

        Message message = new Message(header, body);

        assertEquals("*", message.to());
        assertEquals("BROADCAST", message.type());
    }

    @Test
    @DisplayName("Should handle message with metadata")
    void testMessageWithMetadata() {
        MessageHeader header = createTestHeader("DATA", "producer", "consumer");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("timestamp", System.currentTimeMillis());
        metadata.put("priority", 5);
        metadata.put("encrypted", true);

        MessageBody body = new MessageBody("Payload data", metadata);
        Message message = new Message(header, body);

        assertNotNull(message.body().metadata());
        assertEquals(3, message.body().metadata().size());
        assertEquals(5, message.body().metadata().get("priority"));
    }

    private MessageHeader createTestHeader(String type, String from, String to) {
        return new MessageHeader(
            null, null, "1.0", "1.0", 10, 0, type, from, to,
            System.currentTimeMillis(), false, "text/plain", false, null, false
        );
    }

    private MessageBody createTestBody(String content) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("test", true);
        return new MessageBody(content, metadata);
    }
}

