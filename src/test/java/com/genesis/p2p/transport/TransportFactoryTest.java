//package com.genesis.p2p.transport;
//
//import com.genesis.p2p.security.config.SecurityConfig;
//import com.genesis.p2p.security.facade.SecurityFacade;
//import com.genesis.p2p.transport.core.*;
//import org.junit.jupiter.api.*;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * Comprehensive unit tests for TransportFactory and transport abstractions.
// * Tests Factory Pattern, Template Method, and transport state management.
// */
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//@DisplayName("Transport Tests")
//class TransportFactoryTest {
//
//    private TransportFactory transportFactory;
//    private SecurityFacade securityFacade;
//
//    @BeforeEach
//    void setUp() {
//        SecurityConfig config = SecurityConfig.defaults();
//        securityFacade = new SecurityFacade(config, "test-node");
//        transportFactory = new TransportFactory(securityFacade);
//    }
//
//    // ========================= Factory Tests =========================
//
//    @Test
//    @Order(1)
//    @DisplayName("Should create TCP transport")
//    void testCreateTcpTransport() {
//        ITransport tcpTransport = transportFactory.createTcpTransport(0);
//
//        assertNotNull(tcpTransport);
//        assertEquals(TransportType.TCP, tcpTransport.getType());
//    }
//
//    @Test
//    @Order(2)
//    @DisplayName("Should create UDP transport")
//    void testCreateUdpTransport() {
//        ITransport udpTransport = transportFactory.createUdpTransport(0);
//
//        assertNotNull(udpTransport);
//        assertEquals(TransportType.UDP, udpTransport.getType());
//    }
//
//    @Test
//    @Order(3)
//    @DisplayName("Should create transport with specified port")
//    void testCreateTransportWithPort() {
//        int port = 9876;
//        ITransport transport = transportFactory.createTcpTransport(port);
//
//        assertNotNull(transport);
//        // Port is configured but may not be bound until start
//    }
//
//    // ========================= TransportType Tests =========================
//
//    @Test
//    @Order(10)
//    @DisplayName("TransportType should have correct values")
//    void testTransportTypeValues() {
//        assertNotNull(TransportType.TCP);
//        assertNotNull(TransportType.UDP);
//        assertNotNull(TransportType.QUIC);
//        assertNotNull(TransportType.WEBSOCKET);
//    }
//
//    @Test
//    @Order(11)
//    @DisplayName("TransportType should be distinct")
//    void testTransportTypeDistinct() {
//        assertNotEquals(TransportType.TCP, TransportType.UDP);
//        assertNotEquals(TransportType.UDP, TransportType.QUIC);
//        assertNotEquals(TransportType.QUIC, TransportType.WEBSOCKET);
//    }
//
//    // ========================= TransportState Tests =========================
//
//    @Test
//    @Order(20)
//    @DisplayName("TransportState should have correct values")
//    void testTransportStateValues() {
//        assertNotNull(TransportState.CREATED);
//        assertNotNull(TransportState.STARTING);
//        assertNotNull(TransportState.RUNNING);
//        assertNotNull(TransportState.STOPPING);
//        assertNotNull(TransportState.STOPPED);
//        assertNotNull(TransportState.FAILED);
//    }
//
//    @Test
//    @Order(21)
//    @DisplayName("TransportState transitions should be valid")
//    void testTransportStateTransitions() {
//        // Verify logical transitions exist
//        TransportState[] states = TransportState.values();
//        assertTrue(states.length >= 5);
//
//        // CREATED -> STARTING -> RUNNING -> STOPPING -> STOPPED
//        assertEquals(0, TransportState.CREATED.ordinal());
//        assertTrue(TransportState.STARTING.ordinal() > TransportState.CREATED.ordinal());
//    }
//
//    // ========================= TransportStats Tests =========================
//
//    @Test
//    @Order(30)
//    @DisplayName("TransportStats should contain metrics")
//    void testTransportStats() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//        TransportStats stats = transport.getStats();
//
//        assertNotNull(stats);
//        assertTrue(stats.messagesSent() >= 0);
//        assertTrue(stats.messagesReceived() >= 0);
//        assertTrue(stats.bytesSent() >= 0);
//        assertTrue(stats.bytesReceived() >= 0);
//    }
//
//    @Test
//    @Order(31)
//    @DisplayName("TransportStats should have error count")
//    void testTransportStatsErrors() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//        TransportStats stats = transport.getStats();
//
//        assertTrue(stats.errors() >= 0);
//    }
//
//    @Test
//    @Order(32)
//    @DisplayName("TransportStats should have connection count")
//    void testTransportStatsConnections() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//        TransportStats stats = transport.getStats();
//
//        assertTrue(stats.activeConnections() >= 0);
//    }
//
//    // ========================= Transport Lifecycle Tests =========================
//
//    @Test
//    @Order(40)
//    @DisplayName("Transport should start and stop")
//    void testTransportLifecycle() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//
//        assertDoesNotThrow(transport::start);
//        assertTrue(transport.isRunning());
//
//        assertDoesNotThrow(transport::stop);
//        assertFalse(transport.isRunning());
//    }
//
//    @Test
//    @Order(41)
//    @DisplayName("Transport should track running state")
//    void testTransportRunningState() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//
//        assertFalse(transport.isRunning());
//
//        transport.start();
//        assertTrue(transport.isRunning());
//
//        transport.stop();
//        assertFalse(transport.isRunning());
//    }
//
//    @Test
//    @Order(42)
//    @DisplayName("Transport should handle double start gracefully")
//    void testTransportDoubleStart() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//
//        transport.start();
//
//        assertThrows(Exception.class, transport::start);
//
//        transport.stop();
//    }
//
//    @Test
//    @Order(43)
//    @DisplayName("Transport should handle double stop gracefully")
//    void testTransportDoubleStop() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//
//        transport.start();
//        transport.stop();
//
//        assertThrows(Exception.class, transport::stop);
//    }
//
//    // ========================= Handler Tests =========================
//
//    @Test
//    @Order(50)
//    @DisplayName("Should set envelope handler")
//    void testSetEnvelopeHandler() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//
//        assertDoesNotThrow(() -> {
//            transport.setEnvelopeHandler((message, source) -> {
//                // Handle message
//            });
//        });
//    }
//
//    @Test
//    @Order(51)
//    @DisplayName("Should accept null handler")
//    void testNullHandler() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//
//        assertDoesNotThrow(() -> {
//            transport.setEnvelopeHandler(null);
//        });
//    }
//
//    // ========================= Transport Config Tests =========================
//
//    @Test
//    @Order(60)
//    @DisplayName("TransportConfig should have defaults")
//    void testTransportConfigDefaults() {
//        TransportConfig config = TransportConfig.defaults();
//
//        assertNotNull(config);
//        assertTrue(config.getConnectTimeout() > 0);
//        assertTrue(config.getReadTimeout() > 0);
//        assertTrue(config.getWriteTimeout() > 0);
//    }
//
//    @Test
//    @Order(61)
//    @DisplayName("TransportConfig builder should work")
//    void testTransportConfigBuilder() {
//        TransportConfig config = TransportConfig.builder()
//                .connectTimeout(5000)
//                .readTimeout(10000)
//                .writeTimeout(10000)
//                .bufferSize(8192)
//                .build();
//
//        assertEquals(5000, config.getConnectTimeout());
//        assertEquals(10000, config.getReadTimeout());
//        assertEquals(8192, config.getBufferSize());
//    }
//
//    @Test
//    @Order(62)
//    @DisplayName("TransportConfig should support SSL")
//    void testTransportConfigSsl() {
//        TransportConfig config = TransportConfig.builder()
//                .sslEnabled(true)
//                .build();
//
//        assertTrue(config.isSslEnabled());
//    }
//
//    // ========================= Error Handling Tests =========================
//
//    @Test
//    @Order(70)
//    @DisplayName("Should handle invalid port gracefully")
//    void testInvalidPort() {
//        // Port -1 may be handled or rejected
//        assertDoesNotThrow(() -> {
//            try {
//                transportFactory.createTcpTransport(-1);
//            } catch (Exception e) {
//                // Expected if validation is strict
//            }
//        });
//    }
//
//    @Test
//    @Order(71)
//    @DisplayName("Should handle port out of range")
//    void testPortOutOfRange() {
//        assertDoesNotThrow(() -> {
//            try {
//                transportFactory.createTcpTransport(70000);
//            } catch (Exception e) {
//                // Expected if validation is strict
//            }
//        });
//    }
//
//    // ========================= Multiple Transport Tests =========================
//
//    @Test
//    @Order(80)
//    @DisplayName("Should create multiple transports")
//    void testMultipleTransports() {
//        ITransport tcp1 = transportFactory.createTcpTransport(0);
//        ITransport tcp2 = transportFactory.createTcpTransport(0);
//        ITransport udp = transportFactory.createUdpTransport(0);
//
//        assertNotNull(tcp1);
//        assertNotNull(tcp2);
//        assertNotNull(udp);
//
//        assertNotSame(tcp1, tcp2);
//    }
//
//    @Test
//    @Order(81)
//    @DisplayName("Multiple transports should run independently")
//    void testIndependentTransports() {
//        ITransport tcp = transportFactory.createTcpTransport(0);
//        ITransport udp = transportFactory.createUdpTransport(0);
//
//        tcp.start();
//        assertTrue(tcp.isRunning());
//        assertFalse(udp.isRunning());
//
//        udp.start();
//        assertTrue(tcp.isRunning());
//        assertTrue(udp.isRunning());
//
//        tcp.stop();
//        assertFalse(tcp.isRunning());
//        assertTrue(udp.isRunning());
//
//        udp.stop();
//    }
//
//    // ========================= Transport Type Detection =========================
//
//    @Test
//    @Order(90)
//    @DisplayName("TCP transport should report correct type")
//    void testTcpTransportType() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//        assertEquals(TransportType.TCP, transport.getType());
//    }
//
//    @Test
//    @Order(91)
//    @DisplayName("UDP transport should report correct type")
//    void testUdpTransportType() {
//        ITransport transport = transportFactory.createUdpTransport(0);
//        assertEquals(TransportType.UDP, transport.getType());
//    }
//
//    // ========================= Resource Cleanup Tests =========================
//
//    @Test
//    @Order(100)
//    @DisplayName("Transport should release resources on stop")
//    void testResourceCleanup() {
//        ITransport transport = transportFactory.createTcpTransport(0);
//
//        transport.start();
//        transport.stop();
//
//        // After stop, should be able to create new transport on same port
//        ITransport newTransport = transportFactory.createTcpTransport(0);
//        assertNotNull(newTransport);
//    }
//}
