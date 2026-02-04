package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;

import com.genesis.p2p.core.peer.*;

import com.genesis.p2p.security.facade.SecurityFacade;
import com.google.gson.JsonObject;

public class NodeInfoResponseProcessor extends BaseDiscoveryProcessor {

    public NodeInfoResponseProcessor(NodeConfig config, PeerManager peerManager,
                                     PeerEventBus eventBus, PeerReputationService reputationService,
                                     PeerStore peerStore, SecurityFacade security) {
        super(config, peerManager, eventBus, reputationService, peerStore, security);
    }

    public NodeInfoResponseProcessor(NodeConfig config, PeerManager peerManager,
                                     PeerEventBus eventBus, PeerReputationService reputationService,
                                     PeerStore peerStore) {
        super(config, peerManager, eventBus, reputationService, peerStore);
    }

    @Override
    public void processMessage(Message message) {
        String nodeId = message.header().from();

        log.debug("Received NODE_INFO_RESPONSE", "from", nodeId);

        try {
            JsonObject nodeInfo = parseMetadata(message.body().content());
            if (nodeInfo == null) {
                log.warn("Invalid node info response");
                return;
            }

            // Update peer if exists, otherwise create
            if (validateMetadata(nodeInfo)) {
                String ip = nodeInfo.has("ip") ? nodeInfo.get("ip").getAsString() : "unknown";
                Peer peer = createPeerFromMetadata(nodeInfo, ip);
                peerManager.upsertPeer(peer);

                log.info("Node info updated",
                        "nodeId", nodeId,
                        "peerCount", nodeInfo.has("peerCount") ? nodeInfo.get("peerCount").getAsInt() : 0);
            }

            // Event fired by PeerManager.upsertPeer()

        } catch (Exception e) {
            log.error("Error processing node info response", e);
        }
    }
}