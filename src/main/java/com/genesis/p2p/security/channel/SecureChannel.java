package com.genesis.p2p.security.channel;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.security.cert.Certificate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Secure Channel for encrypted P2P communication.
 *
 * Provides:
 * - AES-256-GCM encryption/decryption
 * - Message authentication (AEAD)
 * - Replay protection with sliding window (SECURITY FIX v2.6)
 * - Session key rotation
 *
 * SECURITY FIX (v2.6): Added strict sequence number validation with sliding window
 * to prevent replay attacks. Previously, receiveCounter was incremented but never
 * validated, allowing out-of-order and replayed messages to be accepted.
 *
 * @author Genesis P2P Framework
 * @version 2.6
 */
public class SecureChannel {

    private static final NodeLogger log = NodeLogger.getLogger(SecureChannel.class);

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int SEQUENCE_NUMBER_SIZE = 8; // 64-bit sequence number

    // Replay protection configuration
    private static final int REPLAY_WINDOW_SIZE = 64; // Accept messages within this window
    private static final long MAX_SEQUENCE_GAP = 10000; // Maximum gap between sequence numbers

    private final String sessionId;
    private final String remotePeerId;
    private final SecretKeySpec sessionKey;
    private final byte[] baseNonce;
    private final Instant createdAt;

    // Message counters for replay protection
    private final AtomicLong sendCounter;
    private final AtomicLong receiveCounter;

    // SECURITY FIX (v2.6): Replay protection with sliding window
    // Tracks the highest sequence number received and a bitmap of recently seen sequences
    private final AtomicLong highestReceivedSequence;
    private final BitSet replayWindow; // Tracks which messages in the window have been seen
    private final Object replayLock = new Object(); // Synchronize replay window updates
    private final AtomicLong replayAttacksDetected;
    private final AtomicLong outOfOrderAccepted;

    // Statistics
    private final AtomicLong bytesEncrypted;
    private final AtomicLong bytesDecrypted;
    private final AtomicLong messagesEncrypted;
    private final AtomicLong messagesDecrypted;

    /**
     * Creates a secure channel.
     */
    public SecureChannel(String sessionId, String remotePeerId,
                        byte[] sessionKeyBytes, byte[] baseNonce) {
        this.sessionId = sessionId;
        this.remotePeerId = remotePeerId;
        this.sessionKey = new SecretKeySpec(sessionKeyBytes, "AES");
        this.baseNonce = baseNonce;
        this.createdAt = Instant.now();
        this.sendCounter = new AtomicLong(0);
        this.receiveCounter = new AtomicLong(0);
        this.bytesEncrypted = new AtomicLong(0);
        this.bytesDecrypted = new AtomicLong(0);
        this.messagesEncrypted = new AtomicLong(0);
        this.messagesDecrypted = new AtomicLong(0);

        // SECURITY FIX (v2.6): Initialize replay protection
        this.highestReceivedSequence = new AtomicLong(-1); // -1 means no messages received yet
        this.replayWindow = new BitSet(REPLAY_WINDOW_SIZE);
        this.replayAttacksDetected = new AtomicLong(0);
        this.outOfOrderAccepted = new AtomicLong(0);

        log.info("SecureChannel created",
                "sessionId", sessionId,
                "remotePeer", remotePeerId,
                "replayWindowSize", REPLAY_WINDOW_SIZE);
    }

