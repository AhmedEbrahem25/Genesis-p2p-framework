package com.genesis.p2p.protocol.validator;

import com.genesis.p2p.core.Message;

/**
 * Validates message Time-To-Live (TTL).
 *
 * Checks that:
 * - TTL is within acceptable range
 * - TTL has not expired (if tracking hops)
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class TtlValidationRule implements ValidationRule {

    private static final String RULE_NAME = "TTL_VALIDATION";

    // Default TTL limits
    public static final int MIN_TTL = 0;
    public static final int MAX_TTL = 100;
    public static final int DEFAULT_TTL = 10;

    private final int minTtl;
    private final int maxTtl;

    /**
     * Creates TTL validator with default limits.
     */
    public TtlValidationRule() {
        this(MIN_TTL, MAX_TTL);
    }

    /**
     * Creates TTL validator with custom limits.
     *
     * @param minTtl minimum acceptable TTL
     * @param maxTtl maximum acceptable TTL
     */
    public TtlValidationRule(int minTtl, int maxTtl) {
        if (minTtl < 0 || maxTtl < minTtl) {
            throw new IllegalArgumentException("Invalid TTL range");
        }
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
    }

    @Override
    public ValidationResult validate(Message message) {
        if (message == null) {
            return ValidationResult.failure(RULE_NAME, "Message is null");
        }

        int ttl = message.header().ttl();

        // Check TTL is within bounds
        if (ttl < minTtl) {
            return ValidationResult.failure(RULE_NAME,
                    String.format("TTL %d is below minimum %d", ttl, minTtl));
        }

        if (ttl > maxTtl) {
            return ValidationResult.failure(RULE_NAME,
                    String.format("TTL %d exceeds maximum %d", ttl, maxTtl));
        }

        // Check TTL hasn't expired
        if (ttl == 0) {
            return ValidationResult.failure(RULE_NAME, "TTL has expired");
        }

        return ValidationResult.success(RULE_NAME);
    }

    @Override
    public String getName() {
        return RULE_NAME;
    }

    public int getMinTtl() {
        return minTtl;
    }

    public int getMaxTtl() {
        return maxTtl;
    }

    @Override
    public String toString() {
        return String.format("TtlValidationRule[min=%d, max=%d]", minTtl, maxTtl);
    }
}

