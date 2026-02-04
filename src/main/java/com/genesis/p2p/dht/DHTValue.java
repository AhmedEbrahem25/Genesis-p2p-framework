package com.genesis.p2p.dht;

import java.time.Instant;

public record DHTValue(NodeId key, byte[] data, NodeId publisherId,
                       Instant storedAt, Instant expiresAt, int republishCount) {
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
}

