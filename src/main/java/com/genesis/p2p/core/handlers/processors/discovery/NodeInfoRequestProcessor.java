package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.*;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.message.MessageSecurityHelper;
import com.genesis.p2p.util.common.Time;

import java.util.*;
public class NodeInfoRequestProcessor extends BaseDiscoveryProcessor {

    private final PeerQueryService queryService;

    public NodeInfoRequestProcessor(NodeConfig config, PeerManager peerManager,
                                    PeerEventBus eventBus, PeerReputationService reputationService,
                                    PeerStore peerStore, SecurityFacade security, PeerQueryService queryService) {
        super(config, peerManager, eventBus, reputationService, peerStore, security);
        this.queryService = queryService;
    }

    public NodeInfoRequestProcessor(NodeConfig config, PeerManager peerManager,
                                    PeerEventBus eventBus, PeerReputationService reputationService,
                                    PeerStore peerStore, PeerQueryService queryService) {
        super(config, peerManager, eventBus, reputationService, peerStore);
        this.queryService = queryService;
    }

    @Override
    public void processMessage(Message message) {
        String requesterId = message.header().from();

        log.debug("Received NODE_INFO_REQUEST", "from", requesterId);

        if (isSelfMessage(requesterId)) {
            return;
        }

        log.debug("Node info requested by", "requester", requesterId);
        // Event handling through PeerManager
    }

    /**
     * Creates NODE_INFO_RESPONSE message.
     */
    public Message createNodeInfoResponse(String targetNodeId) {
        Map<String, Object> nodeInfo = new HashMap<>();
        nodeInfo.put("nodeId", config.nodeId());
        nodeInfo.put("tcpPort", config.tcpPort());
        nodeInfo.put("version", config.protocolVersion());
        nodeInfo.put("os", System.getProperty("os.name"));
        nodeInfo.put("agent", "genesis-p2p/1.0");
        nodeInfo.put("publicKey", getLocalPublicKey());
        nodeInfo.put("peerCount", queryService.getOnlinePeers().size());
        nodeInfo.put("uptime", getUptime());
        nodeInfo.put("capabilities", getCapabilities());

        MessageBody body = new MessageBody(
                gson.toJson(nodeInfo),
                Map.of()
        );

        // Use MessageSecurityHelper for automatic signing
        return MessageSecurityHelper.createSignedMessage(
                "NODE_INFO_RESPONSE",
                config.nodeId(),
                targetNodeId,
                body,
                security
        );
    }

    private long getUptime() {
        return Time.currentMillis(); // Simplified
    }

    private List<String> getCapabilities() {
        return List.of("discovery", "routing", "storage");
    }
}
