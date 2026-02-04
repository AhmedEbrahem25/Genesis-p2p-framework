package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.PeerEventBus;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.peer.PeerReputationService;
import com.genesis.p2p.core.peer.PeerStore;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.google.gson.JsonObject;

public class PeerListResponseProcessor extends BaseDiscoveryProcessor {

    public PeerListResponseProcessor(NodeConfig config, PeerManager peerManager,
                                     PeerEventBus eventBus, PeerReputationService reputationService,
                                     PeerStore peerStore, SecurityFacade security) {
        super(config, peerManager, eventBus, reputationService, peerStore, security);
    }

    public PeerListResponseProcessor(NodeConfig config, PeerManager peerManager,
                                     PeerEventBus eventBus, PeerReputationService reputationService,
                                     PeerStore peerStore) {
        super(config, peerManager, eventBus, reputationService, peerStore);
    }

    @Override
    public void processMessage(Message message) {
        String senderId = message.header().from();

        log.debug("Received PEER_LIST_RESPONSE", "from", senderId);

        try {
            JsonObject content = parseMetadata(message.body().content());
            if (content == null || !content.has("peers")) {
                log.warn("Invalid peer list response");
                return;
            }

            var peersArray = content.getAsJsonArray("peers");
            int addedCount = 0;

            for (var element : peersArray) {
                JsonObject peerData = element.getAsJsonObject();

                if (!validateMetadata(peerData)) {
                    continue;
                }

                String nodeId = peerData.get("nodeId").getAsString();
                if (isSelfMessage(nodeId)) {
                    continue;
                }

                Peer peer = createPeerFromMetadata(peerData, peerData.get("ip").getAsString());

                if (peerManager.upsertPeer(peer)) {
                    addedCount++;
                }
            }

            log.info("Processed peer list", "total", peersArray.size(), "added", addedCount);
            // Events fired by PeerManager.upsertPeer() for each peer

        } catch (Exception e) {
            log.error("Error processing peer list response", e);
        }
    }
}