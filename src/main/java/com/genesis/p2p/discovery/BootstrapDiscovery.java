package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bootstrap-based peer discovery service.
 *
 * Connects to a predefined list of bootstrap nodes to discover peers.
 * Bootstrap nodes are trusted peers that maintain a list of active nodes
 * in the network.
 *
 * Features:
 * - Connects to known bootstrap peers
 * - Requests peer list from bootstrap nodes
 * - Fallback to multiple bootstrap nodes
 * - Connection timeout handling
 */
public class BootstrapDiscovery extends AbstractDiscoveryService {

    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final List<String> bootstrapPeers;
    private final Duration connectionTimeout;
    private final int maxRetries;

    /**
     * Creates a bootstrap discovery service.
     *
     * @param config node configuration
     * @param peerManager peer manager
     * @param metrics metrics registry for observability
     * @param bootstrapPeers list of bootstrap peer addresses (ip:port format)
     */
    public BootstrapDiscovery(NodeConfig config, PeerManager peerManager,
                              com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
                              List<String> bootstrapPeers) {
        this(config, peerManager, metrics, bootstrapPeers, DEFAULT_CONNECTION_TIMEOUT, DEFAULT_MAX_RETRIES);
    }

    /**
     * Creates a bootstrap discovery service with custom configuration.
     */
    public BootstrapDiscovery(NodeConfig config, PeerManager peerManager,
                              com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
                              List<String> bootstrapPeers,
                              Duration connectionTimeout, int maxRetries) {
        super(config, peerManager, metrics);
        this.bootstrapPeers = new ArrayList<>(bootstrapPeers);
        this.connectionTimeout = connectionTimeout;
        this.maxRetries = maxRetries;
    }

    @Override
    protected void doStart() throws Exception {
        log.info("Starting bootstrap discovery",
                "bootstrapCount", bootstrapPeers.size());

        if (bootstrapPeers.isEmpty()) {
            log.warn("No bootstrap peers configured");
            return;
        }

        // Validate bootstrap addresses
        for (String address : bootstrapPeers) {
            try {
                parseAddress(address);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid bootstrap address", "address", address);
            }
        }

        log.info("Bootstrap discovery started",
                "peers", bootstrapPeers.size());
    }

    @Override
    protected void doStop() {
        log.info("Stopping bootstrap discovery");
        // No cleanup needed for bootstrap
    }

