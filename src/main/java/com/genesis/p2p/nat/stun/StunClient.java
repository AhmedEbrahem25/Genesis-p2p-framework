package com.genesis.p2p.nat.stun;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.MessageHandler;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * STUN client that sends real UDP packets to external STUN servers.
 * Implements RFC 5389 STUN protocol for NAT detection.
 *
 * Also integrates with Genesis P2P message system for internal routing.
 */
public class StunClient {

    private static final NodeLogger log = NodeLogger.getLogger(StunClient.class);

    private static final List<String> DEFAULT_SERVERS = Arrays.asList(
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun2.l.google.com:19302"
    );

    // STUN message type constants (RFC 5389)
    private static final short STUN_BINDING_REQUEST = 0x0001;
    private static final short STUN_BINDING_RESPONSE = 0x0101;
    private static final int STUN_MAGIC_COOKIE = 0x2112A442;
    private static final int STUN_HEADER_SIZE = 20;

    // STUN attribute types
    private static final short ATTR_MAPPED_ADDRESS = 0x0001;
    private static final short ATTR_XOR_MAPPED_ADDRESS = 0x0020;

    private final String nodeId;
    private final MessageHandler messageHandler;
    private final List<String> servers;
    private final Duration timeout;
    private final ExecutorService executor;

    // Track pending STUN requests
    private final ConcurrentHashMap<String, CompletableFuture<InetSocketAddress>> pendingRequests;

