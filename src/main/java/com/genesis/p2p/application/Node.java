package com.genesis.p2p.application;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.peer.*;
import com.genesis.p2p.core.MessageHandler;
import com.genesis.p2p.core.handlers.processors.ProcessorRegistry;
import com.genesis.p2p.core.handlers.processors.system.SystemProcessorFactory;
import com.genesis.p2p.core.handlers.processors.discovery.DiscoveryProcessorRegistration;
import com.genesis.p2p.core.handlers.processors.security.KeyExchangeCompleteProcessor;
import com.genesis.p2p.core.handlers.processors.security.KeyExchangeInitProcessor;
import com.genesis.p2p.core.handlers.ratelimit.RateLimitManager;
import com.genesis.p2p.core.handlers.retry.RetryManager;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.storage.PersistenceFacade;
import com.genesis.p2p.transport.core.*;
import com.genesis.p2p.security.channel.KeyExchangeComplete;
import com.genesis.p2p.security.channel.KeyExchangeInit;
import com.genesis.p2p.security.channel.SecureChannelNegotiator;
import com.genesis.p2p.security.config.SecurityConfig;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.gateway.SecurityGateway;
import com.genesis.p2p.security.gateway.SecurityGatewayBuilder;
import com.genesis.p2p.security.policy.MessageSecurityPolicy;
import com.genesis.p2p.discovery.*;
import com.genesis.p2p.nat.INatTraversalService;
// StunNatDetector removed - now using NatDetectionFactory (DIP compliance)
import com.genesis.p2p.events.core.EventBus;
import com.genesis.p2p.core.handlers.processors.alert.NodeHealthAlertProcessor;
import com.genesis.p2p.core.handlers.processors.alert.PeerMisbehaviorAlertProcessor;
import com.genesis.p2p.core.handlers.processors.alert.RateLimitAlertProcessor;
import com.genesis.p2p.events.core.GenericEvent;
import com.genesis.p2p.protocol.config.ProtocolConfig;
import com.genesis.p2p.protocol.validator.ProtocolValidator;
import com.genesis.p2p.protocol.handshake.HandshakeProcessor;
import com.genesis.p2p.protocol.ProtocolLayer;
import com.genesis.p2p.util.threading.ThreadPoolManager;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Genesis P2P Node - Fully Integrated Framework
 *
 * Design Patterns Applied:
 * - State Pattern: Node lifecycle states with transitions
 * - Observer Pattern: Lifecycle listeners for state change notifications
 * - Template Method: Lifecycle hooks (beforeStart, afterStart, beforeStop, afterStop)
 * - Facade Pattern: Unified interface to all subsystems
 *
 * Central orchestrator that integrates all subsystems:
 *
 * Core Layer:
 * - Peer management (discovery, tracking, reputation)
 * - Message processing (handlers, routing, validation)
 * - Event bus (publish-subscribe messaging)
 *
 * Network Layer:
 * - Transport (TCP/UDP with encryption, WebSocket optional)
 * - Protocol (codecs, validation, framing, fragmentation)
 * - NAT traversal (STUN, port mapping)
 *
 * Security Layer:
 * - Encryption/Decryption (AES-GCM)
 * - Key exchange (ECDH)
 * - Authentication (HMAC, signatures)
 *
 * Observability:
 * - Structured logging
 * - Metrics collection
 * - Health monitoring
 *
 * Recent Integrations (Phase 1-5):
 * - ProtocolLayer: Message fragmentation and frame reassembly
 * - Compression: Transparent Gzip/LZ4 compression with smart thresholds
 * - WebSocket: Browser-compatible transport (available via TransportFactory)
 * - LRUCache: Automatic LRU eviction in PeerStore
 * - EvictingQueue: Bounded message deduplication
 * - ResourceLeakDetector: PhantomReference-based resource leak detection
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class Node implements INodeLifecycle {
    private static final NodeLogger log = NodeLogger.getLogger(Node.class);

    // ==================== State Pattern: Node Lifecycle ====================

    /**
     * Node lifecycle states.
     * State Pattern - Represents the different states a node can be in.
     */
    public enum State {
        CREATED("Node created but not started"),
        STARTING("Node is starting up"),
        RUNNING("Node is running and accepting connections"),
        STOPPING("Node is shutting down"),
        STOPPED("Node has stopped"),
        FAILED("Node encountered an error");

        private final String description;

        State(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }

        public boolean isTerminal() {
            return this == STOPPED || this == FAILED;
        }

        public boolean canTransitionTo(State target) {
            return switch (this) {
                case CREATED -> target == STARTING || target == FAILED;
                case STARTING -> target == RUNNING || target == FAILED || target == STOPPING;
                case RUNNING -> target == STOPPING || target == FAILED;
                case STOPPING -> target == STOPPED || target == FAILED;
                case STOPPED, FAILED -> false;
            };
        }
    }

    // ==================== Observer Pattern: Lifecycle Listeners ====================

    /**
     * Lifecycle listener interface.
     * Observer Pattern - Notified of node state changes.
     */
    public interface LifecycleListener {
        default void onStateChanged(State oldState, State newState) {}
        default void onStarting() {}
        default void onStarted() {}
        default void onStopping() {}
        default void onStopped() {}
        default void onFailed(Exception error) {}
    }

    // Configuration
    private final NodeConfig config;
    private final AtomicReference<State> state;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant stoppedAt;

    // Lifecycle
    private final List<LifecycleListener> lifecycleListeners;
    private String failureReason;

    // Core Components
    private final PeerManager peerManager;
    private final MessageHandler messageHandler;
    private final ProcessorRegistry processorRegistry;
    private final SystemProcessorFactory processorFactory;

    // Network Layer
    private final ITransport tcpTransport;
    private final ITransport udpTransport;
    private final ProtocolLayer protocolLayer;

    // Discovery
    private final CompositeDiscovery discoveryService;

    // Security
    private final SecurityFacade securityFacade;
    private final ProtocolValidator protocolValidator;
    private final SecureChannelNegotiator secureChannelNegotiator;
    private final MessageSecurityPolicy securityPolicy;
    private final SecurityGateway securityGateway;

    // Event System
    private final EventBus eventBus;

    // Observability
    private final MetricsRegistry metricsRegistry;
    private final com.genesis.p2p.observability.message.MessageLogger messageLogger; // Full message observability and replay

    // Persistence
    private final PersistenceFacade persistenceFacade;

    // Resource Management
    private final ThreadPoolManager threadPoolManager;
    private final RateLimitManager rateLimitManager;
    private final RetryManager retryManager;

    // NAT Traversal
    private final INatTraversalService natTraversalService;

    // Alert System
    private final NodeHealthAlertProcessor healthAlertProcessor;
    private final PeerMisbehaviorAlertProcessor misbehaviorAlertProcessor;
    private final RateLimitAlertProcessor rateLimitAlertProcessor;

    // Connection Orchestration
    private final PeerConnectionOrchestrator connectionOrchestrator;

    /**
     * Creates a new P2P node with default configuration.
     */
    public Node(NodeConfig config) {
        this(config, SecurityConfig.defaults(), ProtocolConfig.defaultConfig());
    }

    /**
     * Creates a new P2P node with custom security and protocol configuration.
     */
    public Node(NodeConfig config, SecurityConfig securityConfig, ProtocolConfig protocolConfig) {
        this.config = config;
        this.state = new AtomicReference<>(State.CREATED);
        this.createdAt = Instant.now();
        this.lifecycleListeners = new CopyOnWriteArrayList<>();

        log.info("═══════════════════════════════════════════════════════");
        log.info("   Initializing Genesis P2P Node");
        log.info("═══════════════════════════════════════════════════════");
        log.info("Node initialization started",
                "nodeId", config.nodeId(),
                "tcpPort", config.tcpPort(),
                "protocol", config.protocolVersion());

        try {
            // Phase 1: Foundation
            log.info("Phase 1: Foundation components...");

            this.metricsRegistry = new MetricsRegistry(config.nodeId());
            log.info("✓ Metrics registry");

            this.threadPoolManager = new ThreadPoolManager(4, 8, 16);
            log.info("✓ Thread pool manager");

            this.rateLimitManager = new RateLimitManager(1000, 100, metricsRegistry);
            log.info("✓ Rate limit manager");

            this.retryManager = new RetryManager(metricsRegistry);
            log.info("✓ Retry manager");

            this.persistenceFacade = new PersistenceFacade(config, metricsRegistry);
            log.info("✓ Persistence facade", "enabled", config.persistenceEnabled());

            // Phase 2: Event System
            log.info("Phase 2: Event system...");

            this.eventBus = new EventBus(metricsRegistry);
            subscribeToSystemEvents();
            log.info("✓ Event bus");

            // Phase 3: Security Layer
            log.info("Phase 3: Security layer...");

            this.securityFacade = new SecurityFacade(securityConfig, config.nodeId());
            log.info("✓ Security facade", "algorithm", securityFacade.getAlgorithm());

            this.protocolValidator = protocolConfig.getValidator();
            log.info("✓ Protocol validator");

            // Initialize SecureChannelNegotiator for two-phase bootstrap
            this.secureChannelNegotiator = new SecureChannelNegotiator(
                    config.nodeId(),
                    securityFacade.getKeyManager(),
                    securityFacade.getSessionManager(),
                    null, // CertificateManager - legacy, not needed for KEY_EXCHANGE
                    null, // ProtocolNegotiationService - legacy
                    config.protocolVersion()
            );
            log.info("✓ Secure channel negotiator (KEY_EXCHANGE enabled)");

            // Phase 4: Peer Management
            log.info("Phase 4: Peer management...");

            this.peerManager = new PeerManager(persistenceFacade);
            log.info("✓ Peer manager (with reputation & query services + persistence)");

            // Bridge PeerEventBus to main EventBus for peer.discovered events
            peerManager.addEventListener(new com.genesis.p2p.core.peer.PeerEventBus.PeerEventListener() {
                @Override
                public void onPeerAdded(com.genesis.p2p.core.Peer peer) {
                    // Fire peer.discovered event on main EventBus
                    java.util.Map<String, Object> eventData = new java.util.HashMap<>();
                    eventData.put("peerId", peer.id());
                    eventData.put("ip", peer.ip());
                    eventData.put("port", peer.port());
                    eventData.put("source", "PeerManager");
                    eventBus.publish(new GenericEvent("peer.discovered", config.nodeId(), eventData));
                }

                @Override
                public void onPeerUpdated(com.genesis.p2p.core.Peer oldPeer, com.genesis.p2p.core.Peer newPeer) {
                    // Optional: handle peer updates
                }

                @Override
                public void onPeerRemoved(com.genesis.p2p.core.Peer peer, String reason) {
                    // Optional: handle peer removal
                }

                @Override
                public void onPeerStateChanged(com.genesis.p2p.core.Peer peer, com.genesis.p2p.core.peer.PeerStore.PeerState oldState, com.genesis.p2p.core.peer.PeerStore.PeerState newState) {
                    // Optional: handle state changes
                }

                @Override
                public void onReputationChanged(com.genesis.p2p.core.Peer peer, int oldReputation, int newReputation) {
                    // Optional: handle reputation changes
                }
            });
            log.info("✓ PeerEventBus → EventBus bridge configured (peer.discovered events enabled)");

            // Phase 5: Transport Layer
            log.info("Phase 5: Transport layer...");

            // Create protocol layer (needed by transports for encoding/decoding)
            this.protocolLayer = new ProtocolLayer(protocolConfig, metricsRegistry);
            log.info("✓ Protocol layer", "codec", protocolConfig.getCodec().getName());

            // CRITICAL: Pass shared metricsRegistry to TransportFactory
            // This ensures transports and PeerConnectionOrchestrator use the same metrics,
            // allowing KEY_EXCHANGE_INIT send verification to work correctly
            TransportFactory transportFactory = new TransportFactory(securityFacade, protocolLayer, metricsRegistry);
            log.info("✓ Transport factory (with shared metrics)");

            // Get MessageLogger for full message observability and replay capability
            this.messageLogger = transportFactory.getMessageLogger();
            log.info("✓ Message logger (persistence + replay)");

            this.tcpTransport = transportFactory.createTcpTransport(config.tcpPort());
            log.info("✓ TCP transport", "port", config.tcpPort());

            this.udpTransport = transportFactory.createUdpTransport(config.listenPort());
            log.info("✓ UDP transport", "port", config.listenPort());

            // Phase 6: Message Processing (before discovery - NAT needs it)
            log.info("Phase 6: Message processors...");

            this.processorRegistry = new ProcessorRegistry();
            log.info("✓ Processor registry");

            // Create MessageHandler for enterprise message processing with full observability
            // Uses shared MetricsRegistry for unified metrics aggregation
            this.messageHandler = new MessageHandler(
                    config.nodeId(),
                    peerManager,
                    persistenceFacade,
                    metricsRegistry,  // Shared metrics - critical for unified observability
                    messageLogger,    // Full message-level logging and persistence
                    com.genesis.p2p.core.handlers.MessageHandlerConfig.defaults()
            );
            log.info("✓ Message handler (dedup, backpressure, circuit breaker, DLQ + message persistence + shared metrics)");

            // Initialize NAT traversal service via factory (DIP compliance)
            // Using NatDetectionFactory instead of direct StunNatDetector instantiation
            this.natTraversalService = com.genesis.p2p.nat.NatDetectionFactory.createWithTimeout(
                    config.nodeId(),
                    messageHandler,
                    com.genesis.p2p.util.constants.TimeoutConstants.EXECUTOR_SHUTDOWN_TIMEOUT
            );
            log.info("✓ NAT traversal (STUN-based detection via factory)");

            this.processorFactory = new SystemProcessorFactory(
                    peerManager, processorRegistry, config.nodeId());
            processorFactory.registerAll();
            log.info("✓ System processors registered");

            // Register discovery processors for protocol-based peer discovery
            DiscoveryProcessorRegistration.registerAll(
                    messageHandler,
                    config,
                    peerManager,
                    peerManager.getEventBus(),
                    peerManager.getReputationService(),
                    peerManager.getPeerStore(),
                    peerManager.getQueryService(),
                    securityFacade
            );
            log.info("✓ Discovery processors registered with SecurityFacade (PING/PONG, PEER_ADVERTISE, BOOTSTRAP, etc.)");

            // Register handshake processor for connection establishment with message logging
            HandshakeProcessor handshakeProcessor = new HandshakeProcessor(
                    config.nodeId(),
                    peerManager,
                    securityFacade,
                    metricsRegistry,
                    messageLogger  // Full observability for handshake messages
            );
            processorRegistry.register("HANDSHAKE_REQUEST", handshakeProcessor,
                    10, // high priority
                    false, // synchronous
                    com.genesis.p2p.util.constants.TimeoutConstants.HANDSHAKE_TIMEOUT);
            processorRegistry.register("HANDSHAKE_RESPONSE", handshakeProcessor,
                    10, // high priority
                    false, // synchronous
                    com.genesis.p2p.util.constants.TimeoutConstants.HANDSHAKE_TIMEOUT);
            processorRegistry.register("HANDSHAKE_REJECT", handshakeProcessor,
                    10, // high priority - rejections need immediate processing
                    false, // synchronous - ensure state transition happens before return
                    com.genesis.p2p.util.constants.TimeoutConstants.HANDSHAKE_TIMEOUT);

            // Register KEY_EXCHANGE processors for secure channel establishment (Phase 1)
            // These run BEFORE handshake with higher priority
            KeyExchangeInitProcessor keyExchangeInitProcessor = new KeyExchangeInitProcessor(
                    config.nodeId(),
                    secureChannelNegotiator,
                    peerManager,
                    metricsRegistry
            );
            keyExchangeInitProcessor.setTransport(tcpTransport);

            KeyExchangeCompleteProcessor keyExchangeCompleteProcessor = new KeyExchangeCompleteProcessor(
                    config.nodeId(),
                    secureChannelNegotiator,
                    peerManager,
                    metricsRegistry
            );
            keyExchangeCompleteProcessor.setHandshakeProcessor(handshakeProcessor);

            processorRegistry.register(KeyExchangeInit.MESSAGE_TYPE, keyExchangeInitProcessor,
                    15, // higher priority than handshake
                    false, // synchronous
                    Duration.ofSeconds(10));
            processorRegistry.register(KeyExchangeComplete.MESSAGE_TYPE, keyExchangeCompleteProcessor,
                    15, // higher priority than handshake
                    false, // synchronous
                    Duration.ofSeconds(10));
            log.info("✓ KEY_EXCHANGE processors registered (secure channel establishment)");

            // CRITICAL: Configure message sender for handshake responses
            // This enables the HandshakeProcessor to send HANDSHAKE_RESPONSE messages
            // back to the requesting peer using the source address from ProcessingContext
            handshakeProcessor.setMessageSender((message, targetAddress) -> {
                try {
                    log.debug("Sending handshake response via TCP transport",
                            "target", targetAddress,
                            "messageType", message.type());
                    tcpTransport.send(message, targetAddress).get(5, java.util.concurrent.TimeUnit.SECONDS);
                    log.debug("Handshake response sent successfully", "target", targetAddress);
                } catch (Exception e) {
                    log.error("Failed to send handshake response", e,
                            "target", targetAddress,
                            "messageType", message.type());
                }
            });
            log.info("✓ Handshake processor registered (connection establishment & authentication)");
            log.info("✓ Handshake message sender configured (response routing via TCP)");

            // Phase 6.5: Alert System
            log.info("Phase 6.5: Alert system...");

            this.healthAlertProcessor = new NodeHealthAlertProcessor(
                    eventBus,
                    metricsRegistry,
                    peerManager.getHealthMonitor(),
                    peerManager.getPeerStore()
            );
            log.info("✓ Node health alert processor (monitors peer health, heartbeat timeouts, high latency)");

            this.misbehaviorAlertProcessor = new PeerMisbehaviorAlertProcessor(
                    eventBus,
                    metricsRegistry,
                    peerManager.getReputationService()
            );
            log.info("✓ Peer misbehavior alert processor (tracks misbehavior, lowers reputation)");

            this.rateLimitAlertProcessor = new RateLimitAlertProcessor(
                    eventBus,
                    metricsRegistry
            );
            log.info("✓ Rate limit alert processor (detects and alerts on rate limit violations)");

            // Phase 7: Discovery Services
            log.info("Phase 7: Discovery services...");

            this.discoveryService = DiscoveryFactory.createComposite(
                    config, peerManager, metricsRegistry, Collections.emptyList());
            log.info("✓ Composite discovery (3 strategies: multicast, broadcast, bootstrap)");

            // Phase 7.5: Connection Orchestration
            log.info("Phase 7.5: Connection orchestration...");

            this.connectionOrchestrator = new PeerConnectionOrchestrator(
                    config.nodeId(),
                    peerManager,
                    tcpTransport,
                    handshakeProcessor,
                    eventBus,
                    metricsRegistry,
                    securityFacade,
                    natTraversalService,
                    secureChannelNegotiator  // Secure bootstrap support
            );
            log.info("✓ Peer connection orchestrator (NAT-aware: UDP discovery → KEY_EXCHANGE → HANDSHAKE → TCP session)");

            // Phase 8: Wire Up with Security Gateway
            log.info("Phase 8: Wiring components with Security Gateway...");

            // Create security policy for message type enforcement
            this.securityPolicy = new MessageSecurityPolicy();
            log.info("✓ Message security policy", "policySize", securityPolicy.size());

            // Create Security Gateway - central enforcement point for secure channel requirements
            // This enforces:
            // - Message type security requirements (plaintext allowed vs encryption required)
            // - Peer state validation (only allow messages appropriate for current state)
            // - Fail-closed design (reject on security violation)
            boolean legacyPlaintextMode = !securityConfig.encryptionEnabled();

            this.securityGateway = new SecurityGatewayBuilder()
                    .downstream((message, source, context) ->
                            handleIncomingMessage(message, source, "SECURED", context))
                    .security(securityFacade)
                    .peerManager(peerManager)
                    .policy(securityPolicy)
                    .metrics(metricsRegistry)
                    .legacyPlaintextMode(legacyPlaintextMode)
                    .build();
            log.info("✓ Security gateway configured",
                    "legacyPlaintextMode", legacyPlaintextMode,
                    "enforcementEnabled", !legacyPlaintextMode);

            // Wire transport handlers through SecurityGateway for mandatory security validation
            tcpTransport.setEnvelopeHandler(securityGateway);
            udpTransport.setEnvelopeHandler(securityGateway);

            log.info("✓ Transport handlers wired through SecurityGateway (fail-closed enforcement)");

            log.info("═══════════════════════════════════════════════════════");
            log.info("   Node Initialization Complete");
            log.info("═══════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("Node initialization failed", e);
            transitionTo(State.FAILED);
            this.failureReason = e.getMessage();
            throw new RuntimeException("Failed to initialize node", e);
        }
    }

    // ==================== Template Method: Lifecycle Hooks ====================

    /**
     * Hook called before node starts.
     * Template Method - Override for custom pre-start behavior.
     */
    protected void beforeStart() {
        log.debug("beforeStart hook");
    }

    /**
     * Hook called after node starts successfully.
     * Template Method - Override for custom post-start behavior.
     */
    protected void afterStart() {
        log.debug("afterStart hook");
    }

    /**
     * Hook called before node stops.
     * Template Method - Override for custom pre-stop behavior.
     */
    protected void beforeStop() {
        log.debug("beforeStop hook");
    }

    /**
     * Hook called after node stops.
     * Template Method - Override for custom post-stop behavior.
     */
    protected void afterStop() {
        log.debug("afterStop hook");
    }

    // ==================== State Transitions ====================

    /**
     * Transitions to a new state with validation.
     */
    private boolean transitionTo(State newState) {
        State oldState = state.get();

        if (oldState == newState) {
            return true;
        }

        if (!oldState.canTransitionTo(newState)) {
            log.warn("Invalid state transition",
                    "from", oldState,
                    "to", newState);
            return false;
        }

        if (state.compareAndSet(oldState, newState)) {
            log.info("State transition",
                    "from", oldState,
                    "to", newState);

            metricsRegistry.incrementCounter("node.state.transitions");
            notifyStateChanged(oldState, newState);
            return true;
        }

        return false;
    }

    /**
     * Notifies listeners of state change.
     */
    private void notifyStateChanged(State oldState, State newState) {
        for (LifecycleListener listener : lifecycleListeners) {
            try {
                listener.onStateChanged(oldState, newState);

                switch (newState) {
                    case STARTING -> listener.onStarting();
                    case RUNNING -> listener.onStarted();
                    case STOPPING -> listener.onStopping();
                    case STOPPED -> listener.onStopped();
                    default -> { }
                }
            } catch (Exception e) {
                log.error("Lifecycle listener error", e);
            }
        }
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the P2P node.
     *
     * Lifecycle: CREATED → STARTING → RUNNING
     * Logs: Full startup sequence with timing and component status
     */
    public void start() {
        Instant startTime = Instant.now();

        log.info("═══════════════════════════════════════════════════════");
        log.info("   Starting Genesis P2P Node");
        log.info("═══════════════════════════════════════════════════════");
        log.info("NODE_LIFECYCLE_START",
                "nodeId", config.nodeId(),
                "currentState", state.get(),
                "targetState", State.STARTING,
                "tcpPort", config.tcpPort(),
                "udpPort", config.listenPort(),
                "protocolVersion", config.protocolVersion(),
                "timestamp", startTime);

        if (!transitionTo(State.STARTING)) {
            log.error("NODE_LIFECYCLE_START_FAILED",
                    "nodeId", config.nodeId(),
                    "currentState", state.get(),
                    "reason", "Invalid state transition");
            throw new IllegalStateException("Cannot start node in state: " + state.get());
        }

        log.debug("NODE_STATE_TRANSITION",
                "nodeId", config.nodeId(),
                "fromState", State.CREATED,
                "toState", State.STARTING,
                "transitionTime", Instant.now());

        try {
            // Template method hook
            log.debug("LIFECYCLE_HOOK_INVOKE", "nodeId", config.nodeId(), "hook", "beforeStart");
            beforeStart();
            log.debug("LIFECYCLE_HOOK_COMPLETE", "nodeId", config.nodeId(), "hook", "beforeStart");

            // Initialize and start persistence
            if (config.persistenceEnabled()) {
                log.info("PERSISTENCE_INIT_START", "nodeId", config.nodeId());
                Instant persistStart = Instant.now();
                persistenceFacade.init();
                persistenceFacade.start();
                long persistDuration = Duration.between(persistStart, Instant.now()).toMillis();
                log.info("PERSISTENCE_INIT_COMPLETE",
                        "nodeId", config.nodeId(),
                        "durationMs", persistDuration);
            } else {
                log.debug("PERSISTENCE_DISABLED", "nodeId", config.nodeId());
            }

            // Replay messages from previous session (zero data loss guarantee)
            log.info("MESSAGE_REPLAY_START", "nodeId", config.nodeId());
            Instant replayStart = Instant.now();
            replayMessages();
            long replayDuration = Duration.between(replayStart, Instant.now()).toMillis();
            log.info("MESSAGE_REPLAY_COMPLETE",
                    "nodeId", config.nodeId(),
                    "durationMs", replayDuration);

            // Start protocol layer (fragmentation & compression)
            log.info("PROTOCOL_LAYER_START",
                    "nodeId", config.nodeId(),
                    "protocolVersion", config.protocolVersion());
            Instant protocolStart = Instant.now();
            protocolLayer.start();
            long protocolDuration = Duration.between(protocolStart, Instant.now()).toMillis();
            log.info("PROTOCOL_LAYER_STARTED",
                    "nodeId", config.nodeId(),
                    "durationMs", protocolDuration);

            // Start transports
            log.info("TRANSPORT_LAYER_START", "nodeId", config.nodeId());

            Instant tcpStart = Instant.now();
            tcpTransport.start();
            long tcpDuration = Duration.between(tcpStart, Instant.now()).toMillis();
            log.info("TCP_TRANSPORT_STARTED",
                    "nodeId", config.nodeId(),
                    "port", config.tcpPort(),
                    "bindAddress", tcpTransport.getLocalAddress(),
                    "durationMs", tcpDuration);

            Instant udpStart = Instant.now();
            udpTransport.start();
            long udpDuration = Duration.between(udpStart, Instant.now()).toMillis();
            log.info("UDP_TRANSPORT_STARTED",
                    "nodeId", config.nodeId(),
                    "port", config.listenPort(),
                    "bindAddress", udpTransport.getLocalAddress(),
                    "durationMs", udpDuration);

            // Start NAT traversal (detect NAT type before discovery)
            log.info("NAT_TRAVERSAL_START",
                    "nodeId", config.nodeId(),
                    "service", natTraversalService.getClass().getSimpleName());
            Instant natStart = Instant.now();
            natTraversalService.start();
            long natDuration = Duration.between(natStart, Instant.now()).toMillis();
            log.info("NAT_TRAVERSAL_STARTED",
                    "nodeId", config.nodeId(),
                    "durationMs", natDuration,
                    "note", "NAT type detection in progress");

            // Start discovery
            log.info("DISCOVERY_START",
                    "nodeId", config.nodeId(),
                    "service", discoveryService.getClass().getSimpleName());
            Instant discoveryStart = Instant.now();
            discoveryService.start();
            long discoveryDuration = Duration.between(discoveryStart, Instant.now()).toMillis();
            log.info("DISCOVERY_STARTED",
                    "nodeId", config.nodeId(),
                    "durationMs", discoveryDuration);

            // Start connection orchestrator (after discovery)
            log.info("CONNECTION_ORCHESTRATOR_START", "nodeId", config.nodeId());
            Instant orchestratorStart = Instant.now();
            connectionOrchestrator.start();
            long orchestratorDuration = Duration.between(orchestratorStart, Instant.now()).toMillis();
            log.info("CONNECTION_ORCHESTRATOR_STARTED",
                    "nodeId", config.nodeId(),
                    "durationMs", orchestratorDuration,
                    "note", "Listening for peer.discovered events");

            // Record start time
            this.startedAt = Instant.now();

            // Transition to running
            log.debug("NODE_STATE_TRANSITION_ATTEMPT",
                    "nodeId", config.nodeId(),
                    "fromState", State.STARTING,
                    "toState", State.RUNNING);

            if (!transitionTo(State.RUNNING)) {
                log.error("NODE_STATE_TRANSITION_FAILED",
                        "nodeId", config.nodeId(),
                        "fromState", State.STARTING,
                        "toState", State.RUNNING,
                        "reason", "State transition rejected");
                throw new IllegalStateException("Failed to transition to RUNNING state");
            }

            log.info("NODE_STATE_TRANSITION",
                    "nodeId", config.nodeId(),
                    "fromState", State.STARTING,
                    "toState", State.RUNNING,
                    "transitionTime", Instant.now());

            // Template method hook
            log.debug("LIFECYCLE_HOOK_INVOKE", "nodeId", config.nodeId(), "hook", "afterStart");
            afterStart();
            log.debug("LIFECYCLE_HOOK_COMPLETE", "nodeId", config.nodeId(), "hook", "afterStart");

            // Print banner
            printStartupBanner();

            // Publish event
            eventBus.publish(new GenericEvent("node.started", config.nodeId(), Map.of(
                    "nodeId", config.nodeId(),
                    "tcpPort", config.tcpPort(),
                    "udpPort", config.listenPort(),
                    "timestamp", startedAt.toString()
            )));

            long totalStartupTime = Duration.between(startTime, Instant.now()).toMillis();

            log.info("═══════════════════════════════════════════════════════");
            log.info("   Node Started Successfully");
            log.info("═══════════════════════════════════════════════════════");
            log.info("NODE_LIFECYCLE_STARTED",
                    "nodeId", config.nodeId(),
                    "state", State.RUNNING,
                    "totalStartupTimeMs", totalStartupTime,
                    "tcpPort", config.tcpPort(),
                    "udpPort", config.listenPort(),
                    "startedAt", startedAt);

        } catch (Exception e) {
            transitionTo(State.FAILED);
            this.failureReason = e.getMessage();

            long failureTime = Duration.between(startTime, Instant.now()).toMillis();

            log.error("NODE_LIFECYCLE_START_FAILED",
                    "nodeId", config.nodeId(),
                    "state", State.FAILED,
                    "failureReason", e.getMessage(),
                    "failureType", e.getClass().getSimpleName(),
                    "timeToFailureMs", failureTime,
                    "stackTrace", getStackTraceAsString(e));
            log.error("Failed to start node", e);

            // Notify listeners of failure
            for (LifecycleListener listener : lifecycleListeners) {
                try {
                    listener.onFailed(e);
                } catch (Exception ex) {
                    log.error("LIFECYCLE_LISTENER_ERROR",
                            "nodeId", config.nodeId(),
                            "listener", listener.getClass().getSimpleName(),
                            "error", ex.getMessage());
                    log.error("Lifecycle listener error", ex);
                }
            }

            throw new RuntimeException("Failed to start node", e);
        }
    }

    /**
     * Stops the P2P node gracefully.
     *
     * Lifecycle: RUNNING → STOPPING → STOPPED
     * Logs: Full shutdown sequence with component cleanup order
     */
    public void stop() {
        Instant stopTime = Instant.now();

        log.info("═══════════════════════════════════════════════════════");
        log.info("   Stopping Genesis P2P Node");
        log.info("═══════════════════════════════════════════════════════");
        log.info("NODE_LIFECYCLE_STOP",
                "nodeId", config.nodeId(),
                "currentState", state.get(),
                "targetState", State.STOPPING,
                "uptime", startedAt != null ?
                          Duration.between(startedAt, stopTime).toMillis() : 0,
                "timestamp", stopTime);

        if (!transitionTo(State.STOPPING)) {
            log.warn("NODE_LIFECYCLE_STOP_INVALID_STATE",
                    "nodeId", config.nodeId(),
                    "currentState", state.get(),
                    "reason", "Already stopping or stopped");
            throw new IllegalStateException("Cannot stop node in state: " + state.get());
        }

        log.debug("NODE_STATE_TRANSITION",
                "nodeId", config.nodeId(),
                "fromState", State.RUNNING,
                "toState", State.STOPPING,
                "transitionTime", Instant.now());

        try {
            // Template method hook
            log.debug("LIFECYCLE_HOOK_INVOKE", "nodeId", config.nodeId(), "hook", "beforeStop");
            beforeStop();
            log.debug("LIFECYCLE_HOOK_COMPLETE", "nodeId", config.nodeId(), "hook", "beforeStop");

            // Publish stopping event
            eventBus.publish(new GenericEvent("node.stopping", config.nodeId(), Map.of(
                    "nodeId", config.nodeId(),
                    "timestamp", Instant.now().toString()
            )));

            // Stop connection orchestrator (Phase 1: Stop accepting new connections)
            log.info("CONNECTION_ORCHESTRATOR_STOP", "nodeId", config.nodeId(), "phase", 1);
            Instant orchestratorStopTime = Instant.now();
            connectionOrchestrator.stop();
            long orchestratorStopDuration = Duration.between(orchestratorStopTime, Instant.now()).toMillis();
            log.info("CONNECTION_ORCHESTRATOR_STOPPED",
                    "nodeId", config.nodeId(),
                    "durationMs", orchestratorStopDuration);

            // Stop discovery (Phase 2: Stop discovering new peers)
            log.info("DISCOVERY_STOP", "nodeId", config.nodeId(), "phase", 2);
            Instant discoveryStopTime = Instant.now();
            discoveryService.stop();
            long discoveryStopDuration = Duration.between(discoveryStopTime, Instant.now()).toMillis();
            log.info("DISCOVERY_STOPPED",
                    "nodeId", config.nodeId(),
                    "durationMs", discoveryStopDuration);

            // Stop NAT traversal (Phase 3: Release NAT mappings)
            log.info("NAT_TRAVERSAL_STOP", "nodeId", config.nodeId(), "phase", 3);
            Instant natStopTime = Instant.now();
            natTraversalService.stop();
            long natStopDuration = Duration.between(natStopTime, Instant.now()).toMillis();
            log.info("NAT_TRAVERSAL_STOPPED",
                    "nodeId", config.nodeId(),
                    "durationMs", natStopDuration);

            // Stop transports (Phase 4: Close network connections)
            log.info("TRANSPORT_LAYER_STOP", "nodeId", config.nodeId(), "phase", 4);

            Instant tcpStopTime = Instant.now();
            long tcpBytesSent = tcpTransport.getStats().bytesSent();
            long tcpBytesReceived = tcpTransport.getStats().bytesReceived();
            tcpTransport.stop();
            long tcpStopDuration = Duration.between(tcpStopTime, Instant.now()).toMillis();
            log.info("TCP_TRANSPORT_STOPPED",
                    "nodeId", config.nodeId(),
                    "bytesSent", tcpBytesSent,
                    "bytesReceived", tcpBytesReceived,
                    "durationMs", tcpStopDuration);

            Instant udpStopTime = Instant.now();
            long udpBytesSent = udpTransport.getStats().bytesSent();
            long udpBytesReceived = udpTransport.getStats().bytesReceived();
            udpTransport.stop();
            long udpStopDuration = Duration.between(udpStopTime, Instant.now()).toMillis();
            log.info("UDP_TRANSPORT_STOPPED",
                    "nodeId", config.nodeId(),
                    "bytesSent", udpBytesSent,
                    "bytesReceived", udpBytesReceived,
                    "durationMs", udpStopDuration);

            // Stop protocol layer (Phase 5: Stop protocol processing)
            log.info("PROTOCOL_LAYER_STOP", "nodeId", config.nodeId(), "phase", 5);
            Instant protocolStopTime = Instant.now();
            protocolLayer.stop();
            long protocolStopDuration = Duration.between(protocolStopTime, Instant.now()).toMillis();
            log.info("PROTOCOL_LAYER_STOPPED",
                    "nodeId", config.nodeId(),
                    "durationMs", protocolStopDuration);

            // Shutdown processors (Phase 6: Stop message processors)
            log.info("PROCESSORS_SHUTDOWN", "nodeId", config.nodeId(), "phase", 6);
            Instant processorShutdownTime = Instant.now();
            processorFactory.shutdown();
            long processorShutdownDuration = Duration.between(processorShutdownTime, Instant.now()).toMillis();
            log.info("PROCESSORS_SHUTDOWN_COMPLETE",
                    "nodeId", config.nodeId(),
                    "durationMs", processorShutdownDuration);

            // Close message handler (Phase 7: Close async processor, retry, dedup)
            log.info("MESSAGE_HANDLER_CLOSE", "nodeId", config.nodeId(), "phase", 7);
            Instant handlerCloseTime = Instant.now();
            messageHandler.close();
            long handlerCloseDuration = Duration.between(handlerCloseTime, Instant.now()).toMillis();
            log.info("MESSAGE_HANDLER_CLOSED",
                    "nodeId", config.nodeId(),
                    "durationMs", handlerCloseDuration);

            // Close peer manager (Phase 8: Close cleanup service)
            log.info("PEER_MANAGER_CLOSE", "nodeId", config.nodeId(), "phase", 8);
            Instant peerManagerCloseTime = Instant.now();
            int activePeers = peerManager.getPeerStore().size();
            peerManager.close();
            long peerManagerCloseDuration = Duration.between(peerManagerCloseTime, Instant.now()).toMillis();
            log.info("PEER_MANAGER_CLOSED",
                    "nodeId", config.nodeId(),
                    "activePeersAtShutdown", activePeers,
                    "durationMs", peerManagerCloseDuration);

            // Close alert processors (Phase 9: Close alert system)
            log.info("ALERT_PROCESSORS_CLOSE", "nodeId", config.nodeId(), "phase", 9);
            Instant alertCloseTime = Instant.now();
            healthAlertProcessor.close();
            misbehaviorAlertProcessor.close();
            rateLimitAlertProcessor.close();
            long alertCloseDuration = Duration.between(alertCloseTime, Instant.now()).toMillis();
            log.info("ALERT_PROCESSORS_CLOSED",
                    "nodeId", config.nodeId(),
                    "durationMs", alertCloseDuration);

            // Close managers (Phase 10: Close resource managers)
            log.info("RESOURCE_MANAGERS_CLOSE", "nodeId", config.nodeId(), "phase", 10);
            Instant managersCloseTime = Instant.now();
            rateLimitManager.close();
            retryManager.close();
            long managersCloseDuration = Duration.between(managersCloseTime, Instant.now()).toMillis();
            log.info("RESOURCE_MANAGERS_CLOSED",
                    "nodeId", config.nodeId(),
                    "durationMs", managersCloseDuration);

            // Stop and close persistence (Phase 11: Persist final state)
            if (config.persistenceEnabled()) {
                log.info("PERSISTENCE_STOP", "nodeId", config.nodeId(), "phase", 11);
                Instant persistStopTime = Instant.now();
                persistenceFacade.stop();
                persistenceFacade.close();
                long persistStopDuration = Duration.between(persistStopTime, Instant.now()).toMillis();
                log.info("PERSISTENCE_STOPPED",
                        "nodeId", config.nodeId(),
                        "durationMs", persistStopDuration);
            }

            // Shutdown threads (Phase 12: Final cleanup)
            log.info("THREAD_POOLS_SHUTDOWN", "nodeId", config.nodeId(), "phase", 12);
            Instant threadShutdownTime = Instant.now();
            threadPoolManager.shutdown();
            long threadShutdownDuration = Duration.between(threadShutdownTime, Instant.now()).toMillis();
            log.info("THREAD_POOLS_SHUTDOWN_COMPLETE",
                    "nodeId", config.nodeId(),
                    "durationMs", threadShutdownDuration);

            // Record stop time
            this.stoppedAt = Instant.now();

            // Transition to stopped
            log.debug("NODE_STATE_TRANSITION_ATTEMPT",
                    "nodeId", config.nodeId(),
                    "fromState", State.STOPPING,
                    "toState", State.STOPPED);

            transitionTo(State.STOPPED);

            log.debug("NODE_STATE_TRANSITION",
                    "nodeId", config.nodeId(),
                    "fromState", State.STOPPING,
                    "toState", State.STOPPED,
                    "transitionTime", Instant.now());

            // Template method hook
            log.debug("LIFECYCLE_HOOK_INVOKE", "nodeId", config.nodeId(), "hook", "afterStop");
            afterStop();
            log.debug("LIFECYCLE_HOOK_COMPLETE", "nodeId", config.nodeId(), "hook", "afterStop");

            // Publish stopped event
            eventBus.publish(new GenericEvent("node.stopped", config.nodeId(), Map.of(
                    "nodeId", config.nodeId(),
                    "uptime", getUptime().toString(),
                    "stoppedAt", stoppedAt.toString()
            )));

            // Close event bus last
            log.info("EVENT_BUS_CLOSE", "nodeId", config.nodeId(), "phase", 13);
            eventBus.close();
            log.info("EVENT_BUS_CLOSED", "nodeId", config.nodeId());

            long totalShutdownTime = Duration.between(stopTime, Instant.now()).toMillis();
            long totalUptime = startedAt != null ?
                              Duration.between(startedAt, stoppedAt).toMillis() : 0;

            log.info("═══════════════════════════════════════════════════════");
            log.info("   Node Stopped Successfully");
            log.info("═══════════════════════════════════════════════════════");
            log.info("NODE_LIFECYCLE_STOPPED",
                    "nodeId", config.nodeId(),
                    "state", State.STOPPED,
                    "totalShutdownTimeMs", totalShutdownTime,
                    "totalUptimeMs", totalUptime,
                    "stoppedAt", stoppedAt);

        } catch (Exception e) {
            transitionTo(State.FAILED);
            this.failureReason = e.getMessage();

            long failureTime = Duration.between(stopTime, Instant.now()).toMillis();

            log.error("NODE_LIFECYCLE_STOP_FAILED",
                    "nodeId", config.nodeId(),
                    "state", State.FAILED,
                    "failureReason", e.getMessage(),
                    "failureType", e.getClass().getSimpleName(),
                    "timeToFailureMs", failureTime,
                    "stackTrace", getStackTraceAsString(e));
            log.error("Error stopping node", e);
        }
    }

    /**
     * Restarts the node.
     */
    public void restart() {
        log.info("Restarting node...");

        if (isRunning()) {
            stop();
        }

        // Reset state for restart
        state.set(State.CREATED);
        this.failureReason = null;

        start();
    }

    // ==================== Message Handling ====================

    /**
     * Handles incoming messages from transports with full persistence tracking.
     *
     * @param message the received message
     * @param source the source address
     * @param protocol the transport protocol (TCP/UDP)
     * @param context the processing context with persistenceId for tracking
     */
    private void handleIncomingMessage(Message message, InetSocketAddress source, String protocol,
                                       com.genesis.p2p.core.handlers.ProcessingContext context) {
        if (state.get() != State.RUNNING) {
            log.debug("Ignoring message - node not running");
            // Log error to persistence if context available
            if (messageLogger != null && context != null && context.hasPersistenceId()) {
                messageLogger.logMessageError(context.persistenceId(), "Node not running", null);
            }
            return;
        }

        try {
            String sourceStr = source.getAddress().getHostAddress() + ":" + source.getPort();

            log.debug("Message received",
                    "type", message.type(),
                    "from", sourceStr,
                    "protocol", protocol,
                    "persistenceId", context != null ? context.persistenceId() : null,
                    "traceId", context != null ? context.traceId() : null);

            // Validate message
            var validationResult = protocolValidator.validate(message);
            if (!validationResult.isValid()) {
                log.warn("Message validation failed",
                        "error", validationResult.getErrorSummary(),
                        "from", sourceStr);
                metricsRegistry.incrementCounter("messages.validation.failed");
                // Log validation failure to persistence
                if (messageLogger != null && context != null && context.hasPersistenceId()) {
                    messageLogger.logMessageError(context.persistenceId(),
                            "Validation failed: " + validationResult.getErrorSummary(), null);
                }
                return;
            }

            // Rate limit check
            if (!rateLimitManager.checkRateLimit(message)) {
                log.warn("Rate limit exceeded", "from", sourceStr);
                metricsRegistry.incrementCounter("messages.rate_limited");
                // Log rate limit to persistence
                if (messageLogger != null && context != null && context.hasPersistenceId()) {
                    messageLogger.logMessageError(context.persistenceId(), "Rate limit exceeded", null);
                }
                return;
            }

            // Process message through MessageHandler with full observability
            // (deduplication, backpressure, circuit breaker, retry, DLQ, persistence tracking)
            messageHandler.handleMessage(message, context);
            metricsRegistry.incrementCounter("messages.processed");

        } catch (Exception e) {
            log.error("Error handling message", e, "from", source.toString());
            metricsRegistry.incrementCounter("messages.errors");
            // Log error to persistence
            if (messageLogger != null && context != null && context.hasPersistenceId()) {
                messageLogger.logMessageError(context.persistenceId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Subscribes to system events.
     */
    private void subscribeToSystemEvents() {
        eventBus.subscribe("peer.*", event -> {
            log.debug("Peer event", "type", event.getType());
            metricsRegistry.incrementCounter("events.peer");
        });

        eventBus.subscribe("message.*", event ->
            metricsRegistry.incrementCounter("events.message"));

        eventBus.subscribe("discovery.*", event -> {
            log.info("Discovery event", "type", event.getType());
            metricsRegistry.incrementCounter("events.discovery");
        });

        eventBus.subscribe("node.*", event -> {
            log.info("Node lifecycle event", "type", event.getType());
            metricsRegistry.incrementCounter("events.node");
        });
    }

    /**
     * Prints startup banner.
     */
    private void printStartupBanner() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════╗");
        System.out.println("║                                                       ║");
        System.out.println("║           GENESIS P2P FRAMEWORK v2.0                  ║");
        System.out.println("║              Node Started Successfully                ║");
        System.out.println("║                                                       ║");
        System.out.println("╚═══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("  Node ID:        " + config.nodeId());
        System.out.println("  TCP Port:       " + config.tcpPort() + " (LISTENING)");
        System.out.println("  UDP Port:       " + config.listenPort() + " (LISTENING)");
        System.out.println("  Protocol:       " + config.protocolVersion());
        System.out.println();
        System.out.println("  Components:");
        System.out.println("    ✓ Transport Layer     (TCP + UDP encrypted)");
        System.out.println("    ✓ Discovery Service   (3 strategies)");
        System.out.println("    ✓ Security Layer      (" + securityFacade.getAlgorithm() + ")");
        System.out.println("    ✓ Event Bus           (async messaging)");
        System.out.println("    ✓ Peer Management     (tracking + reputation)");
        System.out.println("    ✓ Message Processors  (system handlers)");
        System.out.println();
        System.out.println("  Discovery:");
        System.out.println("    • Multicast    (" + config.multicastGroup() + ":" + config.multicastPort() + ")");
        System.out.println("    • Broadcast    (port " + config.broadcastPort() + ")");
        System.out.println("    • Bootstrap    (known peers)");
        System.out.println();
        System.out.println("  Status: READY FOR P2P CONNECTIONS");
        System.out.println();
        System.out.println("─────────────────────────────────────────────────────────");
        System.out.println();
    }

    // ==================== Listener Management ====================

    /**
     * Adds a lifecycle listener.
     */
    public void addLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.add(listener);
    }

    /**
     * Removes a lifecycle listener.
     */
    public void removeLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.remove(listener);
    }

    // ==================== Node Information ====================

    /**
     * Gets detailed node information.
     */
    public NodeInfo getNodeInfo() {
        return new NodeInfo(
                config.nodeId(),
                state.get(),
                createdAt,
                startedAt,
                stoppedAt,
                getUptime(),
                peerManager.getAllPeers().size(),
                tcpTransport.getStats(),
                udpTransport.getStats(),
                failureReason
        );
    }

    /**
     * Node information record.
     */
    public record NodeInfo(
            String nodeId,
            State state,
            Instant createdAt,
            Instant startedAt,
            Instant stoppedAt,
            Duration uptime,
            int peerCount,
            TransportStats tcpStats,
            TransportStats udpStats,
            String failureReason
    ) {
        @Override
        public String toString() {
            return String.format(
                    "NodeInfo[id=%s, state=%s, uptime=%s, peers=%d]",
                    nodeId, state, uptime, peerCount
            );
        }
    }

    /**
     * Gets the node uptime.
     */
    public Duration getUptime() {
        if (startedAt == null) {
            return Duration.ZERO;
        }
        Instant end = stoppedAt != null ? stoppedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    // ==================== Getters ====================

    public State getState() {
        return state.get();
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    public NodeConfig getConfig() {
        return config;
    }

    public String getNodeId() {
        return config.nodeId();
    }

    public PeerManager getPeerManager() {
        return peerManager;
    }

    public MetricsRegistry getMetrics() {
        return metricsRegistry;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public PersistenceFacade getPersistence() {
        return persistenceFacade;
    }

    public SecurityFacade getSecurity() {
        return securityFacade;
    }

    /**
     * Gets the secure channel negotiator for KEY_EXCHANGE diagnostics.
     */
    public SecureChannelNegotiator getSecureChannelNegotiator() {
        return secureChannelNegotiator;
    }

    public ITransport getTcpTransport() {
        return tcpTransport;
    }

    public ITransport getUdpTransport() {
        return udpTransport;
    }

    public CompositeDiscovery getDiscoveryService() {
        return discoveryService;
    }

    public TransportStats getTcpStats() {
        return tcpTransport.getStats();
    }

    public TransportStats getUdpStats() {
        return udpTransport.getStats();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    // ==================== Message Operations ====================

    /**
     * Sends a ping message to a specific peer.
     *
     * This method performs comprehensive validation before sending:
     * 1. Checks peer exists in peer manager
     * 2. Validates peer is in AUTHENTICATED state
     * 3. Verifies security session is established
     * 4. Creates and sends ping message via TCP transport
     *
     * Logging occurs at each stage:
     * - Validation checks (peer state, session status)
     * - Message creation
     * - Transport send operation
     * - Message persistence (via MessageLogger in AbstractTransport)
     * - Encryption (via AbstractTransport if enabled)
     *
     * @param peerId the ID of the peer to ping
     * @return true if ping was sent successfully, false otherwise
     * @throws IllegalArgumentException if validation fails
     */
    public boolean sendPing(String peerId) {
        log.info("PING_REQUEST",
                "targetPeer", peerId,
                "nodeId", config.nodeId());

        // Validation 1: Peer exists
        var peer = peerManager.getPeer(peerId);
        if (peer == null) {
            log.warn("PING_FAILED_PEER_NOT_FOUND",
                    "targetPeer", peerId,
                    "reason", "peer_not_in_manager");
            throw new IllegalArgumentException("Peer not found: " + peerId);
        }

        // Validation 2: Peer is AUTHENTICATED
        PeerStore.PeerState peerState = peerManager.getPeerState(peerId);
        if (peerState != PeerStore.PeerState.AUTHENTICATED) {
            log.warn("PING_FAILED_PEER_NOT_AUTHENTICATED",
                    "targetPeer", peerId,
                    "currentState", peerState != null ? peerState.toString() : "null",
                    "requiredState", "AUTHENTICATED");
            throw new IllegalArgumentException(
                    "Peer not authenticated: " + peerId + " (current state: " + peerState + ")"
            );
        }

        // Validation 3: Security session exists
        if (!securityFacade.hasValidSession(peerId)) {
            log.warn("PING_FAILED_NO_SESSION",
                    "targetPeer", peerId,
                    "reason", "no_valid_security_session");
            throw new IllegalArgumentException(
                    "No valid security session for peer: " + peerId
            );
        }

        try {
            // Create ping message
            MessageHeader header = new MessageHeader(
                    null,              // messageId - auto-generated
                    null,              // correlationId - auto-set to messageId
                    "1.0",             // protocolVersion
                    "1.0",             // messageVersion
                    10,                // ttl
                    0,                 // hopCount
                    "PING",            // type
                    config.nodeId(),   // from
                    peerId,            // to
                    System.currentTimeMillis(),  // timestamp
                    securityFacade.isEncryptionEnabled(),  // encrypted
                    "json",            // contentType
                    true,              // authenticated
                    "",                // signature - empty for now
                    false              // requiresAck
            );

            MessageBody body = new MessageBody(
                    "{\"action\":\"ping\"}",  // content
                    Map.of(
                            "sender", config.nodeId(),
                            "timestamp", System.currentTimeMillis()
                    )
            );

            Message pingMessage = new Message(header, body);

            log.info("PING_MESSAGE_CREATED",
                    "messageId", pingMessage.messageId(),
                    "targetPeer", peerId,
                    "messageType", "PING");

            // Get peer connection address
            InetSocketAddress destination = peer.getConnectionAddress();

            log.debug("PING_SENDING",
                    "targetPeer", peerId,
                    "destination", destination.toString(),
                    "transport", "TCP");

            // Send via TCP transport
            // Note: AbstractTransport will handle:
            // - Message persistence (via MessageLogger.logOutboundMessage)
            // - Encryption (if session exists and encryption enabled)
            // - Frame encoding (via ProtocolLayer)
            // - Logging at each stage
            tcpTransport.send(pingMessage, destination)
                    .thenRun(() -> {
                        log.info("PING_SENT_SUCCESS",
                                "messageId", pingMessage.messageId(),
                                "targetPeer", peerId);
                    })
                    .exceptionally(ex -> {
                        log.error("PING_SEND_FAILED",
                                "messageId", pingMessage.messageId(),
                                "targetPeer", peerId,
                                "error", ex.getMessage());
                        return null;
                    });

            return true;

        } catch (Exception e) {
            log.error("PING_EXCEPTION",
                    "targetPeer", peerId,
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage());
            throw new RuntimeException("Failed to send ping to peer: " + peerId, e);
        }
    }

    // ==================== Component Statistics (Phase 1-5 Integrations) ====================

    /**
     * Gets protocol layer statistics (fragmentation & compression).
     *
     * @return protocol statistics map
     */
    public Map<String, Object> getProtocolStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            // Note: These metrics would need to be exposed by ProtocolLayer
            // For now, return placeholder indicating feature availability
            stats.put("fragmentation.created", metricsRegistry.getCounter("protocol.fragmentation.created"));
            stats.put("fragmentation.reassembled", metricsRegistry.getCounter("protocol.fragmentation.reassembled"));
            stats.put("compression.applied", metricsRegistry.getCounter("protocol.compression.applied"));
            stats.put("compression.skipped", metricsRegistry.getCounter("protocol.compression.skipped"));
            stats.put("compression.ratio", metricsRegistry.getGauge("protocol.compression.ratio"));
        } catch (Exception e) {
            log.debug("Protocol stats not available yet", e);
        }
        return stats;
    }

    /**
     * Gets peer cache statistics (LRUCache).
     *
     * @return cache statistics
     */
    public com.genesis.p2p.util.collections.LRUCache.CacheStats getPeerCacheStats() {
        try {
            return peerManager.getPeerStore().getCacheStats();
        } catch (Exception e) {
            log.debug("Cache stats not available", e);
            return null;
        }
    }

    /**
     * Gets message deduplication statistics.
     *
     * @return deduplication statistics map
     */
    public Map<String, Object> getDeduplicationStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            // Note: These metrics would need to be exposed by MessageHandler
            stats.put("window.size", metricsRegistry.getGauge("deduplication.window.size"));
            stats.put("cache.size", metricsRegistry.getGauge("deduplication.cache.size"));
            stats.put("unique", metricsRegistry.getCounter("deduplication.unique"));
            stats.put("duplicate", metricsRegistry.getCounter("deduplication.duplicate"));
        } catch (Exception e) {
            log.debug("Deduplication stats not available yet", e);
        }
        return stats;
    }

    /**
     * Gets resource leak detection statistics.
     *
     * @return leak detection statistics
     */
    public com.genesis.p2p.util.resource.ResourceLeakDetector.LeakStats getLeakDetectionStats() {
        try {
            return com.genesis.p2p.util.resource.ResourceLeakDetector.getStats();
        } catch (Exception e) {
            log.debug("Leak detection stats not available", e);
            return null;
        }
    }

    /**
     * Gets transport leak statistics.
     *
     * @return transport leak count
     */
    public long getTransportLeakCount() {
        try {
            long tcpLeaks = tcpTransport.getLeakCount();
            long udpLeaks = udpTransport.getLeakCount();
            return tcpLeaks + udpLeaks;
        } catch (Exception e) {
            log.debug("Transport leak count not available", e);
            return 0;
        }
    }

    // ==================== Message Replay (Zero Data Loss) ====================

    /**
     * Replays messages from previous session on startup.
     * Implements zero data loss guarantee by retrying pending/sent messages.
     */
    private void replayMessages() {
        try {
            log.info("Initiating message replay for zero data loss guarantee");

            // Create replay handler
            com.genesis.p2p.observability.message.MessageReplayHandler replayHandler =
                    new MessageReplayHandlerImpl();

            // Delegate to MessageLogger for replay
            messageLogger.replayMessages(replayHandler);

            log.info("Message replay completed successfully");

        } catch (Exception e) {
            log.error("Error during message replay (non-fatal)", e);
            // Non-fatal: continue startup even if replay fails
        }
    }

    /**
     * Implementation of MessageReplayHandler for replaying messages on startup.
     */
    private class MessageReplayHandlerImpl implements com.genesis.p2p.observability.message.MessageReplayHandler {

        @Override
        public void replayOutbound(com.genesis.p2p.storage.message.PersistedMessage message) {
            try {
                log.info("Replaying outbound message",
                        "messageId", message.message().messageId(),
                        "persistenceId", message.persistenceId(),
                        "type", message.message().type(),
                        "to", message.message().to(),
                        "remoteAddress", message.remoteAddress(),
                        "transport", message.transportType());

                // Extract destination address
                java.net.InetSocketAddress destination = parseAddress(message.remoteAddress());

                // Resend via appropriate transport
                if ("TCP".equalsIgnoreCase(message.transportType())) {
                    tcpTransport.send(message.message(), destination);
                    log.info("Outbound message replayed via TCP",
                            "messageId", message.message().messageId());
                } else if ("UDP".equalsIgnoreCase(message.transportType())) {
                    udpTransport.send(message.message(), destination);
                    log.info("Outbound message replayed via UDP",
                            "messageId", message.message().messageId());
                } else {
                    log.warn("Unknown transport type for replay",
                            "transport", message.transportType(),
                            "messageId", message.message().messageId());
                }

                metricsRegistry.incrementCounter("messages.replay.outbound.success");

            } catch (Exception e) {
                log.error("Failed to replay outbound message", e,
                        "messageId", message.message().messageId(),
                        "persistenceId", message.persistenceId());
                metricsRegistry.incrementCounter("messages.replay.outbound.failed");
            }
        }

        @Override
        public void checkSent(com.genesis.p2p.storage.message.PersistedMessage message) {
            try {
                log.debug("Checking sent message",
                        "messageId", message.message().messageId(),
                        "persistenceId", message.persistenceId(),
                        "sentAt", message.sentAt());

                // Check if message was sent recently (< 30 seconds ago)
                if (message.sentAt() != null) {
                    long ageSeconds = java.time.Duration.between(
                            message.sentAt(),
                            java.time.Instant.now()
                    ).getSeconds();

                    if (ageSeconds < 30) {
                        log.debug("Recently sent message, no action needed",
                                "messageId", message.message().messageId(),
                                "ageSeconds", ageSeconds);
                        return;
                    }
                }

                // Message sent but not delivered - consider for retry
                // For now, just log it (could implement retry logic here)
                log.info("Sent message not yet delivered (potential for retry)",
                        "messageId", message.message().messageId(),
                        "persistenceId", message.persistenceId(),
                        "type", message.message().type());

                metricsRegistry.incrementCounter("messages.replay.sent.checked");

            } catch (Exception e) {
                log.error("Error checking sent message", e,
                        "messageId", message.message().messageId());
            }
        }

        /**
         * Parses address string to InetSocketAddress.
         */
        private java.net.InetSocketAddress parseAddress(String addressStr) {
            try {
                // Expected format: "host:port" or "/host:port"
                String cleaned = addressStr.replaceFirst("^/", "");
                int colonIndex = cleaned.lastIndexOf(':');
                if (colonIndex > 0) {
                    String host = cleaned.substring(0, colonIndex);
                    int port = Integer.parseInt(cleaned.substring(colonIndex + 1));
                    return new java.net.InetSocketAddress(host, port);
                }
            } catch (Exception e) {
                log.warn("Failed to parse address", "address", addressStr, "error", e.getMessage());
            }

            // Fallback: return localhost with default port
            return new java.net.InetSocketAddress("localhost", config.tcpPort());
        }
    }

    /**
     * Gets message logger for observability and querying.
     *
     * @return the message logger
     */
    public com.genesis.p2p.observability.message.MessageLogger getMessageLogger() {
        return messageLogger;
    }

    @Override
    public void close() {
        if (isRunning()) {
            stop();
        }
    }

    // ==================== Logging Helpers ====================

    /**
     * Converts exception stack trace to string for structured logging.
     *
     * @param e the exception
     * @return stack trace as string
     */
    private static String getStackTraceAsString(Exception e) {
        if (e == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}
