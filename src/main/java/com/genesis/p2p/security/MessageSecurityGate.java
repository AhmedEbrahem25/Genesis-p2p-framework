package com.genesis.p2p.security;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.PendingMessageQueue;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.peer.PeerStore;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;

/**
 * Validates messages based on peer state before sending.
 * FIX #3: MESSAGE SECURITY GATE
 */
public class MessageSecurityGate {

    private static final NodeLogger log = NodeLogger.getLogger(MessageSecurityGate.class);

    private final PeerManager peerManager;
    private final PendingMessageQueue pendingQueue;
    private final MetricsRegistry metrics;

    public MessageSecurityGate(PeerManager peerManager,
                             PendingMessageQueue pendingQueue,
                             MetricsRegistry metrics) {
        this.peerManager = peerManager;
        this.pendingQueue = pendingQueue;
        this.metrics = metrics;
        log.info("MessageSecurityGate created");
    }

    public boolean validateAndGate(Message message, String peerId) throws SecurityException {
        PeerStore.PeerState state = peerManager.getPeerState(peerId);
        String messageType = message.type();

        if (isMessageAllowedInState(messageType, state)) {
            metrics.incrementCounter("security_gate.message_allowed");
            return true;
        }

        log.warn("Message blocked by security gate",
                "peerId", peerId,
                "messageType", messageType,
                "peerState", state != null ? state.name() : "UNKNOWN");

        metrics.incrementCounter("security_gate.message_blocked");

        if (pendingQueue != null && shouldQueue(messageType, state)) {
            pendingQueue.enqueue(peerId, message);
            log.debug("Message queued for later delivery", "peerId", peerId, "messageType", messageType);
            metrics.incrementCounter("security_gate.message_queued");
            return false;
        }

        throw new SecurityException(
            String.format("Message type '%s' not allowed in state '%s'",
                messageType, state != null ? state.name() : "UNKNOWN"));
    }

    private boolean isMessageAllowedInState(String messageType, PeerStore.PeerState state) {
        if (state == null) {
            return false;
        }

        switch (state) {
            case CHANNEL_NEGOTIATING:
                return messageType != null && messageType.startsWith("KEY_EXCHANGE");
            case CHANNEL_ESTABLISHED:
            case CONNECTING:
                return messageType != null && messageType.startsWith("HANDSHAKE");
            case CONNECTED:
            case AUTHENTICATED:
                return true;
            default:
                return false;
        }
    }

    private boolean shouldQueue(String messageType, PeerStore.PeerState state) {
        if (pendingQueue == null) {
            return false;
        }

        if (messageType != null && !messageType.startsWith("KEY_EXCHANGE") &&
            !messageType.startsWith("HANDSHAKE")) {
            return true;
        }

        return false;
    }

    public int releaseQueuedMessages(String peerId, java.util.function.Function<Message, Boolean> sendFunction) {
        if (pendingQueue == null) {
            return 0;
        }

        int released = pendingQueue.releaseMessages(peerId, sendFunction);
        log.info("Queued messages released after authentication", "peerId", peerId, "released", released);
        return released;
    }

    public int clearQueuedMessages(String peerId) {
        if (pendingQueue == null) {
            return 0;
        }

        int discarded = pendingQueue.clearQueue(peerId);
        if (discarded > 0) {
            log.info("Queued messages discarded on peer disconnection", "peerId", peerId, "discarded", discarded);
        }
        return discarded;
    }

    public QueueStatistics getQueueStatistics() {
        if (pendingQueue == null) {
            return new QueueStatistics(0, 0);
        }

        return new QueueStatistics(
            pendingQueue.getTotalQueuedMessages(),
            pendingQueue.getPeersWithQueuedMessages());
    }

    public static class QueueStatistics {
        public final int totalMessages;
        public final int peersWithMessages;

        public QueueStatistics(int totalMessages, int peersWithMessages) {
            this.totalMessages = totalMessages;
            this.peersWithMessages = peersWithMessages;
        }

        @Override
        public String toString() {
            return String.format("QueueStatistics{total=%d, peers=%d}", totalMessages, peersWithMessages);
        }
    }
}

