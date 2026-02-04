package com.genesis.p2p.transport.tcp;

import com.genesis.p2p.transport.core.*;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.protocol.ProtocolLayer;
import com.genesis.p2p.observability.message.MessageLogger;
import com.genesis.p2p.util.net.SocketUtils;
import com.genesis.p2p.util.threading.ThreadPoolFactory;
import com.genesis.p2p.util.resource.ResourceLeakDetector;
import com.genesis.p2p.util.resource.ResourceLeakDetector.ResourceTracker;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * TCP transport implementation.
 *
 * Design Pattern: Strategy (ITransport implementation)
 * Integration: Uses SecurityFacade, SocketUtils, and ThreadPoolFactory
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class TcpTransport extends AbstractTransport {

    private ServerSocket serverSocket;
    private final ExecutorService acceptExecutor;
    private final ConcurrentHashMap<String, TcpConnection> connections;
    private final ConcurrentHashMap<String, ResourceTracker> connectionTrackers;

    // FIX #2: Connection listener for security bootstrap
    private volatile TransportConnectionListener connectionListener;

    /**
     * Creates a TCP transport with default metrics registry.
     * @deprecated Use constructor with MetricsRegistry for shared metrics
     */
    @Deprecated
    public TcpTransport(TransportConfig config, SecurityFacade security,
                       ProtocolLayer protocolLayer, MessageLogger messageLogger) {
        super(config, security, protocolLayer, messageLogger);
        this.connections = new ConcurrentHashMap<>();
        this.connectionTrackers = new ConcurrentHashMap<>();
        this.acceptExecutor = ThreadPoolFactory.createNamedExecutor("TCP-Accept", String.valueOf(config.port()));
    }

    /**
     * Creates a TCP transport with shared metrics registry.
     *
     * @param config transport configuration
     * @param security security facade
     * @param protocolLayer protocol layer for encoding/decoding
     * @param messageLogger message logger for observability
     * @param metrics shared metrics registry for unified metrics aggregation
     */
    public TcpTransport(TransportConfig config, SecurityFacade security,
                       ProtocolLayer protocolLayer, MessageLogger messageLogger,
                       com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        super(config, security, protocolLayer, messageLogger, metrics);
        this.connections = new ConcurrentHashMap<>();
        this.connectionTrackers = new ConcurrentHashMap<>();
        this.acceptExecutor = ThreadPoolFactory.createNamedExecutor("TCP-Accept", String.valueOf(config.port()));
    }

    @Override
    public TransportType getType() {
        return TransportType.TCP;
    }

    @Override
    protected void doStart() throws Exception {
        log.info("TCP_TRANSPORT_START",
                "bindAddress", config.bindAddress(),
                "port", config.port());

        // Create server socket
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.bindAddress(), config.port()));

        InetSocketAddress localAddr = (InetSocketAddress) serverSocket.getLocalSocketAddress();

        log.info("TCP_TRANSPORT_BOUND",
                "localAddr", localAddr.getAddress().getHostAddress(),
                "localPort", localAddr.getPort(),
                "backlog", 50);

        // Start accept loop
        acceptExecutor.submit(this::acceptLoop);

        log.info("TCP_TRANSPORT_STARTED",
                "address", config.bindAddress(),
                "port", config.port(),
                "acceptLoopStarted", true);
    }

    @Override
    protected void doStop() throws Exception {
        int connectionCount = connections.size();

        log.info("TCP_TRANSPORT_STOP",
                "activeConnections", connectionCount);

        // Close all connections and untrack them
        connections.forEach((key, connection) -> {
            connection.close();
            ResourceTracker tracker = connectionTrackers.remove(key);
            ResourceLeakDetector.untrack(tracker);
        });
        connections.clear();

        log.info("TCP_CONNECTIONS_CLOSED",
                "closedConnections", connectionCount);

        // Close server socket
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
            log.info("TCP_SERVER_SOCKET_CLOSED",
                    "port", config.port());
        }

        // Shutdown executor
        acceptExecutor.shutdownNow();

        log.info("TCP_TRANSPORT_STOPPED",
                "port", config.port());
    }

    @Override
    protected void doSend(byte[] data, InetSocketAddress destination) throws Exception {
        // Defensive validation
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Cannot send null or empty data");
        }
        if (destination == null) {
            throw new IllegalArgumentException("Destination address cannot be null");
        }

        String key = destination.toString();

        // Get or create connection with defensive error handling
        TcpConnection connection = connections.computeIfAbsent(key, k -> {
            try {
                return connect(destination);
            } catch (Exception e) {
                // Log connection failure but don't crash - isolation
                log.debug("Failed to establish TCP connection (network issue), will retry",
                        "destination", destination.toString(),
                        "error", e.getClass().getSimpleName(),
                        "message", e.getMessage());
                metrics.incrementCounter("transport.tcp.connection.failed");
                return null;
            }
        });

        // If connection doesn't exist, fail fast without affecting other connections
        if (connection == null) {
            throw new IOException("No connection to " + destination);
        }

        // Attempt send with defensive error handling
        try {
            connection.send(data);
            metrics.incrementCounter("transport.tcp.send.success");
        } catch (IOException e) {
            // Connection broken - remove from pool and cleanup (isolation)
            log.debug("TCP connection broken during send, removing connection",
                    "destination", destination.toString(),
                    "error", e.getClass().getSimpleName());

            removeConnection(key, connection);
            metrics.incrementCounter("transport.tcp.connection.broken");

            // Re-throw for upper layers to handle
            throw e;
        }
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) serverSocket.getLocalSocketAddress();
    }

    // ========================= FIX #2: Connection Listener Management =========================

    /**
     * Sets the connection listener for security bootstrap events.
     *
     * FIX #2: Wire SecurityBootstrapManager to react to connection establishment.
     *
     * @param listener the listener to notify on connection events
     */
    public void setConnectionListener(TransportConnectionListener listener) {
        this.connectionListener = listener;
        log.info("Transport connection listener configured",
                "listenerClass", listener != null ? listener.getClass().getSimpleName() : "null");
    }

    // ========================= FIX #1: Explicit Connection Management =========================

    /**
     * Explicitly establishes a TCP connection without sending data.
     * Used by security bootstrap to pre-establish connection before KEY_EXCHANGE_INIT.
     *
     * This addresses FIX #1: Removes race condition where message is lost during
     * lazy connection creation. By pre-establishing the connection, we ensure
     * that the connection is stable and ready BEFORE attempting to send critical
     * security messages like KEY_EXCHANGE_INIT.
     *
     * @param destination the remote address to connect to
     * @return future that completes when connection is established
     */
    public CompletableFuture<Void> connectAsync(InetSocketAddress destination) {
        return CompletableFuture.runAsync(() -> {
            String key = destination.toString();

            if (connections.containsKey(key)) {
                log.debug("TCP connection already exists", "destination", destination);
                return;
            }

            try {
                TcpConnection conn = connect(destination);  // Call private connect method
                connections.put(key, conn);

                log.info("TCP_EXPLICIT_CONNECT_SUCCESS",
                        "destination", destination,
                        "connectionId", conn.getConnectionId(),
                        "activeConnections", connections.size());

                metrics.incrementCounter("transport.tcp.explicit_connect");
            } catch (Exception e) {
                log.error("Failed to establish TCP connection via explicit connect", e,
                        "destination", destination);
                metrics.incrementCounter("transport.tcp.explicit_connect_failed");
                throw new CompletionException(e);
            }
        }, acceptExecutor);
    }

    /**
     * Checks if a connection exists to the destination and is not closed.
     *
     * @param destination the remote address
     * @return true if connection exists and is active
     */
    public boolean isConnected(InetSocketAddress destination) {
        String key = destination.toString();
        TcpConnection conn = connections.get(key);
        return conn != null;  // Connection exists in map (assume active)
    }

    // ========================= Connection Management =========================

    /**
     * Accept loop for incoming connections.
     *
     * Designed for graceful degradation - continues accepting even if individual
     * connections fail, ensuring one bad connection doesn't kill the entire transport.
     */
    private void acceptLoop() {
        int consecutiveErrors = 0;
        final int MAX_CONSECUTIVE_ERRORS = 10;
        final int MAX_CONNECTIONS = 1000; // DoS protection

        while (isRunning() && !serverSocket.isClosed()) {
            try {
                // Defensive check: connection limit (DoS protection)
                if (connections.size() >= MAX_CONNECTIONS) {
                    log.warn("TCP connection limit reached, rejecting new connections",
                            "current", connections.size(),
                            "limit", MAX_CONNECTIONS);
                    metrics.incrementCounter("transport.tcp.connection.limit_reached");

                    // Sleep briefly to avoid tight loop
                    Thread.sleep(1000);
                    continue;
                }

                Socket socket = serverSocket.accept();

                // Defensive validation: null socket (should never happen but defensive)
                if (socket == null) {
                    log.debug("Accepted null socket (unexpected), ignoring");
                    metrics.incrementCounter("transport.tcp.accept.null_socket");
                    continue;
                }

                // Reset error counter on successful accept
                consecutiveErrors = 0;

                // Create connection with isolated error handling
                try {
                    InetSocketAddress localAddr = (InetSocketAddress) socket.getLocalSocketAddress();
                    InetSocketAddress remoteAddr = (InetSocketAddress) socket.getRemoteSocketAddress();

                    // Defensive validation: null addresses
                    if (remoteAddr == null) {
                        log.debug("Accepted connection with null remote address, closing");
                        socket.close();
                        metrics.incrementCounter("transport.tcp.accept.null_address");
                        continue;
                    }

                    // Extract peer ID from remote address (may be null initially)
                    String peerId = extractPeerId(remoteAddr);

                    // Create connection with state awareness and metrics support
                    // NOTE: Receive loop will wait for valid session before processing data
                    TcpConnection connection = new TcpConnection(
                            socket,
                            this::handleIncoming,
                            security,
                            peerId,
                            metrics,                    // Pass metrics for error classification
                            this::reportPeerReputation  // Callback for reputation changes
                    );

                    String key = remoteAddr.toString();
                    connections.put(key, connection);

                    // Track connection for leak detection
                    ResourceTracker tracker = ResourceLeakDetector.track(connection, "TcpConnection");
                    connectionTrackers.put(key, tracker);

                    // Log accepted connection
                    log.info("TCP_CONNECTION_ACCEPT",
                            "connectionId", connection.getConnectionId(),
                            "localAddr", localAddr.getAddress().getHostAddress(),
                            "localPort", localAddr.getPort(),
                            "remoteAddr", remoteAddr.getAddress().getHostAddress(),
                            "remotePort", remoteAddr.getPort(),
                            "peerId", peerId != null ? peerId : "unknown",
                            "activeConnections", connections.size());

                    // ═══════════════════════════════════════════════════════════════════════
                    // FIX #2: NOTIFY CONNECTION LISTENER (INBOUND)
                    // ═══════════════════════════════════════════════════════════════════════
                    if (connectionListener != null) {
                        try {
                            connectionListener.onConnectionEstablished(peerId, remoteAddr, true);
                            log.debug("Connection listener notified (inbound)",
                                    "peerId", peerId,
                                    "address", remoteAddr);
                        } catch (Exception e) {
                            log.error("Connection listener failed, closing connection", e,
                                    "peerId", peerId);
                            connection.close();
                            continue;
                        }
                    }

                    // Start receive loop (will wait for session to be ready internally)
                    connection.startReceiving();

                    metrics.incrementCounter("transport.tcp.connection.accepted");

                } catch (Exception e) {
                    // Connection setup failed - cleanup and continue (isolation)
                    log.debug("Failed to setup TCP connection, closing socket",
                            "error", e.getClass().getSimpleName(),
                            "message", e.getMessage());

                    try {
                        socket.close();
                    } catch (IOException closeError) {
                        // Ignore close errors
                    }

                    metrics.incrementCounter("transport.tcp.connection.setup_failed");
                    // Continue accepting - don't let one bad connection break the loop
                }

            } catch (SocketException e) {
                // Socket closed during shutdown - expected
                if (isRunning()) {
                    log.error("TCP_ACCEPT_SOCKET_ERROR",
                            "error", e.getMessage(),
                            "port", config.port());
                    metrics.incrementCounter("transport.tcp.accept.socket_error");
                }
                // Break on socket errors - can't continue accepting
                break;

            } catch (InterruptedException e) {
                // Thread interrupted - shutdown
                Thread.currentThread().interrupt();
                log.info("Accept loop interrupted, shutting down");
                break;

            } catch (Exception e) {
                consecutiveErrors++;

                // Downgrade to DEBUG for network noise, but track consecutive errors
                if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                    log.debug("TCP accept error (network noise), continuing",
                            "error", e.getClass().getSimpleName(),
                            "message", e.getMessage(),
                            "consecutiveErrors", consecutiveErrors);
                    metrics.incrementCounter("transport.tcp.accept.error");
                } else {
                    // Too many consecutive errors - log as error and break
                    log.error("Too many consecutive TCP accept errors, stopping accept loop",
                            "consecutiveErrors", consecutiveErrors,
                            "error", e.getClass().getSimpleName());
                    metrics.incrementCounter("transport.tcp.accept.fatal_error");
                    break;
                }
            }
        }

        log.info("TCP accept loop terminated",
                "reason", isRunning() ? "socket_closed" : "shutdown",
                "activeConnections", connections.size());
    }

    /**
     * Connects to a remote endpoint with defensive error handling.
     *
     * Ensures proper cleanup on failure to prevent resource leaks.
     */
    private TcpConnection connect(InetSocketAddress destination) throws Exception {
        Socket socket = null;
        TcpConnection connection = null;
        String key = destination.toString();

        try {
            long startTime = System.currentTimeMillis();

            log.info("TCP_CONNECTION_INITIATE",
                    "remoteAddr", destination.getAddress().getHostAddress(),
                    "remotePort", destination.getPort(),
                    "timeout", config.connectionTimeout());

            // Create and connect socket
            socket = SocketUtils.createConnectedSocket(
                destination.getAddress().getHostAddress(),
                destination.getPort(),
                config.connectionTimeout()
            );

            // Defensive check: verify socket is connected
            if (!socket.isConnected()) {
                throw new IOException("Socket not connected after createConnectedSocket");
            }

            // Configure socket for optimal P2P communication
            SocketUtils.configureSocket(socket);

            long handshakeTime = System.currentTimeMillis() - startTime;
            InetSocketAddress localAddr = (InetSocketAddress) socket.getLocalSocketAddress();
            InetSocketAddress remoteAddr = (InetSocketAddress) socket.getRemoteSocketAddress();

            // Defensive check: verify addresses
            if (localAddr == null || remoteAddr == null) {
                throw new IOException("Socket addresses are null after connection");
            }

            // Extract peer ID (may be null initially, will be set after handshake)
            String peerId = extractPeerId(destination);

            // Create connection wrapper with state awareness and metrics support
            // NOTE: Receive loop will wait for valid session before processing data
            connection = new TcpConnection(
                    socket,
                    this::handleIncoming,
                    security,
                    peerId,
                    metrics,                    // Pass metrics for error classification
                    this::reportPeerReputation  // Callback for reputation changes
            );

            // Track connection for leak detection
            ResourceTracker tracker = ResourceLeakDetector.track(connection, "TcpConnection");
            connectionTrackers.put(key, tracker);

            log.info("TCP_HANDSHAKE_COMPLETE",
                    "connectionId", connection.getConnectionId(),
                    "localAddr", localAddr.getAddress().getHostAddress() + ":" + localAddr.getPort(),
                    "remoteAddr", remoteAddr.getAddress().getHostAddress() + ":" + remoteAddr.getPort(),
                    "peerId", peerId != null ? peerId : "unknown",
                    "handshakeTime", handshakeTime);

            // ═══════════════════════════════════════════════════════════════════════════
            // FIX #2: NOTIFY CONNECTION LISTENER (OUTBOUND)
            // ═══════════════════════════════════════════════════════════════════════════
            if (connectionListener != null) {
                try {
                    connectionListener.onConnectionEstablished(peerId, remoteAddr, false);
                    log.debug("Connection listener notified (outbound)",
                            "peerId", peerId,
                            "address", remoteAddr);
                } catch (Exception e) {
                    log.error("Connection listener failed, aborting connection", e,
                            "peerId", peerId);
                    socket.close();
                    throw new SecurityException("Security bootstrap failed", e);
                }
            }

            // Start receive loop (will wait for session to be ready internally)
            connection.startReceiving();

            metrics.incrementCounter("transport.tcp.connection.established");

            return connection;

        } catch (Exception e) {
            // Connection failed - cleanup to prevent leaks (defensive)
            log.debug("TCP connection failed, cleaning up",
                    "destination", destination.toString(),
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage());

            // Close socket if created
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException closeError) {
                    // Ignore close errors
                }
            }

            // Remove tracker if added
            ResourceTracker tracker = connectionTrackers.remove(key);
            if (tracker != null) {
                ResourceLeakDetector.untrack(tracker);
            }

            metrics.incrementCounter("transport.tcp.connection.failed");

            // Re-throw original exception
            throw e;
        }
    }

    /**
     * Removes and cleans up a connection.
     *
     * Ensures proper cleanup and leak detection tracking.
     */
    private void removeConnection(String key, TcpConnection connection) {
        // Remove from connection map
        connections.remove(key);

        // Untrack from leak detector
        ResourceTracker tracker = connectionTrackers.remove(key);
        if (tracker != null) {
            ResourceLeakDetector.untrack(tracker);
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // FIX #2: NOTIFY CONNECTION LISTENER OF CLOSURE
        // ═══════════════════════════════════════════════════════════════════════════
        if (connectionListener != null) {
            try {
                // Note: peerId may be null if connection was not fully established
                // The listener implementation should handle null peerId gracefully
                connectionListener.onConnectionClosed(null, "CONNECTION_REMOVED");
            } catch (Exception e) {
                log.debug("Connection listener error during close", e);
            }
        }

        // Close connection
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                // Ignore close errors
                log.debug("Error closing connection during cleanup",
                        "key", key,
                        "error", e.getClass().getSimpleName());
            }
        }

        log.debug("TCP connection removed",
                "key", key,
                "remainingConnections", connections.size());

        metrics.incrementCounter("transport.tcp.connection.removed");
    }

    // ========================= Error Metrics Support =========================

    /**
     * Reports a peer reputation change.
     * Called by TcpConnection when peer misbehavior is detected.
     *
     * @param peerId the peer ID
     * @param delta the reputation change (negative for decrease)
     */
    private void reportPeerReputation(String peerId, int delta) {
        // Log the reputation change for observability
        log.debug("TCP_PEER_REPUTATION_CHANGE",
                "peerId", peerId,
                "delta", delta,
                "reason", delta < 0 ? "tcp_error" : "tcp_success");

        // Record metric
        if (delta < 0) {
            metrics.incrementCounter("transport.tcp.reputation.decreased");
        } else {
            metrics.incrementCounter("transport.tcp.reputation.increased");
        }

        // Note: Actual reputation system integration would go here
        // For now, we just record the metric and log
    }

    /**
     * Gets error metrics by classification.
     * Useful for monitoring and alerting on error patterns.
     *
     * @return map of classification name to error count
     */
    public java.util.Map<String, Long> getErrorMetrics() {
        java.util.Map<String, Long> errorMetrics = new java.util.HashMap<>();
        for (TcpErrorClassification classification : TcpErrorClassification.values()) {
            String metricName = "transport.tcp.error." + classification.getMetricSuffix();
            errorMetrics.put(classification.name(), metrics.getCounter(metricName));
        }
        return errorMetrics;
    }
}

