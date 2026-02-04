package com.genesis.p2p.transport.ws;

import com.genesis.p2p.protocol.ProtocolLayer;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.transport.core.*;
import com.genesis.p2p.observability.message.MessageLogger;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket transport implementation.
 *
 * Design Pattern: Strategy (ITransport implementation)
 * Integration: Uses ProtocolLayer for encoding/decoding
 *
 * Provides WebSocket transport alongside TCP/UDP for:
 * - Browser-based P2P nodes (JavaScript WebSocket API)
 * - Firewall traversal (uses ports 80/443)
 * - Corporate network compatibility (HTTP proxies allow WebSocket)
 * - Mobile app support (native WebSocket in iOS/Android)
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class WebSocketTransport extends AbstractTransport {

    private WebSocketServer server;
    private final Map<InetSocketAddress, WebSocket> connections = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, String> connectionIds = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Instant> handshakeStartTimes = new ConcurrentHashMap<>();

    /**
     * Creates a WebSocket transport with default metrics registry.
     * @deprecated Use constructor with MetricsRegistry for shared metrics
     */
    @Deprecated
    public WebSocketTransport(TransportConfig config, SecurityFacade security,
                             ProtocolLayer protocolLayer, MessageLogger messageLogger) {
        super(config, security, protocolLayer, messageLogger);
    }

    /**
     * Creates a WebSocket transport with shared metrics registry.
     *
     * @param config transport configuration
     * @param security security facade
     * @param protocolLayer protocol layer for encoding/decoding
     * @param messageLogger message logger for observability
     * @param metrics shared metrics registry for unified metrics aggregation
     */
    public WebSocketTransport(TransportConfig config, SecurityFacade security,
                             ProtocolLayer protocolLayer, MessageLogger messageLogger,
                             com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        super(config, security, protocolLayer, messageLogger, metrics);
    }

    @Override
    public TransportType getType() {
        return TransportType.WEBSOCKET;
    }

    @Override
    protected void doStart() throws Exception {
        server = new WebSocketServer(new InetSocketAddress(config.bindAddress(), config.port())) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                InetSocketAddress addr = conn.getRemoteSocketAddress();
                String connectionId = UUID.randomUUID().toString();
                Instant handshakeStart = Instant.now();

                connections.put(addr, conn);
                connectionIds.put(addr, connectionId);
                handshakeStartTimes.put(addr, handshakeStart);

                // Log handshake start
                log.info("WEBSOCKET_HANDSHAKE_START",
                        "connectionId", connectionId,
                        "remoteAddr", addr.getAddress().getHostAddress(),
                        "remotePort", addr.getPort(),
                        "path", handshake.getResourceDescriptor(),
                        "upgradeHeader", handshake.getFieldValue("Upgrade"));

                // Log handshake complete (happens immediately after onOpen)
                log.info("WEBSOCKET_HANDSHAKE_COMPLETE",
                        "connectionId", connectionId,
                        "remoteAddr", addr.getAddress().getHostAddress(),
                        "protocol", "ws",
                        "activeConnections", connections.size());
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                // Text messages not supported - only binary
                InetSocketAddress addr = conn.getRemoteSocketAddress();
                String connectionId = connectionIds.get(addr);

                log.warn("WEBSOCKET_TEXT_MESSAGE_IGNORED",
                        "connectionId", connectionId,
                        "from", addr,
                        "messageLength", message.length());
            }

            @Override
            public void onMessage(WebSocket conn, ByteBuffer message) {
                InetSocketAddress addr = conn.getRemoteSocketAddress();
                String connectionId = connectionIds.get(addr);

                byte[] data = new byte[message.remaining()];
                message.get(data);

                // Log frame received
                log.debug("WEBSOCKET_FRAME_RECEIVED",
                        "connectionId", connectionId,
                        "opcode", "BINARY",
                        "frameSize", data.length,
                        "remoteAddr", addr);

                handleIncoming(data, conn.getRemoteSocketAddress());
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                InetSocketAddress addr = conn.getRemoteSocketAddress();
                String connectionId = connectionIds.remove(addr);
                handshakeStartTimes.remove(addr);
                connections.remove(addr);

                log.info("WEBSOCKET_CONNECTION_CLOSED",
                    "connectionId", connectionId,
                    "remote", addr,
                    "closeCode", code,
                    "reason", reason != null && !reason.isEmpty() ? reason : "none",
                    "initiatedByRemote", remote,
                    "remainingConnections", connections.size());
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                if (conn != null) {
                    InetSocketAddress addr = conn.getRemoteSocketAddress();
                    String connectionId = connectionIds.get(addr);

                    log.error("WEBSOCKET_CONNECTION_ERROR",
                            "connectionId", connectionId,
                            "remote", addr,
                            "error", ex.getMessage(),
                            "errorType", ex.getClass().getSimpleName());
                } else {
                    log.error("WEBSOCKET_SERVER_ERROR",
                            "error", ex.getMessage(),
                            "errorType", ex.getClass().getSimpleName());
                }
            }

            @Override
            public void onStart() {
                log.info("WEBSOCKET_SERVER_STARTED",
                    "address", getAddress().getHostString(),
                    "port", getPort());
            }
        };

        log.info("WEBSOCKET_TRANSPORT_START",
                "bindAddress", config.bindAddress(),
                "port", config.port());

        server.start();

        log.info("WEBSOCKET_TRANSPORT_STARTED",
                "port", config.port());
    }

    @Override
    protected void doStop() throws Exception {
        if (server != null) {
            int activeConnectionCount = connections.size();

            log.info("WEBSOCKET_TRANSPORT_STOP",
                    "activeConnections", activeConnectionCount);

            // Close all active connections
            for (WebSocket conn : connections.values()) {
                try {
                    conn.close(1000, "Server shutting down");
                } catch (Exception e) {
                    log.warn("WEBSOCKET_CLOSE_ERROR",
                            "error", e.getMessage(),
                            "remote", conn.getRemoteSocketAddress());
                }
            }
            connections.clear();
            connectionIds.clear();
            handshakeStartTimes.clear();

            log.info("WEBSOCKET_CONNECTIONS_CLOSED",
                    "closedConnections", activeConnectionCount);

            // Stop server with 5 second timeout
            server.stop(5000);

            log.info("WEBSOCKET_TRANSPORT_STOPPED",
                    "port", config.port());
        }
    }

    @Override
    protected void doSend(byte[] data, InetSocketAddress destination) throws Exception {
        WebSocket conn = connections.get(destination);

        if (conn == null || !conn.isOpen()) {
            throw new IOException("No active WebSocket connection to " + destination);
        }

        String connectionId = connectionIds.get(destination);

        // Log frame sent
        log.debug("WEBSOCKET_FRAME_SENT",
                "connectionId", connectionId,
                "opcode", "BINARY",
                "frameSize", data.length,
                "destination", destination);

        // Send as binary frame
        conn.send(ByteBuffer.wrap(data));
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return server != null ? server.getAddress() : null;
    }

    /**
     * Gets the number of active WebSocket connections.
     */
    public int getActiveConnections() {
        return connections.size();
    }

    /**
     * Checks if a connection exists to the given address.
     */
    public boolean hasConnection(InetSocketAddress address) {
        WebSocket conn = connections.get(address);
        return conn != null && conn.isOpen();
    }
}
