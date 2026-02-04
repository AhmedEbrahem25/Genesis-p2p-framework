package com.genesis.p2p.protocol.model;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Frame for message fragmentation support.
 *
 * Large messages can be split into multiple frames for transmission.
 * Each frame carries:
 * - Message ID (to group fragments)
 * - Fragment index and total fragments
 * - Frame payload
 *
 * Wire format:
 * [16 bytes: message ID] [4 bytes: fragment index] [4 bytes: total fragments]
 * [4 bytes: payload length] [variable: payload]
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class Frame {

    // Frame header size
    public static final int HEADER_SIZE = 28;

    // Maximum frame payload size (1MB)
    public static final int MAX_PAYLOAD_SIZE = 1024 * 1024;

    // Maximum reasonable number of fragments (prevents DoS)
    private static final int MAX_FRAGMENTS = 10000;

    // Maximum total message size (10MB - prevents memory exhaustion)
    private static final long MAX_TOTAL_MESSAGE_SIZE = 10L * 1024 * 1024;

    private final UUID messageId;
    private final int fragmentIndex;
    private final int totalFragments;
    private final byte[] payload;

    /**
     * Creates a new frame.
     *
     * @param messageId unique identifier for the message
     * @param fragmentIndex zero-based index of this fragment
     * @param totalFragments total number of fragments in the message
     * @param payload frame payload data
     */
    public Frame(UUID messageId, int fragmentIndex, int totalFragments, byte[] payload) {
        if (messageId == null) {
            throw new IllegalArgumentException("Message ID cannot be null");
        }
        if (fragmentIndex < 0 || fragmentIndex >= totalFragments) {
            throw new IllegalArgumentException("Invalid fragment index: " + fragmentIndex);
        }
        if (totalFragments <= 0) {
            throw new IllegalArgumentException("Total fragments must be positive");
        }
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Payload cannot be null or empty");
        }
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload exceeds maximum size");
        }

        this.messageId = messageId;
        this.fragmentIndex = fragmentIndex;
        this.totalFragments = totalFragments;
        this.payload = payload;
    }

    // ==================== Serialization ====================

    /**
     * Serializes frame to bytes for wire transmission.
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);

        // Message ID (16 bytes: most significant bits + least significant bits)
        buffer.putLong(messageId.getMostSignificantBits());
        buffer.putLong(messageId.getLeastSignificantBits());

        // Fragment index
        buffer.putInt(fragmentIndex);

        // Total fragments
        buffer.putInt(totalFragments);

        // Payload length
        buffer.putInt(payload.length);

        // Payload
        buffer.put(payload);

        return buffer.array();
    }

    /**
     * Deserializes frame from wire bytes.
     *
     * @param data raw bytes from wire
     * @return parsed frame
     * @throws IllegalArgumentException if data is invalid
     */
    public static Frame fromBytes(byte[] data) {
        // Defensive validation: null or too short
        if (data == null) {
            throw new IllegalArgumentException("Invalid frame: data is null");
        }
        if (data.length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                String.format("Invalid frame: too short (got %d bytes, need %d)",
                    data.length, HEADER_SIZE));
        }
        // Defensive validation: unreasonably large
        if (data.length > HEADER_SIZE + MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException(
                String.format("Invalid frame: too large (got %d bytes, max %d)",
                    data.length, HEADER_SIZE + MAX_PAYLOAD_SIZE));
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        try {
            // Read message ID
            long mostSigBits = buffer.getLong();
            long leastSigBits = buffer.getLong();
            UUID messageId = new UUID(mostSigBits, leastSigBits);

            // Read fragment index
            int fragmentIndex = buffer.getInt();

            // Read total fragments
            int totalFragments = buffer.getInt();

            // Defensive validation: unreasonable fragment count (DoS prevention)
            if (totalFragments <= 0) {
                throw new IllegalArgumentException(
                    "Invalid frame: totalFragments must be positive, got " + totalFragments);
            }
            if (totalFragments > MAX_FRAGMENTS) {
                throw new IllegalArgumentException(
                    String.format("Invalid frame: too many fragments (got %d, max %d)",
                        totalFragments, MAX_FRAGMENTS));
            }

            // Defensive validation: fragment index in range
            if (fragmentIndex < 0 || fragmentIndex >= totalFragments) {
                throw new IllegalArgumentException(
                    String.format("Invalid frame: fragmentIndex %d out of range [0, %d)",
                        fragmentIndex, totalFragments));
            }

            // Read payload length
            int payloadLength = buffer.getInt();

            // Defensive validation: payload length consistency
            if (payloadLength < 0) {
                throw new IllegalArgumentException(
                    "Invalid frame: negative payload length " + payloadLength);
            }
            if (payloadLength == 0) {
                throw new IllegalArgumentException(
                    "Invalid frame: empty payload");
            }
            if (payloadLength > MAX_PAYLOAD_SIZE) {
                throw new IllegalArgumentException(
                    String.format("Invalid frame: payload too large (got %d, max %d)",
                        payloadLength, MAX_PAYLOAD_SIZE));
            }
            // Check exact size match (prevents buffer overflow)
            int expectedTotalSize = HEADER_SIZE + payloadLength;
            if (data.length != expectedTotalSize) {
                throw new IllegalArgumentException(
                    String.format("Invalid frame: size mismatch (got %d bytes, expected %d)",
                        data.length, expectedTotalSize));
            }

            // Read payload
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);

            // Construct validated frame (constructor does additional validation)
            return new Frame(messageId, fragmentIndex, totalFragments, payload);

        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException(
                "Invalid frame: buffer underflow during parsing", e);
        } catch (IllegalArgumentException e) {
            // Re-throw validation errors from constructor or our checks
            throw e;
        } catch (Exception e) {
            // Catch any unexpected errors and wrap them
            throw new IllegalArgumentException(
                "Invalid frame: parsing failed - " + e.getMessage(), e);
        }
    }

    // ==================== Getters ====================

    public UUID getMessageId() {
        return messageId;
    }

    public int getFragmentIndex() {
        return fragmentIndex;
    }

    public int getTotalFragments() {
        return totalFragments;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getTotalSize() {
        return HEADER_SIZE + payload.length;
    }

    /**
     * Checks if this is the last fragment.
     */
    public boolean isLastFragment() {
        return fragmentIndex == totalFragments - 1;
    }

    /**
     * Checks if this is the first fragment.
     */
    public boolean isFirstFragment() {
        return fragmentIndex == 0;
    }

    // ==================== Factory Methods ====================

    /**
     * Creates frames from a large payload.
     *
     * @param payload the complete payload to fragment
     * @param maxFrameSize maximum size for each frame payload
     * @return array of frames
     */
    public static Frame[] fragment(byte[] payload, int maxFrameSize) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Payload cannot be null or empty");
        }
        if (maxFrameSize <= 0 || maxFrameSize > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Invalid frame size");
        }

        UUID messageId = UUID.randomUUID();
        int totalFragments = (int) Math.ceil((double) payload.length / maxFrameSize);
        Frame[] frames = new Frame[totalFragments];

        for (int i = 0; i < totalFragments; i++) {
            int offset = i * maxFrameSize;
            int length = Math.min(maxFrameSize, payload.length - offset);
            byte[] fragmentPayload = new byte[length];
            System.arraycopy(payload, offset, fragmentPayload, 0, length);
            frames[i] = new Frame(messageId, i, totalFragments, fragmentPayload);
        }

        return frames;
    }

    /**
     * Reassembles frames into the original payload.
     *
     * @param frames array of frames (must be complete and in order)
     * @return reassembled payload
     * @throws IllegalArgumentException if frames are invalid or reassembly fails
     */
    public static byte[] reassemble(Frame[] frames) {
        // Defensive validation: null or empty
        if (frames == null) {
            throw new IllegalArgumentException("Frames cannot be null");
        }
        if (frames.length == 0) {
            throw new IllegalArgumentException("Frames cannot be empty");
        }

        // Defensive validation: too many fragments (DoS prevention)
        if (frames.length > MAX_FRAGMENTS) {
            throw new IllegalArgumentException(
                String.format("Too many fragments to reassemble: %d (max %d)",
                    frames.length, MAX_FRAGMENTS));
        }

        // Validate all frames belong to same message
        UUID messageId = frames[0].getMessageId();
        int totalFragments = frames[0].getTotalFragments();

        if (frames.length != totalFragments) {
            throw new IllegalArgumentException(
                String.format("Incomplete frame set: have %d frames, expected %d",
                    frames.length, totalFragments));
        }

        // Calculate total size and validate consistency
        long totalSize = 0;  // Use long to prevent integer overflow
        for (int i = 0; i < frames.length; i++) {
            Frame frame = frames[i];

            // Defensive validation: null frame
            if (frame == null) {
                throw new IllegalArgumentException(
                    "Frame at index " + i + " is null");
            }

            // Defensive validation: message ID consistency
            if (!frame.getMessageId().equals(messageId)) {
                throw new IllegalArgumentException(
                    String.format("Frame %d message ID mismatch: expected %s, got %s",
                        i, messageId, frame.getMessageId()));
            }

            // Defensive validation: total fragments consistency
            if (frame.getTotalFragments() != totalFragments) {
                throw new IllegalArgumentException(
                    String.format("Frame %d totalFragments mismatch: expected %d, got %d",
                        i, totalFragments, frame.getTotalFragments()));
            }

            // Defensive validation: frames in order
            if (frame.getFragmentIndex() != i) {
                throw new IllegalArgumentException(
                    String.format("Frame at position %d has wrong index %d (not in order)",
                        i, frame.getFragmentIndex()));
            }

            // Accumulate size
            totalSize += frame.getPayload().length;

            // Defensive validation: prevent memory exhaustion
            if (totalSize > MAX_TOTAL_MESSAGE_SIZE) {
                throw new IllegalArgumentException(
                    String.format("Reassembled message too large: %d bytes (max %d MB)",
                        totalSize, MAX_TOTAL_MESSAGE_SIZE / (1024 * 1024)));
            }
        }

        // Defensive validation: sanity check final size
        if (totalSize <= 0) {
            throw new IllegalArgumentException("Invalid total size: " + totalSize);
        }
        if (totalSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Total size exceeds int range: " + totalSize);
        }

        try {
            // Reassemble with validated size
            byte[] result = new byte[(int) totalSize];
            int offset = 0;

            for (Frame frame : frames) {
                byte[] payload = frame.getPayload();

                // Defensive check: prevent buffer overflow
                if (offset + payload.length > result.length) {
                    throw new IllegalStateException(
                        String.format("Buffer overflow during reassembly at offset %d", offset));
                }

                System.arraycopy(payload, 0, result, offset, payload.length);
                offset += payload.length;
            }

            // Defensive validation: all bytes written
            if (offset != totalSize) {
                throw new IllegalStateException(
                    String.format("Reassembly size mismatch: wrote %d bytes, expected %d",
                        offset, totalSize));
            }

            return result;

        } catch (OutOfMemoryError e) {
            throw new IllegalArgumentException(
                "Out of memory during reassembly (message too large)", e);
        }
    }

    @Override
    public String toString() {
        return String.format("Frame[messageId=%s, fragment=%d/%d, size=%d]",
                messageId, fragmentIndex + 1, totalFragments, payload.length);
    }
}

