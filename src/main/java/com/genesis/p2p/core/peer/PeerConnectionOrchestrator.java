package com.genesis.p2p.core.peer;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.events.core.EventBus;
import com.genesis.p2p.events.core.GenericEvent;
import com.genesis.p2p.events.core.IEvent;
import com.genesis.p2p.nat.ConnectionStrategy;
import com.genesis.p2p.nat.INatTraversalService;
import com.genesis.p2p.nat.NatResolutionService;
import com.genesis.p2p.nat.NatType;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.protocol.handshake.HandshakeProcessor;
import com.genesis.p2p.protocol.handshake.HandshakeRequest;
import com.genesis.p2p.security.channel.KeyExchangeInit;
import com.genesis.p2p.security.channel.SecureChannelNegotiator;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.message.MessageSecurityHelper;
import com.genesis.p2p.transport.core.ITransport;
import com.genesis.p2p.transport.tcp.TcpTransport;
import com.google.gson.Gson;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates the complete peer connection lifecycle.
 *
 * Bridges the gap between UDP discovery and TCP session establishment:
 * 1. Listens for peer discovery events
 * 2. Initiates TCP connections to discovered peers
 * 3. Performs handshake authentication
 * 4. Establishes persistent TCP sessions
 * 5. Manages connection health and retries
 *
 * Design Pattern: Mediator + Observer
 * - Mediates between Discovery, Transport, and PeerManager
 * - Observes peer.discovered events from EventBus
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class PeerConnectionOrchestrator {

    private static final NodeLogger log = NodeLogger.getLogger(PeerConnectionOrchestrator.class);
    private static final Gson gson = new Gson();

    private final String nodeId;
    private final PeerManager peerManager;
    private final ITransport tcpTransport;
    private final HandshakeProcessor handshakeProcessor;
    private final EventBus eventBus;
    private final MetricsRegistry metrics;
    private final SecurityFacade security;
    private final INatTraversalService natService; // NAT-aware
    private final SecureChannelNegotiator secureChannelNegotiator; // Secure bootstrap

    // Decoupled NAT resolution service (async, non-blocking)
    private final NatResolutionService natResolutionService;

    private final AtomicBoolean running;
    private final ScheduledExecutorService scheduler;

    // Track pending connections to avoid duplicates
    private final Map<String, CompletableFuture<Boolean>> pendingConnections;

    // Configuration
    private static final int CONNECTION_TIMEOUT_MS = 10000;  // Increased from 5s to 10s for cross-network
    private static final int TCP_CONNECT_TIMEOUT_SECONDS = 10;  // TCP socket connect timeout
    private static final int MAX_CONCURRENT_CONNECTIONS = 50;
    private final Semaphore connectionSemaphore;

    /**
     * Creates a peer connection orchestrator.
     */
    public PeerConnectionOrchestrator(
            String nodeId,
            PeerManager peerManager,
            ITransport tcpTransport,
            HandshakeProcessor handshakeProcessor,
            EventBus eventBus,
            MetricsRegistry metrics,
            SecurityFacade security,
            INatTraversalService natService,
            SecureChannelNegotiator secureChannelNegotiator) {

        this.nodeId = nodeId;
        this.peerManager = peerManager;
        this.tcpTransport = tcpTransport;
        this.handshakeProcessor = handshakeProcessor;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.security = security;
        this.natService = natService;
        this.secureChannelNegotiator = secureChannelNegotiator;

        // Create decoupled NAT resolution service
        // NAT resolution is now fully async and non-blocking
        this.natResolutionService = new NatResolutionService(
                nodeId,
                peerManager.getPeerStore(),
                natService,
                eventBus,
                metrics
        );

        this.running = new AtomicBoolean(false);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "PeerConnectionOrchestrator");
            t.setDaemon(true);
            return t;
        });

        this.pendingConnections = new ConcurrentHashMap<>();
        this.connectionSemaphore = new Semaphore(MAX_CONCURRENT_CONNECTIONS);
    }

    /**
     * Starts the orchestrator.
     *
     * DECOUPLED ARCHITECTURE:
     * - NAT detection is now fully async via NatResolutionService
     * - Connections are NOT blocked by NAT detection
     * - Handshake completes first → peer becomes CONNECTED/AUTHENTICATED
     * - NAT resolution runs in background for optimization
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting peer connection orchestrator (decoupled NAT architecture)");

            // Start NAT resolution service (async, non-blocking)
            // This detects local NAT type in background - does NOT block connections
            natResolutionService.start();

            // Subscribe to peer discovery events
            eventBus.subscribe("peer.discovered", this::handlePeerDiscovered);

            // Schedule periodic connection health checks
            scheduler.scheduleAtFixedRate(
                this::checkConnectionHealth,
                30, 30, TimeUnit.SECONDS
            );

            // Schedule periodic handshake cleanup (expired pending handshakes)
            scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        handshakeProcessor.cleanupExpiredHandshakes();
                    } catch (Exception e) {
                        log.warn("Handshake cleanup failed", "error", e.getMessage());
                    }
                },
                15, 15, TimeUnit.SECONDS
            );

            // Schedule periodic cleanup of stuck CHANNEL_NEGOTIATING peers
            // Peers stuck in this state for > 30 seconds should be reset to DISCONNECTED
            scheduler.scheduleAtFixedRate(
                this::cleanupStuckChannelNegotiations,
                30, 30, TimeUnit.SECONDS
            );

            log.info("Peer connection orchestrator started",
                    "architecture", "decoupled_nat",
                    "natBlocking", false,
                    "handshakeFirst", true,
                    "handshakeCleanupIntervalSec", 15,
                    "stuckNegotiationCleanupIntervalSec", 30);
        }
    }

    /**
     * Stops the orchestrator.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping peer connection orchestrator");

            eventBus.unsubscribe("peer.discovered", this::handlePeerDiscovered);

            // Stop NAT resolution service
            natResolutionService.stop();

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Cancel all pending connections
            pendingConnections.values().forEach(future -> future.cancel(true));
            pendingConnections.clear();

            log.info("Peer connection orchestrator stopped");
        }
    }

    /**
     * Handles peer discovered events.
     */
    private void handlePeerDiscovered(IEvent event) {
        if (!running.get()) {
            return;
        }

        try {
            // Extract peer info from event payload
            Object payload = event.getPayload();
            if (!(payload instanceof Map)) {
                log.warn("Invalid event payload type", "type", payload.getClass().getName());
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payload;
            String peerId = (String) data.get("peerId");

            if (peerId == null || peerId.equals(nodeId)) {
                return; // Ignore invalid or self
            }

            log.info("Peer discovered event received",
                    "peerId", peerId,
                    "source", data.get("source"));

            // Get peer from manager
            Peer peer = peerManager.getPeer(peerId);
            if (peer == null) {
                log.warn("Peer not found in manager after discovery event", "peerId", peerId);
                return;
            }

            // Initiate connection (async)
            initiateConnection(peer);

        } catch (Exception e) {
            log.error("Error handling peer discovered event", e);
            metrics.incrementCounter("orchestrator.errors.event_handling");
        }
    }

    /**
     * Initiates connection to a peer.
     *
     * Thread-safe: Uses atomic operations to prevent TOCTOU race conditions
     * when checking for existing pending connections.
     */
    public CompletableFuture<Boolean> initiateConnection(Peer peer) {
        String peerId = peer.id();

        // Check if already connected (fast path)
        if (peerManager.isConnected(peerId)) {
            log.debug("Peer already connected", "peerId", peerId);
            return CompletableFuture.completedFuture(true);
        }

        // ATOMIC: Use computeIfAbsent to prevent TOCTOU race condition
        // This ensures only one thread creates the connection future for a given peer
        CompletableFuture<Boolean> existingFuture = pendingConnections.get(peerId);
        if (existingFuture != null) {
            log.debug("Connection already pending", "peerId", peerId);
            return existingFuture;
        }

        log.info("Initiating connection to peer",
                "peerId", peerId,
                "ip", peer.ip(),
                "port", peer.port());

        // CRITICAL: Mark as CHANNEL_NEGOTIATING *before* starting async work
        // This is the first phase of the secure bootstrap flow:
        // DISCOVERED → CHANNEL_NEGOTIATING → CHANNEL_ESTABLISHED → CONNECTING → CONNECTED
        //
        // NOTE: Using CHANNEL_NEGOTIATING (not CONNECTING) to properly support
        // the two-phase secure bootstrap design where KEY_EXCHANGE happens first.
        boolean markedChannelNegotiating = peerManager.markChannelNegotiating(peerId);
        if (!markedChannelNegotiating) {
            log.debug("Failed to mark peer as channel negotiating (may already be in progress)",
                    "peerId", peerId,
                    "currentState", peerManager.getPeerState(peerId));
            // Check if there's now a pending connection from another thread
            existingFuture = pendingConnections.get(peerId);
            if (existingFuture != null) {
                return existingFuture;
            }
        }

        metrics.incrementCounter("orchestrator.connections.initiated");

        // Create connection future
        CompletableFuture<Boolean> connectionFuture = CompletableFuture.supplyAsync(() -> {
            try {
                // Acquire semaphore to limit concurrent connections
                if (!connectionSemaphore.tryAcquire(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    log.warn("Connection semaphore timeout", "peerId", peerId);
                    metrics.incrementCounter("orchestrator.connections.semaphore_timeout");
                    // Mark as disconnected on failure
                    peerManager.markDisconnected(peerId);
                    return false;
                }

                try {
                    return connectAndHandshake(peer);
                } finally {
                    connectionSemaphore.release();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Connection interrupted", "peerId", peerId);
                peerManager.markDisconnected(peerId);
                return false;
            } catch (Exception e) {
                log.error("Connection failed", e, "peerId", peerId);
                metrics.incrementCounter("orchestrator.connections.failed");
                peerManager.markDisconnected(peerId);
                return false;
            }
        }, scheduler);

        // ATOMIC: Use putIfAbsent to handle race condition where another thread
        // may have added a pending connection between our check and put
        CompletableFuture<Boolean> racingFuture = pendingConnections.putIfAbsent(peerId, connectionFuture);
        if (racingFuture != null) {
            // Another thread won the race, cancel our future and return theirs
            connectionFuture.cancel(true);
            log.debug("Lost race for connection, using existing future", "peerId", peerId);
            return racingFuture;
        }

        // Cleanup after completion
        connectionFuture.whenComplete((success, error) -> {
            pendingConnections.remove(peerId);

            if (success != null && success) {
                log.info("✓ Peer connection established successfully", "peerId", peerId);
                metrics.incrementCounter("orchestrator.connections.successful");

                // Fire connection established event
                Map<String, Object> eventData = new HashMap<>();
                eventData.put("peerId", peerId);
                eventData.put("ip", peer.ip());
                eventData.put("port", peer.port());

                eventBus.publish(new GenericEvent("peer.connected", nodeId, eventData));
            } else {
                log.warn("✗ Peer connection failed", "peerId", peerId,
                        "error", error != null ? error.getMessage() : "unknown");
                metrics.incrementCounter("orchestrator.connections.failed");

                // Schedule retry after delay (only if still running and peer exists)
                scheduleConnectionRetry(peerId, 30);  // Retry after 30 seconds
            }
        });

        return connectionFuture;
    }

    /**
     * Performs DIRECT connection (NAT-independent).
     *
     * SIMPLE APPROACH:
     * 1. Use the address from discovery directly
     * 2. No NAT detection required
     * 3. Connection works with or without NAT
     */
    private boolean connectAndHandshake(Peer peer) {
        String peerId = peer.id();

        // Simply use DIRECT strategy - no NAT complexity needed
        ConnectionStrategy strategy = ConnectionStrategy.DIRECT;

        // Get the peer's address from discovery
        InetSocketAddress targetAddress = getConnectionAddress(peer, strategy);

        if (targetAddress == null) {
            log.error("Cannot determine target address", "peerId", peerId);
            return false;
        }

        log.info("Direct connection attempt (NAT-independent)",
                "peerId", peerId,
                "targetAddress", targetAddress.getAddress().getHostAddress() + ":" + targetAddress.getPort());

        metrics.incrementCounter("orchestrator.strategy.direct");

        try {
            // Perform direct TCP connection + handshake
            return performHandshake(peer, targetAddress, strategy);

        } catch (Exception e) {
            log.error("Connection failed", e, "peerId", peerId);
            metrics.incrementCounter("orchestrator.connections.error");
            return false;
        }
    }

    /**
     * Gets the connection address for a peer.
     *
     * SIMPLE DIRECT CONNECTION (NAT-independent):
     * - Uses the address we received from discovery (local IP)
     * - NAT detection is optional and non-blocking
     * - Connection works whether NAT is detected or not
     */
    private InetSocketAddress getConnectionAddress(Peer peer, ConnectionStrategy strategy) {
        String peerId = peer.id();

        // Get the address from peer discovery
        // This is the IP:port the peer announced in broadcast/multicast
        InetSocketAddress peerAddr = peer.getLocalAddress();

        log.info("Connection address selection (NAT-independent)",
                "peerId", peerId,
                "peerIp", peer.ip(),
                "peerPort", peer.port(),
                "publicIp", peer.publicIp(),
                "publicPort", peer.publicPort(),
                "strategy", strategy.name());

        // Simply use the peer's announced address
        // This is what was received from discovery and should be reachable
        log.info("Using peer's discovered address",
                "peerId", peerId,
                "address", peerAddr.getAddress().getHostAddress() + ":" + peerAddr.getPort());

        return peerAddr;
    }

    /**
     * Performs the handshake (authentication phase).
     * Direct TCP connection - no NAT complexity.
     */
    private boolean performHandshake(Peer peer, InetSocketAddress address, ConnectionStrategy strategy) {
        String peerId = peer.id();

        log.info("Performing direct TCP handshake",
                "peerId", peerId,
                "address", address.getAddress().getHostAddress() + ":" + address.getPort());

        try {
            // Direct TCP connection + key exchange
            return connectDirect(peer, address);

        } catch (Exception e) {
            log.error("Handshake failed", e, "peerId", peerId);
            return false;
        }
    }

    /**
     * Triggers async NAT resolution for a peer.
     * This is NON-BLOCKING and runs after handshake succeeds.
     */
    private void triggerAsyncNatResolution(String peerId) {
        // Fire and forget - NAT resolution is for optimization, not gating
        natResolutionService.resolveAsync(peerId)
                .thenAccept(result -> {
                    log.info("Async NAT resolution complete",
                            "peerId", peerId,
                            "status", result.getStatus(),
                            "natType", result.getNatType(),
                            "strategy", result.getRecommendedStrategy());
                })
                .exceptionally(error -> {
                    // NAT resolution failure is NON-FATAL
                    log.debug("Async NAT resolution failed (non-fatal)",
                            "peerId", peerId,
                            "error", error.getMessage());
                    return null;
                });
    }

    /**
     * Direct TCP connection with SECURE CHANNEL FIRST.
     *
     * FIX #1: PRE-ESTABLISH TCP CONNECTION BEFORE KEY_EXCHANGE_INIT
     * This removes the race condition where KEY_EXCHANGE_INIT is lost during lazy
     * connection creation.
     *
     * NEW SECURE BOOTSTRAP FLOW:
     * 1. PRE-ESTABLISH TCP connection (new - FIX #1)
     * 2. Send KEY_EXCHANGE_INIT (signed ephemeral key)
     * 3. Receive KEY_EXCHANGE_COMPLETE (via KeyExchangeCompleteProcessor)
     * 4. Secure channel established
     * 5. HANDSHAKE_REQUEST sent encrypted (triggered by KeyExchangeCompleteProcessor)
     *
     * This method initiates Phase 1 (KEY_EXCHANGE).
     * Phase 2 (HANDSHAKE) is triggered by KeyExchangeCompleteProcessor after
     * KEY_EXCHANGE_COMPLETE is received and verified.
     */
    private boolean connectDirect(Peer peer, InetSocketAddress address) {
        String peerId = peer.id();

        log.info("Establishing secure channel (Phase 1: KEY_EXCHANGE)",
                "peerId", peerId,
                "address", address,
                "peerIp", peer.ip(),
                "peerPort", peer.port(),
                "publicIp", peer.publicIp(),
                "publicPort", peer.publicPort());

        try {
            // ═══════════════════════════════════════════════════════════════════════════
            // FIX #1: PRE-ESTABLISH TCP CONNECTION
            // ═══════════════════════════════════════════════════════════════════════════
            // CRITICAL: Create TCP connection BEFORE sending KEY_EXCHANGE_INIT
            // This prevents the message from being lost during connection setup
            log.info("Pre-establishing TCP connection for KEY_EXCHANGE",
                    "peerId", peerId,
                    "targetAddress", address.getAddress().getHostAddress(),
                    "targetPort", address.getPort());

            boolean needsConnection = !((TcpTransport)tcpTransport).isConnected(address);

            if (needsConnection) {
                long startTime = System.currentTimeMillis();
                try {
                    ((TcpTransport)tcpTransport).connectAsync(address).get(TCP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    long elapsed = System.currentTimeMillis() - startTime;

                    log.info("TCP connection pre-established successfully",
                            "peerId", peerId,
                            "address", address,
                            "elapsedMs", elapsed);
                } catch (java.util.concurrent.TimeoutException te) {
                    log.error("TCP connection timeout - peer may be unreachable or firewalled",
                            "peerId", peerId,
                            "targetAddress", address.getAddress().getHostAddress(),
                            "targetPort", address.getPort(),
                            "timeoutSeconds", TCP_CONNECT_TIMEOUT_SECONDS,
                            "hint", "Check firewall settings on both endpoints");
                    throw te;
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    log.error("TCP connection failed",
                            "peerId", peerId,
                            "targetAddress", address.getAddress().getHostAddress(),
                            "targetPort", address.getPort(),
                            "errorType", cause != null ? cause.getClass().getSimpleName() : "Unknown",
                            "errorMessage", cause != null ? cause.getMessage() : ee.getMessage());
                    throw ee;
                }

                // Small delay to ensure connection is fully ready
                Thread.sleep(100);
            } else {
                log.debug("TCP connection already exists",
                        "peerId", peerId,
                        "address", address);
            }

            // Get peer's identity public key for signature verification
            byte[] peerIdentityKey = null;
            if (peer.hasIdentityPublicKey()) {
                peerIdentityKey = peer.getIdentityPublicKeyBytes();
            }

            // Phase 1: Initiate KEY_EXCHANGE (secure channel establishment)
            // NOTE: State already transitioned to CHANNEL_NEGOTIATING in initiateConnection()
            KeyExchangeInit keyExchangeInit = secureChannelNegotiator.initiateKeyExchange(
                    peerId, peerIdentityKey);

            log.debug("KEY_EXCHANGE initiated",
                    "peerId", peerId,
                    "correlationId", keyExchangeInit.getCorrelationId(),
                    "currentState", peerManager.getPeerState(peerId));

            Map<String, Object> bodyMetadata = new HashMap<>();
            bodyMetadata.put("keyExchange", "init");

            MessageBody messageBody = new MessageBody(
                gson.toJson(keyExchangeInit.toJson()),
                bodyMetadata
            );

            // Create KEY_EXCHANGE_INIT message (signed, not encrypted)
            MessageHeader header = new MessageHeader(
                    null,                               // messageId - auto-generated
                    keyExchangeInit.getCorrelationId(), // correlationId
                    "1.0",                              // protocolVersion
                    "1.0",                              // messageVersion
                    10, 0,                              // ttl, hopCount
                    KeyExchangeInit.MESSAGE_TYPE,       // type
                    nodeId,                             // from
                    peerId,                             // to
                    System.currentTimeMillis(),         // timestamp
                    false,                              // encrypted (KEY_EXCHANGE is signed, not encrypted)
                    "json",                             // contentType
                    false,                              // authenticated
                    null,                               // signature
                    false                               // requiresAck
            );
            Message keyExchangeMessage = new Message(header, messageBody);

            log.info("Sending KEY_EXCHANGE_INIT over pre-established connection",
                    "peerId", peerId,
                    "correlationId", keyExchangeInit.getCorrelationId(),
                    "address", address);

            // ═══════════════════════════════════════════════════════════════════════════
            // FIX #1: VERIFY MESSAGE WAS ACTUALLY SENT
            // ═══════════════════════════════════════════════════════════════════════════
            long sentCountBefore = metrics.getCounter("transport.tcp.send.success");

            // Send KEY_EXCHANGE_INIT via TCP (connection already exists)
            tcpTransport.send(keyExchangeMessage, address).get(5, TimeUnit.SECONDS);

            long sentCountAfter = metrics.getCounter("transport.tcp.send.success");

            if (sentCountAfter <= sentCountBefore) {
                throw new IOException("KEY_EXCHANGE_INIT send verification failed - metric did not increment");
            }

            log.info("KEY_EXCHANGE_INIT sent and verified",
                    "peerId", peerId,
                    "via", "TCP",
                    "correlationId", keyExchangeInit.getCorrelationId(),
                    "metricIncrement", sentCountAfter - sentCountBefore);

            // Note: The flow continues when we receive KEY_EXCHANGE_COMPLETE
            // KeyExchangeCompleteProcessor will:
            // 1. Verify the response signature
            // 2. Complete ECDH key derivation
            // 3. Transition peer to CHANNEL_ESTABLISHED
            // 4. Trigger HandshakeProcessor to send encrypted HANDSHAKE_REQUEST

            metrics.incrementCounter("orchestrator.key_exchange.init_sent");
            return true;

        } catch (Exception e) {
            log.error("KEY_EXCHANGE initiation failed", e,
                    "peerId", peerId,
                    "address", address);
            metrics.incrementCounter("orchestrator.key_exchange.init_failed");

            // Cleanup negotiation state
            secureChannelNegotiator.cancelNegotiation(peerId);
            return false;
        }
    }

    /**
     * UDP hole punching followed by TCP connection.
     */
    private boolean connectViaHolePunch(Peer peer, InetSocketAddress address) {
        String peerId = peer.id();

        log.info("Attempting UDP hole punch",
                "peerId", peerId,
                "address", address);

        try {
            if (natService == null) {
                log.warn("NAT service not available for hole punching");
                return connectDirect(peer, address); // Fallback
            }

            // Establish hole punch
            boolean holePunched = natService.establishConnection(address)
                .get(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (!holePunched) {
                log.warn("Hole punch failed, attempting direct connection anyway",
                        "peerId", peerId);
                metrics.incrementCounter("orchestrator.holepunch.failed");
                return connectDirect(peer, address); // Fallback to direct
            }

            log.info("Hole punch successful, establishing TCP connection",
                    "peerId", peerId);
            metrics.incrementCounter("orchestrator.holepunch.success");

            // Now attempt TCP connection through the punched hole
            return connectDirect(peer, address);

        } catch (Exception e) {
            log.error("Hole punch connection failed", e,
                    "peerId", peerId);
            metrics.incrementCounter("orchestrator.holepunch.error");
            return false;
        }
    }

    /**
     * TURN relay connection (SYMMETRIC NAT).
     * @deprecated Use performHandshake with appropriate strategy
     */
    @Deprecated
    private boolean connectViaRelay(Peer peer, InetSocketAddress address) {
        String peerId = peer.id();

        log.warn("RELAY strategy required but not implemented",
                "peerId", peerId,
                "localNat", natResolutionService.getLocalNatType(),
                "remoteNat", peer.natType());

        // TODO: Implement TURN relay
        metrics.incrementCounter("orchestrator.relay.not_implemented");

        // For now, attempt direct connection as fallback
        log.warn("Falling back to direct connection attempt", "peerId", peerId);
        return connectDirect(peer, address);
    }

    /**
     * Schedules a connection retry after a delay.
     * Only retries if orchestrator is still running and peer is in a retriable state.
     *
     * @param peerId the peer to retry connecting to
     * @param delaySeconds delay before retry attempt
     */
    private void scheduleConnectionRetry(String peerId, int delaySeconds) {
        if (!running.get()) {
            return;
        }

        scheduler.schedule(() -> {
            try {
                if (!running.get()) {
                    return;
                }

                // Check if peer still exists and is in retriable state
                Peer peer = peerManager.getPeer(peerId);
                if (peer == null) {
                    log.debug("Retry cancelled - peer no longer exists", "peerId", peerId);
                    return;
                }

                PeerStore.PeerState state = peerManager.getPeerState(peerId);
                if (state == PeerStore.PeerState.CONNECTED ||
                    state == PeerStore.PeerState.AUTHENTICATED ||
                    state == PeerStore.PeerState.CHANNEL_ESTABLISHED) {
                    log.debug("Retry cancelled - peer already connected", "peerId", peerId, "state", state);
                    return;
                }

                // Check if already pending
                if (pendingConnections.containsKey(peerId)) {
                    log.debug("Retry cancelled - connection already pending", "peerId", peerId);
                    return;
                }

                log.info("Retrying connection to peer", "peerId", peerId, "previousState", state);
                metrics.incrementCounter("orchestrator.connections.retry");

                // Reset state if needed and retry
                if (state == PeerStore.PeerState.DISCONNECTED ||
                    state == PeerStore.PeerState.CHANNEL_NEGOTIATING) {
                    // Reset to allow retry
                    peerManager.markDisconnected(peerId);
                }

                initiateConnection(peer);

            } catch (Exception e) {
                log.warn("Connection retry failed", "peerId", peerId, "error", e.getMessage());
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Periodic health check for connections.
     */
    private void checkConnectionHealth() {
        if (!running.get()) {
            return;
        }

        try {
            var peers = peerManager.getAllPeers();

            log.debug("Connection health check",
                    "totalPeers", peers.size(),
                    "pendingConnections", pendingConnections.size());

            int connected = 0;
            int disconnected = 0;

            for (Peer peer : peers) {
                if (peerManager.isConnected(peer.id())) {
                    connected++;
                } else {
                    disconnected++;
                }
            }

            metrics.setGauge("orchestrator.peers.connected", connected);
            metrics.setGauge("orchestrator.peers.disconnected", disconnected);
            metrics.setGauge("orchestrator.connections.pending", pendingConnections.size());

            log.debug("Connection health status",
                    "connected", connected,
                    "disconnected", disconnected,
                    "pending", pendingConnections.size());

        } catch (Exception e) {
            log.error("Error in connection health check", e);
        }
    }

    /**
     * Cleans up peers stuck in CHANNEL_NEGOTIATING state.
     * Called after cleanupExpiredKeyExchanges() removes timed-out negotiations.
     *
     * This prevents deadlock where:
     * 1. KEY_EXCHANGE times out (>10s)
     * 2. Peer remains in CHANNEL_NEGOTIATING forever
     * 3. Cannot reconnect because state machine blocks transition
     *
     * Solution: Transition stuck peers to DISCONNECTED so they can retry.
     */
    private void cleanupStuckChannelNegotiations() {
        try {
            var allPeers = peerManager.getAllPeers();
            int transitioned = 0;

            for (var peer : allPeers) {
                String peerId = peer.id();
                PeerStore.PeerState state = peerManager.getPeerState(peerId);

                // Check if stuck in CHANNEL_NEGOTIATING without active KEY_EXCHANGE
                if (state == PeerStore.PeerState.CHANNEL_NEGOTIATING) {
                    boolean hasActiveNegotiation = secureChannelNegotiator.hasActiveNegotiation(peerId);

                    if (!hasActiveNegotiation) {
                        // No active KEY_EXCHANGE but stuck in CHANNEL_NEGOTIATING → TIMEOUT
                        log.warn("STUCK_CHANNEL_NEGOTIATING_DETECTED",
                                "peerId", peerId,
                                "currentState", "CHANNEL_NEGOTIATING",
                                "hasActiveNegotiation", false,
                                "action", "Transitioning to DISCONNECTED",
                                "canRetry", true);

                        boolean success = peerManager.markDisconnected(peerId);
                        if (success) {
                            transitioned++;
                            metrics.incrementCounter("orchestrator.channel_negotiating.timeout_cleanup");
                        }
                    }
                }
            }

            if (transitioned > 0) {
                log.info("CHANNEL_NEGOTIATING_CLEANUP_COMPLETE",
                        "transitioned", transitioned,
                        "newState", "DISCONNECTED",
                        "effect", "Peers can now retry connection");
            }

        } catch (Exception e) {
            log.error("Error in stuck channel negotiation cleanup", e);
        }
    }

    /**
     * Checks if orchestrator is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Gets pending connection count.
     */
    public int getPendingConnectionCount() {
        return pendingConnections.size();
    }

    // ========================= NAT Resolution Accessors =========================

    /**
     * Gets the NAT resolution service.
     */
    public NatResolutionService getNatResolutionService() {
        return natResolutionService;
    }

    /**
     * Gets the local NAT type (may be UNKNOWN if not yet detected).
     * This is NON-BLOCKING.
     */
    public NatType getLocalNatType() {
        return natResolutionService.getLocalNatType();
    }

    /**
     * Checks if local NAT has been detected.
     */
    public boolean isLocalNatDetected() {
        return natResolutionService.isLocalNatDetected();
    }

    /**
     * Gets the NAT resolution status for a peer.
     *
     * @param peerId the peer ID
     * @return NAT resolution status (PENDING, RESOLVING, RESOLVED, FAILED, SKIPPED)
     */
    public PeerStore.NatResolutionStatus getPeerNatStatus(String peerId) {
        return peerManager.getPeerStore().getNatStatus(peerId);
    }

    /**
     * Checks if NAT resolution is complete for a peer.
     * Complete means: RESOLVED, FAILED, or SKIPPED (not PENDING or RESOLVING).
     */
    public boolean isNatResolutionComplete(String peerId) {
        return peerManager.getPeerStore().isNatResolutionComplete(peerId);
    }
}

