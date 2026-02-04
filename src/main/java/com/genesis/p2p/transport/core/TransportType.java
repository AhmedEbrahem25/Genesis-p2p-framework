package com.genesis.p2p.transport.core;

/**
 * Transport type enumeration.
 */
public enum TransportType {
    TCP("TCP", true),
    UDP("UDP", false),
    WEBSOCKET("WebSocket", true);

    private final String name;
    private final boolean reliable;

    TransportType(String name, boolean reliable) {
        this.name = name;
        this.reliable = reliable;
    }

    public String getName() { return name; }
    public boolean isReliable() { return reliable; }
}
