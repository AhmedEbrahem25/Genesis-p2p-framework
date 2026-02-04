package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.util.common.Time;
import com.genesis.p2p.util.threading.ThreadPoolFactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Broadcast-based peer discovery service.
 *
 * Uses UDP broadcast to discover peers on the local network.
 * Similar to multicast but uses broadcast addresses instead.
 * Works well for LANs and simple network topologies.
 *
 * Features:
 * - UDP broadcast announcements
 * - Periodic broadcasts
 * - Local network discovery
 * - Simple and lightweight
 */
public class BroadcastDiscovery extends AbstractDiscoveryService {

    private static final int DEFAULT_BROADCAST_PORT = 5001;
    private static final Duration DEFAULT_ANNOUNCE_INTERVAL = Duration.ofSeconds(30);
    private static final int MAX_PACKET_SIZE = 65507;
    private static final String BROADCAST_ADDRESS = "255.255.255.255";

    private final int broadcastPort;
    private final Duration announceInterval;
    private final Gson gson;

    private DatagramSocket socket;
    private InetAddress broadcastAddress;

    private ScheduledExecutorService announceScheduler;
    private ExecutorService listenerExecutor;
    private volatile boolean listening;

    /**
     * Creates a broadcast discovery service with default configuration.
     */
    public BroadcastDiscovery(NodeConfig config, PeerManager peerManager, com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        this(config, peerManager, metrics, DEFAULT_BROADCAST_PORT, DEFAULT_ANNOUNCE_INTERVAL);
    }

    /**
     * Creates a broadcast discovery service with custom configuration.
     */
    public BroadcastDiscovery(NodeConfig config, PeerManager peerManager,
                              com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
                              int broadcastPort, Duration announceInterval) {
        super(config, peerManager, metrics);
        this.broadcastPort = broadcastPort;
        this.announceInterval = announceInterval;
        this.gson = new Gson();
        this.listening = false;
    }

    @Override
    protected void doStart() throws Exception {
        log.info("BROADCAST_DISCOVERY_START",
                "nodeId", config.nodeId(),
                "broadcastAddress", BROADCAST_ADDRESS,
                "port", broadcastPort);

        try {
            // Create datagram socket - bind to 0.0.0.0:port with SO_BROADCAST enabled
            socket = new DatagramSocket(broadcastPort);
            socket.setBroadcast(true);
            socket.setSoTimeout(1000); // 1 second timeout for receive

            log.info("BROADCAST_SOCKET_BOUND",
                    "nodeId", config.nodeId(),
                    "bindAddress", "0.0.0.0",
                    "port", broadcastPort,
                    "SO_BROADCAST", true);

            // Get broadcast address
            broadcastAddress = InetAddress.getByName(BROADCAST_ADDRESS);

            // Start announcement scheduler
            startAnnounceLoop();

            // Start listener thread
            startListenerThread();

            log.info("BROADCAST_DISCOVERY_STARTED",
                    "nodeId", config.nodeId(),
                    "listening", "UDP 0.0.0.0:" + broadcastPort,
                    "broadcastAddress", BROADCAST_ADDRESS);

        } catch (IOException e) {
            log.error("BROADCAST_DISCOVERY_FAILED",
                    "nodeId", config.nodeId(),
                    "error", e.getMessage());
            throw new Exception("Failed to start broadcast discovery", e);
        }
    }

