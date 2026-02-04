package com.genesis.p2p.core;

import java.util.UUID;

public record MessageHeader(
        String messageId,
        String correlationId,
        String protocolVersion,
        String messageVersion,
        int ttl,
        int hopCount,
        String type,
        String from,
        String to,
        long timestamp,
        boolean encrypted,
        String contentType,
        boolean authenticated,
        String signature,
        /** Whether this message requires acknowledgment */
        boolean requiresAck
        ) {

    public MessageHeader {
        // ---------- ID fields ----------
        if (messageId == null || messageId.isEmpty())
            messageId = UUID.randomUUID().toString();

        if (correlationId == null || correlationId.isEmpty())
            correlationId = messageId; // default: single message, no correlation yet

        // ---------- Versioning ----------
        if (protocolVersion == null || protocolVersion.isEmpty())
            protocolVersion = "1.0";

        if (messageVersion == null || messageVersion.isEmpty())
            messageVersion = "1.0";

        // ---------- Routing ----------
        if (ttl <= 0)
            ttl = 10;

        if (hopCount < 0)
            hopCount = 0;

        // ---------- Types ----------
        if (type == null || type.isEmpty())
            throw new IllegalArgumentException("Message type cannot be empty.");

        if (from == null || from.isEmpty())
            throw new IllegalArgumentException("Message sender cannot be empty.");

        // Optional: to
        if (timestamp <= 0)
            timestamp = System.currentTimeMillis();

        // ---------- Content ----------
        if (contentType == null || contentType.isEmpty())
            contentType = "json";

        // ---------- Security ----------
        if (signature == null)
            signature = ""; // empty until we implement ECDSA

        // requiresAck defaults to false (no validation needed)
    }

    /**
     * Creates a copy with requiresAck set.
     */
    public MessageHeader withRequiresAck(boolean requiresAck) {
        return new MessageHeader(
                messageId, correlationId, protocolVersion, messageVersion,
                ttl, hopCount, type, from, to, timestamp, encrypted,
                contentType, authenticated, signature, requiresAck
        );
    }

    /**
     * Creates a copy with a new messageId.
     */
    public MessageHeader withNewMessageId() {
        return new MessageHeader(
                UUID.randomUUID().toString(), correlationId, protocolVersion, messageVersion,
                ttl, hopCount, type, from, to, timestamp, encrypted,
                contentType, authenticated, signature, requiresAck
        );
    }
}
