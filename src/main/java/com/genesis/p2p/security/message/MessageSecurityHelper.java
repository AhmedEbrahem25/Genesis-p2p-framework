package com.genesis.p2p.security.message;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.security.facade.SecurityFacade;

import java.util.Base64;
import java.util.Map;

/**
 * Message Security Helper - Centralized security for all messages.
 *
 * Provides:
 * - Automatic message signing for outgoing messages
 * - Signature verification for incoming messages
 * - Security context management
 * - Signature data preparation
 *
 * Usage:
 * <pre>
 * // Create signed message
 * Message signedMessage = MessageSecurityHelper.createSignedMessage(
 *     "HEARTBEAT", fromNodeId, toNodeId, body, security
 * );
 *
 * // Verify incoming message
 * boolean isValid = MessageSecurityHelper.verifyMessage(message, security);
 * </pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0.1 (Security Enhanced)
 */
public class MessageSecurityHelper {

    private static final NodeLogger log = NodeLogger.getLogger(MessageSecurityHelper.class);

    /**
     * Creates a signed message with automatic signature generation.
     *
     * @param messageType type of message (e.g., "HEARTBEAT", "PING")
     * @param fromNodeId sender node ID
     * @param toNodeId recipient node ID
     * @param body message body
     * @param security SecurityFacade for signing (can be null)
     * @return Message with signature if security is available
     */
    public static Message createSignedMessage(
            String messageType,
            String fromNodeId,
            String toNodeId,
            MessageBody body,
            SecurityFacade security) {

        return createSignedMessage(messageType, fromNodeId, toNodeId, body, null, security);
    }

    /**
     * Creates a signed message with custom metadata.
     *
     * @param messageType type of message
     * @param fromNodeId sender node ID
     * @param toNodeId recipient node ID
     * @param body message body
     * @param metadata custom metadata (can be null)
     * @param security SecurityFacade for signing (can be null)
     * @return Message with signature if security is available
     */
    public static Message createSignedMessage(
            String messageType,
            String fromNodeId,
            String toNodeId,
            MessageBody body,
            Map<String, String> metadata,
            SecurityFacade security) {

        long timestamp = System.currentTimeMillis();

        // Create header without signature first
        MessageHeader unsignedHeader = new MessageHeader(
                null,  // messageId - auto-generated
                null,  // correlationId - auto-generated
                "1.0", // protocolVersion
                "1.0", // messageVersion
                10,    // ttl
                0,     // hopCount
                messageType,
                fromNodeId,
                toNodeId,
                timestamp,
                false, // encrypted - handled by transport layer
                "application/json",
                false, // will be set to true if signed
                null,  // signature - will be generated
                false  // requiresAck
        );

        // Generate signature if security is available
        String signature = null;
        boolean authenticated = false;

        if (security != null) {
            try {
                String messageData = prepareMessageDataForSigning(
                    messageType, fromNodeId, toNodeId, timestamp, body.content()
                );

                byte[] signatureBytes = security.sign(messageData.getBytes());
                signature = Base64.getEncoder().encodeToString(signatureBytes);
                authenticated = true;

                log.debug("Message signed",
                        "type", messageType,
                        "from", fromNodeId,
                        "to", toNodeId);

            } catch (Exception e) {
                log.error("Failed to sign message", e,
                        "type", messageType,
                        "from", fromNodeId);
                // Continue without signature
            }
        }

        // Create final header with signature
        MessageHeader signedHeader = new MessageHeader(
                unsignedHeader.messageId(),
                unsignedHeader.correlationId(),
                unsignedHeader.protocolVersion(),
                unsignedHeader.messageVersion(),
                unsignedHeader.ttl(),
                unsignedHeader.hopCount(),
                messageType,
                fromNodeId,
                toNodeId,
                timestamp,
                false, // encrypted
                "application/json",
                authenticated,
                signature,
                false  // requiresAck
        );

        return new Message(signedHeader, body);
    }

    /**
     * Verifies message signature.
     *
     * @param message message to verify
     * @param security SecurityFacade for verification
     * @return true if signature is valid, false otherwise
     */
    public static boolean verifyMessage(Message message, SecurityFacade security) {
        if (security == null) {
            log.debug("No SecurityFacade available - skipping verification");
            return true; // No security = allow message
        }

        MessageHeader header = message.header();

        // If message is not marked as authenticated, check if signature required
        if (!header.authenticated()) {
            log.debug("Message not authenticated", "type", header.type());
            return true; // Allow unsigned messages (backward compatibility)
        }

        String signature = header.signature();
        if (signature == null || signature.isEmpty()) {
            log.warn("Message marked as authenticated but no signature present",
                    "type", header.type(),
                    "from", header.from());
            return false; // Marked authenticated but missing signature
        }

        try {
            String messageData = prepareMessageDataForVerification(message);
            byte[] signatureBytes = Base64.getDecoder().decode(signature);

            boolean isValid = security.verify(
                messageData.getBytes(),
                signatureBytes,
                header.from()
            );

            if (isValid) {
                log.debug("Message signature verified",
                        "type", header.type(),
                        "from", header.from());
            } else {
                log.warn("Invalid message signature",
                        "type", header.type(),
                        "from", header.from());
            }

            return isValid;

        } catch (Exception e) {
            log.error("Error verifying message signature", e,
                    "type", header.type(),
                    "from", header.from());
            return false;
        }
    }

    /**
     * Prepares message data for signing.
     */
    public static String prepareMessageDataForSigning(
            String messageType,
            String fromNodeId,
            String toNodeId,
            long timestamp,
            String bodyContent) {

        return messageType + "|" +
               fromNodeId + "|" +
               toNodeId + "|" +
               timestamp + "|" +
               (bodyContent != null ? bodyContent : "");
    }

    /**
     * Prepares message data for verification.
     */
    public static String prepareMessageDataForVerification(Message message) {
        MessageHeader header = message.header();
        return prepareMessageDataForSigning(
            header.type(),
            header.from(),
            header.to(),
            header.timestamp(),
            message.body().content()
        );
    }

    /**
     * Checks if a message is signed.
     */
    public static boolean isSigned(Message message) {
        return message.header().authenticated() &&
               message.header().signature() != null &&
               !message.header().signature().isEmpty();
    }

    /**
     * Gets signature from message.
     */
    public static String getSignature(Message message) {
        return message.header().signature();
    }
}

