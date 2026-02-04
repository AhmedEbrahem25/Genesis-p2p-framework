package com.genesis.p2p.util.io;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Comprehensive I/O stream utilities for P2P network operations.
 * Provides efficient methods for reading/writing primitives, strings,
 * and bulk data transfers with proper error handling.
 *
 * All methods use big-endian byte order for network compatibility.
 * All string operations use UTF-8 encoding.
 *
 * Thread-safe for independent operations on different streams.
 */
public final class IOStreams {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int MAX_BUFFER_SIZE = 1024 * 1024; // 1MB
    private static final int MAX_STRING_LENGTH = 64 * 1024 * 1024; // 64MB

    private IOStreams() {
        throw new AssertionError("No instances allowed");
    }

    // ==================== Bulk Operations ====================

    /**
     * Reads all remaining bytes from input stream into byte array.
     * WARNING: Can consume large amounts of memory for large streams.
     *
     * @param input the input stream to read from
     * @return byte array containing all data
     * @throws IOException if I/O error occurs
     */
    public static byte[] readAllBytes(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Input stream cannot be null");
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        copyTo(input, buffer);
        return buffer.toByteArray();
    }

    /**
     * Copies all data from input to output stream.
     *
     * @param input source stream
     * @param output destination stream
     * @return total bytes copied
     * @throws IOException if I/O error occurs
     */
    public static long copyTo(InputStream input, OutputStream output) throws IOException {
        return copyTo(input, output, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Copies all data from input to output stream with specified buffer size.
     *
     * @param input source stream
     * @param output destination stream
     * @param bufferSize size of temporary buffer
     * @return total bytes copied
     * @throws IOException if I/O error occurs
     */
    public static long copyTo(InputStream input, OutputStream output, int bufferSize)
            throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("Streams cannot be null");
        }

        byte[] buffer = new byte[Math.min(Math.max(bufferSize, 256), MAX_BUFFER_SIZE)];
        long total = 0;
        int read;

        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            total += read;
        }

