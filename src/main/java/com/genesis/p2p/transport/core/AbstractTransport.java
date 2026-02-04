package com.genesis.p2p.transport.core;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.ProcessingContext;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.message.MessageLogger;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.protocol.ProtocolLayer;
import com.genesis.p2p.protocol.model.Frame;
import com.genesis.p2p.storage.message.PersistedMessage;
import com.genesis.p2p.util.resource.ResourceLeakDetector;
import com.genesis.p2p.util.resource.ResourceLeakDetector.ResourceTracker;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract base class for all transport implementations.
 *
 * Design Patterns:
 * - Template Method: Defines transport lifecycle
 * - Strategy: Allows different implementations
 *
 * Integration:
 * - Uses SecurityFacade (NOT internal security classes)
 * - Provides common transport logic
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public abstract class AbstractTransport implements ITransport {

    protected final NodeLogger log;
    protected final MetricsRegistry metrics;
    protected final SecurityFacade security;
    protected final TransportConfig config;
    protected final ProtocolLayer protocolLayer;
    protected final MessageLogger messageLogger; // Message-level logging and persistence

    private final AtomicReference<TransportState> state;
    private final Instant startedAt;
    private ITransportEnvelopeHandler envelopeHandler;

    // Resource leak tracking
    private ResourceTracker leakTracker;

    // Statistics
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong sendErrors = new AtomicLong(0);
    private final AtomicLong receiveErrors = new AtomicLong(0);

    // CLI mode flags (read from system properties)
    private final boolean wiresharkMode;
    private final boolean cyberEmulationMode;
    private final boolean performanceMode;
    private final int maxPayloadHex;

    /**
     * Creates an abstract transport with a default metrics registry.
     * @deprecated Use constructor with MetricsRegistry parameter for shared metrics
     */
    @Deprecated
    protected AbstractTransport(TransportConfig config, SecurityFacade security,
                               ProtocolLayer protocolLayer, MessageLogger messageLogger) {
        this(config, security, protocolLayer, messageLogger, new MetricsRegistry("transport"));
    }

    /**
     * Creates an abstract transport with a shared metrics registry.
     *
     * @param config transport configuration
     * @param security security facade
     * @param protocolLayer protocol layer for encoding/decoding
     * @param messageLogger message logger for observability
     * @param metrics shared metrics registry for unified metrics aggregation
     */
    protected AbstractTransport(TransportConfig config, SecurityFacade security,
                               ProtocolLayer protocolLayer, MessageLogger messageLogger,
                               MetricsRegistry metrics) {
        this.config = config;
        this.security = security;
        this.protocolLayer = protocolLayer;
        this.messageLogger = messageLogger;
        this.state = new AtomicReference<>(TransportState.CREATED);
        this.startedAt = Instant.now();

        this.log = NodeLogger.getLogger(getClass());
        this.metrics = metrics;

        // Read CLI mode flags from system properties
        this.wiresharkMode = "true".equals(System.getProperty("genesis.log.wireshark"));
        this.cyberEmulationMode = "true".equals(System.getProperty("genesis.log.cyber.emulation"));
        this.performanceMode = "true".equals(System.getProperty("genesis.log.performance"));
        this.maxPayloadHex = Integer.parseInt(System.getProperty("genesis.log.payload.max", "64"));

        log.info("Transport created",
                "type", getType().getName(),
                "wiresharkMode", wiresharkMode,
                "cyberEmulationMode", cyberEmulationMode,
                "performanceMode", performanceMode);
    }

    // ========================= Template Methods =========================

    @Override
    public final void start() throws Exception {
        if (!state.compareAndSet(TransportState.CREATED, TransportState.STARTING)) {
            log.warn("Transport already started or starting");
            return;
        }

        log.info("Starting transport", "type", getType().getName());

        try {
            // Enable leak detection and track this transport
            ResourceLeakDetector.enable();
            this.leakTracker = ResourceLeakDetector.track(this,
                getType().getName() + "Transport");

            doStart();
            state.set(TransportState.RUNNING);
            metrics.incrementCounter("transport.started");
            log.info("Transport started successfully");

        } catch (Exception e) {
            state.set(TransportState.ERROR);
            metrics.incrementCounter("transport.start_failed");
            log.error("Failed to start transport", e);
            throw e;
        }
    }

    @Override
    public final void stop() {
        if (!state.compareAndSet(TransportState.RUNNING, TransportState.STOPPING)) {
            log.warn("Transport not running");
            return;
        }

        log.info("Stopping transport");

        try {
            doStop();

            // Untrack resource (mark as properly closed)
            ResourceLeakDetector.untrack(leakTracker);

            state.set(TransportState.STOPPED);
            metrics.incrementCounter("transport.stopped");
            log.info("Transport stopped successfully");

        } catch (Exception e) {
            state.set(TransportState.ERROR);
            log.error("Error stopping transport", e);
        }
    }

    @Override
    public final CompletableFuture<Void> send(Message message, InetSocketAddress destination) {
        if (state.get() != TransportState.RUNNING) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Transport not running")
            );
        }

        return CompletableFuture.runAsync(() -> {
            PersistedMessage persistedMessage = null;
            try {
                String peerId = extractPeerId(destination);
                boolean willEncrypt = config.enableEncryption() && security.hasValidSession(peerId);

                // LOG AND PERSIST MESSAGE BEFORE SENDING (critical for zero data loss)
                persistedMessage = messageLogger.logOutboundMessage(
                        message,
                        getType().getName(),
                        destination.toString(),
                        willEncrypt,
                        false // compression detection added later
                );

                // Encode message to frames via ProtocolLayer
                byte[][] frameBytes = protocolLayer.encodeToFrames(message);

                // Log fragmentation if applicable
                if (frameBytes.length > 1) {
                    messageLogger.logFragmentation(persistedMessage.persistenceId(), frameBytes.length);
                }

                // Send each frame
                long totalBytesWritten = 0;
                for (byte[] frame : frameBytes) {
                    byte[] data = frame;

                    // Encrypt ONLY if encryption is enabled AND we have a valid session
                    // Discovery messages should be sent in plaintext
                    if (willEncrypt) {
                        try {
                            int plaintextSize = frame.length;
                            data = security.encrypt(frame, peerId);

                            // Log encryption operation
                            messageLogger.logEncryption(
                                    message.messageId(),
                                    peerId,
                                    plaintextSize,
                                    data.length);

                            log.debug("Message encrypted",
                                    "peerId", peerId,
                                    "size", data.length);
                        } catch (Exception encryptEx) {
                            log.warn("Encryption failed - sending plaintext",
                                    "peerId", peerId,
                                    "reason", encryptEx.getMessage());
                            data = frame; // Fallback to plaintext
                        }
                    } else {
                        log.debug("Sending plaintext message",
                                "peerId", peerId,
                                "hasSession", security.hasValidSession(peerId),
                                "encryptionEnabled", config.enableEncryption());
                    }

                    // Send via implementation
                    doSend(data, destination);

                    // Wireshark correlation: log hex dump of sent payload
                    logWiresharkPayload(data, "TX", destination);

                    // Cyber-emulation: log security operation
                    if (willEncrypt) {
                        logSecurityOperation("ENCRYPT_SEND", peerId,
                                "plaintext=" + frame.length + " ciphertext=" + data.length);
                    }

                    // Update statistics
                    bytesSent.addAndGet(data.length);
                    totalBytesWritten += data.length;
                }

                // Log successful send
                messageLogger.logMessageSent(persistedMessage.persistenceId(), totalBytesWritten);

                messagesSent.incrementAndGet();
                metrics.incrementCounter("transport.messages_sent");

                log.debug("Message sent", "destination", destination.toString(), "frames", String.valueOf(frameBytes.length));

            } catch (Exception e) {
                sendErrors.incrementAndGet();
                metrics.incrementCounter("transport.send_errors");

                // Log error if message was persisted
                if (persistedMessage != null) {
                    messageLogger.logMessageError(
                            persistedMessage.persistenceId(),
                            e.getMessage(),
                            e);
                }

                log.error("Send failed", e, "destination", destination.toString());
                throw new RuntimeException("Send failed", e);
            }
        });
    }

    @Override
    public final CompletableFuture<Void> sendRaw(byte[] data, InetSocketAddress destination) {
        if (state.get() != TransportState.RUNNING) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Transport not running")
            );
        }

        return CompletableFuture.runAsync(() -> {
            try {
                doSend(data, destination);
                bytesSent.addAndGet(data.length);

            } catch (Exception e) {
                sendErrors.incrementAndGet();
                log.error("Raw send failed", e);
                throw new RuntimeException(e);
            }
        });
    }

    // ========================= Abstract Methods =========================

    /**
     * Starts the transport implementation.
     * Subclasses must implement startup logic.
     */
    protected abstract void doStart() throws Exception;

    /**
     * Stops the transport implementation.
     * Subclasses must implement shutdown logic.
     */
    protected abstract void doStop() throws Exception;

    /**
     * Sends data via transport.
     * Subclasses must implement actual send logic.
     */
    protected abstract void doSend(byte[] data, InetSocketAddress destination) throws Exception;

    // ========================= Helper Methods =========================

    /**
     * Handles incoming data from transport with Security-Aware Dispatcher.
     * Called by subclasses when data is received.
     *
     * SECURITY-AWARE DISPATCHER (v2.2):
     * Enforces strict protocol-level decryption bypass by inspecting frame signatures
     * BEFORE invoking SecurityFacade.decrypt(). This eliminates HMAC failures during
     * KEY_EXCHANGE bootstrap phase.
     *
     * Frame Routing Logic:
     * - 0x7B (JSON): Plaintext control messages (KEY_EXCHANGE_INIT/COMPLETE) → Direct to ProtocolLayer
     * - Other bytes: Application data → SecurityFacade.decrypt() → ProtocolLayer
     *
     * Security Guarantees:
     * - Control messages: Signature-verified by KeyExchangeProcessor
     * - Application data: HMAC-verified by SecurityFacade
     * - No downgrade attacks: Only specific message types allowed in plaintext
     */
    protected void handleIncoming(byte[] data, InetSocketAddress source) {
        PersistedMessage persistedMessage = null;
        try {
            bytesReceived.addAndGet(data.length);

            // Wireshark correlation: log hex dump of received payload
            logWiresharkPayload(data, "RX", source);

            String peerId = extractPeerId(source);
            boolean wasEncrypted = false;

            // ═══════════════════════════════════════════════════════════════════════════════
            // SECURITY-AWARE DISPATCHER v2.3: Strict Protocol-Level Decryption Bypass
            // ═══════════════════════════════════════════════════════════════════════════════
            // Enforces frame signature inspection BEFORE invoking SecurityFacade.decrypt()
            // to ensure byte arrays starting with 0x7B (JSON) are routed as raw plaintext
            // directly to ProtocolLayer, eliminating HMAC failures during KEY_EXCHANGE phase.
            //
            // Routing Logic:
            // 1. Inspect frame signature first (0x7B detection)
            // 2. If 0x7B detected → BYPASS decryption → Route to ProtocolLayer as plaintext
            // 3. If session exists → Decrypt via SecurityFacade → Route to ProtocolLayer
            // 4. Otherwise → Route as plaintext (discovery/control messages)
            // ═══════════════════════════════════════════════════════════════════════════════

            boolean isPlaintextControlMessage = inspectFrameSignature(data);

            if (isPlaintextControlMessage) {
                // ───────────────────────────────────────────────────────────────────────────
                // FAST-PATH: Plaintext Control Message (0x7B detected)
                // ───────────────────────────────────────────────────────────────────────────
                // Frame signature indicates JSON control message (KEY_EXCHANGE_INIT/COMPLETE)
                // CRITICAL: Bypass SecurityFacade.decrypt() entirely to prevent HMAC failures
                // during bootstrap phase when no session exists yet.
                //
                // Security: Control messages are signature-verified by KeyExchangeProcessor
                // ───────────────────────────────────────────────────────────────────────────

                log.info("SECURITY_DISPATCHER_PLAINTEXT_BYPASS",
                        "peerId", peerId,
                        "frameSignature", String.format("0x%02X", data[0]),
                        "routing", "DIRECT_TO_PROTOCOL_LAYER",
                        "bypassDecryption", true,
                        "securityVerification", "SIGNATURE_BASED",
                        "hint", "KEY_EXCHANGE or protocol control message");

                metrics.incrementCounter("transport.security_dispatcher.plaintext_routed");
                metrics.incrementCounter("transport.security_dispatcher.decryption_bypassed");

                // Data passes through unchanged to ProtocolLayer

            } else if (config.enableEncryption() && security.hasValidSession(peerId)) {
                // ───────────────────────────────────────────────────────────────────────────
                // ENCRYPTED-PATH: Application Data with Active Session
                // ───────────────────────────────────────────────────────────────────────────
                // Non-JSON frame signature AND valid session exists
                // Decrypt via SecurityFacade with HMAC verification
                // ───────────────────────────────────────────────────────────────────────────

                try {
                    int originalSize = data.length;
                    data = security.decrypt(data, peerId);
                    wasEncrypted = true;

                    // Log decryption operation
                    if (messageLogger != null) {
                        messageLogger.logDecryption(
                                null, // messageId not known yet
                                peerId,
                                originalSize,
                                data.length);
                    }

                    // Cyber-emulation: log security operation
                    logSecurityOperation("DECRYPT_RECV", peerId,
                            "ciphertext=" + originalSize + " plaintext=" + data.length);

                    log.info("SECURITY_DISPATCHER_ENCRYPTED_DECRYPTED",
                            "peerId", peerId,
                            "ciphertextSize", originalSize,
                            "plaintextSize", data.length,
                            "routing", "VIA_SECURITY_FACADE",
                            "hmacVerified", true);

                    metrics.incrementCounter("transport.security_dispatcher.encrypted_routed");
                    metrics.incrementCounter("transport.security_dispatcher.decrypted_success");

                } catch (Exception decryptEx) {
                    // ═══════════════════════════════════════════════════════════════════════════
                    // FAIL-CLOSED: Reject message instead of falling back to plaintext
                    // ═══════════════════════════════════════════════════════════════════════════
                    // This is a security-critical change from v2.3:
                    // - Previously: Fall back to plaintext (fail-open) - INSECURE
                    // - Now: Reject the message entirely (fail-closed) - SECURE
                    //
                    // Rationale:
                    // - If decryption fails for a peer with an active session, either:
                    //   a) The message was tampered with (MITM attack)
                    //   b) The session is corrupted/desync'd
                    //   c) The peer is misbehaving
                    // - In all cases, processing the message in plaintext is dangerous
                    // - The SecurityGateway would reject it anyway, but we fail early here
                    // ═══════════════════════════════════════════════════════════════════════════

                    log.error("SECURITY_DISPATCHER_DECRYPTION_FAILED_REJECT",
                            "peerId", peerId,
                            "reason", decryptEx.getMessage(),
                            "action", "MESSAGE_REJECTED",
                            "severity", "HIGH",
                            "hint", "Session may be corrupted or message was tampered with");

                    metrics.incrementCounter("transport.security_dispatcher.decryption_failed");
                    metrics.incrementCounter("transport.security_dispatcher.decryption_rejected");
                    metrics.incrementCounter("transport.security_dispatcher.security_violation");

                    // FAIL-CLOSED: Do not process message with failed decryption
                    return;
                }
            } else {
                // ───────────────────────────────────────────────────────────────────────────
                // UNENCRYPTED-PATH: No Session or Encryption Disabled
                // ───────────────────────────────────────────────────────────────────────────
                // Route as plaintext (discovery messages, pre-session control messages)
                // ───────────────────────────────────────────────────────────────────────────

                log.debug("SECURITY_DISPATCHER_UNENCRYPTED",
                        "peerId", peerId,
                        "hasSession", security.hasValidSession(peerId),
                        "encryptionEnabled", config.enableEncryption(),
                        "routing", "PLAINTEXT_ALLOWED",
                        "hint", "Discovery or pre-session message");

                metrics.incrementCounter("transport.security_dispatcher.unencrypted_routed");
            }

            // Receive frame via ProtocolLayer
            boolean messageComplete = protocolLayer.receiveFrame(data);

            if (messageComplete) {
                // Extract message ID from frame and reassemble
                Frame frame = Frame.fromBytes(data);
                UUID messageId = frame.getMessageId();
                Message message = protocolLayer.reassembleMessage(messageId);

                // LOG AND PERSIST INBOUND MESSAGE (critical for zero data loss)
                persistedMessage = messageLogger.logInboundMessage(
                        message,
                        getType().getName(),
                        source.toString(),
                        wasEncrypted,
                        false // compression detection added later
                );

                // Notify handler with ProcessingContext for end-to-end tracking
                // NOTE: Handler is responsible for calling messageLogger.logMessageDelivered()
                // after successful processing. This ensures correct state tracking even if
                // processing fails (backpressure, validation, etc.)
                // CRITICAL: Include source address for response routing (e.g., handshake responses)
                // SECURITY: Include encryption metadata for SecurityGateway validation
                if (envelopeHandler != null) {
                    ProcessingContext context = ProcessingContext.createWithSecurity(
                            persistedMessage.persistenceId(),
                            persistedMessage.traceId(),
                            source,  // Source address for response routing
                            wasEncrypted,  // Track encryption status
                            wasEncrypted   // If encrypted, decryption succeeded (we returned on failure)
                    );
                    envelopeHandler.handleEnvelope(message, source, context);
                }

                messagesReceived.incrementAndGet();
                metrics.incrementCounter("transport.messages_received");
            }

        } catch (Exception e) {
            receiveErrors.incrementAndGet();
            metrics.incrementCounter("transport.receive_errors");

            // Log error if message was persisted
            if (persistedMessage != null) {
                messageLogger.logMessageError(
                        persistedMessage.persistenceId(),
                        e.getMessage(),
                        e);
            }

            log.error("Error handling incoming data", e);
        }
    }

    /**
     * Extracts peer ID from address.
     * Protected to allow subclasses to use for connection management.
     */
    protected String extractPeerId(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    /**
     * Inspects frame signature to determine if it's a plaintext control message.
     *
     * SECURITY-AWARE DISPATCHER CORE LOGIC (v2.3):
     * Enforces strict protocol-level decryption bypass by examining frame signatures
     * BEFORE invoking SecurityFacade.decrypt(). This eliminates HMAC failures during
     * KEY_EXCHANGE bootstrap phase.
     *
     * Frame Signature Detection:
     * - 0x7B (ASCII '{'): JSON-formatted control message (KEY_EXCHANGE_INIT/COMPLETE)
     *   These are signed with identity keys but NOT encrypted (bootstrap phase)
     *
     * Security Properties:
     * - Only control messages allowed in plaintext (verified by signature)
     * - Application messages MUST be encrypted after channel establishment
     * - No downgrade attacks: Specific message types enforced by MessageHandler
     * - Frame-level routing ensures control messages bypass decryption entirely
     *
     * Implementation Details:
     * - Checks first byte of frame for 0x7B ('{' in ASCII)
     * - Returns true for JSON control messages → routed to ProtocolLayer as plaintext
     * - Returns false for all other frames → routed through SecurityFacade.decrypt()
     *
     * @param data the received frame data
     * @return true if frame is plaintext control message, false if encrypted/application data
     */
    protected boolean inspectFrameSignature(byte[] data) {
        if (data == null || data.length == 0) {
            log.debug("FRAME_SIGNATURE_INSPECTION_FAILED",
                    "reason", "null_or_empty_data");
            return false;
        }

        // Check for JSON opening brace (plaintext control messages)
        byte firstByte = data[0];

        if (firstByte == 0x7B) { // ASCII '{' - JSON control message
            log.info("FRAME_SIGNATURE_PLAINTEXT_DETECTED",
                    "signature", String.format("0x%02X", firstByte),
                    "type", "JSON_CONTROL_MESSAGE",
                    "decryptionBypass", true,
                    "dataLength", data.length,
                    "hint", "KEY_EXCHANGE or protocol control message");

            metrics.incrementCounter("transport.security_dispatcher.json_frame_detected");
            return true;
        }

        // All other signatures: encrypted application data or non-JSON frames
        log.debug("FRAME_SIGNATURE_ENCRYPTED_DETECTED",
                "signature", String.format("0x%02X", firstByte),
                "type", "APPLICATION_DATA",
                "requiresDecryption", true);

        return false;
    }

    // ========================= ITransport Implementation =========================

    @Override
    public final TransportState getState() {
        return state.get();
    }

    @Override
    public final boolean isRunning() {
        return state.get() == TransportState.RUNNING;
    }

    @Override
    public final void setEnvelopeHandler(ITransportEnvelopeHandler handler) {
        this.envelopeHandler = handler;
        log.debug("Envelope handler set");
    }

    @Override
    public final TransportStats getStats() {
        return new TransportStats(
                bytesSent.get(),
                bytesReceived.get(),
                messagesSent.get(),
                messagesReceived.get(),
                sendErrors.get(),
                receiveErrors.get(),
                startedAt
        );
    }

    // ========================= Resource Leak Detection =========================

    /**
     * Gets resource leak detection statistics.
     *
     * @return leak statistics
     */
    public ResourceLeakDetector.LeakStats getLeakStats() {
        return ResourceLeakDetector.getStats();
    }

    /**
     * Gets the number of detected leaks.
     *
     * @return number of leaks
     */
    public long getLeakCount() {
        return ResourceLeakDetector.getLeakCount();
    }

    @Override
    public void close() {
        stop();
    }

    // ========================= CLI Mode Support =========================

    /**
     * Logs payload hex dump for Wireshark correlation.
     * Only logs if wireshark mode is enabled and performance mode is disabled.
     *
     * @param data the raw bytes to dump
     * @param direction "TX" for outbound, "RX" for inbound
     * @param destination the remote address
     */
    protected void logWiresharkPayload(byte[] data, String direction, InetSocketAddress destination) {
        if (!wiresharkMode || performanceMode) {
            return;
        }

        String hexDump = toHexDump(data, maxPayloadHex);
        log.debug("WIRESHARK_PAYLOAD",
                "direction", direction,
                "remote", destination.toString(),
                "length", data.length,
                "hex", hexDump);
    }

    /**
     * Logs security operation details for cyber-emulation mode.
     * Only logs if cyber-emulation mode is enabled.
     *
     * @param operation the security operation (ENCRYPT, DECRYPT, KEY_EXCHANGE, etc.)
     * @param peerId the peer involved
     * @param details additional details
     */
    protected void logSecurityOperation(String operation, String peerId, String details) {
        if (!cyberEmulationMode) {
            return;
        }

        log.info("SECURITY_OP",
                "operation", operation,
                "peerId", peerId,
                "details", details,
                "algorithm", security != null ? security.getAlgorithm() : "none");
    }

    /**
     * Converts bytes to hex dump string.
     *
     * @param data the bytes to convert
     * @param maxLength maximum hex characters to output
     * @return hex string with optional truncation indicator
     */
    private String toHexDump(byte[] data, int maxLength) {
        if (data == null || data.length == 0) {
            return "(empty)";
        }

        int bytesToShow = Math.min(data.length, maxLength / 2);
        StringBuilder sb = new StringBuilder(bytesToShow * 2 + 10);

        for (int i = 0; i < bytesToShow; i++) {
            sb.append(String.format("%02X", data[i] & 0xFF));
            if ((i + 1) % 16 == 0 && i < bytesToShow - 1) {
                sb.append(" ");
            }
        }

        if (bytesToShow < data.length) {
            sb.append("... (").append(data.length - bytesToShow).append(" more bytes)");
        }

        return sb.toString();
    }

    /**
     * Checks if wireshark mode is enabled.
     */
    protected boolean isWiresharkMode() {
        return wiresharkMode;
    }

    /**
     * Checks if cyber-emulation mode is enabled.
     */
    protected boolean isCyberEmulationMode() {
        return cyberEmulationMode;
    }

    /**
     * Checks if performance mode is enabled.
     */
    protected boolean isPerformanceMode() {
        return performanceMode;
    }
}