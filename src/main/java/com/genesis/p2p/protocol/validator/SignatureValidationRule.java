package com.genesis.p2p.protocol.validator;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.security.facade.SecurityFacade;

/**
 * Validates message digital signatures.
 *
 * Checks that:
 * - Message has a valid signature (if required)
 * - Signature matches the sender's public key
 * - Signature algorithm is supported
 *
 * Integrated with SecurityFacade for cryptographic validation.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class SignatureValidationRule implements ValidationRule {

    private static final NodeLogger log = NodeLogger.getLogger(SignatureValidationRule.class);
    private static final String RULE_NAME = "SIGNATURE_VALIDATION";

    private final boolean requireSignature;
    private final SecurityFacade security;

    /**
     * Creates signature validator with SecurityFacade.
     *
     * @param requireSignature if true, all messages must be signed
     * @param security security facade for signature verification (can be null)
     */
    public SignatureValidationRule(boolean requireSignature, SecurityFacade security) {
        this.requireSignature = requireSignature;
        this.security = security;
    }

    /**
     * Creates signature validator that requires signatures.
     *
     * @param security security facade for signature verification
     */
    public SignatureValidationRule(SecurityFacade security) {
        this(true, security);
    }

    /**
     * Creates signature validator without SecurityFacade (backward compatibility).
     *
     * @param requireSignature if true, all messages must be signed
     */
    public SignatureValidationRule(boolean requireSignature) {
        this(requireSignature, null);
    }

    /**
     * Creates signature validator that requires signatures (backward compatibility).
     */
    public SignatureValidationRule() {
        this(true, null);
    }

    @Override
    public ValidationResult validate(Message message) {
        if (message == null) {
            return ValidationResult.failure(RULE_NAME, "Message is null");
        }

        String senderId = message.header().from();
        if (senderId == null || senderId.isEmpty()) {
            if (requireSignature) {
                return ValidationResult.failure(RULE_NAME, "Sender ID is missing");
            }
            return ValidationResult.success(RULE_NAME);
        }

        // Check if message is marked as authenticated
        boolean isAuthenticated = message.header().authenticated();
        String signature = message.header().signature();

        if (requireSignature) {
            if (!isAuthenticated || signature == null || signature.isEmpty()) {
                return ValidationResult.failure(RULE_NAME,
                    "Message signature is required but missing");
            }

            // Verify signature using SecurityFacade
            if (security != null) {
                try {
                    // Prepare message data for verification
                    String messageData = prepareMessageDataForVerification(message);

                    // Verify signature
                    boolean isValid = security.verify(
                        messageData.getBytes(),
                        signature.getBytes(),
                        senderId
                    );

                    if (!isValid) {
                        log.warn("Signature verification failed",
                            "senderId", senderId,
                            "messageId", message.header().messageId());
                        return ValidationResult.failure(RULE_NAME,
                            "Invalid signature for sender: " + senderId);
                    }

                    log.debug("Signature verified successfully",
                        "senderId", senderId,
                        "messageId", message.header().messageId());
                    return ValidationResult.success(RULE_NAME);

                } catch (Exception e) {
                    log.error("Signature verification error", e,
                        "senderId", senderId,
                        "messageId", message.header().messageId());
                    return ValidationResult.failure(RULE_NAME,
                        "Signature verification error: " + e.getMessage());
                }
            } else {
                // No security facade - fallback to basic validation
                log.warn("SecurityFacade not available, using basic validation");
                if (!isValidSenderId(senderId)) {
                    return ValidationResult.failure(RULE_NAME,
                        "Invalid sender ID format: " + senderId);
                }
            }
        }

        return ValidationResult.success(RULE_NAME);
    }

    /**
     * Prepares message data for signature verification.
     * Creates a canonical representation of the message.
     */
    private String prepareMessageDataForVerification(Message message) {
        // Create canonical message representation for signature verification
        // Include: messageId, from, to, type, timestamp, content
        StringBuilder sb = new StringBuilder();
        sb.append(message.header().messageId()).append("|");
        sb.append(message.header().from()).append("|");
        sb.append(message.header().to()).append("|");
        sb.append(message.header().type()).append("|");
        sb.append(message.header().timestamp()).append("|");
        sb.append(message.body().content());
        return sb.toString();
    }

    @Override
    public String getName() {
        return RULE_NAME;
    }

    /**
     * Basic validation of sender ID format.
     * In production, this would verify cryptographic identity.
     */
    private boolean isValidSenderId(String senderId) {
        // Check basic format constraints
        if (senderId.length() < 8 || senderId.length() > 256) {
            return false;
        }

        // Check for valid characters (alphanumeric, dash, underscore)
        return senderId.matches("[a-zA-Z0-9_-]+");
    }

    public boolean isRequireSignature() {
        return requireSignature;
    }

    @Override
    public String toString() {
        return String.format("SignatureValidationRule[requireSignature=%s]", requireSignature);
    }
}

