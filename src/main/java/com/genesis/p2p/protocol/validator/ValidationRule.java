package com.genesis.p2p.protocol.validator;

import com.genesis.p2p.core.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Chain of Responsibility pattern for message validation.
 *
 * Each validation rule is independent and can be composed.
 * Rules are executed in order, and validation stops at first failure.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public interface ValidationRule {

    /**
     * Validates a message according to this rule.
     *
     * @param message the message to validate
     * @return validation result
     */
    ValidationResult validate(Message message);

    /**
     * Gets the name of this validation rule.
     *
     * @return rule name
     */
    String getName();

    /**
     * Result of validation.
     */
    class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final String ruleName;

        private ValidationResult(boolean valid, String errorMessage, String ruleName) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.ruleName = ruleName;
        }

        public static ValidationResult success(String ruleName) {
            return new ValidationResult(true, null, ruleName);
        }

        public static ValidationResult failure(String ruleName, String errorMessage) {
            return new ValidationResult(false, errorMessage, ruleName);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getRuleName() {
            return ruleName;
        }

        @Override
        public String toString() {
            if (valid) {
                return String.format("ValidationResult[rule=%s, valid=true]", ruleName);
            } else {
                return String.format("ValidationResult[rule=%s, valid=false, error=%s]", 
                        ruleName, errorMessage);
            }
        }
    }
}

