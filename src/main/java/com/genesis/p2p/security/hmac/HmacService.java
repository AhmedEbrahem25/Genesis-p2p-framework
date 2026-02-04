package com.genesis.p2p.security.hmac;

import com.genesis.p2p.security.SecurityException;
import com.genesis.p2p.observability.logging.NodeLogger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * HMAC (Hash-based Message Authentication Code) Service.
 *
 * Provides message authentication using HMAC-SHA256.
 * HMAC ensures:
 * - Message integrity (data hasn't been modified)
 * - Message authenticity (sender has the shared key)
 *
 * Usage Pattern:
 * 1. Sender: compute HMAC, append to message
 * 2. Receiver: verify HMAC matches computed value
 *
 * Security Notes:
 * - Uses constant-time comparison to prevent timing attacks
 * - HMAC keys should be at least 32 bytes (256 bits)
 * - Different keys should be used for encryption and HMAC
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class HmacService {

    private static final NodeLogger log = NodeLogger.getLogger(HmacService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int HMAC_LENGTH = 32; // SHA-256 produces 32 bytes

    /**
     * Computes HMAC for the given data using the provided key.
     *
     * @param data the data to authenticate
     * @param key the HMAC key (should be 32 bytes)
     * @return HMAC tag (32 bytes)
     * @throws SecurityException if HMAC computation fails
     */
    public static byte[] computeHmac(byte[] data, byte[] key) throws SecurityException {
        if (data == null || data.length == 0) {
            throw new SecurityException("Data cannot be null or empty");
        }
        if (key == null || key.length < 16) {
            throw new SecurityException("HMAC key must be at least 16 bytes");
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_ALGORITHM);
            mac.init(keySpec);

            byte[] hmac = mac.doFinal(data);

            log.debug("HMAC computed", "dataLen", data.length, "hmacLen", hmac.length);
            return hmac;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new SecurityException("HMAC computation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies that the HMAC matches the expected value.
     * Uses constant-time comparison to prevent timing attacks.
     *
     * @param data the original data
     * @param key the HMAC key
     * @param expectedHmac the HMAC to verify against
     * @return true if HMAC is valid, false otherwise
     * @throws SecurityException if verification fails
     */
    public static boolean verifyHmac(byte[] data, byte[] key, byte[] expectedHmac)
            throws SecurityException {

        if (expectedHmac == null || expectedHmac.length != HMAC_LENGTH) {
            log.warn("Invalid HMAC length", "expected", HMAC_LENGTH,
                    "actual", expectedHmac != null ? expectedHmac.length : 0);
            return false;
        }

        byte[] computedHmac = computeHmac(data, key);

        // Constant-time comparison to prevent timing attacks
        boolean valid = MessageDigest.isEqual(computedHmac, expectedHmac);

        if (!valid) {
            log.warn("HMAC verification failed", "dataLen", data.length);
        }

        return valid;
    }

    /**
     * Computes HMAC with additional context information.
     * Useful for domain separation and preventing cross-protocol attacks.
     *
     * @param data the data to authenticate
     * @param key the HMAC key
     * @param context additional context (e.g., message type, peer ID)
     * @return HMAC tag
     */
    public static byte[] computeHmacWithContext(byte[] data, byte[] key, String context)
            throws SecurityException {

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_ALGORITHM);
            mac.init(keySpec);

            // Include context first
            if (context != null && !context.isEmpty()) {
                mac.update(context.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                mac.update((byte) 0x00); // Separator
            }

            mac.update(data);

            byte[] hmac = mac.doFinal();

            log.debug("HMAC with context computed",
                    "context", context,
                    "dataLen", data.length);

            return hmac;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new SecurityException("HMAC computation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies HMAC with context.
     */
    public static boolean verifyHmacWithContext(
            byte[] data,
            byte[] key,
            byte[] expectedHmac,
            String context) throws SecurityException {

        if (expectedHmac == null || expectedHmac.length != HMAC_LENGTH) {
            return false;
        }

        byte[] computedHmac = computeHmacWithContext(data, key, context);
        return MessageDigest.isEqual(computedHmac, expectedHmac);
    }

    /**
     * Appends HMAC to data, creating authenticated message.
     *
     * Format: [data][hmac]
     *
     * @param data the data to authenticate
     * @param key the HMAC key
     * @return data with HMAC appended
     */
    public static byte[] createAuthenticatedMessage(byte[] data, byte[] key)
            throws SecurityException {

        byte[] hmac = computeHmac(data, key);

        ByteBuffer buffer = ByteBuffer.allocate(data.length + hmac.length);
        buffer.put(data);
        buffer.put(hmac);

        return buffer.array();
    }

    /**
     * Verifies and extracts data from authenticated message.
     *
     * @param authenticatedMessage message with HMAC appended
     * @param key the HMAC key
     * @return original data if HMAC is valid
     * @throws SecurityException if HMAC verification fails
     */
    public static byte[] verifyAndExtractMessage(byte[] authenticatedMessage, byte[] key)
            throws SecurityException {

        if (authenticatedMessage == null || authenticatedMessage.length <= HMAC_LENGTH) {
            throw new SecurityException("Message too short to contain HMAC");
        }

        int dataLength = authenticatedMessage.length - HMAC_LENGTH;

        byte[] data = new byte[dataLength];
        byte[] receivedHmac = new byte[HMAC_LENGTH];

        System.arraycopy(authenticatedMessage, 0, data, 0, dataLength);
        System.arraycopy(authenticatedMessage, dataLength, receivedHmac, 0, HMAC_LENGTH);

        if (!verifyHmac(data, key, receivedHmac)) {
            throw new SecurityException("HMAC verification failed - message may be tampered");
        }

        return data;
    }

    /**
     * Truncates HMAC to specified length (for bandwidth optimization).
     *
     * SECURITY WARNING: Truncating reduces security!
     * - 16 bytes (128 bits): Acceptable for most uses
     * - 12 bytes (96 bits): Minimum recommended
     *
     * SECURITY FIX (v2.6): Minimum length changed from 8 to 12 bytes.
     * 8-byte (64-bit) HMAC provides insufficient security against
     * birthday attacks in high-volume scenarios.
     *
     * @param hmac full HMAC
     * @param length desired length (12-32 bytes)
     * @return truncated HMAC
     * @throws IllegalArgumentException if length is below minimum (12) or above maximum (32)
     */
    public static byte[] truncateHmac(byte[] hmac, int length) {
        // SECURITY FIX (v2.6): Minimum changed from 8 to 12 bytes
        if (length < 12 || length > HMAC_LENGTH) {
            throw new IllegalArgumentException(
                    "Length must be between 12 and 32 bytes (was: " + length +
                    "). HMAC truncation below 12 bytes is not secure.");
        }

        return Arrays.copyOf(hmac, length);
    }

    /**
     * Computes HMAC over multiple data segments efficiently.
     * Useful for large messages or streaming scenarios.
     *
     * @param segments array of data segments
     * @param key the HMAC key
     * @return HMAC over all segments
     */
    public static byte[] computeHmacMultipart(byte[][] segments, byte[] key)
            throws SecurityException {

        if (segments == null || segments.length == 0) {
            throw new SecurityException("Segments cannot be null or empty");
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_ALGORITHM);
            mac.init(keySpec);

            for (byte[] segment : segments) {
                if (segment != null && segment.length > 0) {
                    mac.update(segment);
                }
            }

            return mac.doFinal();

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new SecurityException("HMAC computation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Gets the HMAC output length for this algorithm.
     */
    public static int getHmacLength() {
        return HMAC_LENGTH;
    }

    /**
     * Validates that an HMAC key meets security requirements.
     *
     * @param key the key to validate
     * @return true if key is valid, false otherwise
     */
    public static boolean isValidHmacKey(byte[] key) {
        if (key == null) {
            return false;
        }

        // Key should be at least 16 bytes (128 bits)
        // Recommended: 32 bytes (256 bits)
        if (key.length < 16) {
            log.warn("HMAC key too short", "length", key.length);
            return false;
        }

        // Check if key is all zeros (weak key)
        boolean allZeros = true;
        for (byte b : key) {
            if (b != 0) {
                allZeros = false;
                break;
            }
        }

        if (allZeros) {
            log.warn("HMAC key is all zeros - weak key detected");
            return false;
        }

        return true;
    }
}