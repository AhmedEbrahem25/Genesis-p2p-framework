package com.genesis.p2p.transport.core;

/**
 * Transport configuration.
 */
public record TransportConfig(
        int port,
        String bindAddress,
        int sendBufferSize,
        int receiveBufferSize,
        int connectionTimeout,
        boolean enableEncryption,
        boolean enableCompression
) {
    public static TransportConfig defaults() {
        return new TransportConfig(
                8080,           // port
                "0.0.0.0",     // bind to all interfaces
                65536,         // 64KB send buffer
                65536,         // 64KB receive buffer
                5000,          // 5s connection timeout
                true,          // encryption enabled
                false          // compression disabled
        );
    }
}
