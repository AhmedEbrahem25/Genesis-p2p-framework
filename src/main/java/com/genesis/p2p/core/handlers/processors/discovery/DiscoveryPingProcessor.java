package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.*;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.security.message.MessageSecurityHelper;
import com.genesis.p2p.util.common.Time;

import java.util.*;
public class DiscoveryPingProcessor extends BaseDiscoveryProcessor {

    public DiscoveryPingProcessor(NodeConfig config, PeerManager peerManager,
                                  PeerEventBus eventBus, PeerReputationService reputationService,
                                  PeerStore peerStore, SecurityFacade security) {
        super(config, peerManager, eventBus, reputationService, peerStore, security);
    }

    public DiscoveryPingProcessor(NodeConfig config, PeerManager peerManager,
                                  PeerEventBus eventBus, PeerReputationService reputationService,
                                  PeerStore peerStore) {
        super(config, peerManager, eventBus, reputationService, peerStore);
    }

    @Override
    public void processMessage(Message message) {
        String senderId = message.header().from();

        log.debug("Received DISCOVERY_PING", "from", senderId);

        // Ignore self-pings
        if (isSelfMessage(senderId)) {
            return;
        }

        // Update peer activity if known
        if (peerManager.containsPeer(senderId)) {
            peerManager.refreshLastSeen(senderId);
        }

        // Note: Event firing handled by PeerManager when peer is updated

        // Send PONG response (handled by network layer)
        log.debug("Responding to PING with PONG", "to", senderId);
    }

    /**
     * Creates PONG response message.
     */
    public Message createPongResponse(String targetNodeId) {
        MessageBody body = new MessageBody(
                createBroadcastMetadata(),
                Map.of("responseType", "ping")
        );

        // Use MessageSecurityHelper for automatic signing
        return MessageSecurityHelper.createSignedMessage(
                "DISCOVERY_PONG",
                config.nodeId(),
                targetNodeId,
                body,
                security
        );
    }
}
