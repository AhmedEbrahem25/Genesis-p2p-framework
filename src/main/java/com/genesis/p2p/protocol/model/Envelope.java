package com.genesis.p2p.protocol.model;

import com.genesis.p2p.core.Message;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Protocol envelope that wraps messages for transport.
 *
 * The envelope adds:
 * - Protocol version for compatibility
 * - Flags (encryption, compression, priority)
 * - Checksum for integrity
 * - Transport metadata (source, destination addresses)
 *
 * Wire format:
 * [4 bytes: magic] [2 bytes: version] [2 bytes: flags]
 * [4 bytes: payload length] [4 bytes: checksum]
 * [variable: payload]
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class Envelope {

    // Magic bytes to identify Genesis P2P protocol
    public static final byte[] MAGIC = {0x47, 0x50, 0x32, 0x50}; // "GP2P"

    // Header size (fixed portion)
    public static final int HEADER_SIZE = 16;

    // Flags
    public static final int FLAG_ENCRYPTED = 0x0001;
    public static final int FLAG_COMPRESSED = 0x0002;
    public static final int FLAG_PRIORITY = 0x0004;
    public static final int FLAG_REQUIRE_ACK = 0x0008;
    public static final int FLAG_IS_ACK = 0x0010;
    public static final int FLAG_FRAGMENTED = 0x0020;

    private final ProtocolVersion version;
    private final int flags;
    private final byte[] payload;
    private final long checksum;
    private final Instant timestamp;
    private final String sourceAddress;
    private final String destinationAddress;

    /**
     * Creates envelope from message payload.
     */
    public Envelope(byte[] payload, ProtocolVersion version, int flags) {
        this(payload, version, flags, null, null);
    }

    /**
     * Creates envelope with full metadata.
     */
    public Envelope(byte[] payload, ProtocolVersion version, int flags,
                    String sourceAddress, String destinationAddress) {
        this.payload = payload;
        this.version = version;
        this.flags = flags;
        this.checksum = calculateChecksum(payload);
        this.timestamp = Instant.now();
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
    }

    /**
     * Private constructor for deserialization.
     */
    private Envelope(byte[] payload, ProtocolVersion version, int flags,
                     long checksum, String sourceAddress, String destinationAddress) {
        this.payload = payload;
        this.version = version;
        this.flags = flags;
        this.checksum = checksum;
        this.timestamp = Instant.now();
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
    }

    // ==================== Factory Methods ====================

    /**
     * Creates envelope for a message (default settings).
     */
    public static Envelope wrap(byte[] payload) {
        return new Envelope(payload, ProtocolVersion.current(), 0);
    }

    /**
     * Creates encrypted envelope.
     */
    public static Envelope wrapEncrypted(byte[] payload) {
        return new Envelope(payload, ProtocolVersion.current(), FLAG_ENCRYPTED);
    }

    /**
     * Creates high-priority envelope.
     */
    public static Envelope wrapPriority(byte[] payload) {
        return new Envelope(payload, ProtocolVersion.current(), FLAG_PRIORITY);
    }

    // ==================== Serialization ====================

    /**
     * Serializes envelope to bytes for wire transmission.
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);

        // Magic bytes
        buffer.put(MAGIC);

        // Version (major.minor)
        buffer.put((byte) version.getMajor());
        buffer.put((byte) version.getMinor());

        // Flags
        buffer.putShort((short) flags);

        // Payload length
        buffer.putInt(payload.length);

        // Checksum
        buffer.putInt((int) checksum);

        // Payload
        buffer.put(payload);

        return buffer.array();
    }

    /**
     * Deserializes envelope from wire bytes.
     *
     * @param data raw bytes from wire
     * @return parsed envelope
     * @throws IllegalArgumentException if data is invalid
     */
    public static Envelope fromBytes(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid envelope: too short");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        // Verify magic bytes
        byte[] magic = new byte[4];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("Invalid envelope: bad magic bytes");
        }

        // Read version
        int major = buffer.get() & 0xFF;
        int minor = buffer.get() & 0xFF;
        ProtocolVersion version = new ProtocolVersion(major, minor);

        // Read flags
        int flags = buffer.getShort() & 0xFFFF;

        // Read payload length
        int payloadLength = buffer.getInt();
        if (payloadLength < 0 || payloadLength > data.length - HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid envelope: bad payload length");
        }

        // Read checksum
        long expectedChecksum = buffer.getInt() & 0xFFFFFFFFL;

        // Read payload
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        // Verify checksum
        long actualChecksum = calculateChecksum(payload);
        if (actualChecksum != expectedChecksum) {
            throw new IllegalArgumentException("Invalid envelope: checksum mismatch");
        }

        return new Envelope(payload, version, flags, expectedChecksum, null, null);
    }

    // ==================== Checksum ====================

    /**
     * Calculates CRC32 checksum for payload.
     */
    private static long calculateChecksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Verifies payload integrity.
     */
    public boolean verifyChecksum() {
        return calculateChecksum(payload) == checksum;
    }

    // ==================== Flag Operations ====================

    public boolean isEncrypted() {
        return (flags & FLAG_ENCRYPTED) != 0;
    }

    public boolean isCompressed() {
        return (flags & FLAG_COMPRESSED) != 0;
    }

    public boolean isPriority() {
        return (flags & FLAG_PRIORITY) != 0;
    }

    public boolean requiresAck() {
        return (flags & FLAG_REQUIRE_ACK) != 0;
    }

    public boolean isAck() {
        return (flags & FLAG_IS_ACK) != 0;
    }

    public boolean isFragmented() {
        return (flags & FLAG_FRAGMENTED) != 0;
    }

    // ==================== Getters ====================

    public byte[] getPayload() {
        return payload;
    }

    public ProtocolVersion getVersion() {
        return version;
    }

    public int getFlags() {
        return flags;
    }

    public long getChecksum() {
        return checksum;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getSourceAddress() {
        return sourceAddress;
    }

    public String getDestinationAddress() {
        return destinationAddress;
    }

    public int getTotalSize() {
        return HEADER_SIZE + payload.length;
    }

    // ==================== Builder ====================

    /**
     * Builder for creating envelopes with custom settings.
     */
    public static class Builder {
        private byte[] payload;
        private ProtocolVersion version = ProtocolVersion.current();
        private int flags = 0;
        private String sourceAddress;
        private String destinationAddress;

        public Builder payload(byte[] payload) {
            this.payload = payload;
            return this;
        }

        public Builder version(ProtocolVersion version) {
            this.version = version;
            return this;
        }

        public Builder encrypted() {
            this.flags |= FLAG_ENCRYPTED;
            return this;
        }

        public Builder compressed() {
            this.flags |= FLAG_COMPRESSED;
            return this;
        }

        public Builder priority() {
            this.flags |= FLAG_PRIORITY;
            return this;
        }

        public Builder requireAck() {
            this.flags |= FLAG_REQUIRE_ACK;
            return this;
        }

        public Builder sourceAddress(String address) {
            this.sourceAddress = address;
            return this;
        }

        public Builder destinationAddress(String address) {
            this.destinationAddress = address;
            return this;
        }

        public Envelope build() {
            if (payload == null) {
                throw new IllegalStateException("Payload is required");
            }
            return new Envelope(payload, version, flags, sourceAddress, destinationAddress);
        }
    }

    @Override
    public String toString() {
        return String.format("Envelope[version=%s, flags=0x%04X, size=%d, checksum=%08X]",
                version, flags, payload.length, checksum);
    }
}
