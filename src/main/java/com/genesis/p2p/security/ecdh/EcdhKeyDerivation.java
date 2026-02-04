package com.genesis.p2p.security.ecdh;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * HKDF-based key derivation for ECDH shared secrets.
 *
 * Derives cryptographically independent keys from shared secret:
 * - Session ID (for identification)
 * - Encryption key (for AES-GCM)
 * - HMAC key (for message authentication)
 *
 * Uses HKDF (RFC 5869) with SHA-256 for secure key derivation.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class EcdhKeyDerivation {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HASH_ALGORITHM = "SHA-256";

    // Key derivation purposes
    public static final String PURPOSE_ENCRYPTION = "ENCRYPTION";
    public static final String PURPOSE_HMAC = "HMAC";
    public static final String PURPOSE_SESSION_ID = "SESSION_ID";

    // Key sizes in bytes
    private static final int ENCRYPTION_KEY_SIZE = 32; // 256-bit AES key
    private static final int HMAC_KEY_SIZE = 32;       // 256-bit HMAC key
    private static final int SESSION_ID_SIZE = 16;     // 128-bit session ID

    /**
     * Derives session keys from ECDH shared secret using HKDF.
     *
     * @param sharedSecret raw ECDH shared secret
     * @param localPeerId  local peer identifier
     * @param remotePeerId remote peer identifier
     * @param salt         optional salt (can be null for default)
     * @return derived keys for session
     * @throws Exception if key derivation fails
     */
    public static DerivedKeys deriveSessionKeys(
            byte[] sharedSecret,
            String localPeerId,
            String remotePeerId,
            byte[] salt) throws Exception {

        if (sharedSecret == null || sharedSecret.length == 0) {
            throw new IllegalArgumentException("Shared secret cannot be null or empty");
        }

        // Create context info (peer IDs determine key direction)
        String contextInfo = createContextInfo(localPeerId, remotePeerId);

        // Use default salt if not provided
        if (salt == null) {
            salt = "genesis-p2p-v2".getBytes(StandardCharsets.UTF_8);
        }

        // HKDF-Extract: derive PRK from shared secret
        byte[] prk = hkdfExtract(salt, sharedSecret);

        // HKDF-Expand: derive individual keys
        byte[] encryptionKey = hkdfExpand(prk, contextInfo + PURPOSE_ENCRYPTION, ENCRYPTION_KEY_SIZE);
        byte[] hmacKey = hkdfExpand(prk, contextInfo + PURPOSE_HMAC, HMAC_KEY_SIZE);
        byte[] sessionIdBytes = hkdfExpand(prk, contextInfo + PURPOSE_SESSION_ID, SESSION_ID_SIZE);

        return new DerivedKeys(sessionIdBytes, encryptionKey, hmacKey);
    }

    /**
     * Derives a rotated key from an existing key.
     *
     * @param currentKey current key material
     * @param counter    rotation counter
     * @param purpose    key purpose (ENCRYPTION or HMAC)
     * @return new rotated key
     * @throws Exception if derivation fails
     */
    public static byte[] deriveRotatedKey(byte[] currentKey, long counter, String purpose)
            throws Exception {

        // Create rotation context
        String context = "rotation:" + counter + ":" + purpose;
        byte[] contextBytes = context.getBytes(StandardCharsets.UTF_8);

        // Use current key as input keying material
        byte[] prk = hkdfExtract(contextBytes, currentKey);

        // Expand to new key
        int keySize = PURPOSE_ENCRYPTION.equals(purpose) ? ENCRYPTION_KEY_SIZE : HMAC_KEY_SIZE;
        return hkdfExpand(prk, context, keySize);
    }

    /**
     * HKDF-Extract: derives pseudorandom key from input keying material.
     *
     * @param salt optional salt value
     * @param ikm  input keying material
     * @return pseudorandom key
     */
    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);

        if (salt == null || salt.length == 0) {
            // Use zero-filled salt if not provided
            salt = new byte[32];
        }

        mac.init(new SecretKeySpec(salt, HMAC_ALGORITHM));
        return mac.doFinal(ikm);
    }

    /**
     * HKDF-Expand: expands pseudorandom key to desired length.
     *
     * @param prk    pseudorandom key from extract phase
     * @param info   context and application-specific information
     * @param length desired output length in bytes
     * @return expanded key material
     */
    private static byte[] hkdfExpand(byte[] prk, String info, int length) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(prk, HMAC_ALGORITHM));

        byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
        int hashLen = 32; // SHA-256 output length
        int n = (length + hashLen - 1) / hashLen;

        ByteBuffer output = ByteBuffer.allocate(n * hashLen);
        byte[] previous = new byte[0];

        for (int i = 1; i <= n; i++) {
            mac.reset();
            mac.update(previous);
            mac.update(infoBytes);
            mac.update((byte) i);
            previous = mac.doFinal();
            output.put(previous);
        }

        return Arrays.copyOf(output.array(), length);
    }

    /**
     * Creates context info from peer IDs.
     * Orders IDs deterministically for consistent derivation.
     */
    private static String createContextInfo(String localPeerId, String remotePeerId) {
        // Ensure consistent ordering regardless of which peer initiates
        if (localPeerId.compareTo(remotePeerId) < 0) {
            return localPeerId + ":" + remotePeerId + ":";
        } else {
            return remotePeerId + ":" + localPeerId + ":";
        }
    }

    /**
     * Container for derived session keys.
     */
    public static class DerivedKeys {
        private final byte[] sessionId;
        private final byte[] encryptionKey;
        private final byte[] hmacKey;

        public DerivedKeys(byte[] sessionId, byte[] encryptionKey, byte[] hmacKey) {
            this.sessionId = sessionId;
            this.encryptionKey = encryptionKey;
            this.hmacKey = hmacKey;
        }

        public byte[] getSessionId() {
            return sessionId;
        }

        public String getSessionIdHex() {
            return bytesToHex(sessionId);
        }

        public byte[] getEncryptionKey() {
            return encryptionKey;
        }

        public byte[] getHmacKey() {
            return hmacKey;
        }

        /**
         * Securely zeros out all key material.
         */
        public void destroy() {
            Arrays.fill(sessionId, (byte) 0);
            Arrays.fill(encryptionKey, (byte) 0);
            Arrays.fill(hmacKey, (byte) 0);
        }

        private static String bytesToHex(byte[] bytes) {
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
    }
}
