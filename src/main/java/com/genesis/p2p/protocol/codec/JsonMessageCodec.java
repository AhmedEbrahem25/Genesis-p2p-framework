package com.genesis.p2p.protocol.codec;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON-based message codec implementation.
 *
 * Uses Gson for JSON serialization/deserialization.
 * Suitable for:
 * - Development and debugging (human-readable)
 * - Low-throughput scenarios
 * - Interoperability with web services
 *
 * Trade-offs:
 * - Pros: Human-readable, easy to debug, widely supported
 * - Cons: Larger payload size, slower than binary formats
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class JsonMessageCodec implements MessageCodec {

    private static final String NAME = "json";
    private static final String MIME_TYPE = "application/json";

    private final Gson gson;

    public JsonMessageCodec() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Creates codec with custom Gson.
     *
     * @param gson custom Gson instance
     */
    public JsonMessageCodec(Gson gson) {
        this.gson = gson;
    }

    @Override
    public byte[] encode(Message message) throws CodecException {
        if (message == null) {
            throw new CodecException("Message cannot be null");
        }

        try {
            // Create a serializable wrapper
            MessageDto dto = new MessageDto(message);
            String json = gson.toJson(dto);
            return json.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CodecException("Failed to encode message to JSON", e);
        }
    }

    @Override
    public Message decode(byte[] data) throws CodecException {
        if (data == null || data.length == 0) {
            throw new CodecException("Data cannot be null or empty");
        }

        try {
            String json = new String(data, StandardCharsets.UTF_8);
            MessageDto dto = gson.fromJson(json, MessageDto.class);
            return dto.toMessage();
        } catch (Exception e) {
            throw new CodecException("Failed to decode JSON to message", e);
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getMimeType() {
        return MIME_TYPE;
    }

    /**
     * Data Transfer Object for JSON serialization.
     */
    private static class MessageDto {
        public HeaderDto header;
        public BodyDto body;

        // Default constructor for Gson
        public MessageDto() {
        }

        public MessageDto(Message message) {
            this.header = new HeaderDto(message.header());
            this.body = new BodyDto(message.body());
        }

        public Message toMessage() {
            MessageHeader header = this.header.toHeader();
            MessageBody body = this.body.toBody();
            return new Message(header, body);
        }
    }

    private static class HeaderDto {
        public String messageId;
        public String correlationId;
        public String protocolVersion;
        public String messageVersion;
        public int ttl;
        public int hopCount;
        public String type;
        public String from;
        public String to;
        public long timestamp;
        public boolean encrypted;
        public String contentType;
        public boolean authenticated;
        public String signature;

        public HeaderDto() {
        }

        public HeaderDto(MessageHeader header) {
            this.messageId = header.messageId();
            this.correlationId = header.correlationId();
            this.protocolVersion = header.protocolVersion();
            this.messageVersion = header.messageVersion();
            this.ttl = header.ttl();
            this.hopCount = header.hopCount();
            this.type = header.type();
            this.from = header.from();
            this.to = header.to();
            this.timestamp = header.timestamp();
            this.encrypted = header.encrypted();
            this.contentType = header.contentType();
            this.authenticated = header.authenticated();
            this.signature = header.signature();
        }

        public MessageHeader toHeader() {
            return new MessageHeader(
                    messageId, correlationId, protocolVersion, messageVersion,
                    ttl, hopCount, type, from, to, timestamp,
                    encrypted, contentType, authenticated, signature,
                    false // requiresAck - default false when deserializing
            );
        }
    }

    private static class BodyDto {
        public String content;
        public Map<String, Object> metadata;

        public BodyDto() {
        }

        public BodyDto(MessageBody body) {
            this.content = body.content();
            this.metadata = body.metadata();
        }

        public MessageBody toBody() {
            return new MessageBody(content, metadata != null ? metadata : new HashMap<>());
        }
    }

    @Override
    public String toString() {
        return String.format("JsonMessageCodec[name=%s, mimeType=%s]", NAME, MIME_TYPE);
    }
}

