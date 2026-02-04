package com.genesis.p2p.util.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Comprehensive byte array utility class for P2P protocol operations.
 *
 * Provides efficient methods for:
 * - Byte array manipulation (concat, slice, copy)
 * - Primitive type conversions (int, long, short)
 * - String conversions (UTF-8)
 * - Bitwise operations (XOR, comparison)
 * - Security operations (constant-time comparison, zeroing)
 *
 * All multi-byte conversions use big-endian (network byte order).
 * All methods are thread-safe for independent operations.
 */
public final class Bytes {

    // Empty byte array constant
    public static final byte[] EMPTY = new byte[0];

    private Bytes() {
        throw new AssertionError("No instances allowed");
    }

    // ==================== Array Manipulation ====================

    /**
     * Concatenates multiple byte arrays into single array.
     * Handles null arrays by treating them as empty.
     *
     * @param arrays arrays to concatenate
     * @return concatenated array (never null)
     */
    public static byte[] concat(byte[]... arrays) {
        if (arrays == null || arrays.length == 0) {
            return EMPTY;
        }

        // Calculate total length
        int totalLength = 0;
        for (byte[] array : arrays) {
            if (array != null) {
                totalLength += array.length;
            }
        }

        if (totalLength == 0) {
            return EMPTY;
        }

        // Concatenate
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] array : arrays) {
            if (array != null && array.length > 0) {
                System.arraycopy(array, 0, result, offset, array.length);
                offset += array.length;
            }
        }

        return result;
    }

    /**
     * Creates a copy of byte array slice.
     *
     * @param bytes source array
     * @param offset start position
     * @param length number of bytes to copy
     * @return copied slice
     * @throws IndexOutOfBoundsException if parameters are invalid
     */
    public static byte[] slice(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            throw new IllegalArgumentException("Byte array cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException(
                    String.format("Invalid slice: offset=%d, length=%d, array.length=%d",
                            offset, length, bytes.length));
        }

        if (length == 0) {
            return EMPTY;
        }

        return Arrays.copyOfRange(bytes, offset, offset + length);
    }

    /**
     * Creates a deep copy of byte array.
     * Returns null if input is null.
     */
    public static byte[] copy(byte[] bytes) {
        return bytes != null ? Arrays.copyOf(bytes, bytes.length) : null;
    }

    /**
     * Creates a copy with specified length (truncate or pad with zeros).
     */
    public static byte[] copyOf(byte[] bytes, int newLength) {
        if (bytes == null) {
            throw new IllegalArgumentException("Byte array cannot be null");
        }
        return Arrays.copyOf(bytes, newLength);
    }

    // ==================== Comparison ====================

    /**
     * Safely compares two byte arrays in constant time.
     * Prevents timing attacks by always comparing all bytes.
     *
     * Critical for cryptographic operations like HMAC verification.
     *
     * @param a first array
     * @param b second array
     * @return true if arrays are equal
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.length != b.length) {
            return false;
        }

        // XOR all bytes and check if result is zero
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }

        return result == 0;
    }

    /**
     * Standard equality check (uses Arrays.equals internally).
     * Faster than constantTimeEquals but vulnerable to timing attacks.
     *
     * Use for non-security-critical comparisons.
     */
    public static boolean equals(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    /**
     * Compares two byte arrays lexicographically.
     *
     * @return negative if a < b, zero if a == b, positive if a > b
     */
    public static int compare(byte[] a, byte[] b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }

        int minLength = Math.min(a.length, b.length);
        for (int i = 0; i < minLength; i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }

        return a.length - b.length;
    }

    // ==================== Integer Conversions (Big-Endian) ====================

    /**
     * Converts int to 4-byte array (big-endian).
     */
    public static byte[] fromInt(int value) {
        return new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    /**
     * Converts 4-byte array to int (big-endian).
     */
    public static int toInt(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new IllegalArgumentException("Byte array must be at least 4 bytes");
        }
        return toInt(bytes, 0);
    }

    /**
     * Converts 4-byte array at offset to int (big-endian).
     */
    public static int toInt(byte[] bytes, int offset) {
        if (bytes == null || bytes.length < offset + 4) {
            throw new IllegalArgumentException("Invalid byte array or offset for int conversion");
        }

        return ((bytes[offset] & 0xFF) << 24) |
                ((bytes[offset + 1] & 0xFF) << 16) |
                ((bytes[offset + 2] & 0xFF) << 8) |
                (bytes[offset + 3] & 0xFF);
    }

    /**
     * Writes int to byte array at offset (big-endian).
     */
    public static void writeInt(byte[] bytes, int offset, int value) {
        if (bytes == null || bytes.length < offset + 4) {
            throw new IllegalArgumentException("Invalid byte array or offset");
        }

        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    // ==================== Long Conversions (Big-Endian) ====================

    /**
     * Converts long to 8-byte array (big-endian).
     */
    public static byte[] fromLong(long value) {
        return new byte[] {
                (byte) (value >>> 56),
                (byte) (value >>> 48),
                (byte) (value >>> 40),
                (byte) (value >>> 32),
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    /**
     * Converts 8-byte array to long (big-endian).
     */
    public static long toLong(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            throw new IllegalArgumentException("Byte array must be at least 8 bytes");
        }
        return toLong(bytes, 0);
    }

    /**
     * Converts 8-byte array at offset to long (big-endian).
     */
    public static long toLong(byte[] bytes, int offset) {
        if (bytes == null || bytes.length < offset + 8) {
            throw new IllegalArgumentException("Invalid byte array or offset for long conversion");
        }

        return ((long) (bytes[offset] & 0xFF) << 56) |
                ((long) (bytes[offset + 1] & 0xFF) << 48) |
                ((long) (bytes[offset + 2] & 0xFF) << 40) |
                ((long) (bytes[offset + 3] & 0xFF) << 32) |
                ((long) (bytes[offset + 4] & 0xFF) << 24) |
                ((long) (bytes[offset + 5] & 0xFF) << 16) |
                ((long) (bytes[offset + 6] & 0xFF) << 8) |
                ((long) (bytes[offset + 7] & 0xFF));
    }

    /**
     * Writes long to byte array at offset (big-endian).
     */
    public static void writeLong(byte[] bytes, int offset, long value) {
        if (bytes == null || bytes.length < offset + 8) {
            throw new IllegalArgumentException("Invalid byte array or offset");
        }

        bytes[offset] = (byte) (value >>> 56);
        bytes[offset + 1] = (byte) (value >>> 48);
        bytes[offset + 2] = (byte) (value >>> 40);
        bytes[offset + 3] = (byte) (value >>> 32);
        bytes[offset + 4] = (byte) (value >>> 24);
        bytes[offset + 5] = (byte) (value >>> 16);
        bytes[offset + 6] = (byte) (value >>> 8);
        bytes[offset + 7] = (byte) value;
    }

    // ==================== Short Conversions (Big-Endian) ====================

    /**
     * Converts short to 2-byte array (big-endian).
     */
    public static byte[] fromShort(short value) {
        return new byte[] {
                (byte) (value >>> 8),
                (byte) value
        };
    }

    /**
     * Converts 2-byte array to short (big-endian).
     */
    public static short toShort(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            throw new IllegalArgumentException("Byte array must be at least 2 bytes");
        }
        return toShort(bytes, 0);
    }

    /**
     * Converts 2-byte array at offset to short (big-endian).
     */
    public static short toShort(byte[] bytes, int offset) {
        if (bytes == null || bytes.length < offset + 2) {
            throw new IllegalArgumentException("Invalid byte array or offset for short conversion");
        }

        return (short) (((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF));
    }

    /**
     * Writes short to byte array at offset (big-endian).
     */
    public static void writeShort(byte[] bytes, int offset, short value) {
        if (bytes == null || bytes.length < offset + 2) {
            throw new IllegalArgumentException("Invalid byte array or offset");
        }

        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    // ==================== String Conversions ====================

    /**
     * Converts string to UTF-8 bytes.
     * Returns empty array for null input.
     */
    public static byte[] fromString(String str) {
        return str != null ? str.getBytes(StandardCharsets.UTF_8) : EMPTY;
    }

    /**
     * Converts UTF-8 bytes to string.
     * Returns empty string for null input.
     */
    public static String toString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Converts UTF-8 bytes to string with offset and length.
     */
    public static String toString(byte[] bytes, int offset, int length) {
        if (bytes == null || length == 0) {
            return "";
        }
        return new String(bytes, offset, length, StandardCharsets.UTF_8);
    }

    // ==================== Bitwise Operations ====================

    /**
     * XOR two byte arrays of equal length.
     * Useful for cryptographic operations.
     *
     * @param a first array
     * @param b second array
     * @return new array containing XOR result
     * @throws IllegalArgumentException if arrays are different lengths
     */
    public static byte[] xor(byte[] a, byte[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Arrays cannot be null");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Arrays must have same length: " + a.length + " != " + b.length);
        }

        byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    /**
     * XOR byte array with single byte.
     */
    public static byte[] xor(byte[] array, byte value) {
        if (array == null) {
            throw new IllegalArgumentException("Array cannot be null");
        }

        byte[] result = new byte[array.length];
        for (int i = 0; i < array.length; i++) {
            result[i] = (byte) (array[i] ^ value);
        }
        return result;
    }

    /**
     * AND two byte arrays.
     */
    public static byte[] and(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("Arrays must be non-null and same length");
        }

        byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] & b[i]);
        }
        return result;
    }

    /**
     * OR two byte arrays.
     */
    public static byte[] or(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("Arrays must be non-null and same length");
        }

        byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] | b[i]);
        }
        return result;
    }

    /**
     * NOT (invert) byte array.
     */
    public static byte[] not(byte[] array) {
        if (array == null) {
            throw new IllegalArgumentException("Array cannot be null");
        }

        byte[] result = new byte[array.length];
        for (int i = 0; i < array.length; i++) {
            result[i] = (byte) ~array[i];
        }
        return result;
    }

    // ==================== Utility Methods ====================

    /**
     * Fills byte array with specified value.
     */
    public static void fill(byte[] bytes, byte value) {
        if (bytes != null) {
            Arrays.fill(bytes, value);
        }
    }

    /**
     * Zeros out byte array (security cleanup).
     * Use after handling sensitive data like keys or passwords.
     */
    public static void zero(byte[] bytes) {
        fill(bytes, (byte) 0);
    }

    /**
     * Checks if byte array is empty or null.
     */
    public static boolean isEmpty(byte[] bytes) {
        return bytes == null || bytes.length == 0;
    }

    /**
     * Checks if byte array is null or all zeros.
     */
    public static boolean isZero(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return true;
        }

        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Wraps byte array in ByteBuffer.
     */
    public static ByteBuffer wrap(byte[] bytes) {
        return ByteBuffer.wrap(bytes != null ? bytes : EMPTY);
    }

    /**
     * Wraps byte array segment in ByteBuffer.
     */
    public static ByteBuffer wrap(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            throw new IllegalArgumentException("Byte array cannot be null");
        }
        return ByteBuffer.wrap(bytes, offset, length);
    }

    /**
     * Reverses byte array.
     */
    public static byte[] reverse(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = bytes[bytes.length - 1 - i];
        }
        return result;
    }

    /**
     * Reverses byte array in place.
     */
    public static void reverseInPlace(byte[] bytes) {
        if (bytes == null || bytes.length <= 1) {
            return;
        }

        for (int i = 0; i < bytes.length / 2; i++) {
            byte temp = bytes[i];
            bytes[i] = bytes[bytes.length - 1 - i];
            bytes[bytes.length - 1 - i] = temp;
        }
    }

    /**
     * Ensures array has minimum length, padding with zeros if needed.
     */
    public static byte[] ensureLength(byte[] bytes, int minLength) {
        if (bytes == null) {
            return new byte[minLength];
        }
        if (bytes.length >= minLength) {
            return bytes;
        }
        return copyOf(bytes, minLength);
    }

    /**
     * Safely gets byte at index, returns 0 if out of bounds.
     */
    public static byte safeGet(byte[] bytes, int index) {
        if (bytes == null || index < 0 || index >= bytes.length) {
            return 0;
        }
        return bytes[index];
    }
}