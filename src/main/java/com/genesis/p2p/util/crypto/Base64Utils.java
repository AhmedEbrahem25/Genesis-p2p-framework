package com.genesis.p2p.util.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Base64 encoding/decoding utilities.
 *
 * Provides convenient methods for Base64 operations:
 * - Standard encoding (RFC 4648)
 * - URL-safe encoding (RFC 4648 Section 5)
 * - MIME encoding (RFC 2045)
 * - String/byte array conversions
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class Base64Utils {

    private Base64Utils() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    // ==================== Standard Base64 ====================

    /**
     * Encodes bytes to Base64 string (standard).
     */
    public static String encode(byte[] data) {
        if (data == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * Encodes string to Base64 (standard).
     */
    public static String encode(String data) {
        if (data == null) {
            return null;
        }
        return encode(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes Base64 string to bytes (standard).
     */
    public static byte[] decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new Base64Exception("Invalid Base64 string", e);
        }
    }

    /**
     * Decodes Base64 string to string (standard).
     */
    public static String decodeToString(String encoded) {
        if (encoded == null) {
            return null;
        }
        byte[] decoded = decode(encoded);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    // ==================== URL-Safe Base64 ====================

    /**
     * Encodes bytes to URL-safe Base64 string.
     * Uses '-' and '_' instead of '+' and '/', no padding.
     */
    public static String encodeUrlSafe(byte[] data) {
        if (data == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /**
     * Encodes string to URL-safe Base64.
     */
    public static String encodeUrlSafe(String data) {
        if (data == null) {
            return null;
        }
        return encodeUrlSafe(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes URL-safe Base64 string to bytes.
     */
    public static byte[] decodeUrlSafe(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new Base64Exception("Invalid URL-safe Base64 string", e);
        }
    }

    /**
     * Decodes URL-safe Base64 string to string.
     */
    public static String decodeUrlSafeToString(String encoded) {
        if (encoded == null) {
            return null;
        }
        byte[] decoded = decodeUrlSafe(encoded);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    // ==================== MIME Base64 ====================

    /**
     * Encodes bytes to MIME Base64 string.
     * Adds line breaks every 76 characters.
     */
    public static String encodeMime(byte[] data) {
        if (data == null) {
            return null;
        }
        return Base64.getMimeEncoder().encodeToString(data);
    }

    /**
     * Encodes string to MIME Base64.
     */
    public static String encodeMime(String data) {
        if (data == null) {
            return null;
        }
        return encodeMime(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes MIME Base64 string to bytes.
     */
    public static byte[] decodeMime(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            return Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new Base64Exception("Invalid MIME Base64 string", e);
        }
    }

    /**
     * Decodes MIME Base64 string to string.
     */
    public static String decodeMimeToString(String encoded) {
        if (encoded == null) {
            return null;
        }
        byte[] decoded = decodeMime(encoded);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    // ==================== Validation ====================

    /**
     * Checks if string is valid Base64 (standard).
     */
    public static boolean isValid(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return false;
        }
        try {
            Base64.getDecoder().decode(encoded);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Checks if string is valid URL-safe Base64.
     */
    public static boolean isValidUrlSafe(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return false;
        }
        try {
            Base64.getUrlDecoder().decode(encoded);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Gets estimated encoded size (standard Base64).
     * Formula: ((4 * n / 3) + 3) & ~3
     */
    public static int getEncodedSize(int dataLength) {
        return ((4 * dataLength / 3) + 3) & ~3;
    }

    /**
     * Gets estimated decoded size (standard Base64).
     */
    public static int getDecodedSize(String encoded) {
        if (encoded == null) {
            return 0;
        }
        int length = encoded.length();
        int padding = 0;
        if (length > 0 && encoded.charAt(length - 1) == '=') {
            padding++;
            if (length > 1 && encoded.charAt(length - 2) == '=') {
                padding++;
            }
        }
        return (length * 3 / 4) - padding;
    }

    /**
     * Removes padding from Base64 string.
     */
    public static String removePadding(String encoded) {
        if (encoded == null) {
            return null;
        }
        return encoded.replaceAll("=+$", "");
    }

    /**
     * Adds padding to Base64 string.
     */
    public static String addPadding(String encoded) {
        if (encoded == null) {
            return null;
        }
        int remainder = encoded.length() % 4;
        if (remainder > 0) {
            int padding = 4 - remainder;
            return encoded + "=".repeat(padding);
        }
        return encoded;
    }

    // ==================== Conversion Utilities ====================

    /**
     * Converts standard Base64 to URL-safe.
     */
    public static String toUrlSafe(String standardBase64) {
        if (standardBase64 == null) {
            return null;
        }
        return standardBase64
                .replace('+', '-')
                .replace('/', '_')
                .replaceAll("=+$", "");
    }

    /**
     * Converts URL-safe Base64 to standard.
     */
    public static String fromUrlSafe(String urlSafeBase64) {
        if (urlSafeBase64 == null) {
            return null;
        }
        String standard = urlSafeBase64
                .replace('-', '+')
                .replace('_', '/');
        return addPadding(standard);
    }

    // ==================== Exception ====================

    /**
     * Base64 encoding/decoding exception.
     */
    public static class Base64Exception extends RuntimeException {
        public Base64Exception(String message) {
            super(message);
        }

        public Base64Exception(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

