package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;

import com.genesis.p2p.core.peer.*;
import com.genesis.p2p.discovery.BroadcastDiscovery;
import com.genesis.p2p.discovery.MulticastDiscovery;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.security.facade.SecurityFacade;

import java.time.Duration;

public class DiscoveryProcessorRegistration {

    private static final NodeLogger log = NodeLogger.getLogger(DiscoveryProcessorRegistration.class);

    /**
     * Registers all discovery processors with the MessageHandler.
     *
     * @param messageHandler the message handler to register with
     * @param config node configuration
     * @param peerManager peer manager instance
     * @param eventBus event bus for peer events
     * @param reputationService reputation service
     * @param peerStore peer store for direct access
     * @param queryService query service for peer selection
     * @param security security facade for cryptographic operations
     */
    public static void registerAll(
            MessageHandler messageHandler,
            NodeConfig config,
            PeerManager peerManager,
            PeerEventBus eventBus,
            PeerReputationService reputationService,
            PeerStore peerStore,
            PeerQueryService queryService,
            SecurityFacade security) {

        log.info("Registering discovery processors with SecurityFacade");

        // PING/PONG - High priority, async
        messageHandler.registerProcessor(
                "DISCOVERY_PING",
                new DiscoveryPingProcessor(config, peerManager, eventBus, reputationService, peerStore, security),
                10, // high priority
                true, // async
                Duration.ofSeconds(5)
        );

        messageHandler.registerProcessor(
                "DISCOVERY_PONG",
                new DiscoveryPongProcessor(config, peerManager, eventBus, reputationService, peerStore, security),
                10, // high priority
                true, // async
                Duration.ofSeconds(5)
        );

        // Peer Advertise - Medium priority, async
        messageHandler.registerProcessor(
                "PEER_ADVERTISE",
                new PeerAdvertiseProcessor(config, peerManager, eventBus, reputationService, peerStore, security),
                7,
                true,
                Duration.ofSeconds(10)
        );

        // Peer List - Medium priority, async
        messageHandler.registerProcessor(
                "PEER_LIST_REQUEST",
                new PeerListRequestProcessor(config, peerManager, eventBus, reputationService, peerStore, security, queryService),
                7,
                true,
                Duration.ofSeconds(10)
        );

        messageHandler.registerProcessor(
                "PEER_LIST_RESPONSE",
                new PeerListResponseProcessor(config, peerManager, eventBus, reputationService, peerStore, security),
                7,
                true,
                Duration.ofSeconds(15)
        );

        // Bootstrap - High priority, async (critical for new nodes)
        messageHandler.registerProcessor(
                "BOOTSTRAP_REQUEST",
                new BootstrapRequestProcessor(config, peerManager, eventBus, reputationService, peerStore, security, queryService),
                9,
                true,
                Duration.ofSeconds(20)
        );

        messageHandler.registerProcessor(
                "BOOTSTRAP_RESPONSE",
                new BootstrapResponseProcessor(config, peerManager, eventBus, reputationService, peerStore, security),
                9,
                true,
                Duration.ofSeconds(30)
        );

        // Node Info - Lower priority, async
        messageHandler.registerProcessor(
                "NODE_INFO_REQUEST",
                new NodeInfoRequestProcessor(config, peerManager, eventBus, reputationService, peerStore, security, queryService),
                5,
                true,
                Duration.ofSeconds(10)
        );

        messageHandler.registerProcessor(
                "NODE_INFO_RESPONSE",
                new NodeInfoResponseProcessor(config, peerManager, eventBus, reputationService, peerStore, security),
                5,
                true,
                Duration.ofSeconds(10)
        );

        log.info("Discovery processors registered successfully with SecurityFacade integration");
    }

    /**
     * Unregisters all discovery processors.
     *
     * @param messageHandler the message handler to unregister from
     */
    public static void unregisterAll(MessageHandler messageHandler) {
        log.info("Unregistering discovery processors");

        messageHandler.unregisterProcessor("DISCOVERY_PING");
        messageHandler.unregisterProcessor("DISCOVERY_PONG");
        messageHandler.unregisterProcessor("PEER_ADVERTISE");
        messageHandler.unregisterProcessor("PEER_LIST_REQUEST");
        messageHandler.unregisterProcessor("PEER_LIST_RESPONSE");
        messageHandler.unregisterProcessor("BOOTSTRAP_REQUEST");
        messageHandler.unregisterProcessor("BOOTSTRAP_RESPONSE");
        messageHandler.unregisterProcessor("NODE_INFO_REQUEST");
        messageHandler.unregisterProcessor("NODE_INFO_RESPONSE");

        log.info("Discovery processors unregistered");
    }

    /**
     * Creates event listeners that integrate discovery with multicast/broadcast.
     *
     * @param eventBus the event bus to register with
     * @param multicastDiscovery multicast discovery service
     * @param broadcastDiscovery broadcast discovery service
     */
    public static void setupEventListeners(
            PeerEventBus eventBus,
            MulticastDiscovery multicastDiscovery,
            BroadcastDiscovery broadcastDiscovery) {

        eventBus.register(new PeerEventBus.PeerEventListener() {
            @Override
            public void onPeerAdded(Peer peer) {
                // Announce ourselves when new peer discovered
                if (multicastDiscovery != null && multicastDiscovery.isRunning()) {
                    multicastDiscovery.announceSelf();
                }
                if (broadcastDiscovery != null && broadcastDiscovery.isRunning()) {
                    broadcastDiscovery.announceSelf();
                }
            }

            @Override
            public void onPeerUpdated(Peer oldPeer, Peer newPeer) {
                // No action needed
            }

            @Override
            public void onPeerRemoved(Peer peer, String reason) {
                // No action needed
            }

            @Override
            public void onPeerStateChanged(Peer peer, PeerStore.PeerState oldState, PeerStore.PeerState newState) {
                // No action needed
            }

            @Override
            public void onReputationChanged(Peer peer, int oldReputation, int newReputation) {
                // Ban peer if reputation drops too low
                if (newReputation < 10) {
                    log.warn("Peer reputation critically low", "peerId", peer.id(), "reputation", newReputation);
                }
            }
        });

        log.info("Discovery event listeners configured");
    }
}