package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;

import com.genesis.p2p.core.peer.*;

import com.genesis.p2p.security.facade.SecurityFacade;
import com.google.gson.JsonObject;

public class BootstrapResponseProcessor extends BaseDiscoveryProcessor {

    public BootstrapResponseProcessor(NodeConfig config, PeerManager peerManager,
                                      PeerEventBus eventBus, PeerReputationService reputationService,
                                      PeerStore peerStore, SecurityFacade security) {
        super(config, peerManager, eventBus, reputationService, peerStore, security);
    }

    public BootstrapResponseProcessor(NodeConfig config, PeerManager peerManager,
                                      PeerEventBus eventBus, PeerReputationService reputationService,
                                      PeerStore peerStore) {
        super(config, peerManager, eventBus, reputationService, peerStore);
    }

    @Override
    public void processMessage(Message message) {
        String bootstrapNodeId = message.header().from();

        log.info("Received BOOTSTRAP_RESPONSE", "from", bootstrapNodeId);

        try {
            JsonObject content = parseMetadata(message.body().content());
            if (content == null || !content.has("peers")) {
                log.warn("Invalid bootstrap response");
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
                    reputationService.increaseReputation(peer.id());
                }
            }

            int networkSize = content.has("networkSize") ?
                    content.get("networkSize").getAsInt() : 0;

            log.info("Bootstrap complete",
                    "peersAdded", addedCount,
                    "networkSize", networkSize,
                    "bootstrapNode", bootstrapNodeId);

            // Events fired by PeerManager.upsertPeer() and reputation updates

        } catch (Exception e) {
            log.error("Error processing bootstrap response", e);
        }
    }
}