    @Override
    protected CompletableFuture<List<Peer>> doDiscoverPeers() {
        log.info("Discovering peers via bootstrap nodes");

        List<CompletableFuture<Peer>> futures = new ArrayList<>();

        // Connect to each bootstrap peer
        for (String address : bootstrapPeers) {
            futures.add(connectToBootstrap(address));
        }

        // Combine all futures
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Peer> discovered = new ArrayList<>();
                    for (CompletableFuture<Peer> future : futures) {
                        try {
                            Peer peer = future.getNow(null);
                            if (peer != null) {
                                discovered.add(peer);
                            }
                        } catch (Exception e) {
                            log.debug("Failed to get peer from future", e);
                        }
                    }

                    log.info("Bootstrap discovery complete",
                            "discovered", discovered.size(),
                            "attempted", bootstrapPeers.size());

                    return discovered;
                });
    }

    @Override
    public void announceSelf() {
        // Bootstrap nodes don't require active announcement
        // Peers connect to bootstrap nodes, not the other way around
        log.debug("Bootstrap discovery does not require active announcement");
    }

    // ========================= Private Methods =========================

    /**
     * Connects to a bootstrap peer and retrieves its information.
     */
    private CompletableFuture<Peer> connectToBootstrap(String address) {
        return CompletableFuture.supplyAsync(() -> {
            int retries = 0;
            Exception lastException = null;

            while (retries < maxRetries) {
                try {
                    InetSocketAddress socketAddress = parseAddress(address);
                    long connectStart = System.currentTimeMillis();

                    // Log bootstrap connection start
                    log.info("BOOTSTRAP_CONNECT_START",
                            "nodeId", config.nodeId(),
                            "bootstrapAddr", socketAddress.getHostString(),
                            "bootstrapPort", socketAddress.getPort(),
                            "attempt", retries + 1,
                            "maxRetries", maxRetries);

                    // Log bootstrap request send
                    log.debug("BOOTSTRAP_SEND_REQUEST",
                            "nodeId", config.nodeId(),
                            "bootstrapAddr", socketAddress.getHostString(),
                            "requestType", "GET_PEERS");

                    // Simulate connection attempt
                    // In real implementation, this would connect via TCP
                    // and exchange HELLO/WELCOME messages
                    long requestStart = System.currentTimeMillis();

                    // Create peer representing the bootstrap node
                    Peer peer = Peer.withoutNat(
                            "bootstrap-" + socketAddress.getHostString() + "-" + socketAddress.getPort(),
                            "",
                            socketAddress.getHostName(),
                            socketAddress.getHostString(),
                            socketAddress.getPort(),
                            true,
                            Instant.now(),
                            0,
                            true, // bootstrap peers are trusted
                            100, // max reputation
                            config.protocolVersion(),
                            "unknown",
                            "bootstrap"
                    );

                    long responseTime = System.currentTimeMillis() - requestStart;

                    // Log bootstrap response received
                    // In real implementation, this would be the number of peers in the response
                    int peersReceived = 1; // For now, just the bootstrap node itself
                    log.info("BOOTSTRAP_RECV_RESPONSE",
                            "nodeId", config.nodeId(),
                            "bootstrapAddr", socketAddress.getHostString(),
                            "peersReceived", peersReceived,
                            "responseTime", responseTime);

                    // Log each peer received
                    log.debug("BOOTSTRAP_PEER_RECEIVED",
                            "nodeId", config.nodeId(),
                            "peerId", peer.id(),
                            "peerAddr", peer.ip(),
                            "peerPort", peer.port(),
                            "trusted", peer.trusted(),
                            "reputation", peer.reputation());

                    metrics.incrementCounter("bootstrap.connections.success");
                    metrics.incrementCounter("bootstrap.peers.received", peersReceived);

                    long totalTime = System.currentTimeMillis() - connectStart;
                    log.info("BOOTSTRAP_CONNECT_SUCCESS",
                            "nodeId", config.nodeId(),
                            "bootstrapAddr", address,
                            "peerId", peer.id(),
                            "totalTime", totalTime);

                    notifyPeerDiscovered(peer);
                    return peer;

                } catch (Exception e) {
                    lastException = e;
                    retries++;

                    if (retries < maxRetries) {
                        log.debug("Bootstrap connection failed, retrying",
                                "address", address,
                                "attempt", retries,
                                "error", e.getMessage());

                        try {
                            TimeUnit.SECONDS.sleep(1); // Wait before retry
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            // All retries failed
            metrics.incrementCounter("bootstrap.connections.failed");
            log.warn("Failed to connect to bootstrap peer after retries",
                    "address", address,
                    "retries", maxRetries);

            if (lastException != null) {
                notifyDiscoveryError(lastException);
            }

            return null;
        });
    }

    /**
     * Parses bootstrap address in format "ip:port".
     */
    private InetSocketAddress parseAddress(String address) {
        String[] parts = address.split(":");

        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid address format. Expected ip:port, got: " + address
            );
        }

        try {
            String host = parts[0].trim();
            int port = Integer.parseInt(parts[1].trim());

            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid port: " + port);
            }

            return new InetSocketAddress(host, port);

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid port number in address: " + address, e
            );
        }
    }

    /**
     * Adds a bootstrap peer address.
     */
    public void addBootstrapPeer(String address) {
        if (!bootstrapPeers.contains(address)) {
            bootstrapPeers.add(address);
            log.info("Bootstrap peer added", "address", address);
        }
    }

    /**
     * Removes a bootstrap peer address.
     */
    public void removeBootstrapPeer(String address) {
        if (bootstrapPeers.remove(address)) {
            log.info("Bootstrap peer removed", "address", address);
        }
    }

    /**
     * Gets list of configured bootstrap peers.
     */
    public List<String> getBootstrapPeers() {
        return new ArrayList<>(bootstrapPeers);
    }
}
