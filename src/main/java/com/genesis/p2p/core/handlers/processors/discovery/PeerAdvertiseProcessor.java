package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.PeerEventBus;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.peer.PeerReputationService;
import com.genesis.p2p.core.peer.PeerStore;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.message.MessageSecurityHelper;
import com.genesis.p2p.util.common.Time;
import com.google.gson.JsonObject;
import java.util.*;
public class PeerAdvertiseProcessor extends BaseDiscoveryProcessor {

    public PeerAdvertiseProcessor(NodeConfig config, PeerManager peerManager,
                                  PeerEventBus eventBus, PeerReputationService reputationService,
                                  PeerStore peerStore, SecurityFacade security) {
        super(config, peerManager, eventBus, reputationService, peerStore, security);
    }

    public PeerAdvertiseProcessor(NodeConfig config, PeerManager peerManager,
                                  PeerEventBus eventBus, PeerReputationService reputationService,
                                  PeerStore peerStore) {
        super(config, peerManager, eventBus, reputationService, peerStore);
    }

    @Override
    public void processMessage(Message message) {
        String senderId = message.header().from();

        log.debug("Received PEER_ADVERTISE", "from", senderId);

        // Ignore self-advertisements
        if (isSelfMessage(senderId)) {
            return;
        }

        // Parse metadata
        JsonObject metadata = parseMetadata(message.body().content());
        if (!validateMetadata(metadata)) {
            log.warn("Invalid ADVERTISE metadata", "from", senderId);
            return;
        }

        String senderAddress = extractAddress(message);
        Peer peer = createPeerFromMetadata(metadata, senderAddress);

        boolean added = peerManager.upsertPeer(peer);

        if (added) {
            log.info("Peer advertised", "peerId", peer.id(), "ip", peer.ip());
            reputationService.increaseReputation(peer.id());
        }

        // Event fired by PeerManager.upsertPeer()
    }

    private String extractAddress(Message message) {
        Object addr = message.body().metadata().get("senderAddress");
        return addr != null ? addr.toString() : "unknown";
    }

    /**
     * Creates PEER_ADVERTISE message for broadcasting.
     */
    public Message createAdvertiseMessage() {
        MessageBody body = new MessageBody(
                createBroadcastMetadata(),
                Map.of("broadcast", true)
        );

        // Use MessageSecurityHelper for automatic signing
        return MessageSecurityHelper.createSignedMessage(
                "PEER_ADVERTISE",
                config.nodeId(),
                "", // broadcast
                body,
                security
        );
    }
}