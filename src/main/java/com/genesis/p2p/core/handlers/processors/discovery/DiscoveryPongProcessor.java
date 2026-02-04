package com.genesis.p2p.core.handlers.processors.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.*;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.util.common.Time;
import com.google.gson.JsonObject;

public class DiscoveryPongProcessor extends BaseDiscoveryProcessor {

    public DiscoveryPongProcessor(NodeConfig config, PeerManager peerManager,
                                  PeerEventBus eventBus, PeerReputationService reputationService,
                                  PeerStore peerStore, SecurityFacade security) {
        super(config, peerManager, eventBus, reputationService, peerStore, security);
    }

    public DiscoveryPongProcessor(NodeConfig config, PeerManager peerManager,
                                  PeerEventBus eventBus, PeerReputationService reputationService,
                                  PeerStore peerStore) {
        super(config, peerManager, eventBus, reputationService, peerStore);
    }

    @Override
    public void processMessage(Message message) {
        String senderId = message.header().from();

        log.debug("Received DISCOVERY_PONG", "from", senderId);

        // Ignore self-pongs
        if (isSelfMessage(senderId)) {
            return;
        }

        // Parse metadata
        JsonObject metadata = parseMetadata(message.body().content());
        if (!validateMetadata(metadata)) {
            log.warn("Invalid PONG metadata", "from", senderId);
            return;
        }

        // Extract sender address (from metadata or use message header)
        String senderAddress = extractSenderAddress(message);

        // Create or update peer
        Peer peer = createPeerFromMetadata(metadata, senderAddress);
        boolean added = peerManager.upsertPeer(peer);

        if (added) {
            log.info("Peer discovered via PONG", "peerId", peer.id(), "ip", peer.ip());
            reputationService.increaseReputation(peer.id());
        }

        // Event fired by PeerManager.upsertPeer()
    }

    private String extractSenderAddress(Message message) {
        // Try to get from message metadata
        Object addr = message.body().metadata().get("senderAddress");
        return addr != null ? addr.toString() : "unknown";
    }
}