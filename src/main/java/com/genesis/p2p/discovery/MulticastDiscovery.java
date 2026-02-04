package com.genesis.p2p.discovery;

import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.util.threading.ThreadPoolFactory;
import com.genesis.p2p.util.common.Time;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class MulticastDiscovery extends AbstractDiscoveryService {

    private static final String DEFAULT_MULTICAST_GROUP = "239.255.0.1";
    private static final int DEFAULT_MULTICAST_PORT = 5000;
    private static final Duration DEFAULT_ANNOUNCE_INTERVAL = Duration.ofSeconds(30);
    private static final int MAX_PACKET_SIZE = 8192; // Reduced for better compatibility
    private static final int SO_TIMEOUT = 1000; // Socket timeout

    private final String multicastGroup;
    private final int multicastPort;
    private final Duration announceInterval;
    private final Gson gson;

    private MulticastSocket socket;
    private InetAddress group;
    private NetworkInterface networkInterface;

    private ScheduledExecutorService announceScheduler;
    private ExecutorService listenerExecutor;
    private volatile boolean listening;

    public MulticastDiscovery(NodeConfig config, PeerManager peerManager, com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        this(config, peerManager, metrics, DEFAULT_MULTICAST_GROUP, DEFAULT_MULTICAST_PORT, DEFAULT_ANNOUNCE_INTERVAL);
    }

    public MulticastDiscovery(NodeConfig config, PeerManager peerManager,
                              com.genesis.p2p.observability.metrics.MetricsRegistry metrics,
                              String multicastGroup, int multicastPort,
                              Duration announceInterval) {
        super(config, peerManager, metrics);
        this.multicastGroup = multicastGroup;
        this.multicastPort = multicastPort;
        this.announceInterval = announceInterval;
        this.gson = new Gson();
        this.listening = false;
    }

    @Override
    protected void doStart() throws Exception {
        log.info("MULTICAST_DISCOVERY_START",
                "nodeId", config.nodeId(),
                "multicastGroup", multicastGroup,
                "port", multicastPort,
                "ttl", 255);

        try {
            // Validate multicast address
            group = InetAddress.getByName(multicastGroup);

            if (!group.isMulticastAddress()) {
                throw new IllegalArgumentException(
                        "Address is not a multicast address: " + multicastGroup
                );
            }

            // Find suitable network interface
            networkInterface = findSuitableNetworkInterface();
            if (networkInterface == null) {
                log.warn("No suitable network interface found, using default");
            } else {
                log.info("Using network interface",
                        "name", networkInterface.getDisplayName(),
                        "address", getLocalIPAddress());
            }

            // Create and configure multicast socket - bind to 0.0.0.0:port
            socket = new MulticastSocket(multicastPort);
            socket.setReuseAddress(true);
            socket.setTimeToLive(255);
            socket.setSoTimeout(SO_TIMEOUT);

            log.info("MULTICAST_SOCKET_BOUND",
                    "nodeId", config.nodeId(),
                    "bindAddress", "0.0.0.0",
                    "port", multicastPort,
                    "ttl", 255);

            // Join multicast group
            joinMulticastGroup();

            // Verify multicast group joined
            log.info("MULTICAST_GROUP_JOINED",
                    "nodeId", config.nodeId(),
                    "group", multicastGroup,
                    "port", multicastPort,
                    "interface", networkInterface != null ? networkInterface.getDisplayName() : "default");

            // Start announcement scheduler
            startAnnounceLoop();

            // Start listener thread
            startListenerThread();

            log.info("MULTICAST_DISCOVERY_STARTED",
                    "nodeId", config.nodeId(),
                    "listening", "UDP 0.0.0.0:" + multicastPort,
                    "multicastGroup", multicastGroup);

        } catch (IOException e) {
            // Cleanup on failure
            cleanup();
            log.error("MULTICAST_DISCOVERY_FAILED",
                    "nodeId", config.nodeId(),
                    "error", e.getMessage());
            throw new Exception("Failed to start multicast discovery", e);
        }
    }

    @Override
    protected void doStop() {
        log.info("Stopping multicast discovery");

        // Stop listening
        listening = false;

        // Shutdown schedulers
        shutdownExecutor(announceScheduler, "Announcement");
        shutdownExecutor(listenerExecutor, "Listener");

        // Leave multicast group and close socket
        leaveMulticastGroup();

        if (socket != null && !socket.isClosed()) {
            socket.close();
            log.info("Multicast socket closed");
        }
    }

    @Override
    protected CompletableFuture<List<Peer>> doDiscoverPeers() {
        // Return currently known peers
        List<Peer> peers = new ArrayList<>();
        if (peerManager != null) {
            try {
                peers.addAll(peerManager.getAllPeers());
            } catch (Exception e) {
                log.error("Error getting peers from manager", e);
            }
        }
        return CompletableFuture.completedFuture(peers);
    }

    @Override
    public void announceSelf() {
        if (!isRunning() || socket == null || socket.isClosed()) {
            log.warn("Cannot announce: socket not ready");
            return;
        }

        try {
            // Create announcement message
            Map<String, Object> announcement = createAnnouncement();
            String json = gson.toJson(announcement);
            byte[] data = json.getBytes();

            if (data.length > MAX_PACKET_SIZE) {
                log.warn("Announcement too large", "size", data.length);
                return;
            }

            String localAddr = getLocalIPAddress();

            // Log multicast send request
            log.debug("MULTICAST_SEND_REQUEST",
                    "nodeId", config.nodeId(),
                    "multicastGroup", multicastGroup,
                    "port", multicastPort,
                    "packetSize", data.length,
                    "messageType", "DISCOVERY_REQUEST");

            // Send multicast packet
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, group, multicastPort
            );
            socket.send(packet);

            // Log packet details for Wireshark correlation
            log.debug("PACKET_SENT_UDP",
                    "protocol", "multicast",
                    "srcAddr", localAddr,
                    "dstAddr", multicastGroup,
                    "srcPort", multicastPort,
                    "dstPort", multicastPort,
                    "packetSize", data.length,
                    "payloadHex", bytesToHex(data, 32)); // First 32 bytes

            metrics.incrementCounter("multicast.announcements.sent");

        } catch (IOException e) {
            log.error("MULTICAST_SEND_FAILED",
                    "nodeId", config.nodeId(),
                    "error", e.getMessage());
            notifyDiscoveryError(e);
        }
    }

    // ========================= Private Helper Methods =========================

    private void joinMulticastGroup() throws IOException {
        if (networkInterface != null) {
            SocketAddress groupAddress = new InetSocketAddress(group, multicastPort);
            socket.joinGroup(groupAddress, networkInterface);
        } else {
            socket.joinGroup(group);
        }
        log.info("Joined multicast group");
    }

    private void leaveMulticastGroup() {
        if (socket != null && !socket.isClosed()) {
            try {
                if (networkInterface != null) {
                    SocketAddress groupAddress = new InetSocketAddress(group, multicastPort);
                    socket.leaveGroup(groupAddress, networkInterface);
                } else {
                    socket.leaveGroup(group);
                }
                log.info("Left multicast group");
            } catch (IOException e) {
                log.error("Error leaving multicast group", e);
            }
        }
    }

    private void startAnnounceLoop() {
        announceScheduler = ThreadPoolFactory.createNamedScheduler(
                "MulticastAnnounce", config.nodeId());

        // Initial delay to avoid startup collision
        announceScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        announceSelf();
                    } catch (Exception e) {
                        log.error("Error in announcement loop", e);
                    }
                },
                5, // Initial delay 5 seconds
                announceInterval.getSeconds(),
                TimeUnit.SECONDS
        );

        log.info("Announcement loop started", "interval", announceInterval);
    }

    private void startListenerThread() {
        listening = true;
        listenerExecutor = ThreadPoolFactory.createNamedExecutor(
                "MulticastListener", config.nodeId());

        listenerExecutor.submit(this::listenForAnnouncements);
        log.info("Listener thread started");
    }

    private void listenForAnnouncements() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];

        log.info("MULTICAST_LISTENER_STARTED",
                "nodeId", config.nodeId(),
                "port", multicastPort);

        while (listening && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                long receiveStart = System.currentTimeMillis();
                socket.receive(packet);
                long receiveTime = System.currentTimeMillis() - receiveStart;

                // Log packet received
                log.debug("PACKET_RECEIVED_UDP",
                        "protocol", "multicast",
                        "srcAddr", packet.getAddress().getHostAddress(),
                        "dstAddr", multicastGroup,
                        "srcPort", packet.getPort(),
                        "dstPort", multicastPort,
                        "packetSize", packet.getLength(),
                        "receiveTime", receiveTime);

                // Process announcement
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                processAnnouncement(data, packet.getAddress());

            } catch (SocketTimeoutException e) {
                // Normal timeout, continue listening
            } catch (IOException e) {
                if (listening) {
                    log.error("Error receiving multicast packet", e);
                    // Don't break loop on single error
                }
            } catch (Exception e) {
                log.error("Unexpected error in listener", e);
            }
        }

        log.info("MULTICAST_LISTENER_STOPPED",
                "nodeId", config.nodeId());
    }

    private void processAnnouncement(byte[] data, InetAddress senderAddress) {
        try {
            String json = new String(data);
            JsonObject announcement = gson.fromJson(json, JsonObject.class);

            if (announcement == null || !announcement.has("nodeId")) {
                log.debug("Invalid announcement format");
                return;
            }

            String nodeId = announcement.get("nodeId").getAsString();

            // Ignore our own announcements
            if (nodeId.equals(config.nodeId())) {
                return;
            }

            // Extract peer information
            String ip = announcement.has("ip") ?
                    announcement.get("ip").getAsString() :
                    senderAddress.getHostAddress();
            int port = announcement.has("port") ?
                    announcement.get("port").getAsInt() :
                    config.tcpPort();
            String version = announcement.has("version") ?
                    announcement.get("version").getAsString() : "1.0";

            // NAT-AWARE: Extract NAT information from announcement
            String publicIp = announcement.has("publicIp") ?
                    announcement.get("publicIp").getAsString() : ip;
            int publicPort = announcement.has("publicPort") ?
                    announcement.get("publicPort").getAsInt() : port;

            com.genesis.p2p.nat.NatType natType = com.genesis.p2p.nat.NatType.UNKNOWN;
            if (announcement.has("natType")) {
                try {
                    natType = com.genesis.p2p.nat.NatType.valueOf(
                        announcement.get("natType").getAsString()
                    );
                } catch (IllegalArgumentException e) {
                    log.debug("Invalid NAT type in announcement", "natType",
                             announcement.get("natType").getAsString());
                }
            }

            boolean behindNat = announcement.has("behindNat") ?
                    announcement.get("behindNat").getAsBoolean() : false;

            // Extract identity public key if present (for secure channel negotiation)
            String identityPublicKey = announcement.has("identityPublicKey") ?
                    announcement.get("identityPublicKey").getAsString() : "";

            // Create peer with NAT information
            Peer peer = new Peer(
                    nodeId,
                    "",
                    senderAddress.getHostName(),
                    ip,
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
                    "multicast-discovered",
                    identityPublicKey
            );

            notifyPeerDiscovered(peer);
            metrics.incrementCounter("multicast.peers.discovered");

            // Log peer discovered
            log.info("PEER_DISCOVERED_MULTICAST",
                    "nodeId", config.nodeId(),
                    "peerId", nodeId,
                    "peerAddr", ip,
                    "peerPort", port,
                    "publicAddr", publicIp,
                    "publicPort", publicPort,
                    "natType", natType,
                    "behindNat", behindNat,
                    "version", version);

            // Send discovery reply back to the peer (unicast UDP)
            sendDiscoveryReply(senderAddress, port);

        } catch (Exception e) {
            log.error("Failed to process multicast announcement", e);
        }
    }

    /**
     * Sends a discovery reply to a peer (unicast UDP).
     */
    private void sendDiscoveryReply(InetAddress peerAddress, int peerTcpPort) {
        try {
            // Create reply announcement
            Map<String, Object> reply = createAnnouncement();
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
            metrics.incrementCounter("multicast.replies.sent");

        } catch (IOException e) {
            log.debug("Failed to send discovery reply (non-critical)", e);
        }
    }

    private Map<String, Object> createAnnouncement() {
        Map<String, Object> announcement = new HashMap<>();
        announcement.put("nodeId", config.nodeId());
        announcement.put("ip", getLocalIPAddress());
        announcement.put("port", config.tcpPort());
        announcement.put("timestamp", Time.currentMillis());
        announcement.put("version", config.protocolVersion());
        announcement.put("multicastPort", multicastPort);

        // NAT-AWARE: Add NAT information if available
        if (natContext != null && natContext.isNatDetectionComplete()) {
            natContext.enhanceAnnouncement(announcement);
        }

        return announcement;
    }

    private NetworkInterface findSuitableNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                if (ni.isUp() && !ni.isLoopback() && ni.supportsMulticast()) {
                    // Check if it has valid addresses
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            log.debug("Found suitable network interface",
                                    "name", ni.getDisplayName(),
                                    "address", addr.getHostAddress());
                            return ni;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            log.error("Error finding network interface", e);
        }

        return null;
    }

    private String getLocalIPAddress() {
        try {
            if (networkInterface != null) {
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }

            // Fallback
            InetAddress local = InetAddress.getLocalHost();
            if (!local.isLoopbackAddress()) {
                return local.getHostAddress();
            }

            return "127.0.0.1";

        } catch (Exception e) {
            log.warn("Failed to get local IP address", e);
            return "127.0.0.1";
        }
    }


    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    log.warn("{} executor forcibly shutdown", name);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void cleanup() {
        listening = false;

        if (announceScheduler != null) {
            announceScheduler.shutdownNow();
        }

        if (listenerExecutor != null) {
            listenerExecutor.shutdownNow();
        }

        if (socket != null && !socket.isClosed()) {
            socket.close();
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