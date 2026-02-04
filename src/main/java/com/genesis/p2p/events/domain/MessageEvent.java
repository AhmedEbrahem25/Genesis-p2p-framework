
// ============================================================================
// MESSAGE EVENTS
// ============================================================================
package com.genesis.p2p.events.domain;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.ProcessingResult;
import com.genesis.p2p.events.core.AbstractEvent;

/**
 * Events related to message processing.
 */
public class MessageEvent extends AbstractEvent {

    public static final String MESSAGE_RECEIVED = "MESSAGE_RECEIVED";
    public static final String MESSAGE_SENT = "MESSAGE_SENT";
    public static final String MESSAGE_PROCESSED = "MESSAGE_PROCESSED";
    public static final String MESSAGE_FAILED = "MESSAGE_FAILED";

    private final MessageDirection direction;
    private final ProcessingResult result;

    public enum MessageDirection {
        INBOUND,
        OUTBOUND
    }

    private MessageEvent(String type, String source, Message message,
                         MessageDirection direction, ProcessingResult result) {
        super(type, source, message);
        this.direction = direction;
        this.result = result;
    }

    /**
     * Creates a message received event.
     */
    public static MessageEvent received(String source, Message message) {
        return new MessageEvent(MESSAGE_RECEIVED, source, message,
                MessageDirection.INBOUND, null);
    }

    /**
     * Creates a message sent event.
     */
    public static MessageEvent sent(String source, Message message) {
        return new MessageEvent(MESSAGE_SENT, source, message,
                MessageDirection.OUTBOUND, null);
    }

    /**
     * Creates a message processed event.
     */
    public static MessageEvent processed(String source, Message message,
                                         ProcessingResult result) {
        return new MessageEvent(MESSAGE_PROCESSED, source, message,
                MessageDirection.INBOUND, result);
    }

    /**
     * Creates a message failed event.
     */
    public static MessageEvent failed(String source, Message message, String error) {
        ProcessingResult failedResult = ProcessingResult.failure(
                message.header().messageId(),
                java.time.Duration.ZERO,
                error
        );
        return new MessageEvent(MESSAGE_FAILED, source, message,
                MessageDirection.INBOUND, failedResult);
    }

    public Message getMessage() {
        return (Message) payload;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public ProcessingResult getResult() {
        return result;
    }
}