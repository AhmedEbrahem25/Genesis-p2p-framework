package com.genesis.p2p.core.handlers.validation;

import com.genesis.p2p.core.Message;

/**
 * Validates incoming messages.
 */
@FunctionalInterface
public interface MessageValidator {
    ValidationResult validate(Message message);
}