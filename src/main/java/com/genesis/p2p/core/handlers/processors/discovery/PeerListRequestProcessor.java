package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.*;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.message.MessageSecurityHelper;
import com.genesis.p2p.util.common.Time;
import java.util.*;
import java.util.stream.Collectors;

public class PeerListRequestProcessor extends BaseDiscoveryProcessor {

    private final PeerQueryService queryService;

    public PeerListRequestProcessor(NodeConfig config, PeerManager peerManager,
                                    PeerEventBus eventBus, PeerReputationService reputationService,
                                    PeerStore peerStore, SecurityFacade security, PeerQueryService queryService) {
        super(config, peerManager, eventBus, reputationService, peerStore, security);
        this.queryService = queryService;
    }

    public PeerListRequestProcessor(NodeConfig config, PeerManager peerManager,
                                    PeerEventBus eventBus, PeerReputationService reputationService,
                                    PeerStore peerStore, PeerQueryService queryService) {
        super(config, peerManager, eventBus, reputationService, peerStore);
        this.queryService = queryService;
    }

    @Override
    public void processMessage(Message message) {
        String requesterId = message.header().from();

        log.debug("Received PEER_LIST_REQUEST", "from", requesterId);

        // Ignore self-requests
        if (isSelfMessage(requesterId)) {
            return;
        }

        // Get trusted online peers
        List<Peer> peers = queryService.getTrustedPeers().stream()
                .filter(Peer::online)
                .limit(20) // Limit response size
                .collect(Collectors.toList());

        log.debug("Responding with peer list", "count", peers.size(), "to", requesterId);

        // Response is created by network layer
        // Events fired by PeerManager operations
    }

    /**
     * Creates PEER_LIST_RESPONSE message.
     */
    public Message createPeerListResponse(String targetNodeId, List<Peer> peers) {
        List<Map<String, Object>> peerList = peers.stream()
                .map(this::peerToMap)
                .collect(Collectors.toList());

        MessageBody body = new MessageBody(
                gson.toJson(Map.of("peers", peerList)),
                Map.of("count", peers.size())
        );

        // Use MessageSecurityHelper for automatic signing
        return MessageSecurityHelper.createSignedMessage(
                "PEER_LIST_RESPONSE",
                config.nodeId(),
                targetNodeId,
                body,
                security
        );
    }

    private Map<String, Object> peerToMap(Peer peer) {
        Map<String, Object> map = new HashMap<>();
        map.put("nodeId", peer.id());
        map.put("ip", peer.ip());
        map.put("tcpPort", peer.port());
        map.put("version", peer.version());
        map.put("os", peer.os());
        map.put("agent", peer.agent());
        map.put("publicKey", peer.publicKey());
        map.put("reputation", peer.reputation());
        return map;
    }
}
