package com.genesis.p2p.protocol.validator;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.security.facade.SecurityFacade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates chain of validation rules.
 *
 * Implements Chain of Responsibility pattern where each rule
 * is executed in sequence. Validation stops at first failure.
 *
 * Common validation chains:
 * - Basic: Version, TTL
 * - Secure: Version, TTL, Signature
 * - Custom: User-defined rules
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ProtocolValidator {

    private final List<ValidationRule> rules;
    private final boolean stopOnFirstFailure;

    /**
     * Creates validator with default behavior (stop on first failure).
     *
     * @param rules validation rules to apply
     */
    public ProtocolValidator(List<ValidationRule> rules) {
        this(rules, true);
    }

    /**
     * Creates validator with custom behavior.
     *
     * @param rules validation rules to apply
     * @param stopOnFirstFailure if true, stop at first failure; if false, run all rules
     */
    public ProtocolValidator(List<ValidationRule> rules, boolean stopOnFirstFailure) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("At least one validation rule is required");
        }
        this.rules = new ArrayList<>(rules);
        this.stopOnFirstFailure = stopOnFirstFailure;
    }

    /**
     * Validates a message against all rules.
     *
     * @param message the message to validate
     * @return aggregated validation result
     */
    public AggregatedValidationResult validate(Message message) {
        List<ValidationRule.ValidationResult> results = new ArrayList<>();

        for (ValidationRule rule : rules) {
            ValidationRule.ValidationResult result = rule.validate(message);
            results.add(result);

            if (!result.isValid() && stopOnFirstFailure) {
                break;
            }
        }

        return new AggregatedValidationResult(results);
    }

    /**
     * Gets the list of validation rules.
     *
     * @return unmodifiable list of rules
     */
    public List<ValidationRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    public boolean isStopOnFirstFailure() {
        return stopOnFirstFailure;
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a basic validator (version + TTL).
     */
    public static ProtocolValidator basic() {
        List<ValidationRule> rules = new ArrayList<>();
        rules.add(new VersionValidationRule());
        rules.add(new TtlValidationRule());
        return new ProtocolValidator(rules);
    }

    /**
     * Creates a secure validator (version + TTL + signature).
     *
     * @param security security facade for signature verification
     */
    public static ProtocolValidator secure(SecurityFacade security) {
        List<ValidationRule> rules = new ArrayList<>();
        rules.add(new VersionValidationRule());
        rules.add(new TtlValidationRule());
        rules.add(new SignatureValidationRule(security));
        return new ProtocolValidator(rules);
    }

    /**
     * Creates a secure validator without SecurityFacade (backward compatibility).
     * Uses basic signature validation without cryptographic verification.
     */
    public static ProtocolValidator secure() {
        return secure(null);
    }

    /**
     * Creates a permissive validator (signatures optional).
     *
     * @param security security facade for signature verification
     */
    public static ProtocolValidator permissive(SecurityFacade security) {
        List<ValidationRule> rules = new ArrayList<>();
        rules.add(new VersionValidationRule());
        rules.add(new TtlValidationRule());
        rules.add(new SignatureValidationRule(false, security));
        return new ProtocolValidator(rules);
    }

    /**
     * Creates a permissive validator without SecurityFacade (backward compatibility).
     */
    public static ProtocolValidator permissive() {
        return permissive(null);
    }

    // ==================== Builder ====================

    /**
     * Builder for creating custom validators.
     */
    public static class Builder {
        private final List<ValidationRule> rules = new ArrayList<>();
        private boolean stopOnFirstFailure = true;

        public Builder addRule(ValidationRule rule) {
            if (rule != null) {
                rules.add(rule);
            }
            return this;
        }

        public Builder withVersionValidation() {
            rules.add(new VersionValidationRule());
            return this;
        }

        public Builder withTtlValidation() {
            rules.add(new TtlValidationRule());
            return this;
        }

        public Builder withTtlValidation(int minTtl, int maxTtl) {
            rules.add(new TtlValidationRule(minTtl, maxTtl));
            return this;
        }

        public Builder withSignatureValidation() {
            rules.add(new SignatureValidationRule());
            return this;
        }

        public Builder withSignatureValidation(boolean required) {
            rules.add(new SignatureValidationRule(required));
            return this;
        }

        public Builder stopOnFirstFailure(boolean stop) {
            this.stopOnFirstFailure = stop;
            return this;
        }

        public ProtocolValidator build() {
            if (rules.isEmpty()) {
                throw new IllegalStateException("At least one validation rule is required");
            }
            return new ProtocolValidator(rules, stopOnFirstFailure);
        }
    }

    /**
     * Aggregated result of multiple validation rules.
     */
    public static class AggregatedValidationResult {
        private final List<ValidationRule.ValidationResult> results;

        public AggregatedValidationResult(List<ValidationRule.ValidationResult> results) {
            this.results = new ArrayList<>(results);
        }

        /**
         * Checks if all validations passed.
         */
        public boolean isValid() {
            return results.stream().allMatch(ValidationRule.ValidationResult::isValid);
        }

        /**
         * Gets all validation results.
         */
        public List<ValidationRule.ValidationResult> getResults() {
            return Collections.unmodifiableList(results);
        }

        /**
         * Gets the first failure, if any.
         */
        public ValidationRule.ValidationResult getFirstFailure() {
            return results.stream()
                    .filter(r -> !r.isValid())
                    .findFirst()
                    .orElse(null);
        }

        /**
         * Gets all failures.
         */
        public List<ValidationRule.ValidationResult> getFailures() {
            return results.stream()
                    .filter(r -> !r.isValid())
                    .toList();
        }

        /**
         * Gets a summary error message.
         */
        public String getErrorSummary() {
            if (isValid()) {
                return "All validations passed";
            }

            List<ValidationRule.ValidationResult> failures = getFailures();
            if (failures.isEmpty()) {
                return "Unknown validation error";
            }

            if (failures.size() == 1) {
                ValidationRule.ValidationResult failure = failures.get(0);
                return String.format("%s: %s", failure.getRuleName(), failure.getErrorMessage());
            }

            StringBuilder sb = new StringBuilder("Multiple validation failures: ");
            for (int i = 0; i < failures.size(); i++) {
                ValidationRule.ValidationResult failure = failures.get(i);
                sb.append(failure.getRuleName()).append(" (").append(failure.getErrorMessage()).append(")");
                if (i < failures.size() - 1) {
                    sb.append(", ");
                }
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return String.format("AggregatedValidationResult[valid=%s, total=%d, failures=%d]",
                    isValid(), results.size(), getFailures().size());
        }
    }

    @Override
    public String toString() {
        return String.format("ProtocolValidator[rules=%d, stopOnFirstFailure=%s]",
                rules.size(), stopOnFirstFailure);
    }
}

