package com.genesis.p2p.transport.udp;

import com.genesis.p2p.transport.core.*;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.protocol.ProtocolLayer;
import com.genesis.p2p.observability.message.MessageLogger;
import com.genesis.p2p.util.threading.ThreadPoolFactory;
import com.genesis.p2p.util.io.ByteBufferPool;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/**
 * UDP transport implementation with ByteBuffer pooling.
 */
public class UdpTransport extends AbstractTransport {

    private DatagramSocket socket;
    private final ExecutorService receiveExecutor;
    private final ByteBufferPool bufferPool;
    private static final int MAX_PACKET_SIZE = 65507;

    /**
     * Creates a UDP transport with default metrics registry.
     * @deprecated Use constructor with MetricsRegistry for shared metrics
     */
    @Deprecated
    public UdpTransport(TransportConfig config, SecurityFacade security,
                       ProtocolLayer protocolLayer, MessageLogger messageLogger) {
        super(config, security, protocolLayer, messageLogger);
        this.receiveExecutor = ThreadPoolFactory.createNamedExecutor("UDP-Receive", String.valueOf(config.port()));
        this.bufferPool = new ByteBufferPool(MAX_PACKET_SIZE, 50); // Pool of 50 buffers
    }

    /**
     * Creates a UDP transport with shared metrics registry.
     *
     * @param config transport configuration
     * @param security security facade
     * @param protocolLayer protocol layer for encoding/decoding
     * @param messageLogger message logger for observability
     * @param metrics shared metrics registry for unified metrics aggregation
     */
    public UdpTransport(TransportConfig config, SecurityFacade security,
                       ProtocolLayer protocolLayer, MessageLogger messageLogger,
                       com.genesis.p2p.observability.metrics.MetricsRegistry metrics) {
        super(config, security, protocolLayer, messageLogger, metrics);
        this.receiveExecutor = ThreadPoolFactory.createNamedExecutor("UDP-Receive", String.valueOf(config.port()));
        this.bufferPool = new ByteBufferPool(MAX_PACKET_SIZE, 50); // Pool of 50 buffers
    }

    @Override
    public TransportType getType() {
        return TransportType.UDP;
    }

    @Override
    protected void doStart() throws Exception {
        socket = new DatagramSocket(config.port(),
                InetAddress.getByName(config.bindAddress()));

        receiveExecutor.submit(this::receiveLoop);
        log.info("UDP started", "port", config.port());
    }

    @Override
    protected void doStop() throws Exception {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        receiveExecutor.shutdownNow();
        log.info("UDP stopped");
    }

    @Override
    protected void doSend(byte[] data, InetSocketAddress destination) throws Exception {
        // CRITICAL FIX: Use data.length to avoid sending garbage padding/trailing zeros
        // DatagramPacket MUST be constructed with exact payload length, NOT buffer capacity
        // Using buffer size instead of actual data length causes "Invalid frame: bad payload length" errors
        //
        // CORRECT:   new DatagramPacket(data, data.length, address, port)
        // INCORRECT: new DatagramPacket(data, bufferSize, address, port)

        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Cannot send null or empty data");
        }
        if (destination == null) {
            throw new IllegalArgumentException("Destination address cannot be null");
        }
        if (data.length > MAX_PACKET_SIZE) {
            throw new IllegalArgumentException(
                "Data size " + data.length + " exceeds UDP max packet size " + MAX_PACKET_SIZE);
        }

        // Create packet with EXACT data length - this is critical to prevent corruption
        // The second parameter MUST be data.length, not any buffer capacity
        final int exactPayloadLength = data.length;
        DatagramPacket packet = new DatagramPacket(data, exactPayloadLength, destination);

        // Verify packet length matches data length before sending
        if (packet.getLength() != exactPayloadLength) {
            throw new IllegalStateException(
                "Packet length mismatch: expected " + exactPayloadLength +
                ", got " + packet.getLength());
        }

        socket.send(packet);

        log.debug("UDP packet sent",
                "destination", destination.toString(),
                "exactSize", exactPayloadLength,
                "verified", true);
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    private void receiveLoop() {
        while (isRunning() && !socket.isClosed()) {
            ByteBuffer buffer = bufferPool.acquireDirect();
            try {
                // Allocate buffer for receiving packet
                byte[] array = new byte[buffer.capacity()];
                DatagramPacket packet = new DatagramPacket(array, array.length);

                // Receive the packet
                socket.receive(packet);

                // Extract ONLY the actual received bytes (critical fix for frame corruption)
                // Do NOT pass the entire buffer - only copy the valid data
                int actualLength = packet.getLength();

                // Defensive validation: empty packets (network noise)
                if (actualLength == 0) {
                    log.debug("Received empty UDP packet (network noise), ignoring");
                    metrics.incrementCounter("transport.udp.noise.empty");
                    continue;
                }

                // Defensive validation: unreasonably small packets (< header size)
                // Frame header is 28 bytes minimum
                if (actualLength < 28) {
                    log.debug("Received undersized UDP packet (network noise), ignoring",
                            "size", actualLength);
                    metrics.incrementCounter("transport.udp.noise.undersized");
                    continue;
                }

                // Defensive validation: unreasonably large packets
                if (actualLength > MAX_PACKET_SIZE) {
                    log.debug("Received oversized UDP packet (network noise), ignoring",
                            "size", actualLength,
                            "max", MAX_PACKET_SIZE);
                    metrics.incrementCounter("transport.udp.noise.oversized");
                    continue;
                }

                byte[] data = new byte[actualLength];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, actualLength);

                // Get source address with defensive check
                InetSocketAddress source = (InetSocketAddress) packet.getSocketAddress();
                if (source == null) {
                    log.debug("Received packet with null source address (network noise), ignoring");
                    metrics.incrementCounter("transport.udp.noise.null_source");
                    continue;
                }

                // Log received packet for debugging
                log.debug("UDP packet received",
                        "source", source.toString(),
                        "size", actualLength);

                // Process the frame with ONLY valid bytes
                // Catch processing errors to prevent one bad packet from killing the receive loop
                try {
                    handleIncoming(data, source);
                    metrics.incrementCounter("transport.udp.packets.processed");
                } catch (Exception e) {
                    // Downgrade to DEBUG: malformed packets are expected network noise
                    log.debug("Error processing UDP packet (network noise), ignoring",
                            "source", source.toString(),
                            "size", actualLength,
                            "error", e.getClass().getSimpleName(),
                            "message", e.getMessage());
                    metrics.incrementCounter("transport.udp.noise.processing_error");
                    // Continue receiving - don't let one bad packet break the loop
                }

            } catch (SocketException e) {
                // Socket closed during shutdown - expected
                if (isRunning()) {
                    log.error("UDP socket error", e);
                    metrics.incrementCounter("transport.udp.errors.socket");
                }
                // Break loop on socket errors
                break;
            } catch (SocketTimeoutException e) {
                // Timeout on receive - expected, continue
                log.debug("UDP receive timeout (expected)");
                metrics.incrementCounter("transport.udp.timeouts");
            } catch (Exception e) {
                // Unexpected errors - log but continue (graceful degradation)
                if (isRunning()) {
                    log.debug("Unexpected UDP receive error, continuing",
                            "error", e.getClass().getSimpleName(),
                            "message", e.getMessage());
                    metrics.incrementCounter("transport.udp.errors.unexpected");
                }
                // Don't break - continue receiving
            } finally {
                bufferPool.release(buffer);
            }
        }

        log.info("UDP receive loop terminated",
                "reason", isRunning() ? "socket_closed" : "shutdown");
    }
}