package com.genesis.p2p.protocol;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.observability.context.ObservabilityContext;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.protocol.codec.MessageCodec;
import com.genesis.p2p.protocol.config.ProtocolConfig;
import com.genesis.p2p.protocol.model.Frame;
import com.genesis.p2p.protocol.validator.ProtocolValidator;
import com.genesis.p2p.protocol.validator.ValidationRule;
import com.genesis.p2p.util.common.Time;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol layer for end-to-end message encoding, framing, and validation.
 *
 * Integrates:
 * - Message codecs (JSON, Protobuf)
 * - Frame fragmentation for large messages
 * - Protocol validation
 * - Metrics and logging
 * - Lifecycle management
 *
 * <p>All inbound and outbound messages pass through this layer for:
 * <ul>
 *   <li>Encoding/decoding with configured codec</li>
 *   <li>Fragmentation into frames (for large messages)</li>
 *   <li>Reassembly of frames into complete messages</li>
 *   <li>Protocol validation (version, TTL, signatures)</li>
 *   <li>Observability (metrics and structured logging)</li>
 * </ul>
 *
 * <p><b>Lifecycle</b>:
 * <pre>{@code
 * ProtocolLayer protocol = new ProtocolLayer(config, metricsRegistry);
 * protocol.start();
 *
 * // Outbound: Message -> Frames -> Bytes
 * byte[][] frameBytes = protocol.encodeToFrames(message);
 *
 * // Inbound: Bytes -> Frames -> Message
 * protocol.receiveFrame(frameBytes);
 * if (protocol.isMessageComplete(messageId)) {
 *     Message message = protocol.reassembleMessage(messageId);
 * }
 *
 * protocol.stop();
 * }</pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ProtocolLayer {

    private static final NodeLogger log = NodeLogger.getLogger(ProtocolLayer.class);

    private final ProtocolConfig config;
    private final MessageCodec codec;
    private final ProtocolValidator validator;
    private final MetricsRegistry metrics;

    // Frame reassembly state
    private final Map<UUID, FrameBuffer> frameBuffers = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    /**
     * Creates protocol layer with configuration.
     *
     * @param config protocol configuration
     * @param metrics metrics registry for observability
     */
    public ProtocolLayer(ProtocolConfig config, MetricsRegistry metrics) {
        if (config == null) {
            throw new IllegalArgumentException("Protocol config cannot be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("Metrics registry cannot be null");
        }

        this.config = config;
        this.codec = config.getCodec();
        this.validator = config.getValidator();
        this.metrics = metrics;

        log.info("Protocol layer created",
                "codec", codec.getName(),
                "validator", validator.toString(),
                "maxMessageSize", config.getMaxMessageSize(),
                "maxFrameSize", config.getMaxFrameSize());
    }

    // ==================== Lifecycle ====================

    /**
     * Starts the protocol layer.
     */
    public void start() {
        if (running) {
            log.warn("Protocol layer already running");
            return;
        }

        ObservabilityContext.setComponent("protocol");
        log.info("Starting protocol layer",
                "codec", codec.getName(),
                "fragmentation", config.isEnableFragmentation());

        running = true;

        log.info("Protocol layer started successfully");
        metrics.incrementCounter("protocol.lifecycle.started");
    }

    /**
     * Stops the protocol layer.
     */
    public void stop() {
        if (!running) {
            log.warn("Protocol layer not running");
            return;
        }

        ObservabilityContext.setComponent("protocol");
        log.info("Stopping protocol layer");

        running = false;

        // Clear frame buffers
        frameBuffers.clear();

        log.info("Protocol layer stopped");
        metrics.incrementCounter("protocol.lifecycle.stopped");
    }

    /**
     * Checks if protocol layer is running.
     */
    public boolean isRunning() {
        return running;
    }

    // ==================== Outbound: Message -> Frames ====================

    /**
     * Encodes a message to wire format (frames).
     *
     * Steps:
     * 1. Validate message
     * 2. Encode message to bytes using codec
     * 3. Fragment into frames if needed
     * 4. Return frame bytes for transmission
     *
     * @param message the message to encode
     * @return array of frame byte arrays
     * @throws ProtocolException if encoding fails
     */
    public byte[][] encodeToFrames(Message message) throws ProtocolException {
        if (!running) {
            throw new ProtocolException("Protocol layer not running");
        }

        ObservabilityContext.setComponent("protocol");
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Validate message
            ProtocolValidator.AggregatedValidationResult validation = validator.validate(message);
            if (!validation.isValid()) {
                String errorSummary = validation.getErrorSummary();
                log.warn("Message validation failed",
                        "messageId", message.messageId(),
                        "errors", errorSummary);
                metrics.incrementCounter("protocol.validation.failed");
                throw new ProtocolException("Validation failed: " + errorSummary);
            }

            // Step 2: Encode to bytes
            byte[] encodedBytes = codec.encode(message);
            metrics.incrementCounter("protocol.encoding.success");
            metrics.recordTimer("protocol.encoding.duration",
                    System.currentTimeMillis() - startTime);

            log.debug("Message encoded",
                    "messageId", message.messageId(),
                    "originalSize", encodedBytes.length,
                    "codec", codec.getName());

            // Step 2.5: Compress if enabled
            if (config.isEnableCompression() &&
                config.getCompressionCodec() != null) {

                if (encodedBytes.length >= config.getMinCompressionSize()) {
                    try {
                    long compressionStart = System.currentTimeMillis();
                    byte[] compressed = config.getCompressionCodec().compress(encodedBytes);
                    long compressionTime = System.currentTimeMillis() - compressionStart;

                    // Only use if compression helped
                    if (compressed.length < encodedBytes.length) {
                        double compressionRatio = (double) compressed.length / encodedBytes.length;

                        log.debug("MESSAGE_COMPRESSION_APPLIED",
                                "messageId", message.messageId(),
                                "codec", config.getCompressionCodec().getName(),
                                "originalSize", encodedBytes.length,
                                "compressedSize", compressed.length,
                                "compressionRatio", String.format("%.2f", compressionRatio),
                                "compressionTime", compressionTime);

                        encodedBytes = compressed;
                        metrics.incrementCounter("protocol.compression.applied");
                    } else {
                        log.debug("MESSAGE_COMPRESSION_SKIPPED",
                                "messageId", message.messageId(),
                                "reason", "NOT_BENEFICIAL",
                                "originalSize", encodedBytes.length,
                                "compressedSize", compressed.length);
                        metrics.incrementCounter("protocol.compression.skipped");
                    }
                } catch (Exception e) {
                    log.warn("Compression failed, sending uncompressed", e);
                    metrics.incrementCounter("protocol.compression.failed");
                }
                } else {
                    log.debug("MESSAGE_COMPRESSION_SKIPPED",
                            "messageId", message.messageId(),
                            "reason", "BELOW_THRESHOLD",
                            "originalSize", encodedBytes.length,
                            "minCompressionSize", config.getMinCompressionSize());
                }
            }

            // Step 3: Fragment if needed
            byte[][] frameBytes;
            if (config.isEnableFragmentation() &&
                encodedBytes.length > config.getMaxFrameSize()) {

                // Log fragmentation start
                int totalFrames = (int) Math.ceil((double) encodedBytes.length / config.getMaxFrameSize());
                log.debug("MESSAGE_FRAGMENTATION_START",
                        "messageId", message.messageId(),
                        "messageSize", encodedBytes.length,
                        "frameSize", config.getMaxFrameSize(),
                        "totalFrames", totalFrames,
                        "messageType", message.type());

                frameBytes = fragmentMessage(message.messageId(), encodedBytes);

                log.info("Message fragmented",
                        "messageId", message.messageId(),
                        "totalSize", encodedBytes.length,
                        "frames", frameBytes.length);
                metrics.incrementCounter("protocol.fragmentation.created");
            } else {
                // Single frame
                Frame singleFrame = new Frame(
                        UUID.randomUUID(),
                        0, 1,
                        encodedBytes
                );
                frameBytes = new byte[][] { singleFrame.toBytes() };
            }

            return frameBytes;

        } catch (MessageCodec.CodecException e) {
            log.error("Message encoding failed", e,
                    "messageId", message.messageId(),
                    "codec", codec.getName());
            metrics.incrementCounter("protocol.encoding.failed");
            throw new ProtocolException("Encoding failed", e);
        }
    }

    /**
     * Fragments encoded bytes into multiple frames.
     */
    private byte[][] fragmentMessage(String messageId, byte[] encodedBytes) {
        Frame[] frames = Frame.fragment(encodedBytes, config.getMaxFrameSize());
        byte[][] frameBytes = new byte[frames.length][];

        for (int i = 0; i < frames.length; i++) {
            Frame frame = frames[i];
            frameBytes[i] = frame.toBytes();

            // Log each frame created
            log.debug("FRAME_CREATED",
                    "messageId", messageId,
                    "frameNumber", i + 1,
                    "totalFrames", frames.length,
                    "frameSize", frame.getPayload().length,
                    "offset", i * config.getMaxFrameSize(),
                    "isLast", i == frames.length - 1);
        }

        return frameBytes;
    }

    // ==================== Inbound: Frames -> Message ====================

    /**
     * Receives a frame from the wire.
     *
     * Buffers frames until all fragments are received, then returns true.
     *
     * @param frameBytes raw frame bytes from wire
     * @return true if message is complete and ready for reassembly
     * @throws ProtocolException if frame is invalid
     */
    public boolean receiveFrame(byte[] frameBytes) throws ProtocolException {
        if (!running) {
            throw new ProtocolException("Protocol layer not running");
        }

        ObservabilityContext.setComponent("protocol");

        // Defensive validation: null or empty data (network noise)
        if (frameBytes == null || frameBytes.length == 0) {
            log.debug("Received null or empty frame data (network noise), ignoring");
            metrics.incrementCounter("protocol.frames.noise.empty");
            return false;
        }

        try {
            // Defensive: limit concurrent reassembly operations to prevent resource exhaustion
            if (frameBuffers.size() >= 1000) {
                log.warn("Too many concurrent frame buffers, cleaning up stale buffers",
                        "current", frameBuffers.size());
                cleanupStaleBuffers();
                metrics.incrementCounter("protocol.frames.buffers.cleanup_forced");
            }

            // Parse frame with defensive error handling
            Frame frame;
            try {
                frame = Frame.fromBytes(frameBytes);
            } catch (IllegalArgumentException e) {
                // Downgrade to DEBUG: malformed packets are expected network noise
                log.debug("Malformed frame received (network noise), ignoring",
                        "error", e.getMessage(),
                        "dataSize", frameBytes.length);
                metrics.incrementCounter("protocol.frames.noise.malformed");
                // Silently ignore - don't throw exception
                return false;
            }

            log.debug("Frame received",
                    "messageId", frame.getMessageId(),
                    "fragment", frame.getFragmentIndex() + 1,
                    "total", frame.getTotalFragments());

            metrics.incrementCounter("protocol.frames.received");

            // Buffer frame
            UUID messageId = frame.getMessageId();
            FrameBuffer buffer = frameBuffers.computeIfAbsent(
                    messageId,
                    id -> new FrameBuffer(frame.getTotalFragments())
            );

            // Defensive: validate buffer consistency
            if (buffer.getTotalFrames() != frame.getTotalFragments()) {
                log.debug("Frame totalFragments mismatch with existing buffer, ignoring",
                        "messageId", messageId,
                        "bufferExpects", buffer.getTotalFrames(),
                        "frameReports", frame.getTotalFragments());
                metrics.incrementCounter("protocol.frames.noise.mismatch");
                return false;
            }

            // Add frame to buffer
            try {
                buffer.addFrame(frame);
            } catch (IllegalArgumentException e) {
                // Duplicate or out-of-order frame (network noise)
                log.debug("Invalid frame for buffer (duplicate or out of order), ignoring",
                        "messageId", messageId,
                        "error", e.getMessage());
                metrics.incrementCounter("protocol.frames.noise.duplicate");
                return false;
            }

            // Check if complete
            if (buffer.isComplete()) {
                log.info("Message frames complete",
                        "messageId", messageId,
                        "totalFrames", frame.getTotalFragments());
                metrics.incrementCounter("protocol.fragmentation.reassembled");
                return true;
            }

            return false;

        } catch (Exception e) {
            // Catch-all for unexpected errors - log and continue
            log.debug("Unexpected error processing frame, ignoring",
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage());
            metrics.incrementCounter("protocol.frames.noise.error");
            return false;
        }
    }

    /**
     * Checks if a message is complete and ready for reassembly.
     */
    public boolean isMessageComplete(UUID messageId) {
        FrameBuffer buffer = frameBuffers.get(messageId);
        return buffer != null && buffer.isComplete();
    }

    /**
     * Reassembles a complete message from buffered frames.
     *
     * @param messageId the message ID
     * @return decoded message
     * @throws ProtocolException if reassembly or decoding fails
     */
    public Message reassembleMessage(UUID messageId) throws ProtocolException {
        if (!running) {
            throw new ProtocolException("Protocol layer not running");
        }

        ObservabilityContext.setComponent("protocol");
        long startTime = Time.currentMillis();

        try {
            // Get buffer
            FrameBuffer buffer = frameBuffers.remove(messageId);
            if (buffer == null) {
                throw new ProtocolException("No frames buffered for message: " + messageId);
            }

            if (!buffer.isComplete()) {
                throw new ProtocolException("Message not complete: " + messageId);
            }

            // Log reassembly start
            Frame[] frames = buffer.getFrames();
            log.debug("MESSAGE_REASSEMBLY_START",
                    "messageId", messageId,
                    "framesReceived", frames.length,
                    "totalFrames", frames.length);

            // Reassemble frames
            long reassemblyStart = System.currentTimeMillis();
            byte[] encodedBytes = Frame.reassemble(frames);
            long reassemblyTime = System.currentTimeMillis() - reassemblyStart;

            log.debug("MESSAGE_REASSEMBLY_COMPLETE",
                    "messageId", messageId,
                    "totalFrames", frames.length,
                    "totalSize", encodedBytes.length,
                    "reassemblyTime", reassemblyTime);

            // Decompress if enabled
            if (config.isEnableCompression() && config.getCompressionCodec() != null) {
                try {
                    long decompressionStart = System.currentTimeMillis();
                    byte[] decompressed = config.getCompressionCodec().decompress(encodedBytes);
                    long decompressionTime = System.currentTimeMillis() - decompressionStart;

                    log.debug("MESSAGE_DECOMPRESSION",
                            "messageId", messageId,
                            "codec", config.getCompressionCodec().getName(),
                            "compressedSize", encodedBytes.length,
                            "decompressedSize", decompressed.length,
                            "decompressionTime", decompressionTime);

                    encodedBytes = decompressed;
                    metrics.incrementCounter("protocol.decompression.success");
                } catch (Exception e) {
                    // Not compressed or decompression failed, use as-is
                    log.debug("Decompression skipped or failed, using raw data");
                    metrics.incrementCounter("protocol.decompression.skipped");
                }
            }

            // Decode message
            Message message = codec.decode(encodedBytes);

            metrics.incrementCounter("protocol.decoding.success");
            metrics.recordTimer("protocol.decoding.duration",
                    Time.currentMillis() - startTime);

            log.info("Message decoded",
                    "messageId", message.messageId(),
                    "type", message.type(),
                    "codec", codec.getName());

            // Validate decoded message
            ProtocolValidator.AggregatedValidationResult validation = validator.validate(message);
            if (!validation.isValid()) {
                String errorSummary = validation.getErrorSummary();
                log.warn("Decoded message validation failed",
                        "messageId", message.messageId(),
                        "errors", errorSummary);
                metrics.incrementCounter("protocol.validation.failed");
                throw new ProtocolException("Validation failed: " + errorSummary);
            }

            return message;

        } catch (MessageCodec.CodecException e) {
            log.error("Message decoding failed", e, "messageId", messageId);
            metrics.incrementCounter("protocol.decoding.failed");
            throw new ProtocolException("Decoding failed", e);
        } catch (IllegalArgumentException e) {
            log.error("Frame reassembly failed", e, "messageId", messageId);
            metrics.incrementCounter("protocol.fragmentation.failed");
            throw new ProtocolException("Reassembly failed", e);
        }
    }

    // ==================== Direct Encoding/Decoding (no framing) ====================

    /**
     * Encodes a message directly to bytes (no framing).
     *
     * For backwards compatibility or single-frame messages.
     */
    public byte[] encodeDirect(Message message) throws ProtocolException {
        ObservabilityContext.setComponent("protocol");

        try {
            byte[] bytes = codec.encode(message);
            metrics.incrementCounter("protocol.encoding.direct");
            return bytes;
        } catch (MessageCodec.CodecException e) {
            metrics.incrementCounter("protocol.encoding.failed");
            throw new ProtocolException("Direct encoding failed", e);
        }
    }

    /**
     * Decodes bytes directly to a message (no framing).
     *
     * For backwards compatibility or single-frame messages.
     */
    public Message decodeDirect(byte[] bytes) throws ProtocolException {
        ObservabilityContext.setComponent("protocol");

        try {
            Message message = codec.decode(bytes);
            metrics.incrementCounter("protocol.decoding.direct");
            return message;
        } catch (MessageCodec.CodecException e) {
            metrics.incrementCounter("protocol.decoding.failed");
            throw new ProtocolException("Direct decoding failed", e);
        }
    }

    // ==================== Configuration ====================

    public ProtocolConfig getConfig() {
        return config;
    }

    public MessageCodec getCodec() {
        return codec;
    }

    public ProtocolValidator getValidator() {
        return validator;
    }

    // ==================== Cleanup ====================

    /**
     * Cleans up stale frame buffers that haven't completed within TTL.
     *
     * Prevents memory exhaustion from incomplete messages (DoS mitigation).
     * Default TTL: 60 seconds.
     */
    private void cleanupStaleBuffers() {
        long now = System.currentTimeMillis();
        long ttl = 60_000; // 60 seconds

        int initialSize = frameBuffers.size();
        int removed = 0;

        // Remove buffers older than TTL
        Iterator<Map.Entry<UUID, FrameBuffer>> iterator = frameBuffers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FrameBuffer> entry = iterator.next();
            FrameBuffer buffer = entry.getValue();

            long age = now - buffer.getCreatedAt();
            if (age > ttl) {
                iterator.remove();
                removed++;

                log.debug("Stale frame buffer removed",
                        "messageId", entry.getKey(),
                        "age", age,
                        "receivedFrames", buffer.getReceivedCount(),
                        "totalFrames", buffer.getTotalFrames());
            }
        }

        if (removed > 0) {
            log.info("Stale frame buffers cleaned up",
                    "removed", removed,
                    "remaining", frameBuffers.size(),
                    "initialSize", initialSize);
            metrics.incrementCounter("protocol.frames.buffers.cleanup_stale", removed);
        }
    }

    // ==================== Frame Buffer ====================

    /**
     * Buffers frames for reassembly with timestamp tracking.
     */
    private static class FrameBuffer {
        private final Frame[] frames;
        private final long createdAt;
        private int receivedCount = 0;

        FrameBuffer(int totalFragments) {
            this.frames = new Frame[totalFragments];
            this.createdAt = System.currentTimeMillis();
        }

        void addFrame(Frame frame) {
            int index = frame.getFragmentIndex();
            if (index < 0 || index >= frames.length) {
                throw new IllegalArgumentException("Invalid fragment index");
            }

            if (frames[index] == null) {
                frames[index] = frame;
                receivedCount++;
            }
        }

        boolean isComplete() {
            return receivedCount == frames.length;
        }

        Frame[] getFrames() {
            return frames;
        }

        int getTotalFrames() {
            return frames.length;
        }

        int getReceivedCount() {
            return receivedCount;
        }

        long getCreatedAt() {
            return createdAt;
        }
    }
}
