package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;

import com.genesis.p2p.core.peer.*;

import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.message.MessageSecurityHelper;
import com.genesis.p2p.util.common.Time;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.stream.Collectors;

public class BootstrapRequestProcessor extends BaseDiscoveryProcessor {

    private final PeerQueryService queryService;

    public BootstrapRequestProcessor(NodeConfig config, PeerManager peerManager,
                                     PeerEventBus eventBus, PeerReputationService reputationService,
                                     PeerStore peerStore, SecurityFacade security, PeerQueryService queryService) {
        super(config, peerManager, eventBus, reputationService, peerStore, security);
        this.queryService = queryService;
    }

    public BootstrapRequestProcessor(NodeConfig config, PeerManager peerManager,
                                     PeerEventBus eventBus, PeerReputationService reputationService,
                                     PeerStore peerStore, PeerQueryService queryService) {
        super(config, peerManager, eventBus, reputationService, peerStore);
        this.queryService = queryService;
    }


    @Override
    public void processMessage(Message message) {
        String newNodeId = message.header().from();

        log.info("Received BOOTSTRAP_REQUEST", "from", newNodeId);

        // Parse new node metadata
        JsonObject metadata = parseMetadata(message.body().content());
        if (validateMetadata(metadata)) {
            String senderAddress = extractAddress(message);
            Peer newPeer = createPeerFromMetadata(metadata, senderAddress);
            peerManager.upsertPeer(newPeer);
            log.info("Bootstrapping node added", "nodeId", newNodeId);
        }

        // Get best peers for bootstrap
        List<Peer> bootstrapPeers = queryService.getBestPeersForRouting(30);

        log.debug("Bootstrap request processed", "newNode", newNodeId, "providedPeers", bootstrapPeers.size());
        // Events fired by PeerManager.upsertPeer()
    }

    private String extractAddress(Message message) {
        Object addr = message.body().metadata().get("senderAddress");
        return addr != null ? addr.toString() : "unknown";
    }

    /**
     * Creates BOOTSTRAP_RESPONSE message.
     */
    public Message createBootstrapResponse(String targetNodeId, List<Peer> peers) {
        List<Map<String, Object>> peerList = peers.stream()
                .map(this::peerToMap)
                .collect(Collectors.toList());

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("peers", peerList);
        responseData.put("networkSize", peerStore.size());
        responseData.put("bootstrapNode", config.nodeId());

        MessageBody body = new MessageBody(
                gson.toJson(responseData),
                Map.of("peerCount", peers.size())
        );

        // Use MessageSecurityHelper for automatic signing
        return MessageSecurityHelper.createSignedMessage(
                "BOOTSTRAP_RESPONSE",
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
        return map;
    }
}