    /**
     * Encrypts data.
     *
     * SECURITY FIX (v2.6): Now prepends sequence number for replay protection.
     * Wire format: [8 bytes sequence] + [12 bytes IV] + [ciphertext + auth tag]
     *
     * @param plaintext data to encrypt
     * @return encrypted data (sequence + IV + ciphertext + auth tag)
     * @throws EncryptionException if encryption fails
     */
    public byte[] encrypt(byte[] plaintext) throws EncryptionException {
        try {
            // Generate unique sequence number (also used for IV)
            long sequenceNumber = sendCounter.incrementAndGet();
            byte[] iv = generateIV(sequenceNumber);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, sessionKey, spec);

            // Encrypt
            byte[] ciphertext = cipher.doFinal(plaintext);

            // SECURITY FIX (v2.6): Combine with sequence number for replay protection
            // Format: [8 bytes sequence] + [12 bytes IV] + [ciphertext + auth tag]
            byte[] result = new byte[SEQUENCE_NUMBER_SIZE + iv.length + ciphertext.length];

            // Prepend sequence number (big-endian)
            ByteBuffer seqBuffer = ByteBuffer.wrap(result);
            seqBuffer.putLong(sequenceNumber);

            // Append IV and ciphertext
            System.arraycopy(iv, 0, result, SEQUENCE_NUMBER_SIZE, iv.length);
            System.arraycopy(ciphertext, 0, result, SEQUENCE_NUMBER_SIZE + iv.length, ciphertext.length);

            // Update statistics
            bytesEncrypted.addAndGet(result.length);
            messagesEncrypted.incrementAndGet();

            log.debug("Data encrypted",
                    "plaintextSize", plaintext.length,
                    "ciphertextSize", result.length,
                    "sequenceNumber", sequenceNumber);

            return result;

        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new EncryptionException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypts data with replay protection.
     *
     * SECURITY FIX (v2.6): Added sequence number validation with sliding window.
     * Wire format: [8 bytes sequence] + [12 bytes IV] + [ciphertext + auth tag]
     *
     * The sequence number is validated before decryption to prevent:
     * 1. Replay attacks (duplicate messages)
     * 2. Very old messages (outside sliding window)
     * 3. Messages with excessive gaps (potential attack)
     *
     * @param encrypted encrypted data (sequence + IV + ciphertext + auth tag)
     * @return decrypted plaintext
     * @throws DecryptionException if decryption fails or replay detected
     */
    public byte[] decrypt(byte[] encrypted) throws DecryptionException {
        try {
            // SECURITY FIX (v2.6): Validate minimum length including sequence number
            int minLength = SEQUENCE_NUMBER_SIZE + GCM_IV_LENGTH + 16; // 16 = minimum auth tag
            if (encrypted.length < minLength) {
                throw new DecryptionException("Invalid encrypted data: too short (expected >= " +
                        minLength + ", got " + encrypted.length + ")");
            }

            // Extract sequence number (big-endian)
            ByteBuffer buffer = ByteBuffer.wrap(encrypted);
            long sequenceNumber = buffer.getLong();

            // SECURITY FIX (v2.6): Validate sequence number BEFORE decryption
            // This is critical - we must reject replays before expensive crypto operations
            validateSequenceNumber(sequenceNumber);

            // Extract IV and ciphertext
            byte[] iv = Arrays.copyOfRange(encrypted, SEQUENCE_NUMBER_SIZE, SEQUENCE_NUMBER_SIZE + GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(encrypted, SEQUENCE_NUMBER_SIZE + GCM_IV_LENGTH, encrypted.length);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec);

            // Decrypt and verify auth tag
            byte[] plaintext = cipher.doFinal(ciphertext);

            // SECURITY FIX (v2.6): Mark sequence as received AFTER successful decryption
            // This ensures we don't mark corrupted messages as received
            markSequenceReceived(sequenceNumber);

            // Update counter for statistics
            receiveCounter.incrementAndGet();

            // Update statistics
            bytesDecrypted.addAndGet(encrypted.length);
            messagesDecrypted.incrementAndGet();

            log.debug("Data decrypted",
                    "ciphertextSize", encrypted.length,
                    "plaintextSize", plaintext.length,
                    "sequenceNumber", sequenceNumber);

            return plaintext;

        } catch (ReplayAttackException e) {
            // Don't wrap replay exceptions
            throw new DecryptionException("Replay attack detected: " + e.getMessage());
        } catch (DecryptionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new DecryptionException("Failed to decrypt data", e);
        }
    }

    /**
     * Validates a sequence number against replay protection rules.
     *
     * Rules enforced:
     * 1. Sequence must be positive
     * 2. Sequence must not have been seen before (within window)
     * 3. Sequence must not be too old (below window)
     * 4. Sequence must not jump too far ahead (DoS prevention)
     *
     * @param sequenceNumber the sequence number to validate
     * @throws ReplayAttackException if validation fails
     */
    private void validateSequenceNumber(long sequenceNumber) throws ReplayAttackException {
        if (sequenceNumber < 0) {
            throw new ReplayAttackException("Invalid sequence number: " + sequenceNumber);
        }

        synchronized (replayLock) {
            long highest = highestReceivedSequence.get();

            // Case 1: First message ever
            if (highest < 0) {
                return; // Accept any positive sequence
            }

            // Case 2: Message is below the sliding window (too old)
            long windowBottom = highest - REPLAY_WINDOW_SIZE + 1;
            if (sequenceNumber < windowBottom) {
                replayAttacksDetected.incrementAndGet();
                log.warn("REPLAY_ATTACK_DETECTED: Sequence number too old",
                        "sequenceNumber", sequenceNumber,
                        "windowBottom", windowBottom,
                        "highest", highest,
                        "remotePeer", remotePeerId);
                throw new ReplayAttackException(
                        "Sequence number " + sequenceNumber + " is below window (min: " + windowBottom + ")");
            }

            // Case 3: Message is within the window - check bitmap
            if (sequenceNumber <= highest) {
                int windowIndex = (int) (sequenceNumber % REPLAY_WINDOW_SIZE);
                if (replayWindow.get(windowIndex)) {
                    // Check if this exact sequence was seen (not just same index)
                    // We need to verify the actual sequence, not just the modulo index
                    replayAttacksDetected.incrementAndGet();
                    log.warn("REPLAY_ATTACK_DETECTED: Duplicate sequence number",
                            "sequenceNumber", sequenceNumber,
                            "windowIndex", windowIndex,
                            "remotePeer", remotePeerId);
                    throw new ReplayAttackException(
                            "Duplicate sequence number " + sequenceNumber + " (already received)");
                }
                // Out of order but within window - will be accepted
                outOfOrderAccepted.incrementAndGet();
                log.debug("Out-of-order message accepted",
                        "sequenceNumber", sequenceNumber,
                        "highest", highest,
                        "remotePeer", remotePeerId);
            }

            // Case 4: Message is ahead of current highest
            if (sequenceNumber > highest) {
                long gap = sequenceNumber - highest;
                if (gap > MAX_SEQUENCE_GAP) {
                    // Excessive gap - possible attack or severe network issue
                    log.warn("EXCESSIVE_SEQUENCE_GAP: Possible attack or network issue",
                            "sequenceNumber", sequenceNumber,
                            "highest", highest,
                            "gap", gap,
                            "maxGap", MAX_SEQUENCE_GAP,
                            "remotePeer", remotePeerId);
                    throw new ReplayAttackException(
                            "Sequence gap too large: " + gap + " (max: " + MAX_SEQUENCE_GAP + ")");
                }
            }
        }
    }

    /**
     * Marks a sequence number as received in the replay window.
     * Called AFTER successful decryption to ensure corrupted messages aren't marked.
     *
     * @param sequenceNumber the sequence number to mark
     */
    private void markSequenceReceived(long sequenceNumber) {
        synchronized (replayLock) {
            long highest = highestReceivedSequence.get();

            if (sequenceNumber > highest) {
                // Advance the window: clear bits for sequences that are now out of window
                long oldWindowBottom = highest - REPLAY_WINDOW_SIZE + 1;
                long newWindowBottom = sequenceNumber - REPLAY_WINDOW_SIZE + 1;

                // Clear bits for sequences that fell out of the window
                for (long seq = Math.max(0, oldWindowBottom); seq < newWindowBottom && seq <= highest; seq++) {
                    int windowIndex = (int) (seq % REPLAY_WINDOW_SIZE);
                    replayWindow.clear(windowIndex);
                }

                // Update highest
                highestReceivedSequence.set(sequenceNumber);
            }

            // Mark this sequence as seen
            int windowIndex = (int) (sequenceNumber % REPLAY_WINDOW_SIZE);
            replayWindow.set(windowIndex);
        }
    }

    /**
     * Generates IV using counter and base nonce.
     */
    private byte[] generateIV(long counter) {
        byte[] iv = new byte[GCM_IV_LENGTH];

        // Copy base nonce
        System.arraycopy(baseNonce, 0, iv, 0, Math.min(baseNonce.length, GCM_IV_LENGTH));

        // XOR with counter (last 8 bytes)
        for (int i = 0; i < 8 && i < GCM_IV_LENGTH; i++) {
            iv[GCM_IV_LENGTH - 1 - i] ^= (byte) (counter >>> (i * 8));
        }

        return iv;
    }

    // ==================== Getters ====================

    public String getSessionId() { return sessionId; }
    public String getRemotePeerId() { return remotePeerId; }
    public Instant getCreatedAt() { return createdAt; }
    public long getSendCounter() { return sendCounter.get(); }
    public long getReceiveCounter() { return receiveCounter.get(); }

    /**
     * Gets channel statistics.
     */
    public ChannelStats getStats() {
        return new ChannelStats(
                bytesEncrypted.get(),
                bytesDecrypted.get(),
                messagesEncrypted.get(),
                messagesDecrypted.get(),
                createdAt
        );
    }

    /**
     * Channel statistics.
     */
    public static class ChannelStats {
        private final long bytesEncrypted;
        private final long bytesDecrypted;
        private final long messagesEncrypted;
        private final long messagesDecrypted;
        private final Instant createdAt;

        public ChannelStats(long bytesEncrypted, long bytesDecrypted,
                           long messagesEncrypted, long messagesDecrypted,
                           Instant createdAt) {
            this.bytesEncrypted = bytesEncrypted;
            this.bytesDecrypted = bytesDecrypted;
            this.messagesEncrypted = messagesEncrypted;
            this.messagesDecrypted = messagesDecrypted;
            this.createdAt = createdAt;
        }

        public long getBytesEncrypted() { return bytesEncrypted; }
        public long getBytesDecrypted() { return bytesDecrypted; }
        public long getMessagesEncrypted() { return messagesEncrypted; }
        public long getMessagesDecrypted() { return messagesDecrypted; }
        public Instant getCreatedAt() { return createdAt; }

        @Override
        public String toString() {
            return String.format("ChannelStats[encrypted=%d bytes/%d msgs, decrypted=%d bytes/%d msgs]",
                    bytesEncrypted, messagesEncrypted, bytesDecrypted, messagesDecrypted);
        }
    }

    /**
     * Encryption exception.
     */
    public static class EncryptionException extends Exception {
        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Decryption exception.
     */
    public static class DecryptionException extends Exception {
        public DecryptionException(String message) {
            super(message);
        }

        public DecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Replay attack exception.
     * SECURITY FIX (v2.6): New exception type for replay attacks.
     */
    public static class ReplayAttackException extends Exception {
        public ReplayAttackException(String message) {
            super(message);
        }
    }

    /**
     * Gets the number of detected replay attacks.
     * SECURITY FIX (v2.6): New metric.
     */
    public long getReplayAttacksDetected() {
        return replayAttacksDetected.get();
    }

    /**
     * Gets the number of out-of-order messages accepted within the window.
     * SECURITY FIX (v2.6): New metric.
     */
    public long getOutOfOrderAccepted() {
        return outOfOrderAccepted.get();
    }

    /**
     * Gets the highest received sequence number.
     * SECURITY FIX (v2.6): New metric for debugging.
     */
    public long getHighestReceivedSequence() {
        return highestReceivedSequence.get();
    }

    @Override
    public String toString() {
        return String.format("SecureChannel[session=%s, peer=%s, sent=%d, received=%d, replayBlocked=%d]",
                sessionId, remotePeerId, sendCounter.get(), receiveCounter.get(), replayAttacksDetected.get());
    }
}

