package com.genesis.p2p.protocol.compression;

import java.io.IOException;

/**
 * Compression Codec interface.
 *
 * Provides compression and decompression for message payloads.
 * Reduces network bandwidth usage at the cost of CPU cycles.
 *
 * Implementations:
 * - GzipCodec: Good compression ratio, moderate speed (HTTP standard)
 * - Lz4Codec: Fast compression, good ratio (production recommended)
 * - SnappyCodec: Very fast, lower ratio (real-time applications)
 *
 * Use Cases:
 * - Large message payloads (>1KB)
 * - Text/JSON data (high compression ratio)
 * - Network bandwidth optimization
 * - Storage space reduction
 *
 * Trade-offs:
 * - CPU usage vs Network bandwidth
 * - Compression ratio vs Speed
 * - Small messages may not benefit (overhead)
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public interface CompressionCodec {

    /**
     * Compresses data.
     *
     * @param data uncompressed data
     * @return compressed data
     * @throws IOException if compression fails
     */
    byte[] compress(byte[] data) throws IOException;

    /**
     * Decompresses data.
     *
     * @param compressed compressed data
     * @return uncompressed data
     * @throws IOException if decompression fails
     */
    byte[] decompress(byte[] compressed) throws IOException;

    /**
     * Gets the codec name.
     *
     * @return codec identifier (e.g., "gzip", "lz4")
     */
    String getName();

    /**
     * Checks if compression is beneficial for the given data size.
     * Small payloads may not benefit due to overhead.
     *
     * @param dataSize size of data in bytes
     * @return true if compression is recommended
     */
    default boolean shouldCompress(int dataSize) {
        return dataSize >= getMinCompressionSize();
    }

    /**
     * Gets minimum size for compression to be beneficial.
     *
     * @return minimum data size in bytes (default: 512 bytes)
     */
    default int getMinCompressionSize() {
        return 512;
    }

    /**
     * Gets the compression level (1-9, or codec-specific).
     *
     * @return compression level
     */
    default int getCompressionLevel() {
        return 6; // Default balanced level
    }

    /**
     * Gets statistics about this codec.
     *
     * @return codec statistics
     */
    default CodecStats getStats() {
        return new CodecStats(getName(), 0, 0, 0, 0);
    }

    // ==================== Statistics ====================

    /**
     * Compression codec statistics.
     */
    class CodecStats {
        private final String codecName;
        private final long compressedBytes;
        private final long decompressedBytes;
        private final long compressionCount;
        private final long decompressionCount;

        public CodecStats(String codecName, long compressedBytes, long decompressedBytes,
                         long compressionCount, long decompressionCount) {
            this.codecName = codecName;
            this.compressedBytes = compressedBytes;
            this.decompressedBytes = decompressedBytes;
            this.compressionCount = compressionCount;
            this.decompressionCount = decompressionCount;
        }

        public String getCodecName() { return codecName; }
        public long getCompressedBytes() { return compressedBytes; }
        public long getDecompressedBytes() { return decompressedBytes; }
        public long getCompressionCount() { return compressionCount; }
        public long getDecompressionCount() { return decompressionCount; }

        /**
         * Gets average compression ratio.
         *
         * @return ratio (e.g., 0.5 = 50% size reduction)
         */
        public double getAverageCompressionRatio() {
            if (decompressedBytes == 0) {
                return 0.0;
            }
            return (double) compressedBytes / decompressedBytes;
        }

        /**
         * Gets space saved in bytes.
         *
         * @return bytes saved by compression
         */
        public long getSpaceSaved() {
            return decompressedBytes - compressedBytes;
        }

        @Override
        public String toString() {
            return String.format("CodecStats[codec=%s, ratio=%.2f%%, saved=%d bytes, ops=%d/%d]",
                    codecName,
                    (1.0 - getAverageCompressionRatio()) * 100,
                    getSpaceSaved(),
                    compressionCount,
                    decompressionCount);
        }
    }

    // ==================== Exception ====================

    /**
     * Exception thrown when compression/decompression fails.
     */
    class CompressionException extends IOException {
        public CompressionException(String message) {
            super(message);
        }

        public CompressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

