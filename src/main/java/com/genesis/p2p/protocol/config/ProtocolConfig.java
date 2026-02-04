package com.genesis.p2p.protocol.config;

import com.genesis.p2p.protocol.codec.JsonMessageCodec;
import com.genesis.p2p.protocol.codec.MessageCodec;
import com.genesis.p2p.protocol.codec.ProtobufMessageCodec;
import com.genesis.p2p.protocol.compression.CompressionCodec;
import com.genesis.p2p.protocol.compression.GzipCodec;
import com.genesis.p2p.protocol.compression.Lz4Codec;
import com.genesis.p2p.protocol.model.ProtocolVersion;
import com.genesis.p2p.protocol.validator.ProtocolValidator;

/**
 * Configuration for protocol layer.
 *
 * Uses Builder pattern for flexible configuration.
 * Centralizes protocol settings:
 * - Codec selection (JSON, Protobuf)
 * - Validation rules
 * - Protocol version
 * - Envelope settings
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ProtocolConfig {

    // Default values
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final int DEFAULT_MAX_FRAME_SIZE = 1024 * 1024; // 1 MB
    private static final boolean DEFAULT_ENABLE_COMPRESSION = false;
    private static final boolean DEFAULT_ENABLE_ENCRYPTION = false;
    private static final boolean DEFAULT_ENABLE_FRAGMENTATION = true;

    private final MessageCodec codec;
    private final ProtocolValidator validator;
    private final ProtocolVersion version;
    private final int maxMessageSize;
    private final int maxFrameSize;
    private final boolean enableCompression;
    private final boolean enableEncryption;
    private final boolean enableFragmentation;
    private final CompressionCodec compressionCodec;
    private final int minCompressionSize;

    private ProtocolConfig(Builder builder) {
        this.codec = builder.codec;
        this.validator = builder.validator;
        this.version = builder.version;
        this.maxMessageSize = builder.maxMessageSize;
        this.maxFrameSize = builder.maxFrameSize;
        this.enableCompression = builder.enableCompression;
        this.enableEncryption = builder.enableEncryption;
        this.enableFragmentation = builder.enableFragmentation;
        this.compressionCodec = builder.compressionCodec;
        this.minCompressionSize = builder.minCompressionSize;
    }

    // ==================== Getters ====================

    public MessageCodec getCodec() {
        return codec;
    }

    public ProtocolValidator getValidator() {
        return validator;
    }

    public ProtocolVersion getVersion() {
        return version;
    }

    public int getMaxMessageSize() {
        return maxMessageSize;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public boolean isEnableCompression() {
        return enableCompression;
    }

    public boolean isEnableEncryption() {
        return enableEncryption;
    }

    public boolean isEnableFragmentation() {
        return enableFragmentation;
    }

    public CompressionCodec getCompressionCodec() {
        return compressionCodec;
    }

    public int getMinCompressionSize() {
        return minCompressionSize;
    }

    // ==================== Factory Methods ====================

    /**
     * Creates default configuration (JSON codec, basic validation).
     */
    public static ProtocolConfig defaultConfig() {
        return new Builder().build();
    }

    /**
     * Creates production configuration (Protobuf codec, secure validation).
     */
    public static ProtocolConfig productionConfig() {
        return new Builder()
                .codec(new ProtobufMessageCodec())
                .validator(ProtocolValidator.secure())
                .enableEncryption(true)
                .enableCompression(true)
                .build();
    }

    /**
     * Creates development configuration (JSON codec, permissive validation).
     */
    public static ProtocolConfig developmentConfig() {
        return new Builder()
                .codec(new JsonMessageCodec())
                .validator(ProtocolValidator.permissive())
                .enableEncryption(false)
                .enableCompression(false)
                .build();
    }

    // ==================== Builder ====================

    /**
     * Builder for creating protocol configurations.
     */
    public static class Builder {
        private MessageCodec codec = new JsonMessageCodec();
        private ProtocolValidator validator = ProtocolValidator.basic();
        private ProtocolVersion version = ProtocolVersion.current();
        private int maxMessageSize = DEFAULT_MAX_MESSAGE_SIZE;
        private int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
        private boolean enableCompression = DEFAULT_ENABLE_COMPRESSION;
        private boolean enableEncryption = DEFAULT_ENABLE_ENCRYPTION;
        private boolean enableFragmentation = DEFAULT_ENABLE_FRAGMENTATION;
        private CompressionCodec compressionCodec = null;
        private int minCompressionSize = 1024; // 1KB default

        public Builder codec(MessageCodec codec) {
            if (codec == null) {
                throw new IllegalArgumentException("Codec cannot be null");
            }
            this.codec = codec;
            return this;
        }

        public Builder jsonCodec() {
            this.codec = new JsonMessageCodec();
            return this;
        }

        public Builder protobufCodec() {
            this.codec = new ProtobufMessageCodec();
            return this;
        }

        public Builder validator(ProtocolValidator validator) {
            if (validator == null) {
                throw new IllegalArgumentException("Validator cannot be null");
            }
            this.validator = validator;
            return this;
        }

        public Builder basicValidator() {
            this.validator = ProtocolValidator.basic();
            return this;
        }

        public Builder secureValidator() {
            this.validator = ProtocolValidator.secure();
            return this;
        }

        public Builder permissiveValidator() {
            this.validator = ProtocolValidator.permissive();
            return this;
        }

        public Builder version(ProtocolVersion version) {
            if (version == null) {
                throw new IllegalArgumentException("Version cannot be null");
            }
            this.version = version;
            return this;
        }

        public Builder maxMessageSize(int maxMessageSize) {
            if (maxMessageSize <= 0) {
                throw new IllegalArgumentException("Max message size must be positive");
            }
            this.maxMessageSize = maxMessageSize;
            return this;
        }

        public Builder maxFrameSize(int maxFrameSize) {
            if (maxFrameSize <= 0) {
                throw new IllegalArgumentException("Max frame size must be positive");
            }
            this.maxFrameSize = maxFrameSize;
            return this;
        }

        public Builder enableCompression(boolean enable) {
            this.enableCompression = enable;
            return this;
        }

        public Builder enableEncryption(boolean enable) {
            this.enableEncryption = enable;
            return this;
        }

        public Builder enableFragmentation(boolean enable) {
            this.enableFragmentation = enable;
            return this;
        }

        public Builder gzipCompression() {
            this.compressionCodec = new GzipCodec();
            this.enableCompression = true;
            return this;
        }

        public Builder lz4Compression() {
            this.compressionCodec = new Lz4Codec();
            this.enableCompression = true;
            return this;
        }

        public Builder compressionCodec(CompressionCodec codec) {
            this.compressionCodec = codec;
            this.enableCompression = (codec != null);
            return this;
        }

        public Builder minCompressionSize(int size) {
            if (size < 0) {
                throw new IllegalArgumentException("Min compression size must be non-negative");
            }
            this.minCompressionSize = size;
            return this;
        }

        public ProtocolConfig build() {
            // Validate configuration
            if (maxFrameSize > maxMessageSize) {
                throw new IllegalStateException("Frame size cannot exceed message size");
            }

            return new ProtocolConfig(this);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "ProtocolConfig[codec=%s, validator=%s, version=%s, " +
                "maxMessageSize=%d, maxFrameSize=%d, " +
                "compression=%s, encryption=%s, fragmentation=%s]",
                codec.getName(), validator, version,
                maxMessageSize, maxFrameSize,
                enableCompression, enableEncryption, enableFragmentation
        );
    }
}

