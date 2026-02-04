package com.genesis.p2p.core.handlers.processors.system;

import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.handlers.processors.ProcessorRegistry;
import com.genesis.p2p.observability.logging.NodeLogger;

import java.time.Duration;

/**
 * Factory for creating and registering system message processors.
 *
 * Manages lifecycle of core protocol processors:
 * - HELLO: Initial peer discovery
 * - WELCOME: Response to HELLO
 * - HEARTBEAT: Keep-alive mechanism
 * - GOODBYE: Graceful shutdown
 * - PING/PONG: Connectivity testing
 */
public class SystemProcessorFactory {

    private static final NodeLogger log = NodeLogger.getLogger(SystemProcessorFactory.class);

    private final PeerManager peerManager;
    private final ProcessorRegistry registry;
    private final String localNodeId;
    private final MessageSenderImpl messageSender;
    private final SessionManagerImpl sessionManager;

    // Processor instances
    private HelloProcessor helloProcessor;
    private WelcomeProcessor welcomeProcessor;
    private HeartbeatProcessor heartbeatProcessor;
    private GoodbyeProcessor goodbyeProcessor;
    private PingProcessor pingProcessor;
    private PongProcessor pongProcessor;

    /**
     * Creates the factory.
     *
     * @param peerManager peer management service
     * @param registry processor registry
     * @param localNodeId this node's identifier
     */
    public SystemProcessorFactory(PeerManager peerManager,
                                  ProcessorRegistry registry,
                                  String localNodeId) {
        this.peerManager = peerManager;
        this.registry = registry;
        this.localNodeId = localNodeId;
        this.messageSender = new MessageSenderImpl();
        this.sessionManager = new SessionManagerImpl();

        log.info("SystemProcessorFactory initialized", "nodeId", localNodeId);
    }

    /**
     * Registers all system processors.
     */
    public void registerAll() {
        log.info("Registering all system processors");

        registerHelloProcessor();
        registerWelcomeProcessor();
        registerHeartbeatProcessor();
        registerGoodbyeProcessor();
        registerPingProcessor();
        registerPongProcessor();

        log.info("All system processors registered");
    }

    /**
     * Registers HELLO processor.
     */
    public void registerHelloProcessor() {
        helloProcessor = new HelloProcessor(peerManager, messageSender, localNodeId);
        registry.register("HELLO", helloProcessor,
                10, // high priority
                false, // synchronous
                Duration.ofSeconds(5));

        log.info("HELLO processor registered");
    }

    /**
     * Registers WELCOME processor.
     */
    public void registerWelcomeProcessor() {
        welcomeProcessor = new WelcomeProcessor(peerManager, sessionManager);
        registry.register("WELCOME", welcomeProcessor,
                10, // high priority
                false, // synchronous
                Duration.ofSeconds(5));

        log.info("WELCOME processor registered");
    }

    /**
     * Registers HEARTBEAT processor.
     */
    public void registerHeartbeatProcessor() {
        heartbeatProcessor = new HeartbeatProcessor(peerManager, messageSender, localNodeId);
        registry.register("HEARTBEAT", heartbeatProcessor,
                5, // medium priority
                true, // asynchronous (high frequency)
                Duration.ofSeconds(3));

        log.info("HEARTBEAT processor registered");
    }

    /**
     * Registers HEARTBEAT_ACK processor.
     */
    public void registerHeartbeatAckProcessor() {
        // Simple processor that just records the ack
        registry.register("HEARTBEAT_ACK", message -> {
            String peerId = message.header().from();
            peerManager.refreshLastSeen(peerId);
            peerManager.recordSuccess(peerId);
        }, 5, true, Duration.ofSeconds(3));

        log.info("HEARTBEAT_ACK processor registered");
    }

    /**
     * Registers GOODBYE processor.
     */
    public void registerGoodbyeProcessor() {
        goodbyeProcessor = new GoodbyeProcessor(peerManager);
        registry.register("GOODBYE", goodbyeProcessor,
                10, // high priority
                false, // synchronous
                Duration.ofSeconds(5));

        log.info("GOODBYE processor registered");
    }

    /**
     * Registers PING processor.
     */
    public void registerPingProcessor() {
        pingProcessor = new PingProcessor(peerManager, messageSender, localNodeId);
        registry.register("PING", pingProcessor,
                3, // low priority
                true, // asynchronous
                Duration.ofSeconds(2));

        log.info("PING processor registered");
    }

    /**
     * Registers PONG processor.
     */
    public void registerPongProcessor() {
        pongProcessor = new PongProcessor(peerManager);
        registry.register("PONG", pongProcessor,
                3, // low priority
                true, // asynchronous
                Duration.ofSeconds(2));

        log.info("PONG processor registered");
    }

    /**
     * Shuts down all processors.
     */
    public void shutdown() {
        log.info("Shutting down system processors");

        if (goodbyeProcessor != null) {
            goodbyeProcessor.shutdown();
        }

        log.info("System processors shutdown complete");
    }

    // ========================= Accessor Methods =========================

    public HelloProcessor getHelloProcessor() {
        return helloProcessor;
    }

    public WelcomeProcessor getWelcomeProcessor() {
        return welcomeProcessor;
    }

    public HeartbeatProcessor getHeartbeatProcessor() {
        return heartbeatProcessor;
    }

    public GoodbyeProcessor getGoodbyeProcessor() {
        return goodbyeProcessor;
    }

    public PingProcessor getPingProcessor() {
        return pingProcessor;
    }

    public PongProcessor getPongProcessor() {
        return pongProcessor;
    }

    public void setMessageSenderDelegate(MessageSenderDelegate delegate) {
        this.messageSender.setDelegate(delegate);
    }

    // ========================= Inner Classes =========================

    /**
     * Message sender implementation.
     */
    private static class MessageSenderImpl implements
            HelloProcessor.MessageSender,
            HeartbeatProcessor.MessageSender,
            PingProcessor.MessageSender {

        private volatile MessageSenderDelegate delegate;

        public void setDelegate(MessageSenderDelegate delegate) {
            this.delegate = delegate;
        }

        @Override
        public void sendMessage(com.genesis.p2p.core.Message message) {
            if (delegate != null) {
                delegate.send(message);
            } else {
                log.warn("No message sender delegate configured");
            }
        }
    }

    /**
     * Delegate for actual message sending.
     */
    public interface MessageSenderDelegate {
        void send(com.genesis.p2p.core.Message message);
    }

    /**
     * Session manager implementation.
     */
    private static class SessionManagerImpl implements WelcomeProcessor.SessionManager {

        private final java.util.concurrent.ConcurrentHashMap<String, Long> sessions;

        SessionManagerImpl() {
            this.sessions = new java.util.concurrent.ConcurrentHashMap<>();
        }

        @Override
        public void createSession(String peerId, long timestamp) {
            sessions.put(peerId, timestamp);
            log.debug("Session created", "peerId", peerId, "timestamp", timestamp);
        }

        @Override
        public void closeSession(String peerId) {
            sessions.remove(peerId);
            log.debug("Session closed", "peerId", peerId);
        }

        @Override
        public boolean hasSession(String peerId) {
            return sessions.containsKey(peerId);
        }
    }
}