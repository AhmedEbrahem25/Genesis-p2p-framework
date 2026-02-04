package com.genesis.p2p.transport.core;

import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.config.SecurityConfig;
import com.genesis.p2p.transport.tcp.TcpTransport;
import com.genesis.p2p.transport.udp.UdpTransport;
import com.genesis.p2p.transport.ws.WebSocketTransport;
import com.genesis.p2p.protocol.ProtocolLayer;
import com.genesis.p2p.observability.message.MessageLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.storage.FileKVStore;
import com.genesis.p2p.storage.message.MessagePersistenceConfig;
import com.genesis.p2p.storage.message.MessagePersistenceStore;

import java.util.UUID;

/**
 * Factory for creating transport instances.
 *
 * Design Pattern: Factory Pattern
 * - Simplifies transport creation
 * - Hides implementation details
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class TransportFactory {

    private final SecurityFacade security;
    private final ProtocolLayer protocolLayer;
    private final MessageLogger messageLogger;
    private final MetricsRegistry sharedMetrics;  // Shared metrics for all transports

    /**
     * Creates a transport factory with default security configuration.
     * Generates a random peer ID.
     */
    public TransportFactory() throws Exception {
        this(SecurityConfig.defaults(), "peer-" + UUID.randomUUID());
    }

    /**
     * Creates a transport factory with the given security configuration.
     * Generates a random peer ID.
     *
     * @param securityConfig the security configuration
     */
    public TransportFactory(SecurityConfig securityConfig) throws Exception {
        this(securityConfig, "peer-" + UUID.randomUUID());
    }

    /**
     * Creates a transport factory with the given security configuration and peer ID.
     *
     * @param securityConfig the security configuration
     * @param localPeerId the local peer identifier
     */
    public TransportFactory(SecurityConfig securityConfig, String localPeerId) throws Exception {
        this.security = new SecurityFacade(securityConfig, localPeerId);
        this.protocolLayer = null; // For backwards compatibility
        this.messageLogger = createMessageLogger();
        this.sharedMetrics = null; // Legacy mode - each transport creates its own
    }

    /**
     * Creates a transport factory with an existing security facade and protocol layer.
     *
     * @param security the security facade to use
     * @param protocolLayer the protocol layer to use
     */
    public TransportFactory(SecurityFacade security, ProtocolLayer protocolLayer) {
        this(security, protocolLayer, null);
    }

    /**
     * Creates a transport factory with an existing security facade, protocol layer, and shared metrics.
     *
     * This constructor enables unified metrics aggregation across all transports,
     * allowing components like PeerConnectionOrchestrator to verify message sends.
     *
     * @param security the security facade to use
     * @param protocolLayer the protocol layer to use
     * @param sharedMetrics the shared metrics registry for unified metrics (can be null for legacy behavior)
     */
    public TransportFactory(SecurityFacade security, ProtocolLayer protocolLayer, MetricsRegistry sharedMetrics) {
        this.security = security;
        this.protocolLayer = protocolLayer;
        this.sharedMetrics = sharedMetrics;
        try {
            this.messageLogger = createMessageLogger();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create message logger", e);
        }
    }

    /**
     * Creates the message logger with persistence.
     */
    private MessageLogger createMessageLogger() throws Exception {
        // Create message persistence store
        java.nio.file.Path dataDir = java.nio.file.Paths.get("data/messages");
        java.nio.file.Files.createDirectories(dataDir);

        FileKVStore kvStore = new FileKVStore(dataDir);
        MessagePersistenceConfig config = MessagePersistenceConfig.defaults();
        MessagePersistenceStore persistenceStore = new MessagePersistenceStore(kvStore, config);

        // Create message logger
        MetricsRegistry metrics = new MetricsRegistry("messages");
        return new MessageLogger(metrics, persistenceStore);
    }

    /**
     * Creates a TCP transport with the given port.
     *
     * @param port the port to bind to
     * @return a new TCP transport
     */
    public ITransport createTcpTransport(int port) {
        TransportConfig config = new TransportConfig(
                port,
                "0.0.0.0",
                65536,
                65536,
                5000,
                security.isEncryptionEnabled(),
                false
        );
        if (sharedMetrics != null) {
            return new TcpTransport(config, security, protocolLayer, messageLogger, sharedMetrics);
        }
        return new TcpTransport(config, security, protocolLayer, messageLogger);
    }

    /**
     * Creates a TCP transport with full configuration.
     *
     * @param config the transport configuration
     * @return a new TCP transport
     */
    public ITransport createTcpTransport(TransportConfig config) {
        if (sharedMetrics != null) {
            return new TcpTransport(config, security, protocolLayer, messageLogger, sharedMetrics);
        }
        return new TcpTransport(config, security, protocolLayer, messageLogger);
    }

    /**
     * Creates a UDP transport with the given port.
     *
     * @param port the port to bind to
     * @return a new UDP transport
     */
    public ITransport createUdpTransport(int port) {
        TransportConfig config = new TransportConfig(
                port,
                "0.0.0.0",
                65536,
                65536,
                5000,
                security.isEncryptionEnabled(),
                false
        );
        if (sharedMetrics != null) {
            return new UdpTransport(config, security, protocolLayer, messageLogger, sharedMetrics);
        }
        return new UdpTransport(config, security, protocolLayer, messageLogger);
    }

    /**
     * Creates a UDP transport with full configuration.
     *
     * @param config the transport configuration
     * @return a new UDP transport
     */
    public ITransport createUdpTransport(TransportConfig config) {
        if (sharedMetrics != null) {
            return new UdpTransport(config, security, protocolLayer, messageLogger, sharedMetrics);
        }
        return new UdpTransport(config, security, protocolLayer, messageLogger);
    }

    /**
     * Creates a WebSocket transport with the given port.
     *
     * @param port the port to bind to
     * @return a new WebSocket transport
     */
    public ITransport createWebSocketTransport(int port) {
        TransportConfig config = new TransportConfig(
                port,
                "0.0.0.0",
                65536,
                65536,
                5000,
                security.isEncryptionEnabled(),
                false
        );
        if (sharedMetrics != null) {
            return new WebSocketTransport(config, security, protocolLayer, messageLogger, sharedMetrics);
        }
        return new WebSocketTransport(config, security, protocolLayer, messageLogger);
    }

    /**
     * Creates a WebSocket transport with full configuration.
     *
     * @param config the transport configuration
     * @return a new WebSocket transport
     */
    public ITransport createWebSocketTransport(TransportConfig config) {
        if (sharedMetrics != null) {
            return new WebSocketTransport(config, security, protocolLayer, messageLogger, sharedMetrics);
        }
        return new WebSocketTransport(config, security, protocolLayer, messageLogger);
    }

    /**
     * Creates a transport by type with the given port.
     *
     * @param type the transport type
     * @param port the port to bind to
     * @return a new transport
     */
    public ITransport createTransport(TransportType type, int port) {
        return switch (type) {
            case TCP -> createTcpTransport(port);
            case UDP -> createUdpTransport(port);
            case WEBSOCKET -> createWebSocketTransport(port);
        };
    }

    /**
     * Gets the security facade.
     *
     * @return the security facade
     */
    public SecurityFacade getSecurity() {
        return security;
    }

    /**
     * Gets the message logger for replay and observability.
     *
     * @return the message logger
     */
    public MessageLogger getMessageLogger() {
        return messageLogger;
    }

    /**
     * Gets the protocol layer.
     *
     * @return the protocol layer
     */
    public ProtocolLayer getProtocolLayer() {
        return protocolLayer;
    }
}
