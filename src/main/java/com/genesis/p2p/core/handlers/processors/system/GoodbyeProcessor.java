package com.genesis.p2p.core.handlers.processors.system;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.threading.ThreadPoolFactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.concurrent.*;

/**
 * Processes GOODBYE messages - graceful node shutdown.
 *
 * Purpose:
 * - Node performs clean shutdown
 * - Notifies peers of departure
 *
 * Responsibilities:
 * - Set peer.online = false
 * - Schedule peer removal after TTL
 * - Record reason for departure
 * - Update peer state to DISCONNECTED
 */
public class GoodbyeProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(GoodbyeProcessor.class);

    private final PeerManager peerManager;
    private final ScheduledExecutorService scheduler;
    private final Gson gson;

    // Configuration
    private static final long DEFAULT_REMOVAL_DELAY_MS = 300_000; // 5 minutes
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingRemovals;

    public GoodbyeProcessor(PeerManager peerManager) {
        this.peerManager = peerManager;
        this.gson = new Gson();
        this.pendingRemovals = new ConcurrentHashMap<>();

        this.scheduler = ThreadPoolFactory.createNamedScheduler("GoodbyeProcessor", "handler");

        log.info("GoodbyeProcessor initialized");
    }

    @Override
    public void processMessage(Message message) {
        MessageHeader header = message.header();
        MessageBody body = message.body();

        String peerId = header.from();
        log.info("Processing GOODBYE", "peerId", peerId);

        try {
            // Parse GOODBYE payload
            JsonObject payload = gson.fromJson(body.content(), JsonObject.class);

            String reason = payload.has("reason") ?
                    payload.get("reason").getAsString() : "Unknown";
            boolean graceful = payload.has("graceful") ?
                    payload.get("graceful").getAsBoolean() : true;
            long timestamp = payload.has("timestamp") ?
                    payload.get("timestamp").getAsLong() :
                    System.currentTimeMillis();

            // Get peer
            Peer peer = peerManager.getPeer(peerId);

            if (peer == null) {
                log.warn("GOODBYE from unknown peer", "peerId", peerId);
                return;
            }

            // Process goodbye
            processGoodbye(peer, reason, graceful, timestamp);

        } catch (Exception e) {
            log.error("Error processing GOODBYE", e, "peerId", peerId);
        }
    }

    /**
     * Processes goodbye and schedules cleanup.
     */
    private void processGoodbye(Peer peer, String reason, boolean graceful, long timestamp) {
        String peerId = peer.id();

        log.info("Peer departing",
                "peerId", peerId,
                "reason", reason,
                "graceful", graceful,
                "timestamp", timestamp);

        // Mark peer as offline
        Peer offlinePeer = new Peer(
                peer.id(),
                peer.publicKey(),
                peer.hostName(),
                peer.ip(),
                peer.port(), peer.publicIp(),
                peer.publicPort(),
                peer.natType(),
                peer.behindNat(),
                false, // mark offline
                Instant.now(),
                peer.lastLatency(),
                peer.trusted(),
                peer.reputation(),
                peer.version(),
                peer.os(),
                peer.agent(),
                peer.identityPublicKey()
        );
        peerManager.upsertPeer(offlinePeer);

        // Mark as disconnected
        peerManager.markDisconnected(peerId);

        // Adjust reputation based on departure type
        if (graceful) {
            // Reward graceful shutdown
            peerManager.updateReputation(peerId, 5);
            log.info("Graceful departure recorded", "peerId", peerId);
        } else {
            // Penalize ungraceful shutdown
            peerManager.updateReputation(peerId, -5);
            log.warn("Ungraceful departure recorded", "peerId", peerId);
        }

        // Schedule removal after TTL
        scheduleRemoval(peerId, reason, DEFAULT_REMOVAL_DELAY_MS);
    }

    /**
     * Schedules peer removal after delay.
     */
    private void scheduleRemoval(String peerId, String reason, long delayMs) {
        // Cancel any existing scheduled removal
        ScheduledFuture<?> existing = pendingRemovals.get(peerId);
        if (existing != null) {
            existing.cancel(false);
        }

        // Schedule new removal
        ScheduledFuture<?> removal = scheduler.schedule(() -> {
            try {
                log.info("Removing departed peer",
                        "peerId", peerId,
                        "reason", reason,
                        "delayMs", delayMs);

                boolean removed = peerManager.removePeer(peerId,
                        "TTL expired after GOODBYE: " + reason);

                if (removed) {
                    log.info("Peer removed successfully", "peerId", peerId);
                } else {
                    log.warn("Failed to remove peer", "peerId", peerId);
                }

                pendingRemovals.remove(peerId);

            } catch (Exception e) {
                log.error("Error removing peer", e, "peerId", peerId);
            }
        }, delayMs, TimeUnit.MILLISECONDS);

        pendingRemovals.put(peerId, removal);

        log.info("Peer removal scheduled",
                "peerId", peerId,
                "delayMs", delayMs);
    }

    /**
     * Cancels scheduled removal for a peer.
     * Used when peer reconnects before removal.
     */
    public void cancelScheduledRemoval(String peerId) {
        ScheduledFuture<?> removal = pendingRemovals.remove(peerId);
        if (removal != null) {
            removal.cancel(false);
            log.info("Scheduled removal cancelled", "peerId", peerId);
        }
    }

    /**
     * Gets pending removal count.
     */
    public int getPendingRemovalCount() {
        return pendingRemovals.size();
    }

    /**
     * Shuts down the processor.
     */
    public void shutdown() {
        log.info("Shutting down GoodbyeProcessor");

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("GoodbyeProcessor shutdown complete",
                "pendingRemovals", pendingRemovals.size());
    }
}