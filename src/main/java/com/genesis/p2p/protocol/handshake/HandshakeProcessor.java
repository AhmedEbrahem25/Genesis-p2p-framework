package com.genesis.p2p.protocol.handshake;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.handlers.ProcessingContext;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.message.MessageLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.util.common.Time;
import com.google.gson.Gson;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Processes handshake requests and responses.
 *
 * Handles the handshake protocol:
 * 1. Receive HandshakeRequest
 * 2. Validate request
 * 3. Generate HandshakeResponse
 * 4. Establish security session (ECDH key exchange)
 * 5. Send response back to requester
 * 6. Register peer connection
 *
 * Features:
 * - Protocol version negotiation
 * - ECDH key exchange (secp256r1)
 * - Authentication via challenge-response
 * - Capability exchange
 * - Rate limiting
 * - Bidirectional handshake deduplication
 * - Full observability via HandshakeMetrics and HandshakeTimeline
 * - Configurable via HandshakeConfig
 *
 * @author Genesis P2P Framework
 * @version 2.2
 */
public class HandshakeProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(HandshakeProcessor.class);
    private static final Gson gson = new Gson();

    private final String nodeId;
    private final PeerManager peerManager;
    private final SecurityFacade security;
    private final HandshakeValidator validator;
    private final MetricsRegistry metrics;
    private final MessageLogger messageLogger; // Full observability for handshake messages

    // Enhanced observability (optional, null if disabled)
    private volatile HandshakeConfig config;
    private volatile HandshakeMetrics handshakeMetrics;
    private volatile HandshakeTimeline handshakeTimeline;

    // Message sender for sending responses back (must be set via setMessageSender)
    private volatile MessageSender messageSender;

    // Track source addresses for response routing (peerId -> source address)
    private final Map<String, java.net.InetSocketAddress> peerSourceAddresses = new ConcurrentHashMap<>();

    // Rate limiting: track handshake attempts per peer
    private final Map<String, Long> handshakeAttempts;
    private static final long RATE_LIMIT_WINDOW = TimeUnit.MINUTES.toMillis(1);
    private static final int MAX_ATTEMPTS_PER_WINDOW = 10;

    // Pending handshakes: track outgoing handshake requests
    private final Map<String, HandshakeRequest> pendingHandshakes;
    private static final long PENDING_TIMEOUT = TimeUnit.SECONDS.toMillis(30);

    // Bidirectional deduplication: track peers we've already established sessions with
    private final Map<String, Long> establishedSessions;

    // Track handshake start times for duration metrics
    private final Map<String, Long> handshakeStartTimes = new ConcurrentHashMap<>();

    /**
     * Creates a handshake processor with full observability support.
     *
     * @param nodeId this node's ID
     * @param peerManager peer management service
     * @param security security facade for key exchange
     * @param metrics general metrics registry
     * @param messageLogger message logger for persistence tracking
     * @param config handshake configuration (optional, uses defaults if null)
     * @param handshakeMetrics Prometheus-style metrics (optional)
     * @param handshakeTimeline visual timeline (optional)
     */
    public HandshakeProcessor(String nodeId,
                             PeerManager peerManager,
                             SecurityFacade security,
                             MetricsRegistry metrics,
                             MessageLogger messageLogger,
                             HandshakeConfig config,
                             HandshakeMetrics handshakeMetrics,
                             HandshakeTimeline handshakeTimeline) {
        this.nodeId = nodeId;
        this.peerManager = peerManager;
        this.security = security;
        this.validator = new HandshakeValidator();
        this.metrics = metrics;
        this.messageLogger = messageLogger;
        this.config = config != null ? config : HandshakeConfig.defaults();
        this.handshakeMetrics = handshakeMetrics;
        this.handshakeTimeline = handshakeTimeline;
        this.handshakeAttempts = new ConcurrentHashMap<>();
        this.pendingHandshakes = new ConcurrentHashMap<>();
        this.establishedSessions = new ConcurrentHashMap<>();

        log.info("HandshakeProcessor initialized with full observability",
                "nodeId", nodeId,
                "metricsEnabled", handshakeMetrics != null,
                "timelineEnabled", handshakeTimeline != null,
                "config", this.config);
    }

    /**
     * Creates a handshake processor with message logging.
     */
    public HandshakeProcessor(String nodeId,
                             PeerManager peerManager,
                             SecurityFacade security,
                             MetricsRegistry metrics,
                             MessageLogger messageLogger) {
        this(nodeId, peerManager, security, metrics, messageLogger, null, null, null);
    }

    /**
     * Backward compatibility constructor (deprecated).
     */
    @Deprecated
    public HandshakeProcessor(String nodeId,
                             PeerManager peerManager,
                             SecurityFacade security,
                             MetricsRegistry metrics) {
        this(nodeId, peerManager, security, metrics, null, null, null, null);
        log.warn("HandshakeProcessor created without MessageLogger - logging disabled");
    }

    /**
     * Sets the message sender for sending responses.
     * CRITICAL: This must be called before processing any messages.
     *
     * @param messageSender the message sender implementation
     */
    public void setMessageSender(MessageSender messageSender) {
        this.messageSender = messageSender;
        log.info("HandshakeProcessor message sender configured");
    }

    /**
     * Functional interface for sending messages back to peers.
     * The sender must route the message to the specified target address.
     */
    @FunctionalInterface
    public interface MessageSender {
        /**
         * Send a message to a specific address.
         *
         * @param message the message to send
         * @param targetAddress the target address to send to
         */
        void sendMessage(Message message, java.net.InetSocketAddress targetAddress);
    }

    /**
     * Indicates that this processor uses the ProcessingContext for response routing.
     */
    @Override
    public boolean supportsContext() {
        return true;
    }

    /**
     * Processes handshake messages with context (preferred method).
     * The context provides the source address for response routing.
     */
    @Override
    public void processMessage(Message message, ProcessingContext context) {
        String messageType = message.type();
        String senderId = message.from();

        // TRACE: Entry point logging
        log.info("HANDSHAKE_PROCESS_START",
                "messageType", messageType,
                "senderId", senderId,
                "messageId", message.messageId(),
                "hasContext", context != null,
                "hasSourceAddress", context != null && context.hasSourceAddress(),
                "messageSenderConfigured", messageSender != null);

        // Store source address for response routing
        if (context != null && context.hasSourceAddress()) {
            peerSourceAddresses.put(senderId, context.sourceAddress());
            log.info("HANDSHAKE_SOURCE_ADDRESS_STORED",
                    "peerId", senderId,
                    "address", context.sourceAddress(),
                    "totalStoredAddresses", peerSourceAddresses.size());
        } else {
            log.warn("HANDSHAKE_NO_SOURCE_ADDRESS",
                    "peerId", senderId,
                    "contextNull", context == null,
                    "hasSourceAddress", context != null && context.hasSourceAddress());
        }

        log.debug("Processing handshake message", "type", messageType, "from", senderId);

        try {
            if ("HANDSHAKE_REQUEST".equals(messageType)) {
                log.info("HANDSHAKE_REQUEST_RECEIVED",
                        "senderId", senderId,
                        "messageId", message.messageId());

                // Log handshake message for full observability
                logHandshakeMessage(message, "REQUEST");

                // Deserialize handshake request from message body
                String content = message.body().content();
                log.debug("HANDSHAKE_REQUEST_CONTENT", "contentLength", content.length());

                HandshakeRequest request = gson.fromJson(content, HandshakeRequest.class);
                log.info("HANDSHAKE_REQUEST_PARSED",
                        "nodeId", request.getNodeId(),
                        "protocolVersion", request.getProtocolVersion());

                // Process the request and get response
                HandshakeResponse response = processRequest(request);

                // TRACE: Log response generation result
                log.info("HANDSHAKE_RESPONSE_GENERATED",
                        "senderId", senderId,
                        "responseNull", response == null,
                        "responseStatus", response != null ? response.getStatus() : "null");

                // Get target address for sending response/rejection
                java.net.InetSocketAddress targetAddress = peerSourceAddresses.get(senderId);
                String correlationId = message.header().messageId();

                // CRITICAL: Handle responses based on status
                if (response == null) {
                    // Null response = bidirectional dedup or tie-breaker, no action needed
                    log.info("HANDSHAKE_RESPONSE_NULL_SKIPPED",
                            "senderId", senderId,
                            "reason", "bidirectional_dedup_or_tie_breaker");

                } else if (response.isRejected() && messageSender != null && targetAddress != null) {
                    // REJECTION: Send explicit HANDSHAKE_REJECT instead of HANDSHAKE_RESPONSE
                    // This eliminates silent failures by giving clear rejection semantics
                    HandshakeReject.RejectionReason rejectionReason = mapStatusToRejectionReason(response.getStatus());
                    HandshakeReject reject = new HandshakeReject.Builder()
                            .nodeId(nodeId)
                            .correlationId(correlationId)
                            .reason(rejectionReason)
                            .message(response.getReason())
                            .build();

                    sendHandshakeReject(senderId, reject, correlationId, targetAddress);
                    log.info("HANDSHAKE_REJECT_SENT",
                            "senderId", senderId,
                            "reason", rejectionReason,
                            "message", response.getReason());

                } else if (response != null && messageSender != null) {
                    // ACCEPTED: Send HANDSHAKE_RESPONSE as normal
                    log.info("HANDSHAKE_RESPONSE_SEND_CHECK",
                            "senderId", senderId,
                            "targetAddressNull", targetAddress == null,
                            "targetAddress", targetAddress);

                    if (targetAddress != null) {
                        sendHandshakeResponse(senderId, response, correlationId, targetAddress);
                        log.info("HANDSHAKE_RESPONSE_SENT_SUCCESS", "senderId", senderId);
                    } else {
                        log.error("HANDSHAKE_RESPONSE_FAILED_NO_ADDRESS",
                                "peerId", senderId,
                                "storedAddresses", peerSourceAddresses.keySet());
                        metrics.incrementCounter("handshake.response.no_address");
                    }
                } else if (messageSender == null) {
                    log.error("HANDSHAKE_RESPONSE_FAILED_NO_SENDER",
                            "peerId", senderId);
                    metrics.incrementCounter("handshake.response.send_failed");
                }

            } else if ("HANDSHAKE_RESPONSE".equals(messageType)) {
                // Log handshake message for full observability
                logHandshakeMessage(message, "RESPONSE");

                // Deserialize handshake response from message body
                String content = message.body().content();
                HandshakeResponse response = gson.fromJson(content, HandshakeResponse.class);

                // Process the response
                boolean success = processResponse(senderId, response);

                log.debug("Handshake response processed",
                        "remotePeer", senderId,
                        "success", success);

            } else if ("HANDSHAKE_REJECT".equals(messageType)) {
                // Log handshake rejection for full observability
                logHandshakeMessage(message, "REJECT");

                // Deserialize handshake rejection from message body
                String content = message.body().content();
                HandshakeReject reject = gson.fromJson(content, HandshakeReject.class);

                // Process the rejection
                processRejection(senderId, reject);

                log.info("HANDSHAKE_REJECT_PROCESSED",
                        "remotePeer", senderId,
                        "reason", reject.getReason(),
                        "correlationId", reject.getCorrelationId());

            } else {
                log.warn("Unknown handshake message type", "type", messageType);
                metrics.incrementCounter("handshake.unknown_type");
            }

        } catch (Exception e) {
            log.error("Error processing handshake message", e,
                    "type", messageType,
                    "from", senderId);
            metrics.incrementCounter("handshake.processing.errors");
        }
    }

    /**
     * Processes handshake messages (basic method for backward compatibility).
     * Note: Response routing may fail without context. Use processMessage(Message, ProcessingContext) instead.
     */
    @Override
    public void processMessage(Message message) {
        log.warn("processMessage called without context - response routing may fail");
        processMessage(message, null);
    }

    /**
     * Sends handshake response back to the requester with retry logic.
     *
     * @param targetPeerId the peer to send the response to
     * @param response the handshake response
     * @param correlationId the correlation ID linking to the original request
     * @param targetAddress the target address to send the response to
     */
    private void sendHandshakeResponse(String targetPeerId, HandshakeResponse response,
                                       String correlationId, java.net.InetSocketAddress targetAddress) {
        Map<String, Object> bodyMetadata = new HashMap<>();
        bodyMetadata.put("handshake", "response");

        MessageBody messageBody = new MessageBody(
                gson.toJson(response),
                bodyMetadata
        );

        MessageHeader header = new MessageHeader(
                null, // messageId - will be generated
                correlationId, // correlationId - links to request
                ProtocolVersion.current().toString(),
                "1.0",
                10, 0, // TTL and hopCount
                "HANDSHAKE_RESPONSE",
                nodeId,
                targetPeerId,
                System.currentTimeMillis(),
                false, "application/json",
                false, null,
                false // requiresAck
        );

        Message responseMessage = new Message(header, messageBody);

        // Retry logic: attempt up to 3 times with exponential backoff
        int maxRetries = 3;
        int baseDelayMs = 100;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Sending handshake response",
                        "peerId", targetPeerId,
                        "targetAddress", targetAddress,
                        "status", response.getStatus(),
                        "correlationId", correlationId,
                        "attempt", attempt);

                messageSender.sendMessage(responseMessage, targetAddress);
                metrics.incrementCounter("handshake.response.sent");
                return; // Success - exit retry loop

            } catch (Exception e) {
                log.warn("Failed to send handshake response",
                        "peerId", targetPeerId,
                        "targetAddress", targetAddress,
                        "attempt", attempt,
                        "maxRetries", maxRetries,
                        "error", e.getMessage());

                if (attempt < maxRetries) {
                    // Exponential backoff before retry
                    int delayMs = baseDelayMs * (1 << (attempt - 1));
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Interrupted during handshake response retry",
                                "peerId", targetPeerId);
                        break;
                    }
                    metrics.incrementCounter("handshake.response.retry");
                } else {
                    // Final failure after all retries
                    log.error("Failed to send handshake response after all retries", e,
                            "peerId", targetPeerId,
                            "targetAddress", targetAddress,
                            "attempts", maxRetries);
                    metrics.incrementCounter("handshake.response.send_failed");

                    // Mark peer as disconnected since we couldn't complete handshake
                    try {
                        peerManager.markDisconnected(targetPeerId);
                    } catch (Exception ex) {
                        log.warn("Failed to mark peer as disconnected", "peerId", targetPeerId);
                    }
                }
            }
        }
    }

    /**
     * Sends an explicit handshake rejection message to a peer.
     *
     * This provides clear rejection semantics, allowing the receiving side to:
     * - Immediately transition to DISCONNECTED state
     * - Know the specific rejection reason
     * - Track rejection metrics
     *
     * @param targetPeerId the peer being rejected
     * @param reject the rejection details
     * @param correlationId the correlation ID linking to the original request
     * @param targetAddress the target address to send the rejection to
     */
    private void sendHandshakeReject(String targetPeerId, HandshakeReject reject,
                                      String correlationId, java.net.InetSocketAddress targetAddress) {
        if (messageSender == null) {
            log.error("Cannot send HANDSHAKE_REJECT - message sender not configured",
                    "peerId", targetPeerId,
                    "reason", reject.getReason());
            return;
        }

        if (targetAddress == null) {
            log.error("Cannot send HANDSHAKE_REJECT - no target address",
                    "peerId", targetPeerId,
                    "reason", reject.getReason());
            return;
        }

        Map<String, Object> bodyMetadata = new HashMap<>();
        bodyMetadata.put("handshake", "reject");

        MessageBody messageBody = new MessageBody(
                gson.toJson(reject),
                bodyMetadata
        );

        MessageHeader header = new MessageHeader(
                null, // messageId - will be generated
                correlationId, // correlationId - links to request
                ProtocolVersion.current().toString(),
                "1.0",
                10, 0, // TTL and hopCount
                "HANDSHAKE_REJECT",
                nodeId,
                targetPeerId,
                System.currentTimeMillis(),
                false, "application/json",
                false, null,
                false // requiresAck
        );

        Message rejectMessage = new Message(header, messageBody);

        // Single attempt (no retry for rejections - they're best effort)
        try {
            log.info("Sending handshake rejection",
                    "peerId", targetPeerId,
                    "targetAddress", targetAddress,
                    "reason", reject.getReason(),
                    "correlationId", correlationId);

            messageSender.sendMessage(rejectMessage, targetAddress);
            metrics.incrementCounter("handshake.reject.sent");

            // Record to Prometheus-style metrics
            if (handshakeMetrics != null) {
                handshakeMetrics.recordRejectionSent(targetPeerId, reject.getReason());
            }

        } catch (Exception e) {
            // Rejection is best-effort - log but don't retry
            log.warn("Failed to send handshake rejection",
                    "peerId", targetPeerId,
                    "reason", reject.getReason(),
                    "error", e.getMessage());
            metrics.incrementCounter("handshake.reject.send_failed");
        }
    }

    /**
     * Processes an incoming handshake rejection.
     *
     * This method handles explicit rejection from the responder side:
     * 1. Removes from pendingHandshakes
     * 2. Records duration metrics
     * 3. Records per-reason metrics
     * 4. Immediately transitions peer to DISCONNECTED state
     * 5. Cleans up resources
     *
     * @param remotePeerId the peer that sent the rejection
     * @param reject the rejection details
     */
    private void processRejection(String remotePeerId, HandshakeReject reject) {
        log.info("Processing handshake rejection",
                "remotePeer", remotePeerId,
                "reason", reject.getReason(),
                "retryable", reject.isRetryable(),
                "permanent", reject.isPermanent());

        metrics.incrementCounter("handshake.reject.received");
        metrics.incrementCounter("handshake.failed");

        // Remove from pending handshakes
        HandshakeRequest request = pendingHandshakes.remove(remotePeerId);

        // Calculate duration
        Long startTime = handshakeStartTimes.remove(remotePeerId);
        java.time.Duration duration = (startTime != null)
                ? java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)
                : null;

        // Record to Prometheus-style metrics
        if (handshakeMetrics != null) {
            handshakeMetrics.recordRejectionReceived(remotePeerId, reject.getReason(), duration);
        }

        // Track specific rejection reason
        metrics.incrementCounter("handshake.reject.reason." + reject.getReason().name().toLowerCase());

        // CRITICAL: Immediately transition peer to DISCONNECTED state
        // This prevents zombie connections and stuck CONNECTING states
        try {
            peerManager.markDisconnected(remotePeerId);
            log.info("Peer marked as disconnected after rejection",
                    "peerId", remotePeerId,
                    "reason", reject.getReason());
        } catch (Exception e) {
            log.error("Failed to mark peer as disconnected after rejection", e,
                    "peerId", remotePeerId);
        }

        // Cleanup source address (no longer needed)
        peerSourceAddresses.remove(remotePeerId);

        // Log final status
        log.info("Handshake rejection processed",
                "remotePeer", remotePeerId,
                "reason", reject.getReason(),
                "durationMs", duration != null ? duration.toMillis() : -1,
                "hadPendingRequest", request != null);
    }

    /**
     * Initiates a handshake with a peer.
     */
    public HandshakeRequest initiateHandshake(String targetPeerId) {
        log.info("Initiating handshake", "targetPeer", targetPeerId);

        // Track start time for duration metrics
        handshakeStartTimes.put(targetPeerId, System.currentTimeMillis());

        // Create handshake request
        HandshakeRequest request = new HandshakeRequest.Builder()
                .nodeId(nodeId)
                .protocolVersion(ProtocolVersion.current())
                .publicKey(Base64.getEncoder().encodeToString(security.getPublicKey()))
                .addCapability("transport", "tcp,udp")
                .addCapability("encryption", security.getAlgorithm())
                .addCapability("discovery", "multicast,broadcast,bootstrap")
                .addMetadata("clientVersion", "genesis-p2p-2.0")
                .build();

        // Store pending handshake
        pendingHandshakes.put(targetPeerId, request);

        metrics.incrementCounter("handshake.initiated");

        // Record to Prometheus-style metrics
        if (handshakeMetrics != null) {
            handshakeMetrics.recordHandshakeInitiated(targetPeerId);
        }

        log.debug("Handshake request created", "targetPeer", targetPeerId);

        return request;
    }

    /**
     * Processes an incoming handshake request.
     *
     * This method handles the responder side of the handshake protocol:
     * 1. Validates the request
     * 2. Checks for bidirectional deduplication (prevents double connections)
     * 3. Establishes ECDH security session using requester's public key
     * 4. Marks peer as connected
     * 5. Returns response to be sent back
     */
    public HandshakeResponse processRequest(HandshakeRequest request) {
        String remotePeerId = request.getNodeId();

        log.info("Processing handshake request", "remotePeer", remotePeerId);
        metrics.incrementCounter("handshake.requests.received");

        // Record to Prometheus-style metrics
        if (handshakeMetrics != null) {
            handshakeMetrics.recordHandshakeReceived(remotePeerId);
        }

        try {
            // BIDIRECTIONAL DEDUPLICATION: Check if we already have a session with this peer
            // This prevents the situation where both peers initiate handshakes simultaneously
            // and end up with duplicate sessions
            Long existingSession = establishedSessions.get(remotePeerId);
            if (existingSession != null) {
                long sessionAge = System.currentTimeMillis() - existingSession;
                if (sessionAge < PENDING_TIMEOUT) {
                    log.info("Session already established (bidirectional dedup)",
                            "remotePeer", remotePeerId,
                            "sessionAgeMs", sessionAge);
                    metrics.incrementCounter("handshake.bidirectional_dedup");
                    metrics.incrementCounter("handshake.deduplicated");

                    // Record to Prometheus-style metrics
                    if (handshakeMetrics != null) {
                        handshakeMetrics.recordDeduplicated(remotePeerId);
                    }

                    // Return ALREADY_CONNECTED response
                    return new HandshakeResponse.Builder()
                            .status(HandshakeResponse.Status.ACCEPTED)
                            .nodeId(nodeId)
                            .protocolVersion(ProtocolVersion.current())
                            .publicKey(Base64.getEncoder().encodeToString(security.getPublicKey()))
                            .addMetadata("alreadyConnected", "true")
                            .build();
                }
            }

            // Also check if we have a pending outgoing handshake to this peer
            // Tie-breaker: Lower node ID wins (becomes the responder)
            if (pendingHandshakes.containsKey(remotePeerId)) {
                int comparison = nodeId.compareTo(remotePeerId);
                if (comparison < 0) {
                    // We have lower ID - we should be the responder
                    // Cancel our outgoing handshake and process this request
                    pendingHandshakes.remove(remotePeerId);
                    log.info("Bidirectional handshake resolved - we become responder",
                            "remotePeer", remotePeerId,
                            "ourId", nodeId);
                    metrics.incrementCounter("handshake.bidirectional_resolved.responder");
                } else {
                    // They have lower ID - they should be the responder
                    // Ignore this request, our outgoing handshake will complete
                    log.info("Bidirectional handshake resolved - they become responder",
                            "remotePeer", remotePeerId,
                            "theirId", remotePeerId);
                    metrics.incrementCounter("handshake.bidirectional_resolved.initiator");
                    return null; // Don't send response, wait for our outgoing handshake
                }
            }

            // Rate limiting check
            if (isRateLimited(remotePeerId)) {
                log.warn("Rate limit exceeded for handshake", "remotePeer", remotePeerId);
                metrics.incrementCounter("handshake.rate_limited");
                metrics.incrementCounter("handshake.failed");

                // Record to Prometheus-style metrics
                if (handshakeMetrics != null) {
                    handshakeMetrics.recordRateLimited(remotePeerId);
                }

                return HandshakeResponse.rateLimited();
            }

            // Validate request
            HandshakeValidator.ValidationResult validation = validator.validateRequest(request);
            if (!validation.isValid()) {
                log.warn("Handshake validation failed",
                        "remotePeer", remotePeerId,
                        "reason", validation.getErrorMessage());
                metrics.incrementCounter("handshake.validation.failed");
                metrics.incrementCounter("handshake.failed");

                // Record to Prometheus-style metrics
                if (handshakeMetrics != null) {
                    handshakeMetrics.recordValidationFailed(remotePeerId, validation.getErrorMessage());
                }

                return HandshakeResponse.rejected(validation.getErrorMessage());
            }

            // Check protocol compatibility
            if (!request.getProtocolVersion().isCompatibleWith(ProtocolVersion.current())) {
                log.warn("Protocol version mismatch",
                        "remotePeer", remotePeerId,
                        "requestVersion", request.getProtocolVersion());
                metrics.incrementCounter("handshake.protocol_mismatch");
                metrics.incrementCounter("handshake.failed");

                // Record to Prometheus-style metrics
                if (handshakeMetrics != null) {
                    handshakeMetrics.recordProtocolMismatch(remotePeerId);
                }

                return HandshakeResponse.protocolMismatch(ProtocolVersion.current());
            }

            // CRITICAL: Establish security session on RESPONDER side
            // The initiator will establish their session when they receive our response
            String sessionId = null;
            try {
                byte[] peerPublicKey = Base64.getDecoder().decode(request.getPublicKey());
                sessionId = security.performKeyExchange(remotePeerId, peerPublicKey);

                log.info("Security session established (responder side)",
                        "remotePeer", remotePeerId,
                        "sessionId", sessionId,
                        "algorithm", security.getAlgorithm());
                metrics.incrementCounter("handshake.session.established.responder");

            } catch (Exception keyExchangeEx) {
                log.error("Failed to establish security session (responder)", keyExchangeEx,
                        "remotePeer", remotePeerId);
                metrics.incrementCounter("handshake.session.failed.responder");
                metrics.incrementCounter("handshake.failed");

                // Record to Prometheus-style metrics
                if (handshakeMetrics != null) {
                    handshakeMetrics.recordSessionFailed(remotePeerId, keyExchangeEx.getMessage());
                    handshakeMetrics.recordHandshakeFailed(remotePeerId, "session_establishment_failed",
                            java.time.Duration.ofMillis(System.currentTimeMillis() - request.getTimestamp()));
                }

                return HandshakeResponse.rejected("Security session establishment failed");
            }

            // Accept the handshake
            HandshakeResponse response = new HandshakeResponse.Builder()
                    .status(HandshakeResponse.Status.ACCEPTED)
                    .nodeId(nodeId)
                    .protocolVersion(ProtocolVersion.current())
                    .publicKey(Base64.getEncoder().encodeToString(security.getPublicKey()))
                    .addCapability("transport", "tcp,udp")
                    .addCapability("encryption", security.getAlgorithm())
                    .addCapability("discovery", "multicast,broadcast,bootstrap")
                    .addMetadata("serverVersion", "genesis-p2p-2.0")
                    .addMetadata("sessionId", sessionId)
                    .build();

            // CRITICAL FIX: Ensure peer is registered before state transitions
            // When receiving HANDSHAKE_REQUEST from an unknown peer (not discovered via broadcast),
            // we must register them first, otherwise markConnected() will fail silently
            if (peerManager.getPeer(remotePeerId) == null) {
                java.net.InetSocketAddress sourceAddr = peerSourceAddresses.get(remotePeerId);
                if (sourceAddr != null) {
                    // Create peer from handshake request info
                    com.genesis.p2p.core.Peer newPeer = new com.genesis.p2p.core.Peer(
                            remotePeerId,                           // id
                            request.getPublicKey(),                 // publicKey
                            sourceAddr.getHostString(),             // hostName
                            sourceAddr.getHostString(),             // ip (local)
                            sourceAddr.getPort(),                   // port
                            null,                                   // publicIp (unknown)
                            0,                                      // publicPort (unknown)
                            com.genesis.p2p.nat.NatType.UNKNOWN,    // natType
                            false,                                  // behindNat (unknown)
                            true,                                   // online
                            java.time.Instant.now(),                // lastSeen
                            0,                                      // lastLatency
                            false,                                  // trusted (not yet)
                            50,                                     // reputation (neutral)
                            request.getProtocolVersion().toString(), // version
                            "unknown",                              // os
                            "handshake",                            // agent (discovered via handshake)
                            ""                                      // identityPublicKey (not known yet)
                    );
                    peerManager.upsertPeer(newPeer);
                    log.info("Registered unknown peer from handshake request",
                            "peerId", remotePeerId,
                            "sourceAddress", sourceAddr);
                    metrics.incrementCounter("handshake.peer.registered_from_request");
                } else {
                    log.warn("Cannot register peer - no source address available",
                            "peerId", remotePeerId);
                }
            }

            // CRITICAL: Atomic state transition DISCOVERED/CONNECTING → CONNECTED → AUTHENTICATED
            // Both transitions must succeed for handshake to be complete
            boolean connected = peerManager.markConnected(remotePeerId);
            boolean authenticated = false;
            if (connected) {
                authenticated = peerManager.markAuthenticated(remotePeerId);
            }

            // SECURITY FIX (v2.6): Rollback on partial failure
            // If markConnected succeeded but markAuthenticated failed, we have a peer in
            // CONNECTED state without proper authentication - this is a security risk.
            // We must rollback to prevent zombie connections.
            if (connected && !authenticated) {
                log.error("AUTH_TRANSITION_FAILED_ROLLBACK: Peer connected but not authenticated",
                        "remotePeer", remotePeerId,
                        "action", "ROLLBACK_TO_DISCONNECTED");

                // Rollback: mark peer as disconnected
                try {
                    peerManager.markDisconnected(remotePeerId);
                    log.info("Rollback completed - peer marked as disconnected",
                            "remotePeer", remotePeerId);
                } catch (Exception rollbackEx) {
                    log.error("Rollback failed - peer may be stuck in inconsistent state", rollbackEx,
                            "remotePeer", remotePeerId);
                }

                metrics.incrementCounter("handshake.state_transition.rollback.responder");

                // Record to Prometheus-style metrics
                if (handshakeMetrics != null) {
                    handshakeMetrics.recordHandshakeFailed(remotePeerId, "authentication_transition_failed",
                            java.time.Duration.ofMillis(System.currentTimeMillis() - request.getTimestamp()));
                }

                return HandshakeResponse.rejected("Authentication transition failed - please retry");
            }

            if (!connected) {
                log.warn("State transition failed (responder) - mark connected failed",
                        "remotePeer", remotePeerId);
                metrics.incrementCounter("handshake.state_transition.failed.responder");
            }

            // Record established session for bidirectional deduplication
            establishedSessions.put(remotePeerId, System.currentTimeMillis());

            // Record metrics
            if (handshakeMetrics != null) {
                handshakeMetrics.recordSessionEstablished(remotePeerId, false); // responder
                handshakeMetrics.recordHandshakeSuccess(remotePeerId,
                        java.time.Duration.ofMillis(System.currentTimeMillis() - request.getTimestamp()));
            }

            log.info("Handshake accepted (responder)",
                    "remotePeer", remotePeerId,
                    "sessionId", sessionId,
                    "stateConnected", connected,
                    "stateAuthenticated", authenticated);
            metrics.incrementCounter("handshake.accepted");
            metrics.incrementCounter("handshake.success");

            return response;

        } catch (Exception e) {
            log.error("Error processing handshake request", e, "remotePeer", remotePeerId);
            metrics.incrementCounter("handshake.errors");
            return HandshakeResponse.rejected("Internal error: " + e.getMessage());
        }
    }

    /**
     * Processes an incoming handshake response.
     */
    public boolean processResponse(String remotePeerId, HandshakeResponse response) {
        log.info("Processing handshake response",
                "remotePeer", remotePeerId,
                "status", response.getStatus());

        metrics.incrementCounter("handshake.responses.received");

        try {
            // Validate response
            HandshakeValidator.ValidationResult validation = validator.validateResponse(response);
            if (!validation.isValid()) {
                log.warn("Handshake response validation failed",
                        "remotePeer", remotePeerId,
                        "reason", validation.getErrorMessage());
                metrics.incrementCounter("handshake.response.validation_failed");
                metrics.incrementCounter("handshake.failed");
                cleanupFailedHandshake(remotePeerId, "validation_failed");
                return false;
            }

            // Check if we have a pending handshake
            HandshakeRequest request = pendingHandshakes.remove(remotePeerId);
            if (request == null) {
                log.warn("Received handshake response without pending request",
                        "remotePeer", remotePeerId);
                metrics.incrementCounter("handshake.response.unexpected");
                return false;
            }

            // Handle rejection
            if (response.isRejected()) {
                log.warn("Handshake rejected",
                        "remotePeer", remotePeerId,
                        "status", response.getStatus(),
                        "reason", response.getReason());
                metrics.incrementCounter("handshake.rejected");
                metrics.incrementCounter("handshake.failed");
                cleanupFailedHandshake(remotePeerId, "rejected:" + response.getStatus());
                return false;
            }

            // Handshake accepted
            log.info("Handshake completed successfully",
                    "remotePeer", remotePeerId,
                    "remoteVersion", response.getProtocolVersion());

            // Establish security session using peer's public key (initiator side)
            String sessionId;
            try {
                byte[] peerPublicKey = Base64.getDecoder().decode(response.getPublicKey());
                sessionId = security.performKeyExchange(remotePeerId, peerPublicKey);

                log.info("Security session established (initiator side)",
                        "remotePeer", remotePeerId,
                        "sessionId", sessionId,
                        "algorithm", security.getAlgorithm());
                metrics.incrementCounter("handshake.session.established.initiator");

            } catch (Exception keyExchangeEx) {
                log.error("Failed to establish security session (initiator)", keyExchangeEx,
                        "remotePeer", remotePeerId);
                metrics.incrementCounter("handshake.session.failed.initiator");
                metrics.incrementCounter("handshake.failed");

                // Record to Prometheus-style metrics
                if (handshakeMetrics != null) {
                    handshakeMetrics.recordSessionFailed(remotePeerId, keyExchangeEx.getMessage());
                }

                // Cleanup failed handshake and mark peer as disconnected
                cleanupFailedHandshake(remotePeerId, "session_establishment_failed");
                peerManager.markDisconnected(remotePeerId);
                return false;
            }

            // CRITICAL: Atomic state transition CONNECTING → CONNECTED → AUTHENTICATED
            // Both transitions must succeed for handshake to be complete
            boolean connected = peerManager.markConnected(remotePeerId);
            boolean authenticated = false;
            if (connected) {
                authenticated = peerManager.markAuthenticated(remotePeerId);
            }

            // SECURITY FIX (v2.6): Rollback on partial failure (initiator side)
            // If markConnected succeeded but markAuthenticated failed, we have a peer in
            // CONNECTED state without proper authentication - this is a security risk.
            if (connected && !authenticated) {
                log.error("AUTH_TRANSITION_FAILED_ROLLBACK: Peer connected but not authenticated (initiator)",
                        "remotePeer", remotePeerId,
                        "action", "ROLLBACK_TO_DISCONNECTED");

                // Rollback: mark peer as disconnected
                try {
                    peerManager.markDisconnected(remotePeerId);
                    log.info("Rollback completed - peer marked as disconnected",
                            "remotePeer", remotePeerId);
                } catch (Exception rollbackEx) {
                    log.error("Rollback failed - peer may be stuck in inconsistent state", rollbackEx,
                            "remotePeer", remotePeerId);
                }

                metrics.incrementCounter("handshake.state_transition.rollback.initiator");

                // Record to Prometheus-style metrics
                Long startTime = handshakeStartTimes.remove(remotePeerId);
                if (handshakeMetrics != null && startTime != null) {
                    handshakeMetrics.recordHandshakeFailed(remotePeerId, "authentication_transition_failed",
                            java.time.Duration.ofMillis(System.currentTimeMillis() - startTime));
                }

                return false;
            }

            if (!connected) {
                log.warn("State transition failed (initiator) - mark connected failed",
                        "remotePeer", remotePeerId);
                metrics.incrementCounter("handshake.state_transition.failed.initiator");
            }

            // Record established session for bidirectional deduplication
            establishedSessions.put(remotePeerId, System.currentTimeMillis());

            // Calculate handshake duration and record metrics
            Long startTime = handshakeStartTimes.remove(remotePeerId);
            java.time.Duration duration = (startTime != null)
                    ? java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)
                    : java.time.Duration.ZERO;

            if (handshakeMetrics != null) {
                handshakeMetrics.recordSessionEstablished(remotePeerId, true); // initiator
                handshakeMetrics.recordHandshakeSuccess(remotePeerId, duration);
            }

            log.info("Handshake completed successfully (initiator)",
                    "remotePeer", remotePeerId,
                    "sessionId", sessionId,
                    "stateConnected", connected,
                    "stateAuthenticated", authenticated,
                    "durationMs", duration.toMillis());

            metrics.incrementCounter("handshake.completed");
            metrics.incrementCounter("handshake.success");
            return true;

        } catch (Exception e) {
            log.error("Error processing handshake response", e, "remotePeer", remotePeerId);
            metrics.incrementCounter("handshake.errors");
            return false;
        }
    }

    /**
     * Checks if a peer is rate limited for handshakes.
     */
    private boolean isRateLimited(String peerId) {
        long now = System.currentTimeMillis();

        // Clean up old entries
        handshakeAttempts.entrySet().removeIf(entry ->
                (now - entry.getValue()) > RATE_LIMIT_WINDOW);

        // Check attempts
        Long lastAttempt = handshakeAttempts.get(peerId);
        if (lastAttempt != null && (now - lastAttempt) < RATE_LIMIT_WINDOW) {
            return true;
        }

        // Record this attempt
        handshakeAttempts.put(peerId, now);
        return false;
    }

    /**
     * Cleans up expired pending handshakes and established session records.
     */
    public void cleanupExpiredHandshakes() {
        long now = Time.currentMillis();

        // Cleanup expired pending handshakes
        pendingHandshakes.entrySet().removeIf(entry -> {
            HandshakeRequest request = entry.getValue();
            boolean expired = (now - request.getTimestamp()) > PENDING_TIMEOUT;

            if (expired) {
                String peerId = entry.getKey();
                log.warn("Handshake expired - marking peer as disconnected",
                        "peerId", peerId,
                        "pendingDurationMs", now - request.getTimestamp());
                metrics.incrementCounter("handshake.expired");
                metrics.incrementCounter("handshake.failed");

                // Record to Prometheus-style metrics
                if (handshakeMetrics != null) {
                    handshakeMetrics.recordResponseTimeout(peerId);
                }

                // Cleanup start time
                handshakeStartTimes.remove(peerId);

                // CRITICAL FIX: Mark peer as DISCONNECTED when handshake expires
                // This prevents peers from being stuck in CONNECTING state forever
                try {
                    peerManager.markDisconnected(peerId);
                    log.info("Peer marked as disconnected due to handshake timeout",
                            "peerId", peerId);
                } catch (Exception e) {
                    log.error("Failed to mark peer as disconnected", e,
                            "peerId", peerId);
                }

                // Remove source address (no longer needed)
                peerSourceAddresses.remove(peerId);
            }

            return expired;
        });

        // Cleanup old established session records (keep for 5 minutes for dedup)
        final long SESSION_RECORD_TTL = TimeUnit.MINUTES.toMillis(5);
        establishedSessions.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue()) > SESSION_RECORD_TTL;
            if (expired) {
                log.debug("Removing old session record", "peerId", entry.getKey());
            }
            return expired;
        });

        // Cleanup old source addresses (keep for 1 minute)
        final long SOURCE_ADDRESS_TTL = TimeUnit.MINUTES.toMillis(1);
        // Note: We don't have timestamps for source addresses, so we rely on
        // them being cleaned when the session record is cleaned
    }

    /**
     * Cleans up a failed handshake for a specific peer.
     * Called when handshake fails for any reason (validation, rejection, session error).
     *
     * @param peerId the peer ID
     * @param reason the failure reason for logging/metrics
     */
    private void cleanupFailedHandshake(String peerId, String reason) {
        // Remove from pending handshakes
        pendingHandshakes.remove(peerId);

        // Remove start time
        Long startTime = handshakeStartTimes.remove(peerId);

        // Calculate duration if available
        java.time.Duration duration = (startTime != null)
                ? java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)
                : null;

        // Record to Prometheus-style metrics
        if (handshakeMetrics != null) {
            handshakeMetrics.recordHandshakeFailed(peerId, reason, duration);
        }

        // Remove source address (no longer needed)
        peerSourceAddresses.remove(peerId);

        // CRITICAL FIX: Mark peer as DISCONNECTED when handshake fails
        // This prevents peers from being stuck in CONNECTING state forever
        try {
            peerManager.markDisconnected(peerId);
        } catch (Exception e) {
            log.warn("Failed to mark peer as disconnected during cleanup",
                    "peerId", peerId, "error", e.getMessage());
        }

        log.info("Cleaned up failed handshake",
                "peerId", peerId,
                "reason", reason,
                "durationMs", duration != null ? duration.toMillis() : -1);
    }

    /**
     * Gets statistics about handshake processing.
     */
    public HandshakeStats getStats() {
        return new HandshakeStats(
                pendingHandshakes.size(),
                handshakeAttempts.size()
        );
    }

    /**
     * Maps HandshakeResponse.Status to HandshakeReject.RejectionReason.
     *
     * This mapping allows the existing processRequest() logic to continue
     * returning HandshakeResponse while processMessage() converts rejections
     * to explicit HANDSHAKE_REJECT messages.
     */
    private HandshakeReject.RejectionReason mapStatusToRejectionReason(HandshakeResponse.Status status) {
        return switch (status) {
            case RATE_LIMITED -> HandshakeReject.RejectionReason.RATE_LIMITED;
            case PROTOCOL_MISMATCH -> HandshakeReject.RejectionReason.PROTOCOL_MISMATCH;
            case AUTH_FAILED -> HandshakeReject.RejectionReason.AUTH_FAILED;
            case BLACKLISTED -> HandshakeReject.RejectionReason.BLACKLISTED;
            case REJECTED -> HandshakeReject.RejectionReason.VALIDATION_FAILED; // General rejection maps to validation
            case ACCEPTED -> HandshakeReject.RejectionReason.INTERNAL_ERROR; // Should never happen
        };
    }

    /**
     * Logs handshake message to MessageLogger for full observability.
     * Generates a unique handshake ID for correlation across request/response.
     *
     * Note: The message must already be persisted (which AbstractTransport does automatically).
     * This method marks it as a handshake message and adds handshake-specific context.
     */
    private void logHandshakeMessage(Message message, String phase) {
        if (messageLogger == null) {
            return; // Logging disabled
        }

        try {
            // Generate handshake ID for correlation
            String handshakeId = message.header().correlationId();
            if (handshakeId == null || handshakeId.isEmpty()) {
                handshakeId = message.messageId();
            }

            // Log to MessageLogger with handshake context
            // Note: This requires the message to be already persisted by AbstractTransport
            // The MessageLogger will look it up by messageId and mark it as a handshake message
            messageLogger.logHandshakeMessageByMessageId(message.messageId(), handshakeId, phase);

            log.debug("Handshake message logged",
                    "messageId", message.messageId(),
                    "handshakeId", handshakeId,
                    "phase", phase);

        } catch (Exception e) {
            log.warn("Failed to log handshake message to MessageLogger",
                    "messageId", message.messageId(),
                    "error", e.getMessage());
        }
    }

    // ========================= Observability Accessors =========================

    /**
     * Gets the handshake configuration.
     */
    public HandshakeConfig getConfig() {
        return config;
    }

    /**
     * Sets the handshake configuration.
     */
    public void setConfig(HandshakeConfig config) {
        this.config = config != null ? config : HandshakeConfig.defaults();
        log.info("Handshake configuration updated", "config", this.config);
    }

    /**
     * Gets the handshake metrics (Prometheus-style).
     */
    public HandshakeMetrics getHandshakeMetrics() {
        return handshakeMetrics;
    }

    /**
     * Sets the handshake metrics.
     */
    public void setHandshakeMetrics(HandshakeMetrics handshakeMetrics) {
        this.handshakeMetrics = handshakeMetrics;
        log.info("Handshake metrics " + (handshakeMetrics != null ? "enabled" : "disabled"));
    }

    /**
     * Gets the handshake timeline for visual tracing.
     */
    public HandshakeTimeline getHandshakeTimeline() {
        return handshakeTimeline;
    }

    /**
     * Sets the handshake timeline.
     */
    public void setHandshakeTimeline(HandshakeTimeline handshakeTimeline) {
        this.handshakeTimeline = handshakeTimeline;
        log.info("Handshake timeline " + (handshakeTimeline != null ? "enabled" : "disabled"));
    }

    /**
     * Checks if detailed observability is enabled.
     */
    public boolean isObservabilityEnabled() {
        return handshakeMetrics != null || handshakeTimeline != null;
    }

    /**
     * Gets the node ID.
     */
    public String getNodeId() {
        return nodeId;
    }

    // ========================= Inner Classes =========================

    /**
     * Statistics about handshake processing.
     */
    public static class HandshakeStats {
        private final int pendingHandshakes;
        private final int trackedPeers;

        public HandshakeStats(int pendingHandshakes, int trackedPeers) {
            this.pendingHandshakes = pendingHandshakes;
            this.trackedPeers = trackedPeers;
        }

        public int getPendingHandshakes() {
            return pendingHandshakes;
        }

        public int getTrackedPeers() {
            return trackedPeers;
        }

        @Override
        public String toString() {
            return String.format("HandshakeStats[pending=%d, tracked=%d]",
                    pendingHandshakes, trackedPeers);
        }
    }
}

