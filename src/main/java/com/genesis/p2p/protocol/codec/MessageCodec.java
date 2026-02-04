package com.genesis.p2p.protocol.codec;

import com.genesis.p2p.core.Message;

/**
 * Strategy interface for message encoding/decoding.
 *
 * Implementations define how messages are serialized to bytes
 * and deserialized from bytes for transmission over the network.
 *
 * Common implementations:
 * - JSON codec (human-readable, debugging)
 * - Protobuf codec (efficient, production)
 * - MessagePack codec (compact, fast)
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public interface MessageCodec {

    /**
     * Encodes a message to bytes.
     *
     * @param message the message to encode
     * @return serialized message bytes
     * @throws CodecException if encoding fails
     */
    byte[] encode(Message message) throws CodecException;

    /**
     * Decodes bytes to a message.
     *
     * @param data the bytes to decode
     * @return deserialized message
     * @throws CodecException if decoding fails
     */
    Message decode(byte[] data) throws CodecException;

    /**
     * Gets the name of this codec (e.g., "json", "protobuf").
     *
     * @return codec identifier
     */
    String getName();

    /**
     * Gets the MIME type for this codec (e.g., "application/json").
     *
     * @return MIME type string
     */
    String getMimeType();

    /**
     * Exception thrown when encoding/decoding fails.
     */
    class CodecException extends Exception {
        public CodecException(String message) {
            super(message);
        }

        public CodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

