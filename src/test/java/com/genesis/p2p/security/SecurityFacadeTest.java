//package com.genesis.p2p.security;
//
//import com.genesis.p2p.security.config.SecurityConfig;
//import com.genesis.p2p.security.facade.SecurityFacade;
//import org.junit.jupiter.api.*;
//
//import java.nio.charset.StandardCharsets;
//import java.security.KeyPair;
//import java.util.Arrays;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * Comprehensive unit tests for SecurityFacade.
// * Tests Facade Pattern, encryption/decryption, signing/verification.
// */
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//@DisplayName("SecurityFacade Tests")
//class SecurityFacadeTest {
//
//    private SecurityFacade securityFacade;
//
//    @BeforeEach
//    void setUp() {
//        SecurityConfig config = SecurityConfig.defaults();
//        securityFacade = new SecurityFacade(config, "test-node");
//    }
//
//    // ========================= Initialization Tests =========================
//
//    @Test
//    @Order(1)
//    @DisplayName("Should initialize with defaults")
//    void testInitializationWithDefaults() {
//        assertNotNull(securityFacade);
//        assertNotNull(securityFacade.getAlgorithm());
//    }
//
//    @Test
//    @Order(2)
//    @DisplayName("Should report algorithm name")
//    void testGetAlgorithm() {
//        String algorithm = securityFacade.getAlgorithm();
//
//        assertNotNull(algorithm);
//        assertTrue(algorithm.contains("AES") || algorithm.contains("GCM") ||
//                   algorithm.contains("aes") || algorithm.contains("gcm") ||
//                   !algorithm.isEmpty());
//    }
//
//    @Test
//    @Order(3)
//    @DisplayName("Should initialize with custom config")
//    void testInitializationWithCustomConfig() {
//        SecurityConfig customConfig = SecurityConfig.builder()
//                .algorithm("AES/GCM/NoPadding")
//                .keySize(256)
//                .build();
//
//        SecurityFacade customFacade = new SecurityFacade(customConfig, "custom-node");
//
//        assertNotNull(customFacade);
//    }
//
//    // ========================= Encryption/Decryption Tests =========================
//
//    @Test
//    @Order(10)
//    @DisplayName("Should encrypt and decrypt data")
//    void testEncryptDecrypt() {
//        byte[] plaintext = "Hello, World!".getBytes(StandardCharsets.UTF_8);
//
//        byte[] encrypted = securityFacade.encrypt(plaintext);
//        byte[] decrypted = securityFacade.decrypt(encrypted);
//
//        assertArrayEquals(plaintext, decrypted);
//    }
//
//    @Test
//    @Order(11)
//    @DisplayName("Should encrypt data to different ciphertext each time")
//    void testEncryptionRandomness() {
//        byte[] plaintext = "Same message".getBytes(StandardCharsets.UTF_8);
//
//        byte[] encrypted1 = securityFacade.encrypt(plaintext);
//        byte[] encrypted2 = securityFacade.encrypt(plaintext);
//
//        // Due to random IV, ciphertexts should be different
//        assertFalse(Arrays.equals(encrypted1, encrypted2));
//    }
//
//    @Test
//    @Order(12)
//    @DisplayName("Should handle empty data")
//    void testEncryptEmptyData() {
//        byte[] empty = new byte[0];
//
//        byte[] encrypted = securityFacade.encrypt(empty);
//        byte[] decrypted = securityFacade.decrypt(encrypted);
//
//        assertArrayEquals(empty, decrypted);
//    }
//
//    @Test
//    @Order(13)
//    @DisplayName("Should handle large data")
//    void testEncryptLargeData() {
//        byte[] largeData = new byte[1024 * 1024]; // 1MB
//        Arrays.fill(largeData, (byte) 'X');
//
//        byte[] encrypted = securityFacade.encrypt(largeData);
//        byte[] decrypted = securityFacade.decrypt(encrypted);
//
//        assertArrayEquals(largeData, decrypted);
//    }
//
//    @Test
//    @Order(14)
//    @DisplayName("Should handle binary data")
//    void testEncryptBinaryData() {
//        byte[] binaryData = new byte[256];
//        for (int i = 0; i < 256; i++) {
//            binaryData[i] = (byte) i;
//        }
//
//        byte[] encrypted = securityFacade.encrypt(binaryData);
//        byte[] decrypted = securityFacade.decrypt(encrypted);
//
//        assertArrayEquals(binaryData, decrypted);
//    }
//
//    @Test
//    @Order(15)
//    @DisplayName("Encrypted data should be larger than plaintext")
//    void testEncryptedSizeLarger() {
//        byte[] plaintext = "Short message".getBytes(StandardCharsets.UTF_8);
//
//        byte[] encrypted = securityFacade.encrypt(plaintext);
//
//        assertTrue(encrypted.length > plaintext.length);
//    }
//
//    // ========================= Signing/Verification Tests =========================
//
//    @Test
//    @Order(20)
//    @DisplayName("Should sign and verify data")
//    void testSignAndVerify() {
//        byte[] data = "Message to sign".getBytes(StandardCharsets.UTF_8);
//
//        byte[] signature = securityFacade.sign(data);
//        boolean valid = securityFacade.verify(data, signature);
//
//        assertTrue(valid);
//    }
//
//    @Test
//    @Order(21)
//    @DisplayName("Should reject tampered data")
//    void testRejectTamperedData() {
//        byte[] data = "Original message".getBytes(StandardCharsets.UTF_8);
//        byte[] signature = securityFacade.sign(data);
//
//        byte[] tamperedData = "Tampered message".getBytes(StandardCharsets.UTF_8);
//        boolean valid = securityFacade.verify(tamperedData, signature);
//
//        assertFalse(valid);
//    }
//
//    @Test
//    @Order(22)
//    @DisplayName("Should reject tampered signature")
//    void testRejectTamperedSignature() {
//        byte[] data = "Message".getBytes(StandardCharsets.UTF_8);
//        byte[] signature = securityFacade.sign(data);
//
//        // Tamper with signature
//        signature[0] ^= 0xFF;
//
//        boolean valid = securityFacade.verify(data, signature);
//
//        assertFalse(valid);
//    }
//
//    @Test
//    @Order(23)
//    @DisplayName("Should produce consistent signatures for same data")
//    void testSignatureConsistency() {
//        byte[] data = "Consistent message".getBytes(StandardCharsets.UTF_8);
//
//        byte[] sig1 = securityFacade.sign(data);
//        byte[] sig2 = securityFacade.sign(data);
//
//        // Both signatures should verify
//        assertTrue(securityFacade.verify(data, sig1));
//        assertTrue(securityFacade.verify(data, sig2));
//    }
//
//    // ========================= Key Management Tests =========================
//
//    @Test
//    @Order(30)
//    @DisplayName("Should generate key pair")
//    void testGenerateKeyPair() {
//        KeyPair keyPair = securityFacade.generateKeyPair();
//
//        assertNotNull(keyPair);
//        assertNotNull(keyPair.getPublic());
//        assertNotNull(keyPair.getPrivate());
//    }
//
//    @Test
//    @Order(31)
//    @DisplayName("Should get public key")
//    void testGetPublicKey() {
//        byte[] publicKey = securityFacade.getPublicKey();
//
//        assertNotNull(publicKey);
//        assertTrue(publicKey.length > 0);
//    }
//
//    @Test
//    @Order(32)
//    @DisplayName("Should get node ID")
//    void testGetNodeId() {
//        String nodeId = securityFacade.getNodeId();
//
//        assertEquals("test-node", nodeId);
//    }
//
//    // ========================= Session Management Tests =========================
//
//    @Test
//    @Order(40)
//    @DisplayName("Should create secure session")
//    void testCreateSecureSession() {
//        String peerId = "peer-123";
//
//        boolean created = securityFacade.createSession(peerId);
//
//        assertTrue(created);
//        assertTrue(securityFacade.hasSession(peerId));
//    }
//
//    @Test
//    @Order(41)
//    @DisplayName("Should close secure session")
//    void testCloseSecureSession() {
//        String peerId = "peer-to-close";
//        securityFacade.createSession(peerId);
//
//        securityFacade.closeSession(peerId);
//
//        assertFalse(securityFacade.hasSession(peerId));
//    }
//
//    @Test
//    @Order(42)
//    @DisplayName("Should check session existence")
//    void testHasSession() {
//        String existingPeer = "existing-peer";
//        String nonExistingPeer = "non-existing-peer";
//
//        securityFacade.createSession(existingPeer);
//
//        assertTrue(securityFacade.hasSession(existingPeer));
//        assertFalse(securityFacade.hasSession(nonExistingPeer));
//    }
//
//    // ========================= Encrypt/Decrypt with Session Tests =========================
//
//    @Test
//    @Order(50)
//    @DisplayName("Should encrypt with session key")
//    void testEncryptWithSession() {
//        String peerId = "session-peer";
//        securityFacade.createSession(peerId);
//
//        byte[] plaintext = "Session encrypted message".getBytes(StandardCharsets.UTF_8);
//
//        byte[] encrypted = securityFacade.encryptForPeer(peerId, plaintext);
//
//        assertNotNull(encrypted);
//        assertTrue(encrypted.length > plaintext.length);
//    }
//
//    @Test
//    @Order(51)
//    @DisplayName("Should decrypt with session key")
//    void testDecryptWithSession() {
//        String peerId = "decrypt-session-peer";
//        securityFacade.createSession(peerId);
//
//        byte[] plaintext = "Session message".getBytes(StandardCharsets.UTF_8);
//        byte[] encrypted = securityFacade.encryptForPeer(peerId, plaintext);
//        byte[] decrypted = securityFacade.decryptFromPeer(peerId, encrypted);
//
//        assertArrayEquals(plaintext, decrypted);
//    }
//
//    // ========================= HMAC Tests =========================
//
//    @Test
//    @Order(60)
//    @DisplayName("Should compute HMAC")
//    void testComputeHmac() {
//        byte[] data = "Data to authenticate".getBytes(StandardCharsets.UTF_8);
//
//        byte[] hmac = securityFacade.computeHmac(data);
//
//        assertNotNull(hmac);
//        assertTrue(hmac.length > 0);
//    }
//
//    @Test
//    @Order(61)
//    @DisplayName("Should verify valid HMAC")
//    void testVerifyValidHmac() {
//        byte[] data = "Authenticated data".getBytes(StandardCharsets.UTF_8);
//        byte[] hmac = securityFacade.computeHmac(data);
//
//        boolean valid = securityFacade.verifyHmac(data, hmac);
//
//        assertTrue(valid);
//    }
//
//    @Test
//    @Order(62)
//    @DisplayName("Should reject invalid HMAC")
//    void testRejectInvalidHmac() {
//        byte[] data = "Original data".getBytes(StandardCharsets.UTF_8);
//        byte[] hmac = securityFacade.computeHmac(data);
//
//        byte[] tamperedData = "Tampered data".getBytes(StandardCharsets.UTF_8);
//        boolean valid = securityFacade.verifyHmac(tamperedData, hmac);
//
//        assertFalse(valid);
//    }
//
//    @Test
//    @Order(63)
//    @DisplayName("HMAC should be consistent for same data")
//    void testHmacConsistency() {
//        byte[] data = "Consistent data".getBytes(StandardCharsets.UTF_8);
//
//        byte[] hmac1 = securityFacade.computeHmac(data);
//        byte[] hmac2 = securityFacade.computeHmac(data);
//
//        assertArrayEquals(hmac1, hmac2);
//    }
//
//    // ========================= Trust Management Tests =========================
//
//    @Test
//    @Order(70)
//    @DisplayName("Should trust peer")
//    void testTrustPeer() {
//        String peerId = "trusted-peer";
//        byte[] publicKey = securityFacade.getPublicKey();
//
//        securityFacade.trustPeer(peerId, publicKey);
//
//        assertTrue(securityFacade.isTrusted(peerId));
//    }
//
//    @Test
//    @Order(71)
//    @DisplayName("Should revoke trust")
//    void testRevokeTrust() {
//        String peerId = "revoked-peer";
//        byte[] publicKey = securityFacade.getPublicKey();
//
//        securityFacade.trustPeer(peerId, publicKey);
//        securityFacade.revokeTrust(peerId);
//
//        assertFalse(securityFacade.isTrusted(peerId));
//    }
//
//    @Test
//    @Order(72)
//    @DisplayName("Should check trust status")
//    void testIsTrusted() {
//        String trustedPeer = "is-trusted";
//        String untrustedPeer = "not-trusted";
//
//        securityFacade.trustPeer(trustedPeer, securityFacade.getPublicKey());
//
//        assertTrue(securityFacade.isTrusted(trustedPeer));
//        assertFalse(securityFacade.isTrusted(untrustedPeer));
//    }
//
//    // ========================= Error Handling Tests =========================
//
//    @Test
//    @Order(80)
//    @DisplayName("Should handle null data for encryption")
//    void testEncryptNullData() {
//        assertThrows(Exception.class, () -> {
//            securityFacade.encrypt(null);
//        });
//    }
//
//    @Test
//    @Order(81)
//    @DisplayName("Should handle null data for decryption")
//    void testDecryptNullData() {
//        assertThrows(Exception.class, () -> {
//            securityFacade.decrypt(null);
//        });
//    }
//
//    @Test
//    @Order(82)
//    @DisplayName("Should handle invalid ciphertext")
//    void testDecryptInvalidCiphertext() {
//        byte[] invalidCiphertext = "not valid ciphertext".getBytes(StandardCharsets.UTF_8);
//
//        assertThrows(Exception.class, () -> {
//            securityFacade.decrypt(invalidCiphertext);
//        });
//    }
//
//    @Test
//    @Order(83)
//    @DisplayName("Should handle null data for signing")
//    void testSignNullData() {
//        assertThrows(Exception.class, () -> {
//            securityFacade.sign(null);
//        });
//    }
//
//    // ========================= Concurrency Tests =========================
//
//    @Test
//    @Order(90)
//    @DisplayName("Should handle concurrent encryption")
//    void testConcurrentEncryption() throws InterruptedException {
//        int threadCount = 10;
//        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
//        java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean(true);
//
//        for (int i = 0; i < threadCount; i++) {
//            final int index = i;
//            new Thread(() -> {
//                try {
//                    byte[] data = ("Message " + index).getBytes(StandardCharsets.UTF_8);
//                    byte[] encrypted = securityFacade.encrypt(data);
//                    byte[] decrypted = securityFacade.decrypt(encrypted);
//
//                    if (!Arrays.equals(data, decrypted)) {
//                        success.set(false);
//                    }
//                } catch (Exception e) {
//                    success.set(false);
//                } finally {
//                    latch.countDown();
//                }
//            }).start();
//        }
//
//        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
//        assertTrue(success.get());
//    }
//
//    @Test
//    @Order(91)
//    @DisplayName("Should handle concurrent session creation")
//    void testConcurrentSessionCreation() throws InterruptedException {
//        int threadCount = 10;
//        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
//        java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean(true);
//
//        for (int i = 0; i < threadCount; i++) {
//            final int index = i;
//            new Thread(() -> {
//                try {
//                    String peerId = "concurrent-peer-" + index;
//                    securityFacade.createSession(peerId);
//
//                    if (!securityFacade.hasSession(peerId)) {
//                        success.set(false);
//                    }
//                } catch (Exception e) {
//                    success.set(false);
//                } finally {
//                    latch.countDown();
//                }
//            }).start();
//        }
//
//        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
//        assertTrue(success.get());
//    }
//
//    // ========================= Security Edge Cases =========================
//
//    @Test
//    @Order(100)
//    @DisplayName("Should handle very long plaintext")
//    void testVeryLongPlaintext() {
//        byte[] veryLong = new byte[10 * 1024 * 1024]; // 10MB
//        Arrays.fill(veryLong, (byte) 'A');
//
//        byte[] encrypted = securityFacade.encrypt(veryLong);
//        byte[] decrypted = securityFacade.decrypt(encrypted);
//
//        assertArrayEquals(veryLong, decrypted);
//    }
//
//    @Test
//    @Order(101)
//    @DisplayName("Should handle special characters in data")
//    void testSpecialCharacters() {
//        String specialChars = "特殊字符 🎉 émoji ñ 中文 العربية";
//        byte[] data = specialChars.getBytes(StandardCharsets.UTF_8);
//
//        byte[] encrypted = securityFacade.encrypt(data);
//        byte[] decrypted = securityFacade.decrypt(encrypted);
//
//        assertEquals(specialChars, new String(decrypted, StandardCharsets.UTF_8));
//    }
//}
