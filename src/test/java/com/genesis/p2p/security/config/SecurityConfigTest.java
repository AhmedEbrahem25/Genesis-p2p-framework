package com.genesis.p2p.security.config;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SecurityConfig Tests")
class SecurityConfigTest extends BaseUnitTest {

    @Test
    @DisplayName("Should create default config")
    void testDefaults() {
        SecurityConfig config = SecurityConfig.defaults();

        assertNotNull(config);
        assertTrue(config.encryptionEnabled());
        assertEquals("AES-GCM", config.cryptoAlgorithm());
        assertEquals(256, config.keySize());
    }

    @Test
    @DisplayName("Should have valid session timeout")
    void testSessionTimeout() {
        SecurityConfig config = SecurityConfig.defaults();

        assertNotNull(config.sessionTimeout());
        assertTrue(config.sessionTimeout().toMillis() > 0);
    }

    @Test
    @DisplayName("Should have default trust level")
    void testDefaultTrustLevel() {
        SecurityConfig config = SecurityConfig.defaults();

        assertTrue(config.defaultTrustLevel() >= 0);
        assertTrue(config.defaultTrustLevel() <= 100);
    }

    @Test
    @DisplayName("Should create high security config")
    void testHighSecurity() {
        SecurityConfig config = SecurityConfig.highSecurity();

        assertTrue(config.encryptionEnabled());
        assertTrue(config.requireSignatures());
        assertEquals(0, config.defaultTrustLevel());
    }

    @Test
    @DisplayName("Should use builder to create custom config")
    void testBuilder() {
        SecurityConfig config = SecurityConfig.builder()
            .encryptionEnabled(true)
            .cryptoAlgorithm("AES-GCM")
            .keySize(128)
            .sessionTimeout(Duration.ofMinutes(30))
            .requireSignatures(true)
            .defaultTrustLevel(75)
            .build();

        assertTrue(config.encryptionEnabled());
        assertEquals("AES-GCM", config.cryptoAlgorithm());
        assertEquals(128, config.keySize());
        assertEquals(Duration.ofMinutes(30), config.sessionTimeout());
        assertTrue(config.requireSignatures());
        assertEquals(75, config.defaultTrustLevel());
    }

    @Test
    @DisplayName("Builder should have defaults")
    void testBuilderDefaults() {
        SecurityConfig config = SecurityConfig.builder().build();

        assertNotNull(config);
        assertTrue(config.encryptionEnabled());
        assertNotNull(config.cryptoAlgorithm());
        assertTrue(config.keySize() > 0);
        assertNotNull(config.sessionTimeout());
    }
}

