package com.genesis.p2p.core.handlers;

import java.net.InetSocketAddress;
import java.time.Instant;

/**
 * Processing context that carries message metadata through the processing pipeline.
 *
 * This record enables end-to-end message tracking and persistence by carrying:
 * - persistenceId: Links to MessagePersistenceStore for state updates
 * - traceId: Distributed tracing correlation ID
 * - receivedAt: Timestamp when message was first received
 * - sourceAddress: The source address of the incoming message (for response routing)
 * - wasEncrypted: Whether the message was received encrypted (for security validation)
 * - decryptionSucceeded: Whether decryption was successful (false if plaintext or failed)
 *
 * Used to ensure proper state transitions:
 * PENDING -> RECEIVED -> PROCESSING -> DELIVERED/FAILED
 *
 * The sourceAddress field enables bidirectional communication by allowing
 * processors (like HandshakeProcessor) to send responses back to the sender.
 *
 * The security fields (wasEncrypted, decryptionSucceeded) enable the SecurityGateway
 * to validate that encryption requirements were met at the transport layer.
 *
 * @author Genesis P2P Framework
 * @version 1.2
 */
public record ProcessingContext(
    String persistenceId,
    String traceId,
    Instant receivedAt,
    InetSocketAddress sourceAddress,
    boolean wasEncrypted,
    boolean decryptionSucceeded
) {

    /**
     * Creates a new processing context with auto-generated traceId and current timestamp.
     * Assumes plaintext (not encrypted).
     *
     * @param persistenceId the persistence store ID for this message
     * @return new processing context
     */
    public static ProcessingContext create(String persistenceId) {
        return new ProcessingContext(
            persistenceId,
            java.util.UUID.randomUUID().toString(),
            Instant.now(),
            null,
            false,  // wasEncrypted
            false   // decryptionSucceeded
        );
    }

    /**
     * Creates a processing context with specified traceId.
     * Assumes plaintext (not encrypted).
     *
     * @param persistenceId the persistence store ID
     * @param traceId the trace correlation ID
     * @return new processing context
     */
    public static ProcessingContext create(String persistenceId, String traceId) {
        return new ProcessingContext(
            persistenceId,
            traceId,
            Instant.now(),
            null,
            false,  // wasEncrypted
            false   // decryptionSucceeded
        );
    }

    /**
     * Creates a processing context with source address for response routing.
     * Assumes plaintext (not encrypted).
     *
     * @param persistenceId the persistence store ID
     * @param sourceAddress the source address of the incoming message
     * @return new processing context
     */
    public static ProcessingContext createWithSource(String persistenceId, InetSocketAddress sourceAddress) {
        return new ProcessingContext(
            persistenceId,
            java.util.UUID.randomUUID().toString(),
            Instant.now(),
            sourceAddress,
            false,  // wasEncrypted
            false   // decryptionSucceeded
        );
    }

    /**
     * Creates a full processing context with all fields except security metadata.
     * Assumes plaintext (not encrypted). Use createWithSecurity for encrypted messages.
     *
     * @param persistenceId the persistence store ID
     * @param traceId the trace correlation ID
     * @param sourceAddress the source address of the incoming message
     * @return new processing context
     */
    public static ProcessingContext createFull(String persistenceId, String traceId, InetSocketAddress sourceAddress) {
        return new ProcessingContext(
            persistenceId,
            traceId,
            Instant.now(),
            sourceAddress,
            false,  // wasEncrypted
            false   // decryptionSucceeded
        );
    }

    /**
     * Creates a full processing context with security metadata.
     * This is the preferred factory method for messages processed through the security layer.
     *
     * @param persistenceId the persistence store ID
     * @param traceId the trace correlation ID
     * @param sourceAddress the source address of the incoming message
     * @param wasEncrypted whether the message was encrypted
     * @param decryptionSucceeded whether decryption was successful
     * @return new processing context
     */
    public static ProcessingContext createWithSecurity(
            String persistenceId,
            String traceId,
            InetSocketAddress sourceAddress,
            boolean wasEncrypted,
            boolean decryptionSucceeded) {
        return new ProcessingContext(
            persistenceId,
            traceId,
            Instant.now(),
            sourceAddress,
            wasEncrypted,
            decryptionSucceeded
        );
    }

    /**
     * Creates an empty context when persistence is not available.
     * Used for backwards compatibility.
     *
     * @return empty context with null persistenceId
     */
    public static ProcessingContext empty() {
        return new ProcessingContext(null, null, Instant.now(), null, false, false);
    }

    /**
     * Checks if this context indicates the message was encrypted.
     *
     * @return true if message was encrypted
     */
    public boolean isEncrypted() {
        return wasEncrypted;
    }

    /**
     * Checks if this context indicates decryption was successful.
     *
     * @return true if decryption succeeded
     */
    public boolean isDecryptionSuccessful() {
        return decryptionSucceeded;
    }

    /**
     * Checks if this context has a valid persistence ID.
     *
     * @return true if persistenceId is non-null
     */
    public boolean hasPersistenceId() {
        return persistenceId != null && !persistenceId.isEmpty();
    }

    /**
     * Checks if this context has a valid trace ID.
     *
     * @return true if traceId is non-null
     */
    public boolean hasTraceId() {
        return traceId != null && !traceId.isEmpty();
    }

    /**
     * Gets the age of this context (time since received).
     *
     * @return duration since received
     */
    public java.time.Duration getAge() {
        return java.time.Duration.between(receivedAt, Instant.now());
    }

    /**
     * Checks if this context has a source address for response routing.
     *
     * @return true if sourceAddress is non-null
     */
    public boolean hasSourceAddress() {
        return sourceAddress != null;
    }
}
