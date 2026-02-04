package com.genesis.p2p.util.crypto;

import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random utilities for generating random values.
 *
 * Provides both cryptographically secure and fast random generation:
 * - SecureRandom for cryptographic operations
 * - ThreadLocalRandom for non-security scenarios
 * - UUID generation
 * - Random strings and tokens
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class RandomUtils {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String DIGITS = "0123456789";
    private static final String HEX_CHARS = "0123456789abcdef";

    private RandomUtils() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    // ==================== Secure Random (Cryptographic) ====================

    /**
     * Generates secure random bytes.
     * Use for cryptographic operations (keys, nonces, tokens).
     */
    public static byte[] secureBytes(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive");
        }
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * Generates secure random int.
     */
    public static int secureInt() {
        return SECURE_RANDOM.nextInt();
    }

    /**
     * Generates secure random int in range [0, bound).
     */
    public static int secureInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        return SECURE_RANDOM.nextInt(bound);
    }

    /**
     * Generates secure random int in range [min, max].
     */
    public static int secureInt(int min, int max) {
        if (min >= max) {
            throw new IllegalArgumentException("Min must be less than max");
        }
        return min + SECURE_RANDOM.nextInt(max - min + 1);
    }

    /**
     * Generates secure random long.
     */
    public static long secureLong() {
        return SECURE_RANDOM.nextLong();
    }

    /**
     * Generates secure random long in range [0, bound).
     */
    public static long secureLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        return Math.abs(SECURE_RANDOM.nextLong()) % bound;
    }

    /**
     * Generates secure random boolean.
     */
    public static boolean secureBoolean() {
        return SECURE_RANDOM.nextBoolean();
    }

    /**
     * Generates secure random double in range [0.0, 1.0).
     */
    public static double secureDouble() {
        return SECURE_RANDOM.nextDouble();
    }

    // ==================== Fast Random (Non-Cryptographic) ====================

    /**
     * Generates fast random int.
     * Use for non-security scenarios (testing, simulation).
     */
    public static int randomInt() {
        return ThreadLocalRandom.current().nextInt();
    }

    /**
     * Generates fast random int in range [0, bound).
     */
    public static int randomInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        return ThreadLocalRandom.current().nextInt(bound);
    }

    /**
     * Generates fast random int in range [min, max].
     */
    public static int randomInt(int min, int max) {
        if (min >= max) {
            throw new IllegalArgumentException("Min must be less than max");
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Generates fast random long.
     */
    public static long randomLong() {
        return ThreadLocalRandom.current().nextLong();
    }

    /**
     * Generates fast random long in range [0, bound).
     */
    public static long randomLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        return ThreadLocalRandom.current().nextLong(bound);
    }

    /**
     * Generates fast random long in range [min, max].
     */
    public static long randomLong(long min, long max) {
        if (min >= max) {
            throw new IllegalArgumentException("Min must be less than max");
        }
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /**
     * Generates fast random boolean.
     */
    public static boolean randomBoolean() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    /**
     * Generates fast random double in range [0.0, 1.0).
     */
    public static double randomDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }

    /**
     * Generates fast random double in range [min, max).
     */
    public static double randomDouble(double min, double max) {
        if (min >= max) {
            throw new IllegalArgumentException("Min must be less than max");
        }
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    // ==================== Random Strings ====================

    /**
     * Generates random alphanumeric string (secure).
     */
    public static String secureAlphanumeric(int length) {
        return secureString(length, ALPHANUMERIC);
    }

    /**
     * Generates random numeric string (secure).
     */
    public static String secureNumeric(int length) {
        return secureString(length, DIGITS);
    }

    /**
     * Generates random hex string (secure).
     */
    public static String secureHex(int length) {
        return secureString(length, HEX_CHARS);
    }

    /**
     * Generates random string from charset (secure).
     */
    public static String secureString(int length, String charset) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive");
        }
        if (charset == null || charset.isEmpty()) {
            throw new IllegalArgumentException("Charset cannot be empty");
        }

        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = secureInt(charset.length());
            sb.append(charset.charAt(index));
        }
        return sb.toString();
    }

    /**
     * Generates random alphanumeric string (fast).
     */
    public static String randomAlphanumeric(int length) {
        return randomString(length, ALPHANUMERIC);
    }

    /**
     * Generates random numeric string (fast).
     */
    public static String randomNumeric(int length) {
        return randomString(length, DIGITS);
    }

    /**
     * Generates random hex string (fast).
     */
    public static String randomHex(int length) {
        return randomString(length, HEX_CHARS);
    }

    /**
     * Generates random string from charset (fast).
     */
    public static String randomString(int length, String charset) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive");
        }
        if (charset == null || charset.isEmpty()) {
            throw new IllegalArgumentException("Charset cannot be empty");
        }

        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = randomInt(charset.length());
            sb.append(charset.charAt(index));
        }
        return sb.toString();
    }

    // ==================== UUID ====================

    /**
     * Generates random UUID (Version 4).
     */
    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Generates random UUID without hyphens.
     */
    public static String uuidCompact() {
        return uuid().replace("-", "");
    }

    /**
     * Generates secure random UUID using SecureRandom.
     */
    public static String secureUuid() {
        byte[] randomBytes = secureBytes(16);

        // Set version (4) and variant bits
        randomBytes[6] &= 0x0f;  // Clear version
        randomBytes[6] |= 0x40;  // Set to version 4
        randomBytes[8] &= 0x3f;  // Clear variant
        randomBytes[8] |= 0x80;  // Set to IETF variant

        // Format as UUID string
        return formatUuid(randomBytes);
    }

    /**
     * Formats bytes as UUID string.
     */
    private static String formatUuid(byte[] bytes) {
        long mostSigBits = 0;
        long leastSigBits = 0;

        for (int i = 0; i < 8; i++) {
            mostSigBits = (mostSigBits << 8) | (bytes[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            leastSigBits = (leastSigBits << 8) | (bytes[i] & 0xff);
        }

        return new UUID(mostSigBits, leastSigBits).toString();
    }

    // ==================== Tokens ====================

    /**
     * Generates secure token (URL-safe Base64).
     */
    public static String secureToken(int byteLength) {
        byte[] bytes = secureBytes(byteLength);
        return Base64Utils.encodeUrlSafe(bytes);
    }

    /**
     * Generates secure token with default length (32 bytes = 256 bits).
     */
    public static String secureToken() {
        return secureToken(32);
    }

    /**
     * Generates session ID (16 bytes = 128 bits).
     */
    public static String sessionId() {
        return secureToken(16);
    }

    /**
     * Generates API key (32 bytes = 256 bits).
     */
    public static String apiKey() {
        return secureToken(32);
    }

    // ==================== Selection ====================

    /**
     * Selects random element from array (fast).
     */
    public static <T> T randomElement(T[] array) {
        if (array == null || array.length == 0) {
            return null;
        }
        return array[randomInt(array.length)];
    }

    /**
     * Selects random element from list (fast).
     */
    public static <T> T randomElement(java.util.List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(randomInt(list.size()));
    }

    /**
     * Selects random element from array (secure).
     */
    public static <T> T secureElement(T[] array) {
        if (array == null || array.length == 0) {
            return null;
        }
        return array[secureInt(array.length)];
    }

    /**
     * Selects random element from list (secure).
     */
    public static <T> T secureElement(java.util.List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(secureInt(list.size()));
    }

    // ==================== Shuffle ====================

    /**
     * Shuffles array in place (secure).
     */
    public static <T> void secureShuffle(T[] array) {
        if (array == null || array.length <= 1) {
            return;
        }
        for (int i = array.length - 1; i > 0; i--) {
            int j = secureInt(i + 1);
            T temp = array[i];
            array[i] = array[j];
            array[j] = temp;
        }
    }

    /**
     * Shuffles list in place (secure).
     */
    public static <T> void secureShuffle(java.util.List<T> list) {
        if (list == null || list.size() <= 1) {
            return;
        }
        for (int i = list.size() - 1; i > 0; i--) {
            int j = secureInt(i + 1);
            java.util.Collections.swap(list, i, j);
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Gets SecureRandom instance.
     */
    public static SecureRandom getSecureRandom() {
        return SECURE_RANDOM;
    }

    /**
     * Gets ThreadLocalRandom instance.
     */
    public static Random getRandom() {
        return ThreadLocalRandom.current();
    }
}

