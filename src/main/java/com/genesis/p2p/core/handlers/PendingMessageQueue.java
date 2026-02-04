package com.genesis.p2p.core.handlers;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Queues messages that cannot be sent due to peer state restrictions.
 * FIX #3: MESSAGE SECURITY GATE - QUEUE COMPONENT
 */
public class PendingMessageQueue {

    private static final NodeLogger log = NodeLogger.getLogger(PendingMessageQueue.class);

    private final MetricsRegistry metrics;
    private final ConcurrentHashMap<String, List<Message>> queues = new ConcurrentHashMap<>();

    public PendingMessageQueue(MetricsRegistry metrics) {
        this.metrics = metrics;
        log.info("PendingMessageQueue created");
    }

    public void enqueue(String peerId, Message message) {
        queues.computeIfAbsent(peerId, k -> new ArrayList<>()).add(message);
        log.debug("Message queued (peer state restriction)",
                "peerId", peerId,
                "messageType", message.type());
        metrics.incrementCounter("message_queue.enqueued");
    }

    public int releaseMessages(String peerId, Function<Message, Boolean> sendFunction) {
        List<Message> messages = queues.remove(peerId);

        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        log.info("Releasing queued messages for peer", "peerId", peerId, "queueSize", messages.size());

        int released = 0;
        for (Message message : messages) {
            try {
                if (sendFunction.apply(message)) {
                    released++;
                }
            } catch (Exception e) {
                log.error("Error sending queued message", e, "peerId", peerId);
            }
        }

        log.info("Queued messages released", "peerId", peerId, "released", released);
        metrics.incrementCounter("message_queue.released", released);
        return released;
    }

    public int clearQueue(String peerId) {
        List<Message> messages = queues.remove(peerId);
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        log.info("Clearing queued messages for peer", "peerId", peerId, "discarded", messages.size());
        metrics.incrementCounter("message_queue.discarded", messages.size());
        return messages.size();
    }

    public int getQueueSize(String peerId) {
        List<Message> messages = queues.get(peerId);
        return messages != null ? messages.size() : 0;
    }

    public int getTotalQueuedMessages() {
        return queues.values().stream().mapToInt(List::size).sum();
    }

    public int getPeersWithQueuedMessages() {
        return queues.size();
    }
}