    @Override
    protected void doStop() {
        log.info("Stopping broadcast discovery");

        // Stop listening
        listening = false;

        // Shutdown announcement scheduler
        if (announceScheduler != null) {
            announceScheduler.shutdown();
            try {
                announceScheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Shutdown listener executor
        if (listenerExecutor != null) {
            listenerExecutor.shutdown();
            try {
                listenerExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Close socket
        if (socket != null && !socket.isClosed()) {
            socket.close();
            log.info("Broadcast socket closed");
        }
    }

    @Override
    protected CompletableFuture<List<Peer>> doDiscoverPeers() {
        // For broadcast, discovery happens passively
        // Return currently discovered peers
        return CompletableFuture.completedFuture(
                new ArrayList<>(peerManager.getOnlinePeers())
        );
    }

    @Override
    public void announceSelf() {
        if (!isRunning()) {
            log.warn("Cannot announce: discovery service not running");
            return;
        }

        try {
            sendBroadcast();
        } catch (IOException e) {
            log.error("Failed to send broadcast announcement", e);
            notifyDiscoveryError(e);
        }
    }

    // ========================= Private Methods =========================

    /**
     * Sends broadcast announcement.
     */
    private void sendBroadcast() throws IOException {
        // Create announcement message
        Map<String, Object> announcement = new HashMap<>();
        announcement.put("nodeId", config.nodeId());
        announcement.put("ip", getLocalIPAddress());
        announcement.put("port", config.tcpPort());
        announcement.put("timestamp", Time.currentMillis());
        announcement.put("version", config.protocolVersion());

        // NAT-AWARE: Add NAT information if available
        if (natContext != null && natContext.isNatDetectionComplete()) {
            natContext.enhanceAnnouncement(announcement);
        }

        String json = gson.toJson(announcement);
        byte[] data = json.getBytes();

        String localAddr = getLocalIPAddress();

        // Log broadcast send request
        log.debug("BROADCAST_SEND_REQUEST",
                "nodeId", config.nodeId(),
                "broadcastAddress", BROADCAST_ADDRESS,
                "port", broadcastPort,
                "packetSize", data.length,
                "messageType", "DISCOVERY_REQUEST");

        // Send broadcast packet
        DatagramPacket packet = new DatagramPacket(
                data, data.length, broadcastAddress, broadcastPort
        );
        socket.send(packet);

        // Log packet details for Wireshark correlation
        log.debug("PACKET_SENT_UDP",
                "protocol", "broadcast",
                "srcAddr", localAddr,
                "dstAddr", BROADCAST_ADDRESS,
                "srcPort", broadcastPort,
                "dstPort", broadcastPort,
                "packetSize", data.length,
                "payloadHex", bytesToHex(data, 32));

        metrics.incrementCounter("broadcast.announcements.sent");
    }

    /**
     * Starts the periodic announcement loop.
     */
    private void startAnnounceLoop() {
        announceScheduler = ThreadPoolFactory.createNamedScheduler(
                "BroadcastAnnounce", config.nodeId());

        announceScheduler.scheduleAtFixedRate(
                this::announceSelf,
                0,
                announceInterval.getSeconds(),
                TimeUnit.SECONDS
        );

        log.info("Broadcast announcement loop started", "interval", announceInterval);
    }

    /**
     * Starts the listener thread for incoming broadcasts.
     */
    private void startListenerThread() {
        listening = true;
        listenerExecutor = ThreadPoolFactory.createNamedExecutor(
                "BroadcastListener", config.nodeId());

        listenerExecutor.submit(this::listenForBroadcasts);
        log.info("Broadcast listener thread started");
    }

    /**
     * Listens for incoming broadcast announcements.
     */
    private void listenForBroadcasts() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];

        log.info("BROADCAST_LISTENER_STARTED",
                "nodeId", config.nodeId(),
                "port", broadcastPort);

        while (listening && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                long receiveStart = System.currentTimeMillis();
                socket.receive(packet);
                long receiveTime = System.currentTimeMillis() - receiveStart;

                // Log packet received
                log.debug("PACKET_RECEIVED_UDP",
                        "protocol", "broadcast",
                        "srcAddr", packet.getAddress().getHostAddress(),
                        "dstAddr", BROADCAST_ADDRESS,
                        "srcPort", packet.getPort(),
                        "dstPort", broadcastPort,
                        "packetSize", packet.getLength(),
                        "receiveTime", receiveTime);

                // Process announcement
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                processBroadcast(data, packet.getAddress());

            } catch (SocketTimeoutException e) {
                // Normal timeout, continue listening
            } catch (IOException e) {
                if (listening) {
                    log.error("Error receiving broadcast packet", e);
                }
            }
        }

        log.info("Stopped listening for broadcast announcements");
    }

    /**
     * Processes received broadcast with improved address resolution.
     *
     * Validates announced IP and uses actual sender's address if announced IP is invalid.
     * Filters out loopback addresses, same-host connections, and invalid IPs.
     */
    private void processBroadcast(byte[] data, InetAddress senderAddress) {
        try {
            String json = new String(data);
            JsonObject announcement = gson.fromJson(json, JsonObject.class);

            String nodeId = announcement.get("nodeId").getAsString();

            // Ignore our own broadcasts
            if (nodeId.equals(config.nodeId())) {
                log.debug("Ignoring self-broadcast", "nodeId", nodeId);
                return;
            }

            String announcedIp = announcement.has("ip") ?
                    announcement.get("ip").getAsString() : null;
            int port = announcement.get("port").getAsInt();
            String version = announcement.has("version") ?
                    announcement.get("version").getAsString() : "1.0";

            // IMPROVED: Validate and resolve peer IP address
            String actualSenderIp = senderAddress.getHostAddress();
            String resolvedIp = resolveValidPeerAddress(announcedIp, actualSenderIp, nodeId);

            if (resolvedIp == null) {
                log.debug("Peer filtered due to invalid address",
                        "peerId", nodeId,
                        "announcedIp", announcedIp,
                        "senderIp", actualSenderIp);
                metrics.incrementCounter("broadcast.peers.filtered.invalid_address");
                return;
            }

            // SELF-CONNECTION CHECK: Detect if peer is on same host/port
            if (isSelfConnection(resolvedIp, port)) {
                log.debug("Filtered same-host self-connection",
                        "peerId", nodeId,
                        "ip", resolvedIp,
                        "port", port,
                        "localPort", config.tcpPort());
                metrics.incrementCounter("broadcast.peers.filtered.self_connection");
                return;
            }

            // Log broadcast announcement received
            log.debug("BROADCAST_RECV_ANNOUNCEMENT",
                    "nodeId", config.nodeId(),
                    "fromAddr", actualSenderIp,
                    "announcedPeerId", nodeId,
                    "announcedAddr", announcedIp + ":" + port,
                    "resolvedAddr", resolvedIp + ":" + port,
                    "packetSize", data.length);

            // NAT-AWARE: Extract NAT information from announcement
            String publicIp = announcement.has("publicIp") ?
                    announcement.get("publicIp").getAsString() : resolvedIp;
            int publicPort = announcement.has("publicPort") ?
                    announcement.get("publicPort").getAsInt() : port;

            com.genesis.p2p.nat.NatType natType = com.genesis.p2p.nat.NatType.UNKNOWN;
            if (announcement.has("natType")) {
                try {
                    natType = com.genesis.p2p.nat.NatType.valueOf(
                        announcement.get("natType").getAsString()
                    );
                } catch (IllegalArgumentException e) {
                    log.debug("Invalid NAT type in announcement");
                }
            }

            boolean behindNat = announcement.has("behindNat") ?
                    announcement.get("behindNat").getAsBoolean() : false;

            // Extract identity public key if present (for secure channel negotiation)
            String identityPublicKey = announcement.has("identityPublicKey") ?
                    announcement.get("identityPublicKey").getAsString() : "";

            // Create peer from broadcast with NAT information and VALIDATED IP
            Peer peer = new Peer(
                    nodeId,
                    "",
                    senderAddress.getHostName(),
                    resolvedIp,  // Use validated/resolved IP
                    port,
                    publicIp,
                    publicPort,
                    natType,
                    behindNat,
                    true,
                    Instant.now(),
                    0L,
                    false,
                    50,
                    version,
                    "unknown",
                    "broadcast-discovered",
                    identityPublicKey
            );

            notifyPeerDiscovered(peer);
            metrics.incrementCounter("broadcast.peers.discovered");

            // Log peer discovered
            log.info("PEER_DISCOVERED_BROADCAST",
                    "nodeId", config.nodeId(),
                    "peerId", nodeId,
                    "peerAddr", resolvedIp,  // Use validated/resolved IP
                    "peerPort", port,
                    "publicAddr", publicIp,
                    "publicPort", publicPort,
                    "natType", natType,
                    "behindNat", behindNat,
                    "version", version);

            // Send discovery reply back to the peer (unicast UDP)
            sendDiscoveryReply(senderAddress, port);

        } catch (Exception e) {
            log.error("Failed to process broadcast announcement", e);
        }
    }

    /**
     * Sends a discovery reply to a peer (unicast UDP).
     */
    private void sendDiscoveryReply(InetAddress peerAddress, int peerTcpPort) {
        try {
            // Create reply announcement
            Map<String, Object> reply = new HashMap<>();
            reply.put("nodeId", config.nodeId());
            reply.put("ip", getLocalIPAddress());
            reply.put("port", config.tcpPort());
            reply.put("timestamp", Time.currentMillis());
            reply.put("version", config.protocolVersion());
            reply.put("replyTo", peerAddress.getHostAddress());

            String json = gson.toJson(reply);
            byte[] data = json.getBytes();

            // Send unicast UDP reply to peer's UDP port (TCP port - 1)
            int replyPort = peerTcpPort - 1; // UDP port is typically TCP port - 1
            InetSocketAddress replyAddress = new InetSocketAddress(peerAddress, replyPort);

            DatagramPacket packet = new DatagramPacket(data, data.length, replyAddress);
            socket.send(packet);

            log.debug("Discovery reply sent",
                    "to", peerAddress.getHostAddress() + ":" + replyPort);
            metrics.incrementCounter("broadcast.replies.sent");

        } catch (IOException e) {
            log.debug("Failed to send discovery reply (non-critical)", e);
        }
    }

    /**
     * Gets local IP address for announcements.
     *
     * IMPROVED: Uses network interface enumeration instead of InetAddress.getLocalHost()
     * to avoid returning loopback addresses (127.0.0.1, 127.0.1.1) or hostname-mapped IPs.
     *
     * Prefers non-loopback IPv4 addresses on active network interfaces.
     */
    private String getLocalIPAddress() {
        try {
            // Try to get actual network interface address (NOT loopback)
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                // Skip loopback, inactive, and virtual interfaces
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    // Prefer IPv4, non-loopback, site-local addresses
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        log.debug("Selected network interface address",
                                "interface", ni.getName(),
                                "ip", ip);
                        return ip;
                    }
                }
            }