    /**
     * Creates STUN client with custom configuration.
     */
    public StunClient(String nodeId, MessageHandler messageHandler,
                      Duration timeout, List<String> servers) {
        this.nodeId = nodeId;
        this.messageHandler = messageHandler;
        this.timeout = timeout;
        this.servers = servers != null && !servers.isEmpty() ?
                servers : DEFAULT_SERVERS;
        this.pendingRequests = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "StunClient-" + nodeId);
            t.setDaemon(true);
            return t;
        });

        registerResponseProcessors();
    }

    /**
     * Creates STUN client with default servers.
     */
    public StunClient(String nodeId, MessageHandler messageHandler, Duration timeout) {
        this(nodeId, messageHandler, timeout, null);
    }

    /**
     * Queries STUN server for public endpoint using real UDP packets.
     * Implements RFC 5389 STUN Binding Request.
     */
    public CompletableFuture<InetSocketAddress> queryServer(String server) {
        String requestId = UUID.randomUUID().toString();

        log.debug("Querying STUN server", "server", server, "requestId", requestId);

        CompletableFuture<InetSocketAddress> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        // Parse server address
        String[] parts = server.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 3478; // Default STUN port

        // Send real UDP STUN request
        executor.submit(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setSoTimeout((int) timeout.toMillis());

                InetAddress stunAddr = InetAddress.getByName(host);

                // Generate transaction ID (12 bytes)
                byte[] transactionId = new byte[12];
                new Random().nextBytes(transactionId);

                // Build STUN Binding Request (RFC 5389)
                byte[] stunRequest = buildStunBindingRequest(transactionId);

                // Send request
                DatagramPacket requestPacket = new DatagramPacket(
                        stunRequest, stunRequest.length, stunAddr, port);
                socket.send(requestPacket);

                log.debug("STUN request sent", "server", server, "size", stunRequest.length);

                // Receive response
                byte[] responseBuffer = new byte[576]; // Max STUN response size
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(responsePacket);

                // Parse response
                InetSocketAddress mappedAddress = parseStunResponse(
                        responsePacket.getData(), responsePacket.getLength(), transactionId);

                if (mappedAddress != null) {
                    log.info("STUN response received",
                            "server", server,
                            "mappedAddress", mappedAddress.getAddress().getHostAddress(),
                            "mappedPort", mappedAddress.getPort());
                    future.complete(mappedAddress);
                } else {
                    future.completeExceptionally(new Exception("Failed to parse STUN response"));
                }

            } catch (Exception e) {
                log.debug("STUN request failed", "server", server, "error", e.getMessage());
                future.completeExceptionally(e);
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
                pendingRequests.remove(requestId);
            }
        });

        return future;
    }

    /**
     * Builds a STUN Binding Request per RFC 5389.
     */
    private byte[] buildStunBindingRequest(byte[] transactionId) {
        ByteBuffer buffer = ByteBuffer.allocate(STUN_HEADER_SIZE);

        // Message Type (2 bytes): Binding Request = 0x0001
        buffer.putShort(STUN_BINDING_REQUEST);

        // Message Length (2 bytes): 0 (no attributes)
        buffer.putShort((short) 0);

        // Magic Cookie (4 bytes): 0x2112A442
        buffer.putInt(STUN_MAGIC_COOKIE);

        // Transaction ID (12 bytes)
        buffer.put(transactionId);

        return buffer.array();
    }

    /**
     * Parses a STUN Binding Response and extracts the mapped address.
     */
    private InetSocketAddress parseStunResponse(byte[] data, int length, byte[] expectedTransactionId) {
        if (length < STUN_HEADER_SIZE) {
            log.warn("STUN response too short", "length", length);
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);

        // Check message type (must be Binding Response: 0x0101)
        short messageType = buffer.getShort();
        if (messageType != STUN_BINDING_RESPONSE) {
            log.warn("Unexpected STUN message type", "type", String.format("0x%04X", messageType));
            return null;
        }

        // Message length
        short messageLength = buffer.getShort();

        // Magic Cookie
        int magicCookie = buffer.getInt();
        if (magicCookie != STUN_MAGIC_COOKIE) {
            log.warn("Invalid STUN magic cookie", "cookie", String.format("0x%08X", magicCookie));
            return null;
        }

        // Transaction ID (verify it matches)
        byte[] transactionId = new byte[12];
        buffer.get(transactionId);
        if (!Arrays.equals(transactionId, expectedTransactionId)) {
            log.warn("STUN transaction ID mismatch");
            return null;
        }

        // Parse attributes
        int attributesEnd = STUN_HEADER_SIZE + messageLength;
        while (buffer.position() < attributesEnd && buffer.remaining() >= 4) {
            short attrType = buffer.getShort();
            short attrLength = buffer.getShort();

            if (attrType == ATTR_XOR_MAPPED_ADDRESS || attrType == ATTR_MAPPED_ADDRESS) {
                // Parse mapped address
                if (attrLength >= 8) {
                    buffer.get(); // Reserved byte
                    byte family = buffer.get();
                    short port = buffer.getShort();

                    if (family == 0x01) { // IPv4
                        byte[] ipBytes = new byte[4];
                        buffer.get(ipBytes);

                        if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                            // XOR with magic cookie
                            port ^= (short) (STUN_MAGIC_COOKIE >> 16);
                            ipBytes[0] ^= (byte) (STUN_MAGIC_COOKIE >> 24);
                            ipBytes[1] ^= (byte) (STUN_MAGIC_COOKIE >> 16);
                            ipBytes[2] ^= (byte) (STUN_MAGIC_COOKIE >> 8);
                            ipBytes[3] ^= (byte) STUN_MAGIC_COOKIE;
                        }

                        try {
                            InetAddress addr = InetAddress.getByAddress(ipBytes);
                            return new InetSocketAddress(addr, port & 0xFFFF);
                        } catch (Exception e) {
                            log.warn("Failed to parse IP address", "error", e.getMessage());
                        }
                    } else if (family == 0x02) { // IPv6
                        // Skip IPv6 for now
                        buffer.position(buffer.position() + 16);
                    }
                } else {
                    // Skip malformed attribute
                    buffer.position(buffer.position() + attrLength);
                }
            } else {
                // Skip unknown attribute (pad to 4-byte boundary)
                int paddedLength = (attrLength + 3) & ~3;
                if (buffer.remaining() >= paddedLength) {
                    buffer.position(buffer.position() + paddedLength);
                } else {
                    break;
                }
            }
        }

        log.warn("No mapped address found in STUN response");
        return null;
    }

    /**
     * Creates STUN binding request message.
     */
    private Message createBindingRequest(String requestId, String targetServer) {
        MessageHeader header = new MessageHeader(
                requestId,                      // Message ID
                requestId,                      // Correlation ID
                "1.0",                          // Protocol version
                "1.0",                          // Message version
                10,                             // TTL
                0,                              // Hop count
                StunMessageTypes.STUN_BINDING_REQUEST, // Type
                nodeId,                         // From
                targetServer,                   // To
                System.currentTimeMillis(),     // Timestamp
                false,                          // Not encrypted
                "json",                         // Content type
                false,                          // Not authenticated
                "",                             // No signature
                false                           // requiresAck
        );

        Map<String, Object> requestData = new HashMap<>();
        requestData.put("transactionId", requestId);
        requestData.put("timestamp", Time.currentMillis());
        requestData.put("requestType", "BINDING");

        MessageBody body = new MessageBody(
                "STUN Binding Request",
                requestData
        );

        return new Message(header, body);
    }

    /**
     * Registers processors to handle STUN responses.
     */
    private void registerResponseProcessors() {
        // Register STUN_BINDING_RESPONSE processor
        messageHandler.registerProcessor(
                StunMessageTypes.STUN_BINDING_RESPONSE,
                message -> {
                    String requestId = message.header().correlationId();
                    CompletableFuture<InetSocketAddress> future =
                            pendingRequests.remove(requestId);

                    if (future != null) {
                        InetSocketAddress mappedAddress = extractMappedAddress(message);
                        if (mappedAddress != null) {
                            future.complete(mappedAddress);
                            log.debug("STUN response received",
                                    "address", mappedAddress);
                        } else {
                            future.completeExceptionally(
                                    new Exception("Invalid STUN response - no mapped address")
                            );
                        }
                    } else {
                        log.warn("Received STUN response for unknown request",
                                "requestId", requestId);
                    }
                }
        );

        // Register STUN_ERROR processor
        messageHandler.registerProcessor(
                StunMessageTypes.STUN_ERROR,
                message -> {
                    String requestId = message.header().correlationId();
                    CompletableFuture<InetSocketAddress> future =
                            pendingRequests.remove(requestId);

                    if (future != null) {
                        String error = message.body().content();
                        future.completeExceptionally(new Exception("STUN error: " + error));
                        log.warn("STUN error received", "error", error);
                    }
                }
        );
    }

    /**
     * Extracts mapped address from STUN response message.
     */
    private InetSocketAddress extractMappedAddress(Message response) {
        try {
            Map<String, Object> data = response.body().metadata();
            String ip = (String) data.get("mappedIP");
            Object portObj = data.get("mappedPort");

            int port;
            if (portObj instanceof Integer) {
                port = (Integer) portObj;
            } else if (portObj instanceof String) {
                port = Integer.parseInt((String) portObj);
            } else {
                port = ((Number) portObj).intValue();
            }

            return new InetSocketAddress(ip, port);

        } catch (Exception e) {
            log.error("Failed to extract mapped address from response", e);
            return null;
        }
    }

    /**
     * Gets configured STUN servers.
     */
    public List<String> getServers() {
        return new ArrayList<>(servers);
    }

    /**
     * Gets number of pending requests.
     */
    public int getPendingRequestCount() {
        return pendingRequests.size();
    }
}