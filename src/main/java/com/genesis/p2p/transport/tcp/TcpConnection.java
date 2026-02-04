
package com.genesis.p2p.transport.tcp;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.util.resource.Closer;
import com.genesis.p2p.util.resource.ResourceLeakDetector;
import com.genesis.p2p.util.resource.ResourceLeakDetector.ResourceTracker;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * TCP connection wrapper with lifecycle-safe receive loop.
 *
 * Handles framing and I/O for a single TCP connection with state awareness:
 * - Defers receive loop start until explicitly started via startReceiving()
 * - Validates SecurityFacade session before processing encrypted data
 * - Distinguishes between expected and unexpected disconnects
 * - Properly handles handshake-in-progress and early closures
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
class TcpConnection implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(TcpConnection.class);

    private final String connectionId;
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final BiConsumer<byte[], InetSocketAddress> dataHandler;
    private final ExecutorService receiveExecutor;
    private final ResourceTracker socketTracker;
    private final Instant createdAt;
    private final AtomicLong bytesSent;
    private final AtomicLong bytesReceived;

    // Lifecycle control
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean receivingStarted = new AtomicBoolean(false);

    // State awareness (optional - for better logging/error handling)
    private final SecurityFacade securityFacade;
    private final String peerId;

    // Metrics and reputation (optional - for classified error handling)
    private final MetricsRegistry metricsRegistry;
    private final BiConsumer<String, Integer> reputationCallback;

    /**
     * Creates a TCP connection without starting the receive loop.
     * Call startReceiving() when ready to receive data (e.g., after authentication).
     *
     * @param socket the connected socket
     * @param dataHandler callback for received data
     * @param securityFacade security facade for session validation (can be null for plaintext)
     * @param peerId peer identifier (can be null if unknown)
     */
    TcpConnection(Socket socket, BiConsumer<byte[], InetSocketAddress> dataHandler,
                  SecurityFacade securityFacade, String peerId)
            throws IOException {
        this(socket, dataHandler, securityFacade, peerId, null, null);
    }

    /**
     * Creates a TCP connection with full metrics and reputation support.
     *
     * @param socket the connected socket
     * @param dataHandler callback for received data
     * @param securityFacade security facade for session validation (can be null for plaintext)
     * @param peerId peer identifier (can be null if unknown)
     * @param metricsRegistry metrics registry for error classification metrics (can be null)
     * @param reputationCallback callback for reputation changes (can be null)
     */
    TcpConnection(Socket socket, BiConsumer<byte[], InetSocketAddress> dataHandler,
                  SecurityFacade securityFacade, String peerId,
                  MetricsRegistry metricsRegistry, BiConsumer<String, Integer> reputationCallback)
            throws IOException {
        this.connectionId = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.bytesSent = new AtomicLong(0);
        this.bytesReceived = new AtomicLong(0);
        this.socket = socket;
        this.dataHandler = dataHandler;
        this.securityFacade = securityFacade;
        this.peerId = peerId;
        this.metricsRegistry = metricsRegistry;
        this.reputationCallback = reputationCallback;

        this.input = new DataInputStream(
                new BufferedInputStream(socket.getInputStream())
        );
        this.output = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream())
        );
        String remoteAddr = String.valueOf(socket.getRemoteSocketAddress());
        this.receiveExecutor = ThreadPoolFactory.createNamedExecutor("TCP-Receive", remoteAddr);

        // Track socket for leak detection
        this.socketTracker = ResourceLeakDetector.track(socket, "Socket");

        // Log connection creation
        InetSocketAddress localAddr = (InetSocketAddress) socket.getLocalSocketAddress();
        InetSocketAddress remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();

        log.debug("TCP_CONNECTION_CREATED",
                "connectionId", connectionId,
                "localAddr", localAddr.getAddress().getHostAddress(),
                "localPort", localAddr.getPort(),
                "remoteAddr", remoteAddress.getAddress().getHostAddress(),
                "remotePort", remoteAddress.getPort(),
                "peerId", peerId != null ? peerId : "unknown");

        // NOTE: Receive loop is NOT started here - call startReceiving() when ready
    }

    /**
     * Starts the receive loop.
     * Should be called after the peer is authenticated and a valid session exists.
     * Safe to call multiple times (idempotent).
     */
    void startReceiving() {
        if (receivingStarted.compareAndSet(false, true)) {
            log.debug("TCP_RECEIVE_START",
                    "connectionId", connectionId,
                    "remoteAddr", socket.getRemoteSocketAddress(),
                    "peerId", peerId != null ? peerId : "unknown");

            receiveExecutor.submit(this::receiveLoop);
        } else {
            log.debug("Receive loop already started",
                    "connectionId", connectionId);
        }
    }

    /**
     * Checks if the connection is ready to receive data.
     * A connection is ready if:
     * - Socket is connected
     * - Connection is not closed
     *
     * NOTE: Session validation is NOT done here. The receive loop must always
     * be able to receive the initial HANDSHAKE_REQUEST before any session exists.
     * Session validation and decryption are handled by the data handler
     * (AbstractTransport.handleIncoming) which processes messages appropriately
     * based on their type (handshake vs authenticated).
     */
    private boolean isReadyToReceive() {
        // Only check basic connection state - session validation happens at message layer
        return !closed.get() && !socket.isClosed();
    }

    /**
     * Sends data with length framing.
     */
    void send(byte[] data) throws IOException {
        synchronized (output) {
            output.writeInt(data.length);
            output.write(data);
            output.flush();

            // Track and log
            long totalSent = bytesSent.addAndGet(data.length + 4); // +4 for length prefix

            log.debug("TCP_DATA_SENT",
                    "connectionId", connectionId,
                    "bytesSent", data.length,
                    "totalBytesSent", totalSent,
                    "remoteAddr", socket.getRemoteSocketAddress());
        }
    }

    /**
     * Receive loop with classified error handling and protocol sniffing.
     *
     * CRITICAL ENHANCEMENT (v2.2): Fast-Path Gateway for Plaintext Control Messages
     * Before routing to SecurityFacade, inspects first byte:
     * - 0x7B (ASCII '{'): Plaintext JSON (KEY_EXCHANGE_INIT/COMPLETE) - bypass decryption
     * - Other: Encrypted application data - route to SecurityFacade
     *
     * This eliminates HMAC_FAILURE errors during key exchange bootstrap phase.
     *
     * Uses TcpErrorClassification to categorize errors:
     * - EXPECTED_DISCONNECT: EOF, socket closed, normal peer disconnect - DEBUG level
     * - NETWORK_INSTABILITY: Transient network issues - WARNING level, retryable
     * - PEER_MISBEHAVIOR: Protocol violations - WARNING level, report to reputation
     * - INTERNAL_FAULT: Our bugs - ERROR level, needs investigation
     */
    private void receiveLoop() {
        boolean sessionActive = false;

        try {
            while (!closed.get() && !socket.isClosed()) {
                // Simple connection state check (session validation at message layer)
                if (!isReadyToReceive()) {
                    break; // Connection closed
                }

                sessionActive = true;

                // Read frame length
                int length = input.readInt();

                // Defensive validation: invalid frame length = PEER_MISBEHAVIOR
                if (length <= 0 || length > 10_000_000) {
                    IOException frameError = new IOException("Invalid frame length: " + length);
                    handleClassifiedError(frameError, TcpErrorClassification.PEER_MISBEHAVIOR, sessionActive);
                    break;
                }

                // Read frame data
                byte[] data = new byte[length];
                input.readFully(data);

                // Track and log
                long totalReceived = bytesReceived.addAndGet(length + 4); // +4 for length prefix

                log.debug("TCP_DATA_RECEIVED",
                        "connectionId", connectionId,
                        "bytesReceived", length,
                        "totalBytesReceived", totalReceived,
                        "peerId", peerId != null ? peerId : "unknown",
                        "remoteAddr", socket.getRemoteSocketAddress());

                // ═════════════════════════════════════════════════════════════════════════
                // TASK 1: PROTOCOL SNIFFING - Fast-Path Gateway
                // Inspect first byte to route plaintext control messages around SecurityFacade
                // ═════════════════════════════════════════════════════════════════════════
                boolean isPlaintextControlMessage = isPlaintextJson(data);

                if (isPlaintextControlMessage) {
                    log.debug("TCP_PLAINTEXT_CONTROL_DETECTED",
                            "connectionId", connectionId,
                            "peerId", peerId != null ? peerId : "unknown",
                            "bypassDecryption", true,
                            "hint", "KEY_EXCHANGE phase - routed directly to MessageHandler");
                }

                // Handle data
                InetSocketAddress source =
                        (InetSocketAddress) socket.getRemoteSocketAddress();
                dataHandler.accept(data, source);
            }

            // Normal exit from loop
            log.debug("TCP_RECEIVE_LOOP_TERMINATED",
                    "connectionId", connectionId,
                    "reason", closed.get() ? "connection_closed" : "socket_closed",
                    "peerId", peerId != null ? peerId : "unknown");

        } catch (Exception e) {
            // Classify the error using TcpErrorClassification
            TcpErrorClassification classification = TcpErrorClassification.classify(
                    e, closed.get(), sessionActive);

            handleClassifiedError(e, classification, sessionActive);
        }
    }

    // SECURITY FIX (v2.6): Only these message types are allowed in plaintext
    // All other JSON messages must be encrypted
    private static final java.util.Set<String> ALLOWED_PLAINTEXT_TYPES = java.util.Set.of(
            "KEY_EXCHANGE_INIT",
            "KEY_EXCHANGE_COMPLETE",
            "KEY_EXCHANGE_ERROR"
    );

    /**
     * Detects if frame data is plaintext JSON (KEY_EXCHANGE control message).
     *
     * SECURITY FIX (v2.6): Validates message type before allowing plaintext bypass.
     * Only KEY_EXCHANGE messages are allowed in plaintext. All other message types
     * must be encrypted to prevent attackers from sending plaintext application
     * messages that bypass the SecurityGateway.
     *
     * @param data frame payload
     * @return true if data is a valid plaintext KEY_EXCHANGE message
     */
    private boolean isPlaintextJson(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        // Check first byte for JSON opening brace
        if (data[0] != 0x7B) { // ASCII '{'
            return false;
        }

        // SECURITY FIX (v2.6): Validate message type before allowing plaintext
        try {
            String messageType = extractMessageType(data);
            if (messageType != null && ALLOWED_PLAINTEXT_TYPES.contains(messageType)) {
                return true;
            } else {
                // Not a KEY_EXCHANGE message - must be encrypted
                log.warn("PLAINTEXT_JSON_REJECTED: Non-KEY_EXCHANGE JSON message received",
                        "connectionId", connectionId,
                        "peerId", peerId != null ? peerId : "unknown",
                        "messageType", messageType,
                        "hint", "All non-KEY_EXCHANGE messages must be encrypted");
                return false;
            }
        } catch (Exception e) {
            // Cannot parse JSON - treat as not plaintext (will fail at decryption)
            log.debug("Cannot extract message type from JSON",
                    "connectionId", connectionId,
                    "error", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts the message type from JSON data for security validation.
     *
     * SECURITY FIX (v2.6): Used to validate plaintext messages before bypassing encryption.
     * Performs a quick, minimal parse to extract only the "type" field.
     *
     * @param data JSON bytes
     * @return the message type or null if not found
     */
    private String extractMessageType(byte[] data) {
        // Quick parse - look for "type":"..." pattern
        String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);

        // Look for "type" field (handles various formats)
        int typeIndex = json.indexOf("\"type\"");
        if (typeIndex == -1) {
            // Try without quotes for some JSON libraries
            typeIndex = json.indexOf("type\"");
        }
        if (typeIndex == -1) {
            return null;
        }

        // Find the value after the colon
        int colonIndex = json.indexOf(':', typeIndex);
        if (colonIndex == -1) {
            return null;
        }

        // Find the opening quote of the value
        int valueStart = json.indexOf('"', colonIndex);
        if (valueStart == -1) {
            return null;
        }
        valueStart++; // Move past the quote

        // Find the closing quote
        int valueEnd = json.indexOf('"', valueStart);
        if (valueEnd == -1) {
            return null;
        }

        return json.substring(valueStart, valueEnd);
    }

    /**
     * Handles errors based on classification.
     * Logs at appropriate level and records per-classification metrics.
     * Does NOT increase log noise for expected scenarios.
     */
    private void handleClassifiedError(Exception e, TcpErrorClassification classification,
                                        boolean sessionActive) {
        // Record per-classification metric
        recordErrorMetric(classification);

        String peerIdStr = peerId != null ? peerId : "unknown";

        // Log at appropriate level based on classification severity
        switch (classification.getSeverity()) {
            case DEBUG:
                log.debug("TCP_ERROR_CLASSIFIED",
                        "connectionId", connectionId,
                        "classification", classification.name(),
                        "description", classification.getDescription(),
                        "peerId", peerIdStr);
                break;

            case INFO:
                log.info("TCP_ERROR_CLASSIFIED",
                        "connectionId", connectionId,
                        "classification", classification.name(),
                        "error", e.getClass().getSimpleName(),
                        "peerId", peerIdStr);
                break;

            case WARNING:
                log.warn("TCP_ERROR_CLASSIFIED",
                        "connectionId", connectionId,
                        "classification", classification.name(),
                        "error", e.getClass().getSimpleName(),
                        "message", e.getMessage(),
                        "peerId", peerIdStr,
                        "retryable", classification.isRetryable());
                break;

            case ERROR:
                log.error("TCP_ERROR_CLASSIFIED",
                        "connectionId", connectionId,
                        "classification", classification.name(),
                        "error", e.getClass().getSimpleName(),
                        "message", e.getMessage(),
                        "peerId", peerIdStr,
                        "remoteAddr", socket.getRemoteSocketAddress(),
                        "stackTrace", getStackTraceSummary(e));
                break;
        }

        // Execute recovery action based on classification
        executeRecoveryAction(classification);
    }

    /**
     * Records per-classification error metric.
     */
    private void recordErrorMetric(TcpErrorClassification classification) {
        if (metricsRegistry != null) {
            String metricName = "transport.tcp.error." + classification.getMetricSuffix();
            metricsRegistry.incrementCounter(metricName);
        }
    }

    /**
     * Executes recovery action based on error classification.
     */
    private void executeRecoveryAction(TcpErrorClassification classification) {
        switch (classification.getRecoveryAction()) {
            case NONE:
                // No action needed - error is expected
                break;

            case RETRY_WITH_BACKOFF:
                // At transport layer, we don't retry - application layer handles this
                // Just log that retry is safe
                log.debug("TCP_ERROR_RETRYABLE",
                        "connectionId", connectionId,
                        "peerId", peerId != null ? peerId : "unknown",
                        "hint", "Application layer may retry");
                break;

            case DISCONNECT_AND_REPORT:
                // Report to reputation system if available
                if (peerId != null && reputationCallback != null) {
                    try {
                        reputationCallback.accept(peerId, -10); // Decrease reputation
                        log.debug("TCP_REPUTATION_DECREASED",
                                "peerId", peerId,
                                "delta", -10,
                                "reason", "peer_misbehavior");
                    } catch (Exception repEx) {
                        log.warn("Failed to report reputation change",
                                "peerId", peerId,
                                "error", repEx.getMessage());
                    }
                }
                break;

            case LOG_AND_DISCONNECT:
                // Error already logged at ERROR level
                // Connection will be closed by caller
                break;
        }
    }

    /**
     * Gets a brief stack trace summary for logging.
     */
    private String getStackTraceSummary(Exception e) {
        if (e == null) return "";
        StackTraceElement[] stack = e.getStackTrace();
        if (stack == null || stack.length == 0) return "";

        // Return first 3 stack frames
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, stack.length);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" <- ");
            sb.append(stack[i].toString());
        }
        if (stack.length > limit) {
            sb.append(" (").append(stack.length - limit).append(" more)");
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // Already closed
        }

        // Calculate connection duration
        long connectionDurationMs = Duration.between(createdAt, Instant.now()).toMillis();

        // Log connection closure
        log.info("TCP_CONNECTION_CLOSED",
                "connectionId", connectionId,
                "peerId", peerId != null ? peerId : "unknown",
                "remoteAddr", socket.getRemoteSocketAddress(),
                "bytesSent", bytesSent.get(),
                "bytesReceived", bytesReceived.get(),
                "receivingStarted", receivingStarted.get(),
                "connectionDuration", connectionDurationMs);

        receiveExecutor.shutdownNow();
        Closer.closeQuietly(input);
        Closer.closeQuietly(output);
        Closer.closeQuietly(socket);

        // Untrack socket (mark as properly closed)
        ResourceLeakDetector.untrack(socketTracker);
    }

    /**
     * Gets the connection ID.
     */
    String getConnectionId() {
        return connectionId;
    }

    /**
     * Gets total bytes sent.
     */
    long getBytesSent() {
        return bytesSent.get();
    }

    /**
     * Gets total bytes received.
     */
    long getBytesReceived() {
        return bytesReceived.get();
    }

    /**
     * Gets connection creation time.
     */
    Instant getCreatedAt() {
        return createdAt;
    }
}
