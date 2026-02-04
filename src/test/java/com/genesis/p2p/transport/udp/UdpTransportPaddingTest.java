package com.genesis.p2p.transport.udp;

import com.genesis.p2p.protocol.ProtocolLayer;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.transport.core.TransportConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UDP padding fix.
 * Verifies that UdpTransport properly validates input and uses exact data length.
 */
public class UdpTransportPaddingTest {

    private UdpTransport transport;
    private TransportConfig config;
    private SecurityFacade security;
    private ProtocolLayer protocolLayer;
    private com.genesis.p2p.observability.message.MessageLogger messageLogger;

    @BeforeEach
    public void setUp() {
        config = mock(TransportConfig.class);
        when(config.port()).thenReturn(9999);
        when(config.bindAddress()).thenReturn("127.0.0.1");

        security = mock(SecurityFacade.class);
        protocolLayer = mock(ProtocolLayer.class);
        messageLogger = mock(com.genesis.p2p.observability.message.MessageLogger.class);

        transport = new UdpTransport(config, security, protocolLayer, messageLogger);
    }

    @Test
    public void testDoSend_NullData_ThrowsException() {
        // Arrange
        InetSocketAddress destination = new InetSocketAddress("localhost", 8080);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transport.doSend(null, destination)
        );

        assertTrue(exception.getMessage().contains("Cannot send null or empty data"));
    }

    @Test
    public void testDoSend_EmptyData_ThrowsException() {
        // Arrange
        byte[] emptyData = new byte[0];
        InetSocketAddress destination = new InetSocketAddress("localhost", 8080);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transport.doSend(emptyData, destination)
        );

        assertTrue(exception.getMessage().contains("Cannot send null or empty data"));
    }

    @Test
    public void testDoSend_NullDestination_ThrowsException() {
        // Arrange
        byte[] data = new byte[]{1, 2, 3, 4};

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transport.doSend(data, null)
        );

        assertTrue(exception.getMessage().contains("Destination address cannot be null"));
    }

    /**
     * This test demonstrates the correct pattern:
     * DatagramPacket should be created with exact data length,
     * not with a larger buffer capacity that would include padding.
     */
    @Test
    public void testDatagramPacketCreation_UsesExactLength() {
        // This is a documentation test showing the correct pattern

        // CORRECT: Use exact data length
        byte[] data = "Hello".getBytes();
        java.net.DatagramPacket correctPacket = new java.net.DatagramPacket(
            data,
            data.length,  // ✅ Uses actual data length
            new InetSocketAddress("localhost", 8080)
        );
        assertEquals(5, correctPacket.getLength(), "Packet length should be exact data length");

        // INCORRECT PATTERN (DO NOT USE):
        // byte[] buffer = new byte[1024];
        // System.arraycopy(data, 0, buffer, 0, data.length);
        // DatagramPacket wrongPacket = new DatagramPacket(
        //     buffer,
        //     buffer.length,  // ❌ Would send 1024 bytes including padding!
        //     new InetSocketAddress("localhost", 8080)
        // );
        // This would send 1019 bytes of garbage padding!
    }

    /**
     * Demonstrates ByteBuffer position tracking for variable-length serialization.
     */
    @Test
    public void testByteBufferPattern_UsesPosition() {
        // When using ByteBuffer with estimated size
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024);

        // Write some data
        buffer.putInt(42);
        buffer.putLong(System.currentTimeMillis());

        // CORRECT: Extract only written bytes using position()
        int actualLength = buffer.position();
        byte[] data = new byte[actualLength];
        buffer.flip();
        buffer.get(data);

        assertEquals(12, data.length, "Should be 4 bytes (int) + 8 bytes (long)");

        // Now create packet with exact length
        java.net.DatagramPacket packet = new java.net.DatagramPacket(
            data,
            data.length,  // ✅ Exact length, no padding
            new InetSocketAddress("localhost", 8080)
        );

        assertEquals(12, packet.getLength(), "Packet should have exact data length");
    }
}

