package com.genesis.p2p.core;

import java.util.Map;

public record MessageBody(
        String content,
        Map<String,Object> metadata

) {
    public MessageBody{
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("Message content cannot be null or empty");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }
    }
}