            // Fallback: Try InetAddress.getLocalHost() but validate result
            InetAddress localHost = InetAddress.getLocalHost();
            String hostAddress = localHost.getHostAddress();

            // Validate: reject loopback addresses
            if (hostAddress.startsWith("127.")) {
                log.warn("InetAddress.getLocalHost() returned loopback address",
                        "address", hostAddress,
                        "fallback", "using 0.0.0.0 (will be replaced by receiver)");
                return "0.0.0.0"; // Sentinel value - receiver should use actual sender IP
            }

            return hostAddress;

        } catch (Exception e) {
            log.warn("Failed to get local IP address - using sentinel",
                    "error", e.getMessage());
            return "0.0.0.0"; // Sentinel - receiver will use actual UDP sender IP
        }
    }

    // ==================== Address Validation and Resolution ====================

    /**
     * Resolves and validates peer IP address.
     *
     * Strategy:
     * 1. If announced IP is valid and routable, use it
     * 2. If announced IP is loopback/invalid, use actual sender IP
     * 3. If both are invalid, reject peer (return null)
     *
     * @param announcedIp IP address from broadcast announcement
     * @param senderIp Actual IP address of UDP packet sender
     * @param peerId Peer ID (for logging)
     * @return Validated IP address, or null if both are invalid
     */
    private String resolveValidPeerAddress(String announcedIp, String senderIp, String peerId) {
        // Validate announced IP
        if (isValidPeerAddress(announcedIp)) {
            log.debug("Using announced IP address",
                    "peerId", peerId,
                    "announcedIp", announcedIp);
            return announcedIp;
        }

        // Announced IP invalid - try actual sender IP
        if (isValidPeerAddress(senderIp)) {
            log.debug("Announced IP invalid, using sender IP",
                    "peerId", peerId,
                    "announcedIp", announcedIp,
                    "senderIp", senderIp);
            return senderIp;
        }

        // Both invalid - reject peer
        log.warn("Both announced and sender IPs are invalid, rejecting peer",
                "peerId", peerId,
                "announcedIp", announcedIp,
                "senderIp", senderIp);
        return null;
    }

    /**
     * Validates if an IP address is suitable for peer connection.
     *
     * Rejects:
     * - null or empty strings
     * - Loopback addresses (127.0.0.0/8)
     * - Sentinel values (0.0.0.0)
     * - Invalid IP format
     *
     * @param ip IP address to validate
     * @return true if valid for peer connections
     */
    private boolean isValidPeerAddress(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }

        ip = ip.trim();

        // Reject sentinel value
        if ("0.0.0.0".equals(ip)) {
            return false;
        }

        // Reject loopback addresses (127.0.0.0/8)
        if (ip.startsWith("127.")) {
            return false;
        }

        // Validate IP format
        try {
            InetAddress addr = InetAddress.getByName(ip);

            // Reject loopback
            if (addr.isLoopbackAddress()) {
                return false;
            }

            // Reject wildcard
            if (addr.isAnyLocalAddress()) {
                return false;
            }

            // Valid!
            return true;

        } catch (Exception e) {
            log.debug("Invalid IP address format", "ip", ip);
            return false;
        }
    }

    /**
     * Detects if a discovered peer is actually this same node.
     *
     * Prevents same-host self-connections by checking if:
     * - Peer IP matches any local interface IP
     * - Peer port matches local TCP port
     *
     * @param peerIp Peer's IP address
     * @param peerPort Peer's TCP port
     * @return true if this is a self-connection attempt
     */
    private boolean isSelfConnection(String peerIp, int peerPort) {
        // Check port first (fast check)
        if (peerPort != config.tcpPort()) {
            return false; // Different port - not self
        }

        // Same port - check if IP matches any local interface
        try {
            InetAddress peerAddr = InetAddress.getByName(peerIp);

            // Check all local interfaces
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress localAddr = addresses.nextElement();

                    // Match found - same IP and port
                    if (localAddr.equals(peerAddr)) {
                        log.debug("Self-connection detected",
                                "interface", ni.getName(),
                                "localAddr", localAddr.getHostAddress(),
                                "peerAddr", peerIp,
                                "port", peerPort);
                        return true;
                    }
                }
            }

            // Not a self-connection
            return false;

        } catch (Exception e) {
            log.debug("Error checking self-connection", "error", e.getMessage());
            return false; // Assume not self on error
        }
    }

    /**
     * Converts bytes to hexadecimal string for logging.
     */
    private static String bytesToHex(byte[] bytes, int maxLength) {
        int length = Math.min(bytes.length, maxLength);
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}