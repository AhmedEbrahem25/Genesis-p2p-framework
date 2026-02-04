package com.genesis.p2p.core.handlers.validation;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pipeline of message validators.
 */
public class ValidationPipeline {
    private static final Logger log = LoggerFactory.getLogger(ValidationPipeline.class);

    private final List<MessageValidator> validators;
    private final MetricsRegistry metrics;

    public ValidationPipeline(MetricsRegistry metrics) {
        this.validators = new CopyOnWriteArrayList<>();
        this.metrics = metrics;
        initializeDefaultValidators();
    }

    private void initializeDefaultValidators() {
        // Header validation
        addValidator(message -> {
            try {
                validateHeader(message.header());
                return ValidationResult.success();
            } catch (IllegalArgumentException e) {
                return ValidationResult.failure("Header: " + e.getMessage());
            }
        });

        // Body validation
        addValidator(message -> {
            if (message.body() == null) {
                return ValidationResult.failure("Body is null");
            }
            return ValidationResult.success();
        });

        // TTL validation
        addValidator(message -> {
            if (message.header().ttl() <= 0) {
                return ValidationResult.failure("TTL expired");
            }
            return ValidationResult.success();
        });

        // Timestamp validation
        addValidator(message -> {
            long timestamp = message.header().timestamp();
            long now = System.currentTimeMillis();
            long diff = Math.abs(now - timestamp);

            if (diff > Duration.ofHours(1).toMillis()) {
                return ValidationResult.failure("Timestamp too far from current time");
            }
            return ValidationResult.success();
        });
    }

    public void addValidator(MessageValidator validator) {
        validators.add(validator);
    }

    public ValidationResult validate(Message message) {
        for (MessageValidator validator : validators) {
            ValidationResult result = validator.validate(message);
            if (!result.valid()) {
                metrics.incrementCounter("validation.failed." + message.header().type());
                return result;
            }
        }
        return ValidationResult.success();
    }

    private void validateHeader(MessageHeader header) {
        if (header == null) {
            throw new IllegalArgumentException("Header cannot be null");
        }
        if (header.type() == null || header.type().isEmpty()) {
            throw new IllegalArgumentException("Type cannot be null or empty");
        }
        if (header.timestamp() <= 0) {
            throw new IllegalArgumentException("Invalid timestamp");
        }
        if (header.from() == null || header.from().isEmpty()) {
            throw new IllegalArgumentException("Sender cannot be null or empty");
        }
    }
}