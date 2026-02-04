package com.genesis.p2p.testutil.builders;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.testutil.TestConstants;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builder for creating test Message objects with sensible defaults.
 * <p>
 * Design Pattern: Builder Pattern
 * Simplifies test message creation and improves test readability.
 * <p>
 * Features:
 * - Fluent API for easy customization
 * - Sensible defaults for all fields
 * - Convenience factory methods for common message types
 * - Automatic generation of unique IDs and timestamps
 * <p>
 * Usage:
 * <pre>
 * // Simple message with defaults
 * Message msg = TestMessageBuilder.aMessage().build();
 *
 * // Customized message
 * Message msg = TestMessageBuilder.aMessage()
 *     .withType("CUSTOM")
 *     .withFrom("node-1")
 *     .withTo("node-2")
 *     .withContent("{\"key\":\"value\"}")
 *     .build();
 *
 * // Pre-configured message types
 * Message ping = TestMessageBuilder.aPingMessage().build();
 * Message hello = TestMessageBuilder.aHelloMessage().build();
 * </pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class TestMessageBuilder {

    // Header fields
    private String messageId = null; // Will be auto-generated
    private String correlationId = null; // Will default to messageId
    private String protocolVersion = "2.0";
    private String messageVersion = "1.0";
    private int ttl = 10;
    private int hopCount = 0;
    private String type = "TEST";
    private String from = TestConstants.TEST_NODE_ID_PREFIX + "sender";
    private String to = null; // Can be null (broadcast)
    private long timestamp = 0; // Will be auto-generated
    private boolean encrypted = false;
    private String contentType = "json";
    private boolean authenticated = false;
    private String signature = "";

    // Body fields
    private String content = "{}"; // Empty JSON object
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Creates a new builder with default values.
     *
     * @return a new TestMessageBuilder instance
     */
    public static TestMessageBuilder aMessage() {
        return new TestMessageBuilder();
    }

    /**
     * Creates a builder pre-configured for a PING message.
     *
     * @return a TestMessageBuilder configured for PING
     */
    public static TestMessageBuilder aPingMessage() {
        return new TestMessageBuilder()
            .withType("PING")
            .withContent("{\"timestamp\":" + System.currentTimeMillis() + "}");
    }

    /**
     * Creates a builder pre-configured for a PONG message.
     *
     * @return a TestMessageBuilder configured for PONG
     */
    public static TestMessageBuilder aPongMessage() {
        return new TestMessageBuilder()
            .withType("PONG")
            .withContent("{\"timestamp\":" + System.currentTimeMillis() + "}");
    }

    /**
     * Creates a builder pre-configured for a HELLO message.
     *
     * @return a TestMessageBuilder configured for HELLO
     */
    public static TestMessageBuilder aHelloMessage() {
        return new TestMessageBuilder()
            .withType("HELLO")
            .withContent("{\"version\":\"2.0\",\"capabilities\":[]}");
    }

    /**
     * Creates a builder pre-configured for a WELCOME message.
     *
     * @return a TestMessageBuilder configured for WELCOME
     */
    public static TestMessageBuilder aWelcomeMessage() {
        return new TestMessageBuilder()
            .withType("WELCOME")
            .withContent("{\"peerId\":\"test-peer\",\"timestamp\":" + System.currentTimeMillis() + "}");
    }

    /**
     * Creates a builder pre-configured for a HEARTBEAT message.
     *
     * @return a TestMessageBuilder configured for HEARTBEAT
     */
    public static TestMessageBuilder aHeartbeatMessage() {
        return new TestMessageBuilder()
            .withType("HEARTBEAT")
            .withContent("{\"status\":\"alive\",\"timestamp\":" + System.currentTimeMillis() + "}");
    }

    /**
     * Creates a builder pre-configured for a GOODBYE message.
     *
     * @return a TestMessageBuilder configured for GOODBYE
     */
    public static TestMessageBuilder aGoodbyeMessage() {
        return new TestMessageBuilder()
            .withType("GOODBYE")
            .withContent("{\"reason\":\"shutdown\",\"timestamp\":" + System.currentTimeMillis() + "}");
    }

    // ==================== Header Builders ====================

    public TestMessageBuilder withMessageId(String messageId) {
        this.messageId = messageId;
        return this;
    }

    public TestMessageBuilder withCorrelationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public TestMessageBuilder withProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
        return this;
    }

    public TestMessageBuilder withMessageVersion(String messageVersion) {
        this.messageVersion = messageVersion;
        return this;
    }

    public TestMessageBuilder withTtl(int ttl) {
        this.ttl = ttl;
        return this;
    }

    public TestMessageBuilder withHopCount(int hopCount) {
        this.hopCount = hopCount;
        return this;
    }

    public TestMessageBuilder withType(String type) {
        this.type = type;
        return this;
    }

    public TestMessageBuilder withFrom(String from) {
        this.from = from;
        return this;
    }

    public TestMessageBuilder withTo(String to) {
        this.to = to;
        return this;
    }

    public TestMessageBuilder withTimestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public TestMessageBuilder withEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
        return this;
    }

    public TestMessageBuilder withContentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    public TestMessageBuilder withAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
        return this;
    }

    public TestMessageBuilder withSignature(String signature) {
        this.signature = signature;
        return this;
    }

    // ==================== Body Builders ====================

    public TestMessageBuilder withContent(String content) {
        this.content = content;
        return this;
    }

    public TestMessageBuilder withMetadata(Map<String, Object> metadata) {
        this.metadata = new HashMap<>(metadata);
        return this;
    }

    public TestMessageBuilder addMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    // ==================== Build ====================

    /**
     * Builds the Message instance.
     *
     * @return a new Message with the configured values
     */
    public Message build() {
        // Build header
        MessageHeader header = new MessageHeader(
            messageId,
            correlationId,
            protocolVersion,
            messageVersion,
            ttl,
            hopCount,
            type,
            from,
            to,
            timestamp,
            encrypted,
            contentType,
            authenticated,
            signature,
            false // requiresAck
        );

        // Build body
        MessageBody body = new MessageBody(content, metadata);

        // Build message
        return new Message(header, body);
    }
}
