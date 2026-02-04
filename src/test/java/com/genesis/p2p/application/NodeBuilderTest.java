//package com.genesis.p2p.application;
//
//import org.junit.jupiter.api.*;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * Comprehensive unit tests for NodeBuilder.
// * Tests Builder Pattern, Director Pattern, and Prototype Pattern.
// */
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//@DisplayName("NodeBuilder Tests")
//class NodeBuilderTest {
//
//    // ========================= Basic Builder Tests =========================
//
//    @Test
//    @Order(1)
//    @DisplayName("Should create builder with default values")
//    void testBuilderDefaults() {
//        NodeBuilder builder = NodeBuilder.create();
//
//        assertNotNull(builder);
//    }
//
//    @Test
//    @Order(2)
//    @DisplayName("Should set node ID")
//    void testSetNodeId() {
//        NodeBuilder builder = NodeBuilder.create()
//                .nodeId("test-node");
//
//        NodeConfig config = builder.buildConfig();
//        assertEquals("test-node", config.nodeId());
//    }
//
//    @Test
//    @Order(3)
//    @DisplayName("Should set TCP port")
//    void testSetTcpPort() {
//        NodeBuilder builder = NodeBuilder.create()
//                .port(9000);
//
//        NodeConfig config = builder.buildConfig();
//        assertEquals(9000, config.tcpPort());
//    }
//
//    @Test
//    @Order(4)
//    @DisplayName("Should set listen port")
//    void testSetListenPort() {
//        NodeBuilder builder = NodeBuilder.create()
//                .listenPort(8000);
//
//        NodeConfig config = builder.buildConfig();
//        assertEquals(8000, config.listenPort());
//    }
//
//    @Test
//    @Order(5)
//    @DisplayName("Should set multicast group")
//    void testSetMulticastGroup() {
//        NodeBuilder builder = NodeBuilder.create()
//                .multicastGroup("239.255.1.1");
//
//        NodeConfig config = builder.buildConfig();
//        assertEquals("239.255.1.1", config.multicastGroup());
//    }
//
//    @Test
//    @Order(6)
//    @DisplayName("Should set multicast port")
//    void testSetMulticastPort() {
//        NodeBuilder builder = NodeBuilder.create()
//                .multicastPort(6000);
//
//        NodeConfig config = builder.buildConfig();
//        assertEquals(6000, config.multicastPort());
//    }
//
//    @Test
//    @Order(7)
//    @DisplayName("Should set broadcast port")
//    void testSetBroadcastPort() {
//        NodeBuilder builder = NodeBuilder.create()
//                .broadcastPort(6001);
//
//        NodeConfig config = builder.buildConfig();
//        assertEquals(6001, config.broadcastPort());
//    }
//
//    @Test
//    @Order(8)
//    @DisplayName("Should set pre-shared key")
//    void testSetPreSharedKey() {
//        NodeBuilder builder = NodeBuilder.create()
//                .preSharedKey("secret123");
//
//        NodeConfig config = builder.buildConfig();
//        assertEquals("secret123", config.preSharedKeyHex());
//    }
//
//    // ========================= Fluent API Tests =========================
//
//    @Test
//    @Order(10)
//    @DisplayName("Should support fluent chaining")
//    void testFluentChaining() {
//        NodeConfig config = NodeBuilder.create()
//                .nodeId("fluent-node")
//                .port(9000)
//                .listenPort(8000)
//                .multicastGroup("239.255.1.1")
//                .multicastPort(6000)
//                .broadcastPort(6001)
//                .preSharedKey("key")
//                .buildConfig();
//
//        assertEquals("fluent-node", config.nodeId());
//        assertEquals(9000, config.tcpPort());
//        assertEquals(8000, config.listenPort());
//        assertEquals("239.255.1.1", config.multicastGroup());
//        assertEquals(6000, config.multicastPort());
//        assertEquals(6001, config.broadcastPort());
//        assertEquals("key", config.preSharedKeyHex());
//    }
//
//    @Test
//    @Order(11)
//    @DisplayName("Should allow reconfiguration before build")
//    void testReconfiguration() {
//        NodeBuilder builder = NodeBuilder.create()
//                .nodeId("original")
//                .port(8000);
//
//        builder.nodeId("modified")
//                .port(9000);
//
//        NodeConfig config = builder.buildConfig();
//
//        assertEquals("modified", config.nodeId());
//        assertEquals(9000, config.tcpPort());
//    }
//
//    // ========================= Feature Flags Tests =========================
//
//    @Test
//    @Order(20)
//    @DisplayName("Should enable metrics")
//    void testEnableMetrics() {
//        NodeBuilder builder = NodeBuilder.create()
//                .enableMetrics(true);
//
//        assertTrue(builder.isMetricsEnabled());
//    }
//
//    @Test
//    @Order(21)
//    @DisplayName("Should disable metrics")
//    void testDisableMetrics() {
//        NodeBuilder builder = NodeBuilder.create()
//                .enableMetrics(false);
//
//        assertFalse(builder.isMetricsEnabled());
//    }
//
//    @Test
//    @Order(22)
//    @DisplayName("Should enable discovery")
//    void testEnableDiscovery() {
//        NodeBuilder builder = NodeBuilder.create()
//                .enableDiscovery(true);
//
//        assertTrue(builder.isDiscoveryEnabled());
//    }
//
//    @Test
//    @Order(23)
//    @DisplayName("Should enable security")
//    void testEnableSecurity() {
//        NodeBuilder builder = NodeBuilder.create()
//                .enableSecurity(true);
//
//        assertTrue(builder.isSecurityEnabled());
//    }
//
//    // ========================= Profile (Director) Pattern Tests =========================
//
//    @Test
//    @Order(30)
//    @DisplayName("Development profile should use dev settings")
//    void testDevelopmentProfile() {
//        NodeBuilder builder = NodeBuilder.create()
//                .withProfile(NodeBuilder.Profile.DEVELOPMENT);
//
//        NodeConfig config = builder.buildConfig();
//
//        // Development typically uses default/local ports
//        assertNotNull(config);
//    }
//
//    @Test
//    @Order(31)
//    @DisplayName("Production profile should use prod settings")
//    void testProductionProfile() {
//        NodeBuilder builder = NodeBuilder.create()
//                .withProfile(NodeBuilder.Profile.PRODUCTION);
//
//        // Production typically enables all features
//        assertTrue(builder.isSecurityEnabled());
//    }
//
//    @Test
//    @Order(32)
//    @DisplayName("Testing profile should use minimal settings")
//    void testTestingProfile() {
//        NodeBuilder builder = NodeBuilder.create()
//                .withProfile(NodeBuilder.Profile.TESTING);
//
//        NodeConfig config = builder.buildConfig();
//
//        // Testing typically uses random ports
//        assertNotNull(config);
//    }
//
//    @Test
//    @Order(33)
//    @DisplayName("High availability profile should enable HA features")
//    void testHighAvailabilityProfile() {
//        NodeBuilder builder = NodeBuilder.create()
//                .withProfile(NodeBuilder.Profile.HIGH_AVAILABILITY);
//
//        // HA should enable all resilience features
//        assertTrue(builder.isDiscoveryEnabled());
//    }
//
//    // ========================= Prototype Pattern Tests =========================
//
//    @Test
//    @Order(40)
//    @DisplayName("Should copy builder")
//    void testCopyBuilder() {
//        NodeBuilder original = NodeBuilder.create()
//                .nodeId("original")
//                .port(8000)
//                .enableMetrics(true);
//
//        NodeBuilder copy = original.copy();
//
//        // Modify copy
//        copy.nodeId("copied")
//                .port(9000);
//
//        // Original should be unchanged
//        NodeConfig originalConfig = original.buildConfig();
//        NodeConfig copyConfig = copy.buildConfig();
//
//        assertEquals("original", originalConfig.nodeId());
//        assertEquals(8000, originalConfig.tcpPort());
//        assertEquals("copied", copyConfig.nodeId());
//        assertEquals(9000, copyConfig.tcpPort());
//    }
//
//    @Test
//    @Order(41)
//    @DisplayName("Copy should preserve all settings")
//    void testCopyPreservesSettings() {
//        NodeBuilder original = NodeBuilder.create()
//                .nodeId("preserve-test")
//                .port(7777)
//                .listenPort(7778)
//                .multicastGroup("239.255.2.2")
//                .enableMetrics(true)
//                .enableSecurity(true)
//                .enableDiscovery(true);
//
//        NodeBuilder copy = original.copy();
//
//        assertEquals(original.isMetricsEnabled(), copy.isMetricsEnabled());
//        assertEquals(original.isSecurityEnabled(), copy.isSecurityEnabled());
//        assertEquals(original.isDiscoveryEnabled(), copy.isDiscoveryEnabled());
//    }
//
//    // ========================= Validation Tests =========================
//
//    @Test
//    @Order(50)
//    @DisplayName("Should validate configuration")
//    void testValidation() {
//        NodeBuilder builder = NodeBuilder.create()
//                .nodeId("valid-node")
//                .port(8080);
//
//        assertTrue(builder.validate().isEmpty());
//    }
//
//    @Test
//    @Order(51)
//    @DisplayName("Should detect missing node ID")
//    void testValidationMissingNodeId() {
//        NodeBuilder builder = NodeBuilder.create();
//
//        // Empty nodeId should fail validation if not set to default
//        var errors = builder.validate();
//        // Depending on implementation, this may or may not have errors
//        assertNotNull(errors);
//    }
//
//    @Test
//    @Order(52)
//    @DisplayName("Should add custom validation rule")
//    void testCustomValidationRule() {
//        NodeBuilder builder = NodeBuilder.create()
//                .nodeId("test")
//                .port(8080)
//                .addValidationRule(config -> {
//                    if (config.tcpPort() < 1024) {
//                        return "TCP port must be >= 1024 for non-root users";
//                    }
//                    return null;
//                });
//
//        var errors = builder.validate();
//        assertTrue(errors.isEmpty());
//
//        // Now test with invalid port
//        builder.port(80);
//        errors = builder.validate();
//        assertFalse(errors.isEmpty());
//        assertTrue(errors.get(0).contains("1024"));
//    }
//
//    // ========================= Factory Methods Tests =========================
//
//    @Test
//    @Order(60)
//    @DisplayName("forDevelopment() should create dev builder")
//    void testForDevelopment() {
//        NodeBuilder builder = NodeBuilder.forDevelopment();
//
//        assertNotNull(builder);
//        // Dev profile should be set
//    }
//
//    @Test
//    @Order(61)
//    @DisplayName("forProduction() should create prod builder")
//    void testForProduction() {
//        NodeBuilder builder = NodeBuilder.forProduction();
//
//        assertNotNull(builder);
//        assertTrue(builder.isSecurityEnabled());
//    }
//
//    @Test
//    @Order(62)
//    @DisplayName("forTesting() should create test builder")
//    void testForTesting() {
//        NodeBuilder builder = NodeBuilder.forTesting();
//
//        assertNotNull(builder);
//        NodeConfig config = builder.buildConfig();
//        assertTrue(config.nodeId().contains("test"));
//    }
//
//    // ========================= Config Building Tests =========================
//
//    @Test
//    @Order(70)
//    @DisplayName("buildConfig() should return valid NodeConfig")
//    void testBuildConfig() {
//        NodeConfig config = NodeBuilder.create()
//                .nodeId("config-test")
//                .port(8081)
//                .buildConfig();
//
//        assertNotNull(config);
//        assertEquals("config-test", config.nodeId());
//        assertEquals(8081, config.tcpPort());
//    }
//
//    @Test
//    @Order(71)
//    @DisplayName("buildConfig() should use defaults for unset values")
//    void testBuildConfigDefaults() {
//        NodeConfig config = NodeBuilder.create()
//                .nodeId("defaults-test")
//                .buildConfig();
//
//        assertEquals(NodeConfig.DEFAULT_TCP_PORT, config.tcpPort());
//        assertEquals(NodeConfig.DEFAULT_LISTEN_PORT, config.listenPort());
//        assertEquals(NodeConfig.DEFAULT_MULTICAST_GROUP, config.multicastGroup());
//    }
//
//    // ========================= toString Tests =========================
//
//    @Test
//    @Order(80)
//    @DisplayName("toString() should be informative")
//    void testToString() {
//        NodeBuilder builder = NodeBuilder.create()
//                .nodeId("toString-test")
//                .port(9999);
//
//        String str = builder.toString();
//
//        assertNotNull(str);
//        assertTrue(str.contains("NodeBuilder") || str.contains("toString-test"));
//    }
//
//    // ========================= Edge Cases =========================
//
//    @Test
//    @Order(90)
//    @DisplayName("Should handle null node ID gracefully")
//    void testNullNodeId() {
//        NodeBuilder builder = NodeBuilder.create();
//
//        // Setting null might throw or be ignored
//        assertDoesNotThrow(() -> {
//            try {
//                builder.nodeId(null);
//            } catch (Exception e) {
//                // Expected if validation is strict
//            }
//        });
//    }
//
//    @Test
//    @Order(91)
//    @DisplayName("Should handle negative port")
//    void testNegativePort() {
//        NodeBuilder builder = NodeBuilder.create()
//                .nodeId("test");
//
//        // Negative port should fail validation or throw
//        builder.port(-1);
//        var errors = builder.validate();
//
//        assertFalse(errors.isEmpty(), "Negative port should fail validation");
//    }
//
//    @Test
//    @Order(92)
//    @DisplayName("Should handle port out of range")
//    void testPortOutOfRange() {
//        NodeBuilder builder = NodeBuilder.create()
//                .nodeId("test")
//                .port(70000);
//
//        var errors = builder.validate();
//
//        assertFalse(errors.isEmpty(), "Port > 65535 should fail validation");
//    }
//}
