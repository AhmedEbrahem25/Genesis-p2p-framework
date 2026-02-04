package com.genesis.p2p.core;

public record Message (
        MessageHeader header,
        MessageBody body
){
    public Message {
        if (header == null) {
            throw new IllegalArgumentException("Message header cannot be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("Message body cannot be null");
        }
    }

    /**
     * Convenience method to get message type from header.
     */
    public String type() {
        return header.type();
    }

    /**
     * Convenience method to get sender ID from header.
     */
    public String from() {
        return header.from();
    }

    /**
     * Convenience method to get recipient ID from header.
     */
    public String to() {
        return header.to();
    }

    /**
     * Convenience method to get message ID from header.
     */
    public String messageId() {
        return header.messageId();
    }
}
