package com.genesis.p2p.util.common;

/**
 * Utility class for hexadecimal encoding/decoding operations.
 * Optimized for P2P network operations like peer IDs, message hashes, etc.
 */
public final class HexUtils {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private static final byte[] HEX_BYTES = new byte[128];

    static {
        for (int i = 0; i < HEX_BYTES.length; i++) {
            HEX_BYTES[i] = -1;
        }
        for (int i = 0; i < 10; i++) {
            HEX_BYTES['0' + i] = (byte) i;
        }
        for (int i = 0; i < 6; i++) {
            HEX_BYTES['a' + i] = (byte) (10 + i);
            HEX_BYTES['A' + i] = (byte) (10 + i);
        }
    }

    private HexUtils() {
        throw new AssertionError("No instances");
    }

    /**
     * Converts byte array to hex string.
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return toHex(bytes, 0, bytes.length);
    }

    /**
     * Converts byte array segment to hex string.
     */
    public static String toHex(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            return null;
        }

        char[] hex = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int v = bytes[offset + i] & 0xFF;
            hex[i * 2] = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }

    /**
     * Converts hex string to byte array.
     */
    public static byte[] fromHex(String hex) {
        if (hex == null) {
            return null;
        }

        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }

        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = decodeHexChar(hex.charAt(i));
            int lo = decodeHexChar(hex.charAt(i + 1));
            bytes[i / 2] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }

    private static int decodeHexChar(char c) {
        if (c >= HEX_BYTES.length) {
            throw new IllegalArgumentException("Invalid hex character: " + c);
        }
        byte b = HEX_BYTES[c];
        if (b < 0) {
            throw new IllegalArgumentException("Invalid hex character: " + c);
        }
        return b;
    }

    /**
     * Validates if string is valid hexadecimal.
     */
    public static boolean isValidHex(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() % 2 != 0) {
            return false;
        }

        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (c >= HEX_BYTES.length || HEX_BYTES[c] < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts single byte to hex string.
     */
    public static String toHex(byte b) {
        char[] hex = new char[2];
        int v = b & 0xFF;
        hex[0] = HEX_CHARS[v >>> 4];
        hex[1] = HEX_CHARS[v & 0x0F];
        return new String(hex);
    }

    /**
     * Formats byte array as hex string with separator (e.g., "0a:1b:2c").
     */
    public static String toHexWithSeparator(byte[] bytes, String separator) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(toHex(bytes[i]));
        }
        return sb.toString();
    }
}