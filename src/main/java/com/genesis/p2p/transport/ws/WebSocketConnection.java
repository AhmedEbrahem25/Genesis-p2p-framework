package com.genesis.p2p.transport.ws;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket Connection wrapper.
 *
 * Represents a single WebSocket connection to a peer.
 * Handles:
 * - Connection lifecycle
 * - Message sending/receiving
 * - Connection state tracking
 * - Statistics collection
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class WebSocketConnection {

    private static final NodeLogger log = NodeLogger.getLogger(WebSocketConnection.class);

    private final URI uri;
    private final InetSocketAddress remoteAddress;
    private final Instant connectedAt;
    private final AtomicBoolean connected;

    // Statistics
    private final AtomicLong messagesSent;
    private final AtomicLong messagesReceived;
    private final AtomicLong bytesSent;
    private final AtomicLong bytesReceived;

    // In production, this would hold the actual WebSocket client
    // private final WebSocketClient client;

    /**
     * Creates a WebSocket connection.
     *
     * @param uri the WebSocket URI
     * @param remoteAddress the remote address
     */
    public WebSocketConnection(URI uri, InetSocketAddress remoteAddress) {
        this.uri = uri;
        this.remoteAddress = remoteAddress;
        this.connectedAt = Instant.now();
        this.connected = new AtomicBoolean(true);
        this.messagesSent = new AtomicLong(0);
        this.messagesReceived = new AtomicLong(0);
        this.bytesSent = new AtomicLong(0);
        this.bytesReceived = new AtomicLong(0);

        log.info("WebSocket connection created", "uri", uri);
    }

    /**
     * Sends a message through this connection.
     *
     * @param message the message to send
     * @throws IOException if send fails
     */
    public void send(Message message) throws IOException {
        if (!connected.get()) {
            throw new IOException("Connection is closed");
        }

        try {
            // Serialize message to bytes
            byte[] data = serializeMessage(message);

            // In production, send via WebSocket client:
            /*
            if (client != null && client.isOpen()) {
                client.send(data);
            } else {
                throw new IOException("WebSocket client not open");
            }
            */

            // Update statistics
            messagesSent.incrementAndGet();
            bytesSent.addAndGet(data.length);

            log.debug("Message sent",
                    "type", message.type(),
                    "size", data.length);

        } catch (Exception e) {
            log.error("Failed to send message", e);
            throw new IOException("Failed to send message", e);
        }
    }

    /**
     * Handles incoming message.
     */
    public void handleIncomingMessage(byte[] data) {
        messagesReceived.incrementAndGet();
        bytesReceived.addAndGet(data.length);

        log.debug("Message received", "size", data.length);
    }

    /**
     * Closes this connection.
     */
    public void close() {
        if (!connected.getAndSet(false)) {
            return;
        }

        log.info("Closing WebSocket connection", "uri", uri);

        try {
            // In production, close WebSocket client:
            /*
            if (client != null) {
                client.closeBlocking();
            }
            */

            log.info("WebSocket connection closed", "uri", uri);

        } catch (Exception e) {
            log.error("Error closing connection", e);
        }
    }

    /**
     * Checks if connection is open.
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Gets remote address.
     */
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Gets connection URI.
     */
    public URI getUri() {
        return uri;
    }

    /**
     * Gets when connection was established.
     */
    public Instant getConnectedAt() {
        return connectedAt;
    }

    /**
     * Gets connection age in milliseconds.
     */
    public long getConnectionAgeMs() {
        return Time.currentMillis() - connectedAt.toEpochMilli();
    }

    /**
     * Gets connection statistics.
     */
    public ConnectionStats getStats() {
        return new ConnectionStats(
                messagesSent.get(),
                messagesReceived.get(),
                bytesSent.get(),
                bytesReceived.get(),
                connectedAt
        );
    }

    /**
     * Sends a ping to keep connection alive.
     */
    public void ping() throws IOException {
        if (!connected.get()) {
            throw new IOException("Connection is closed");
        }

        // In production, send WebSocket ping:
        /*
        if (client != null && client.isOpen()) {
            client.sendPing();
        }
        */

        log.debug("Ping sent", "uri", uri);
    }

    // ==================== Helper Methods ====================

    private byte[] serializeMessage(Message message) {
        // Placeholder - implement actual serialization
        // In production, use MessageCodec
        return new byte[0];
    }

    // ==================== Statistics ====================

    /**
     * Connection statistics.
     */
    public static class ConnectionStats {
        private final long messagesSent;
        private final long messagesReceived;
        private final long bytesSent;
        private final long bytesReceived;
        private final Instant connectedAt;

        public ConnectionStats(long messagesSent, long messagesReceived,
                             long bytesSent, long bytesReceived,
                             Instant connectedAt) {
            this.messagesSent = messagesSent;
            this.messagesReceived = messagesReceived;
            this.bytesSent = bytesSent;
            this.bytesReceived = bytesReceived;
            this.connectedAt = connectedAt;
        }

        public long getMessagesSent() { return messagesSent; }
        public long getMessagesReceived() { return messagesReceived; }
        public long getBytesSent() { return bytesSent; }
        public long getBytesReceived() { return bytesReceived; }
        public Instant getConnectedAt() { return connectedAt; }

        @Override
        public String toString() {
            return String.format("ConnectionStats[sent=%d/%d, received=%d/%d]",
                    messagesSent, bytesSent, messagesReceived, bytesReceived);
        }
    }

    @Override
    public String toString() {
        return String.format("WebSocketConnection[uri=%s, connected=%s, sent=%d, received=%d]",
                uri, connected.get(), messagesSent.get(), messagesReceived.get());
    }
}

