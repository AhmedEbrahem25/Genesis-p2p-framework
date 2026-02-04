package com.genesis.p2p.storage.message;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

/**
 * Serializes and deserializes PersistedMessage to/from bytes.
 *
 * Uses JSON format for human-readability and debugging.
 * Can be swapped for binary format (Protobuf, Avro) for production.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class MessageSerializer {

    private final Gson gson;

    public MessageSerializer() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Serializes a PersistedMessage to bytes.
     */
    public byte[] serialize(PersistedMessage pm) {
        JsonObject root = new JsonObject();

        // Persistence metadata
        root.addProperty("persistenceId", pm.persistenceId());
        root.addProperty("persistedAt", pm.persistedAt().toString());
        root.addProperty("state", pm.state().name());
        root.addProperty("direction", pm.direction().name());

        // Transport metadata
        root.addProperty("transportType", pm.transportType());
        root.addProperty("remoteAddress", pm.remoteAddress());

        // Protocol metadata
        root.addProperty("wasEncrypted", pm.wasEncrypted());
        root.addProperty("wasCompressed", pm.wasCompressed());
        root.addProperty("fragmentCount", pm.fragmentCount());

        // Timing
        if (pm.sentAt() != null) root.addProperty("sentAt", pm.sentAt().toString());
        if (pm.receivedAt() != null) root.addProperty("receivedAt", pm.receivedAt().toString());
        if (pm.deliveredAt() != null) root.addProperty("deliveredAt", pm.deliveredAt().toString());

        // Error tracking
        root.addProperty("retryCount", pm.retryCount());
        if (pm.lastError() != null) root.addProperty("lastError", pm.lastError());
        if (pm.lastErrorAt() != null) root.addProperty("lastErrorAt", pm.lastErrorAt().toString());

        // Handshake
        if (pm.handshakeId() != null) root.addProperty("handshakeId", pm.handshakeId());
        root.addProperty("isHandshakeMessage", pm.isHandshakeMessage());

        // Observability
        if (pm.traceId() != null) root.addProperty("traceId", pm.traceId());
        if (pm.spanId() != null) root.addProperty("spanId", pm.spanId());

        // Storage metadata
        root.addProperty("persistenceVersion", pm.persistenceVersion());
        root.addProperty("sizeBytes", pm.sizeBytes());

        // Message header
        JsonObject header = new JsonObject();
        MessageHeader h = pm.message().header();
        header.addProperty("messageId", h.messageId());
        header.addProperty("correlationId", h.correlationId());
        header.addProperty("protocolVersion", h.protocolVersion());
        header.addProperty("messageVersion", h.messageVersion());
        header.addProperty("ttl", h.ttl());
        header.addProperty("hopCount", h.hopCount());
        header.addProperty("type", h.type());
        header.addProperty("from", h.from());
        header.addProperty("to", h.to());
        header.addProperty("timestamp", h.timestamp());
        header.addProperty("encrypted", h.encrypted());
        header.addProperty("contentType", h.contentType());
        header.addProperty("authenticated", h.authenticated());
        if (h.signature() != null) header.addProperty("signature", h.signature());
        root.add("header", header);

        // Message body
        JsonObject body = new JsonObject();
        MessageBody b = pm.message().body();
        body.addProperty("content", b.content());
        // Serialize metadata
        if (b.metadata() != null && !b.metadata().isEmpty()) {
            body.add("metadata", gson.toJsonTree(b.metadata()));
        }
        root.add("body", body);

        return gson.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deserializes a PersistedMessage from bytes.
     */
    public PersistedMessage deserialize(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // Parse header
        JsonObject headerJson = root.getAsJsonObject("header");
        MessageHeader header = new MessageHeader(
                headerJson.get("messageId").getAsString(),
                headerJson.get("correlationId").getAsString(),
                headerJson.get("protocolVersion").getAsString(),
                headerJson.get("messageVersion").getAsString(),
                headerJson.get("ttl").getAsInt(),
                headerJson.get("hopCount").getAsInt(),
                headerJson.get("type").getAsString(),
                headerJson.get("from").getAsString(),
                headerJson.get("to").getAsString(),
                headerJson.get("timestamp").getAsLong(),
                headerJson.get("encrypted").getAsBoolean(),
                headerJson.get("contentType").getAsString(),
                headerJson.get("authenticated").getAsBoolean(),
                headerJson.has("signature") ? headerJson.get("signature").getAsString() : null,
                headerJson.has("requiresAck") && headerJson.get("requiresAck").getAsBoolean()
        );

        // Parse body
        JsonObject bodyJson = root.getAsJsonObject("body");
        Map<String, Object> metadata = new java.util.HashMap<>();
        if (bodyJson.has("metadata")) {
            metadata = gson.fromJson(bodyJson.get("metadata"),
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType());
        }
        MessageBody body = new MessageBody(
                bodyJson.get("content").getAsString(),
                metadata
        );

        Message message = new Message(header, body);

        // Parse persistence metadata
        return new PersistedMessage(
                message,
                root.get("persistenceId").getAsString(),
                Instant.parse(root.get("persistedAt").getAsString()),
                MessageState.valueOf(root.get("state").getAsString()),
                MessageDirection.valueOf(root.get("direction").getAsString()),
                root.get("transportType").getAsString(),
                root.get("remoteAddress").getAsString(),
                root.get("wasEncrypted").getAsBoolean(),
                root.get("wasCompressed").getAsBoolean(),
                root.get("fragmentCount").getAsInt(),
                root.has("sentAt") ? Instant.parse(root.get("sentAt").getAsString()) : null,
                root.has("receivedAt") ? Instant.parse(root.get("receivedAt").getAsString()) : null,
                root.has("deliveredAt") ? Instant.parse(root.get("deliveredAt").getAsString()) : null,
                root.get("retryCount").getAsInt(),
                root.has("lastError") ? root.get("lastError").getAsString() : null,
                root.has("lastErrorAt") ? Instant.parse(root.get("lastErrorAt").getAsString()) : null,
                root.has("handshakeId") ? root.get("handshakeId").getAsString() : null,
                root.get("isHandshakeMessage").getAsBoolean(),
                root.has("traceId") ? root.get("traceId").getAsString() : null,
                root.has("spanId") ? root.get("spanId").getAsString() : null,
                root.get("persistenceVersion").getAsInt(),
                root.get("sizeBytes").getAsLong()
        );
    }
}

