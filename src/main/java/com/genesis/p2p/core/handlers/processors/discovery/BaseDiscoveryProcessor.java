package com.genesis.p2p.core.handlers.processors.discovery;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.*;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.core.peer.*;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.util.common.Time;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.time.Instant;
import java.util.*;


/**
 * Base class for all discovery processors.
 * Provides common functionality for metadata parsing, peer creation,
 * and message signing.
 *
 * Integrated with SecurityFacade for:
 * - Message signing
 * - Signature verification
 * - Trust management
 */
abstract class BaseDiscoveryProcessor implements MessageProcessor {

    protected static final NodeLogger log = NodeLogger.getLogger(BaseDiscoveryProcessor.class);
    protected final NodeConfig config;
    protected final PeerManager peerManager;
    protected final PeerEventBus eventBus;
    protected final PeerReputationService reputationService;
    protected final PeerStore peerStore;
    protected final SecurityFacade security;
    protected final Gson gson;

    protected BaseDiscoveryProcessor(NodeConfig config, PeerManager peerManager,
                                     PeerEventBus eventBus, PeerReputationService reputationService,
                                     PeerStore peerStore, SecurityFacade security) {
        this.config = config;
        this.peerManager = peerManager;
        this.eventBus = eventBus;
        this.reputationService = reputationService;
        this.peerStore = peerStore;
        this.security = security;
        this.gson = new Gson();
    }

    /**
     * Constructor for backward compatibility without SecurityFacade.
     */
    protected BaseDiscoveryProcessor(NodeConfig config, PeerManager peerManager,
                                     PeerEventBus eventBus, PeerReputationService reputationService,
                                     PeerStore peerStore) {
        this(config, peerManager, eventBus, reputationService, peerStore, null);
    }

    /**
     * Parses peer metadata from message content.
     */
    protected JsonObject parseMetadata(String content) {
        try {
            return gson.fromJson(content, JsonObject.class);
        } catch (JsonSyntaxException e) {
            log.error("Failed to parse discovery metadata", e);
            return null;
        }
    }

    /**
     * Validates required fields in metadata.
     */
    protected boolean validateMetadata(JsonObject metadata) {
        if (metadata == null) return false;

        return metadata.has("nodeId") &&
                metadata.has("tcpPort") &&
                metadata.has("version") &&
                metadata.has("os") &&
                metadata.has("agent") &&
                metadata.has("publicKey");
    }

    /**
     * Creates a Peer from metadata.
     */
    protected Peer createPeerFromMetadata(JsonObject metadata, String senderAddress) {
        String nodeId = metadata.get("nodeId").getAsString();
        int tcpPort = metadata.get("tcpPort").getAsInt();
        String version = metadata.get("version").getAsString();
        String os = metadata.get("os").getAsString();
        String agent = metadata.get("agent").getAsString();
        String publicKey = metadata.get("publicKey").getAsString();

        String ip = metadata.has("ip") ?
                metadata.get("ip").getAsString() : senderAddress;

        return Peer.withoutNat(
                nodeId,
                publicKey,
                "", // hostname will be resolved
                ip,
                tcpPort,
                true, // online
                Time.now(),
                0, // no latency yet
                false, // not trusted initially
                50, // initial reputation
                version,
                os,
                agent
        );
    }

    /**
     * Checks if message is from self.
     */
    protected boolean isSelfMessage(String nodeId) {
        return config.nodeId().equals(nodeId);
    }

    /**
     * Creates metadata JSON for broadcasting.
     */
    protected String createBroadcastMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nodeId", config.nodeId());
        metadata.put("tcpPort", config.tcpPort());
        metadata.put("version", config.protocolVersion());
        metadata.put("os", System.getProperty("os.name"));
        metadata.put("agent", "genesis-p2p/1.0");
        metadata.put("publicKey", getLocalPublicKey());
        metadata.put("timestamp", Time.currentMillis());

        return gson.toJson(metadata);
    }

    /**
     * Gets local node's public key from SecurityFacade.
     * Falls back to placeholder if SecurityFacade is not available.
     */
    protected String getLocalPublicKey() {
        if (security != null) {
            try {
                byte[] publicKeyBytes = security.getPublicKey();
                if (publicKeyBytes != null && publicKeyBytes.length > 0) {
                    return java.util.Base64.getEncoder().encodeToString(publicKeyBytes);
                }
            } catch (Exception e) {
                log.warn("Failed to get public key from SecurityFacade, using placeholder", "error", e.getMessage());
            }
        }
        // Fallback for testing or when security is not configured
        return "placeholder-public-key-" + config.nodeId();
    }

    /**
     * Signs a message using SecurityFacade.
     */
    protected String signMessage(String messageData) {
        if (security == null) {
            log.warn("SecurityFacade not available, cannot sign message");
            return "";
        }

        try {
            byte[] signature = security.sign(messageData.getBytes());
            return new String(signature);
        } catch (Exception e) {
            log.error("Failed to sign message", e);
            return "";
        }
    }

    /**
     * Verifies a message signature.
     */
    protected boolean verifyMessageSignature(String senderId, String messageData, String signature) {
        if (security == null) {
            log.warn("SecurityFacade not available, cannot verify signature");
            return false;
        }

        if (signature == null || signature.isEmpty()) {
            return false;
        }

        try {
            return security.verify(
                messageData.getBytes(),
                signature.getBytes(),
                senderId
            );
        } catch (Exception e) {
            log.error("Failed to verify signature", e, "senderId", senderId);
            return false;
        }
    }

    /**
     * Checks if a peer is trusted.
     */
    protected boolean isTrustedPeer(String peerId) {
        if (security == null) {
            return false;
        }

        try {
            return security.isTrusted(peerId);
        } catch (Exception e) {
            log.error("Failed to check trust status", e, "peerId", peerId);
            return false;
        }
    }
}