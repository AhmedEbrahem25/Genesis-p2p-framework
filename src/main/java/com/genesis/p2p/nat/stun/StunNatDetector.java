package com.genesis.p2p.nat.stun;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.MessageHandler;
import com.genesis.p2p.nat.AbstractNatTraversalService;
import com.genesis.p2p.nat.NatType;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * NAT detector using message-based STUN client.
 * Fully integrated with MessageHandler.
 */
public class StunNatDetector extends AbstractNatTraversalService {

    private static final NodeLogger log = NodeLogger.getLogger(StunNatDetector.class);


    private final StunClient stunClient;
    private final MessageHandler messageHandler;

    /**
     * Creates NAT detector with custom configuration.
     */
    public StunNatDetector(String nodeId, MessageHandler messageHandler,
                           Duration timeout, List<String> stunServers) {
        super(nodeId, timeout);
        this.messageHandler = messageHandler;
        this.stunClient = new StunClient(nodeId, messageHandler, timeout, stunServers);
    }

    /**
     * Creates NAT detector with default configuration.
     */
    public StunNatDetector(String nodeId, MessageHandler messageHandler) {
        this(nodeId, messageHandler, Duration.ofSeconds(5), null);
    }

    @Override
    protected NatType performDetection() throws Exception {
        log.info("Starting NAT detection using MessageHandler");

        List<String> servers = stunClient.getServers();

        if (servers.isEmpty()) {
            log.warn("No STUN servers configured");
            return NatType.UNKNOWN;
        }

        // Test 1: Get public endpoint from first server
        log.debug("NAT Test 1: Querying primary STUN server");
        InetSocketAddress publicEndpoint1 = stunClient.queryServer(servers.get(0))
                .get(timeout.toSeconds(), TimeUnit.SECONDS);

        if (publicEndpoint1 == null) {
            log.warn("Failed to get response from primary STUN server");
            return NatType.UNKNOWN;
        }

        log.info("Public endpoint detected", "address", publicEndpoint1);
        recordStunResponse();

        // Check if public IP equals local IP (no NAT)
        try {
            InetAddress localAddr = InetAddress.getLocalHost();
            if (publicEndpoint1.getAddress().getHostAddress()
                    .equals(localAddr.getHostAddress())) {
                log.info("No NAT detected - direct internet connection");
                return NatType.OPEN;
            }
        } catch (Exception e) {
            log.warn("Could not determine local address", e);
        }

        // Test 2: Query same server again to check consistency
        log.debug("NAT Test 2: Second query to verify consistency");
        InetSocketAddress publicEndpoint2 = stunClient.queryServer(servers.get(0))
                .get(timeout.toSeconds(), TimeUnit.SECONDS);

        if (publicEndpoint2 == null) {
            log.warn("Second STUN query failed");
            return NatType.UNKNOWN;
        }

        recordStunResponse();

        // Test 3: Query different server if available
        if (servers.size() > 1) {
            log.debug("NAT Test 3: Querying secondary STUN server");
            InetSocketAddress publicEndpoint3 = stunClient.queryServer(servers.get(1))
                    .get(timeout.toSeconds(), TimeUnit.SECONDS);

            if (publicEndpoint3 != null) {
                recordStunResponse();

                // Check if mappings differ across servers (indicates SYMMETRIC)
                if (!publicEndpoint1.equals(publicEndpoint3)) {
                    log.info("Symmetric NAT detected - different mapping per destination");
                    return NatType.SYMMETRIC;
                }
            }
        }

        // If we reach here, it's a cone NAT
        // Default to RESTRICTED_CONE as safe assumption
        log.info("Cone NAT detected (defaulting to RESTRICTED_CONE)");
        return NatType.RESTRICTED_CONE;
    }

    @Override
    public CompletableFuture<InetSocketAddress> getPublicEndpoint(int localPort) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> servers = stunClient.getServers();
                if (servers.isEmpty()) {
                    log.warn("No STUN servers configured");
                    return null;
                }

                return stunClient.queryServer(servers.get(0))
                        .get(timeout.toSeconds(), TimeUnit.SECONDS);

            } catch (Exception e) {
                log.error("Failed to get public endpoint", e);
                return null;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> establishConnection(InetSocketAddress remoteEndpoint) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Send hole punch message via MessageHandler
                Message holePunch = createHolePunchMessage(remoteEndpoint);
                messageHandler.handleMessage(holePunch);

                log.debug("Hole punch message sent", "remote", remoteEndpoint);
                return true;

            } catch (Exception e) {
                log.error("Hole punching failed", e);
                return false;
            }
        }, executor);
    }

    /**
     * Creates hole punch message for NAT traversal.
     */
    private Message createHolePunchMessage(InetSocketAddress remote) {
        MessageHeader header = new MessageHeader(
                UUID.randomUUID().toString(),   // Message ID
                UUID.randomUUID().toString(),   // Correlation ID
                "1.0",                          // Protocol version
                "1.0",                          // Message version
                5,                              // Short TTL for hole punch
                0,                              // Hop count
                StunMessageTypes.HOLE_PUNCH,    // Type
                nodeId,                         // From
                remote.toString(),              // To
                System.currentTimeMillis(),     // Timestamp
                false,                          // Not encrypted
                "json",                         // Content type
                false,                          // Not authenticated
                "",                             // No signature
                false                           // requiresAck
        );

        MessageBody body = new MessageBody(
                "NAT Hole Punch",
                new HashMap<>(java.util.Map.of(
                        "purpose", "NAT_TRAVERSAL",
                        "timestamp", Time.currentMillis()
                ))
        );

        return new Message(header, body);
    }

    @Override
    protected void onStart() {
        log.info("STUN NAT detector started with MessageHandler integration",
                "servers", stunClient.getServers(),
                "timeout", timeout);
    }

    @Override
    protected void onStop() {
        log.info("STUN NAT detector stopped",
                "pendingRequests", stunClient.getPendingRequestCount());
    }
}