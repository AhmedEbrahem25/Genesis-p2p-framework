package com.genesis.p2p.protocol.codec;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Protocol Buffers-based message codec implementation.
 *
 * Uses a custom binary format inspired by Protobuf for efficient serialization.
 * Suitable for:
 * - Production environments
 * - High-throughput scenarios
 * - Bandwidth-constrained networks
 *
 * Trade-offs:
 * - Pros: Compact size, fast serialization, type-safe
 * - Cons: Not human-readable, requires schema knowledge
 *
 * Wire format (simplified):
 * - Field numbers with type tags
 * - Variable-length encoding for integers
 * - Length-prefixed strings
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ProtobufMessageCodec implements MessageCodec {

    private static final String NAME = "protobuf";
    private static final String MIME_TYPE = "application/x-protobuf";

    // Field tags
    private static final byte FIELD_MESSAGE_ID = 1;
    private static final byte FIELD_TYPE = 2;
    private static final byte FIELD_FROM = 3;
    private static final byte FIELD_TO = 4;
    private static final byte FIELD_TIMESTAMP = 5;
    private static final byte FIELD_TTL = 6;
    private static final byte FIELD_CONTENT = 7;

    @Override
    public byte[] encode(Message message) throws CodecException {
        if (message == null) {
            throw new CodecException("Message cannot be null");
        }

        try {
            MessageHeader header = message.header();
            MessageBody body = message.body();

            // Estimate size (rough estimate to avoid resizing)
            int estimatedSize = 200 +
                    (header.messageId() != null ? header.messageId().length() : 0) +
                    (header.type() != null ? header.type().length() : 0) +
                    (header.from() != null ? header.from().length() : 0) +
                    (header.to() != null ? header.to().length() : 0) +
                    (body.content() != null ? body.content().length() : 0);

            ByteBuffer buffer = ByteBuffer.allocate(estimatedSize);

            // Encode header fields
            encodeString(buffer, FIELD_MESSAGE_ID, header.messageId());
            encodeString(buffer, FIELD_TYPE, header.type());
            encodeString(buffer, FIELD_FROM, header.from());

            if (header.to() != null && !header.to().isEmpty()) {
                encodeString(buffer, FIELD_TO, header.to());
            }

            encodeVarint(buffer, FIELD_TIMESTAMP, header.timestamp());
            encodeVarint(buffer, FIELD_TTL, header.ttl());

            // Encode body
            if (body.content() != null) {
                encodeString(buffer, FIELD_CONTENT, body.content());
            }

            // Get actual data written
            byte[] result = new byte[buffer.position()];
            buffer.flip();
            buffer.get(result);
            return result;

        } catch (Exception e) {
            throw new CodecException("Failed to encode message to protobuf", e);
        }
    }

    @Override
    public Message decode(byte[] data) throws CodecException {
        if (data == null || data.length == 0) {
            throw new CodecException("Data cannot be null or empty");
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);

            String messageId = null;
            String type = null;
            String from = null;
            String to = null;
            long timestamp = 0;
            int ttl = 0;
            String content = null;

            while (buffer.hasRemaining()) {
                byte tag = buffer.get();

                switch (tag) {
                    case FIELD_MESSAGE_ID:
                        messageId = decodeString(buffer);
                        break;
                    case FIELD_TYPE:
                        type = decodeString(buffer);
                        break;
                    case FIELD_FROM:
                        from = decodeString(buffer);
                        break;
                    case FIELD_TO:
                        to = decodeString(buffer);
                        break;
                    case FIELD_TIMESTAMP:
                        timestamp = decodeVarint(buffer);
                        break;
                    case FIELD_TTL:
                        ttl = (int) decodeVarint(buffer);
                        break;
                    case FIELD_CONTENT:
                        content = decodeString(buffer);
                        break;
                    default:
                        throw new CodecException("Unknown field tag: " + tag);
                }
            }

            MessageHeader header = new MessageHeader(
                    messageId, null, null, null,
                    ttl, 0, type, from, to, timestamp,
                    false, "json", false, "",
                    false // requiresAck
            );
            Map<String, Object> metadata = new HashMap<>();
            MessageBody body = new MessageBody(content != null ? content : "", metadata);
            return new Message(header, body);

        } catch (Exception e) {
            throw new CodecException("Failed to decode protobuf to message", e);
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

    // ==================== Encoding Helpers ====================

    /**
     * Encodes a string field with length prefix.
     */
    private void encodeString(ByteBuffer buffer, byte tag, String value) {
        if (value == null) {
            return;
        }
        buffer.put(tag);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        encodeLength(buffer, bytes.length);
        buffer.put(bytes);
    }

    /**
     * Encodes a variable-length integer.
     */
    private void encodeVarint(ByteBuffer buffer, byte tag, long value) {
        buffer.put(tag);
        while (value > 127) {
            buffer.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buffer.put((byte) value);
    }

    /**
     * Encodes a length prefix for strings/bytes.
     */
    private void encodeLength(ByteBuffer buffer, int length) {
        while (length > 127) {
            buffer.put((byte) ((length & 0x7F) | 0x80));
            length >>>= 7;
        }
        buffer.put((byte) length);
    }

    // ==================== Decoding Helpers ====================

    /**
     * Decodes a length-prefixed string.
     */
    private String decodeString(ByteBuffer buffer) throws CodecException {
        int length = decodeLength(buffer);
        if (length < 0 || length > buffer.remaining()) {
            throw new CodecException("Invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a variable-length integer.
     */
    private long decodeVarint(ByteBuffer buffer) throws CodecException {
        long result = 0;
        int shift = 0;
        while (true) {
            if (!buffer.hasRemaining()) {
                throw new CodecException("Unexpected end of varint");
            }
            byte b = buffer.get();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new CodecException("Varint too long");
            }
        }
    }

    /**
     * Decodes a length prefix.
     */
    private int decodeLength(ByteBuffer buffer) throws CodecException {
        int result = 0;
        int shift = 0;
        while (true) {
            if (!buffer.hasRemaining()) {
                throw new CodecException("Unexpected end of length");
            }
            byte b = buffer.get();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 31) {
                throw new CodecException("Length too long");
            }
        }
    }

    @Override
    public String toString() {
        return String.format("ProtobufMessageCodec[name=%s, mimeType=%s]", NAME, MIME_TYPE);
    }
}