        return total;
    }

    /**
     * Copies specified number of bytes from input to output.
     *
     * @param input source stream
     * @param output destination stream
     * @param length exact number of bytes to copy
     * @return bytes copied (should equal length)
     * @throws IOException if I/O error or EOF reached
     */
    public static long copyExactly(InputStream input, OutputStream output, long length)
            throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("Streams cannot be null");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative");
        }

        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        long remaining = length;

        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, toRead);

            if (read == -1) {
                throw new EOFException("Unexpected end of stream after " +
                        (length - remaining) + " bytes");
            }

            output.write(buffer, 0, read);
            remaining -= read;
        }

        return length;
    }

    // ==================== Exact Read Operations ====================

    /**
     * Reads exactly n bytes from input stream.
     * Blocks until all bytes are read or EOF is reached.
     *
     * @param input the input stream
     * @param length number of bytes to read
     * @return byte array of exact length
     * @throws EOFException if stream ends before reading all bytes
     * @throws IOException if I/O error occurs
     */
    public static byte[] readExactly(InputStream input, int length) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Input stream cannot be null");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative");
        }
        if (length == 0) {
            return new byte[0];
        }

        byte[] buffer = new byte[length];
        int offset = 0;

        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read == -1) {
                throw new EOFException("Expected " + length + " bytes but only got " + offset);
            }
            offset += read;
        }

        return buffer;
    }

    /**
     * Skips exactly n bytes from input stream.
     * More reliable than InputStream.skip() which may skip fewer bytes.
     *
     * @param input the input stream
     * @param n number of bytes to skip
     * @throws EOFException if stream ends before skipping all bytes
     * @throws IOException if I/O error occurs
     */
    public static void skipExactly(InputStream input, long n) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Input stream cannot be null");
        }
        if (n < 0) {
            throw new IllegalArgumentException("Skip count cannot be negative");
        }

        long remaining = n;
        while (remaining > 0) {
            long skipped = input.skip(remaining);

            if (skipped == 0) {
                // skip() might return 0, use read() instead
                if (input.read() == -1) {
                    throw new EOFException("Expected to skip " + n +
                            " bytes but only skipped " + (n - remaining));
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    // ==================== Primitive Read Operations ====================

    /**
     * Reads byte from input stream.
     */
    public static byte readByte(InputStream input) throws IOException {
        int b = input.read();
        if (b == -1) {
            throw new EOFException("Unexpected end of stream");
        }
        return (byte) b;
    }

    /**
     * Reads short from input stream (big-endian, 2 bytes).
     */
    public static short readShort(InputStream input) throws IOException {
        byte[] bytes = readExactly(input, 2);
        return (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
    }

    /**
     * Reads int from input stream (big-endian, 4 bytes).
     */
    public static int readInt(InputStream input) throws IOException {
        byte[] bytes = readExactly(input, 4);
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }

    /**
     * Reads long from input stream (big-endian, 8 bytes).
     */
    public static long readLong(InputStream input) throws IOException {
        byte[] bytes = readExactly(input, 8);
        return ((long) (bytes[0] & 0xFF) << 56) |
                ((long) (bytes[1] & 0xFF) << 48) |
                ((long) (bytes[2] & 0xFF) << 40) |
                ((long) (bytes[3] & 0xFF) << 32) |
                ((long) (bytes[4] & 0xFF) << 24) |
                ((long) (bytes[5] & 0xFF) << 16) |
                ((long) (bytes[6] & 0xFF) << 8) |
                ((long) (bytes[7] & 0xFF));
    }

    /**
     * Reads boolean from input stream (1 byte, 0=false, non-zero=true).
     */
    public static boolean readBoolean(InputStream input) throws IOException {
        return readByte(input) != 0;
    }

    // ==================== Primitive Write Operations ====================

    /**
     * Writes byte to output stream.
     */
    public static void writeByte(OutputStream output, byte value) throws IOException {
        output.write(value);
    }

    /**
     * Writes short to output stream (big-endian, 2 bytes).
     */
    public static void writeShort(OutputStream output, short value) throws IOException {
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    /**
     * Writes int to output stream (big-endian, 4 bytes).
     */
    public static void writeInt(OutputStream output, int value) throws IOException {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    /**
     * Writes long to output stream (big-endian, 8 bytes).
     */
    public static void writeLong(OutputStream output, long value) throws IOException {
        output.write((int) ((value >>> 56) & 0xFF));
        output.write((int) ((value >>> 48) & 0xFF));
        output.write((int) ((value >>> 40) & 0xFF));
        output.write((int) ((value >>> 32) & 0xFF));
        output.write((int) ((value >>> 24) & 0xFF));
        output.write((int) ((value >>> 16) & 0xFF));
        output.write((int) ((value >>> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }

    /**
     * Writes boolean to output stream (1 byte, 0 or 1).
     */
    public static void writeBoolean(OutputStream output, boolean value) throws IOException {
        output.write(value ? 1 : 0);
    }

    // ==================== String Operations ====================

    /**
     * Reads string from input stream.
     * Format: 4-byte length prefix (int) + UTF-8 encoded bytes.
     *
     * @param input the input stream
     * @return decoded string (never null, may be empty)
     * @throws IOException if I/O error or invalid format
     */
    public static String readString(InputStream input) throws IOException {
        int length = readInt(input);

        if (length < 0) {
            throw new IOException("Invalid string length: " + length);
        }
        if (length == 0) {
            return "";
        }
        if (length > MAX_STRING_LENGTH) {
            throw new IOException("String too large: " + length + " bytes");
        }

        byte[] bytes = readExactly(input, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Writes string to output stream.
     * Format: 4-byte length prefix (int) + UTF-8 encoded bytes.
     *
     * @param output the output stream
     * @param str the string to write (null treated as empty)
     * @throws IOException if I/O error occurs
     */
    public static void writeString(OutputStream output, String str) throws IOException {
        if (str == null || str.isEmpty()) {
            writeInt(output, 0);
            return;
        }

        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > MAX_STRING_LENGTH) {
            throw new IOException("String too large: " + bytes.length + " bytes");
        }

        writeInt(output, bytes.length);
        output.write(bytes);
    }

    /**
     * Reads string with maximum length constraint.
     *
     * @param input the input stream
     * @param maxLength maximum allowed string length in bytes
     * @return decoded string
     * @throws IOException if string exceeds maxLength
     */
    public static String readString(InputStream input, int maxLength) throws IOException {
        int length = readInt(input);

        if (length < 0) {
            throw new IOException("Invalid string length: " + length);
        }
        if (length > maxLength) {
            throw new IOException("String exceeds max length: " + length + " > " + maxLength);
        }
        if (length == 0) {
            return "";
        }

        byte[] bytes = readExactly(input, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==================== Channel Operations ====================

    /**
     * Reads from channel into ByteBuffer until buffer is full or EOF.
     *
     * @param channel the channel to read from
     * @param buffer the buffer to fill
     * @return total bytes read (may be less than buffer capacity on EOF)
     * @throws IOException if I/O error occurs
     */
    public static int readFully(ReadableByteChannel channel, ByteBuffer buffer)
            throws IOException {
        if (channel == null || buffer == null) {
            throw new IllegalArgumentException("Channel and buffer cannot be null");
        }

        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read == -1) {
                break;
            }
            total += read;
        }
        return total;
    }

    /**
     * Writes entire ByteBuffer content to channel.
     *
     * @param channel the channel to write to
     * @param buffer the buffer to write (from position to limit)
     * @return total bytes written
     * @throws IOException if I/O error occurs
     */
    public static int writeFully(WritableByteChannel channel, ByteBuffer buffer)
            throws IOException {
        if (channel == null || buffer == null) {
            throw new IllegalArgumentException("Channel and buffer cannot be null");
        }

        int total = 0;
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            total += written;
        }
        return total;
    }

    // ==================== Utility Methods ====================

    /**
     * Safely closes Closeable resource without throwing exceptions.
     * Useful in finally blocks.
     *
     * @param closeable the resource to close (can be null)
     */
    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // Silently ignore
            }
        }
    }

    /**
     * Wraps byte array as InputStream.
     */
    public static InputStream toInputStream(byte[] bytes) {
        if (bytes == null) {
            return new ByteArrayInputStream(new byte[0]);
        }
        return new ByteArrayInputStream(bytes);
    }

    /**
     * Wraps byte array segment as InputStream.
     */
    public static InputStream toInputStream(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            return new ByteArrayInputStream(new byte[0]);
        }
        return new ByteArrayInputStream(bytes, offset, length);
    }

    /**
     * Creates buffered input stream with default buffer size.
     */
    public static BufferedInputStream buffer(InputStream input) {
        if (input == null) {
            throw new IllegalArgumentException("Input stream cannot be null");
        }
        return new BufferedInputStream(input, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates buffered output stream with default buffer size.
     */
    public static BufferedOutputStream buffer(OutputStream output) {
        if (output == null) {
            throw new IllegalArgumentException("Output stream cannot be null");
        }
        return new BufferedOutputStream(output, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates buffered input stream with custom buffer size.
     */
    public static BufferedInputStream buffer(InputStream input, int bufferSize) {
        if (input == null) {
            throw new IllegalArgumentException("Input stream cannot be null");
        }
        return new BufferedInputStream(input,
                Math.min(Math.max(bufferSize, 256), MAX_BUFFER_SIZE));
    }

    /**
     * Creates buffered output stream with custom buffer size.
     */
    public static BufferedOutputStream buffer(OutputStream output, int bufferSize) {
        if (output == null) {
            throw new IllegalArgumentException("Output stream cannot be null");
        }
        return new BufferedOutputStream(output,
                Math.min(Math.max(bufferSize, 256), MAX_BUFFER_SIZE));
    }

    /**
     * Drains input stream (reads and discards all data).
     * Useful for clearing stream before closing.
     *
     * @param input the stream to drain
     * @return total bytes drained
     */
    public static long drain(InputStream input) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        long total = 0;
        int read;

        while ((read = input.read(buffer)) != -1) {
            total += read;
        }

        return total;
    }
}