package com.genesis.p2p.integration;

import com.genesis.p2p.testutil.base.BaseIntegrationTest;
import com.genesis.p2p.security.config.SecurityConfig;
import com.genesis.p2p.security.hmac.HmacService;
import com.genesis.p2p.security.trust.TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@DisplayName("Security Integration Tests")
class SecurityIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should configure security with defaults")
    void testDefaultSecurityConfig() {
        SecurityConfig config = SecurityConfig.defaults();

        assertTrue(config.encryptionEnabled());
        assertEquals("AES-GCM", config.cryptoAlgorithm());
        assertEquals(256, config.keySize());
        assertNotNull(config.sessionTimeout());
    }

    @Test
    @DisplayName("Should configure high security mode")
    void testHighSecurityConfig() {
        SecurityConfig config = SecurityConfig.highSecurity();

        assertTrue(config.encryptionEnabled());
        assertTrue(config.requireSignatures());
        assertEquals(0, config.defaultTrustLevel());
    }

    @Test
    @DisplayName("Should compute and verify HMAC for message integrity")
    void testMessageIntegrity() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);

        byte[] message = "Important P2P message content".getBytes();

        // Compute HMAC
        byte[] hmac = HmacService.computeHmac(message, key);

        // Verify HMAC
        assertTrue(HmacService.verifyHmac(message, key, hmac));

        // Tampered message should fail verification
        byte[] tamperedMessage = "Tampered P2P message content".getBytes();
        assertFalse(HmacService.verifyHmac(tamperedMessage, key, hmac));
    }

    @Test
    @DisplayName("Should manage peer trust levels")
    void testPeerTrustManagement() {
        TrustManager trustManager = new TrustManager(50, 60);

        // New peer gets default trust
        assertEquals(50, trustManager.getTrustLevel("new-peer"));
        assertFalse(trustManager.isTrusted("new-peer")); // Below threshold

        // Trust a peer
        trustManager.trustPeer("trusted-peer", 80);
        assertTrue(trustManager.isTrusted("trusted-peer"));

        // Untrust a peer
        trustManager.untrustPeer("trusted-peer");
        assertFalse(trustManager.isTrusted("trusted-peer"));
    }

    @Test
    @DisplayName("Should handle trust-based access control")
    void testTrustBasedAccessControl() {
        TrustManager trustManager = new TrustManager(30, 50);

        // Simulate different peer trust levels
        trustManager.trustPeer("admin-peer", 100);
        trustManager.trustPeer("normal-peer", 60);
        trustManager.trustPeer("new-peer", 40);
        trustManager.trustPeer("suspicious-peer", 10);

        assertTrue(trustManager.isTrusted("admin-peer"));
        assertTrue(trustManager.isTrusted("normal-peer"));
        assertFalse(trustManager.isTrusted("new-peer"));
        assertFalse(trustManager.isTrusted("suspicious-peer"));
    }

    @Test
    @DisplayName("Should use secure configuration builder")
    void testSecurityConfigBuilder() {
        SecurityConfig config = SecurityConfig.builder()
            .encryptionEnabled(true)
            .cryptoAlgorithm("AES-GCM")
            .keySize(256)
            .requireSignatures(true)
            .defaultTrustLevel(25)
            .build();

        assertTrue(config.encryptionEnabled());
        assertTrue(config.requireSignatures());
        assertEquals(256, config.keySize());
        assertEquals(25, config.defaultTrustLevel());
    }

    @Test
    @DisplayName("Should handle different key sizes")
    void testHmacWithDifferentKeySizes() {
        byte[] message = "Test message".getBytes();

        // 16 bytes (128-bit) key
        byte[] key128 = new byte[16];
        new SecureRandom().nextBytes(key128);
        byte[] hmac128 = HmacService.computeHmac(message, key128);
        assertTrue(HmacService.verifyHmac(message, key128, hmac128));

        // 32 bytes (256-bit) key
        byte[] key256 = new byte[32];
        new SecureRandom().nextBytes(key256);
        byte[] hmac256 = HmacService.computeHmac(message, key256);
        assertTrue(HmacService.verifyHmac(message, key256, hmac256));

        // Different keys produce different HMACs
        assertFalse(java.util.Arrays.equals(hmac128, hmac256));
    }
}

