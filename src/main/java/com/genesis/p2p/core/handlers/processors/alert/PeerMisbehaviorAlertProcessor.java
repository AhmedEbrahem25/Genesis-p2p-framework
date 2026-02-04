package com.genesis.p2p.core.handlers.processors.alert;

import com.genesis.p2p.core.peer.PeerReputationService;
import com.genesis.p2p.events.core.EventBus;
import com.genesis.p2p.events.core.GenericEvent;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.facade.SecurityFacade;
import com.genesis.p2p.util.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects peer misbehavior, lowers reputation scores, and generates alerts.
 */
public class PeerMisbehaviorAlertProcessor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PeerMisbehaviorAlertProcessor.class);
    private static final long HISTORY_RETENTION_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final long ALERT_WINDOW_MS = 10 * 60 * 1000; // 10 minutes
    private static final int ALERT_THRESHOLD = 3;
    private static final int SEVERE_MISBEHAVIOR_THRESHOLD = 7;
    private static final int BAN_THRESHOLD = 10;

    private final EventBus eventBus;
    private final MetricsRegistry metricsRegistry;
    private final PeerReputationService reputationService;
    private final ConcurrentHashMap<String, List<MisbehaviorEvent>> misbehaviorHistory;

    public PeerMisbehaviorAlertProcessor(EventBus eventBus, MetricsRegistry metricsRegistry,
                                         PeerReputationService reputationService) {
        this.eventBus = eventBus;
        this.metricsRegistry = metricsRegistry;
        this.reputationService = reputationService;
        this.misbehaviorHistory = new ConcurrentHashMap<>();
        setupEventListeners();
        log.info("PeerMisbehaviorAlertProcessor initialized");
    }

    private void setupEventListeners() {
        eventBus.subscribe("peer:misbehavior", event -> {
            if (event.getPayload() instanceof MisbehaviorEvent) {
                processMisbehavior((MisbehaviorEvent) event.getPayload());
            }
        });
    }

    public void processMisbehavior(MisbehaviorEvent event) {
        if (event == null || event.peerId == null) {
            log.warn("Received invalid misbehavior event");
            return;
        }

        recordMisbehavior(event);
        adjustReputationScore(event);

        metricsRegistry.incrementCounter("peer.misbehavior.total");
        metricsRegistry.incrementCounter("peer.misbehavior.type." + event.type.name().toLowerCase());
        metricsRegistry.incrementCounter("peer.misbehavior.peer." + sanitizeId(event.peerId));

        if (shouldAlert(event.peerId)) {
            generateMisbehaviorAlert(event);
        }

        if (event.severity >= SEVERE_MISBEHAVIOR_THRESHOLD) {
            handleSevereMisbehavior(event);
        }

        if (shouldBan(event.peerId)) {
            banPeer(event.peerId);
        }

        log.debug("Misbehavior processed for peer {}: {} [severity={}]",
                event.peerId, event.type, event.severity);
    }

    private void recordMisbehavior(MisbehaviorEvent event) {
        misbehaviorHistory.computeIfAbsent(event.peerId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(event);
        cleanupHistory(event.peerId);
    }

    private void cleanupHistory(String peerId) {
        List<MisbehaviorEvent> history = misbehaviorHistory.get(peerId);
        if (history != null) {
            long cutoff = Time.currentMillis() - HISTORY_RETENTION_MS;
            synchronized (history) {
                history.removeIf(e -> e.timestamp < cutoff);
            }
        }
    }

    private void adjustReputationScore(MisbehaviorEvent event) {
        int penalty = event.severity * 10;
        int newReputation = reputationService.updateReputation(event.peerId, -penalty);

        log.debug("Reputation adjusted for peer {}: penalty={}, newReputation={}",
                event.peerId, penalty, newReputation);

        metricsRegistry.incrementCounter("peer.reputation.penalties");
    }

    private boolean shouldAlert(String peerId) {
        List<MisbehaviorEvent> history = misbehaviorHistory.get(peerId);
        if (history == null) return false;

        long now = System.currentTimeMillis();
        long recentCount;

        synchronized (history) {
            recentCount = history.stream()
                    .filter(e -> now - e.timestamp < ALERT_WINDOW_MS)
                    .count();
        }

        return recentCount >= ALERT_THRESHOLD;
    }

    private void generateMisbehaviorAlert(MisbehaviorEvent event) {
        List<MisbehaviorEvent> history = misbehaviorHistory.get(event.peerId);
        long recentCount = 0;

        if (history != null) {
            long now = System.currentTimeMillis();
            synchronized (history) {
                recentCount = history.stream()
                        .filter(e -> now - e.timestamp < ALERT_WINDOW_MS)
                        .count();
            }
        }

        AlertProcessor.Alert alert = new AlertProcessor.Alert(
                "misbehavior_" + event.peerId + "_" + Instant.now().toEpochMilli(),
                AlertProcessor.Severity.HIGH,  // <-- Correct reference
                String.format("Peer %s misbehavior detected: %d incidents in %d minutes",
                        event.peerId, recentCount, ALERT_WINDOW_MS / 60000),
                "PeerMisbehaviorAlertProcessor",
                Instant.now(),  // <-- Correct Instant
                Map.of(
                        "peerId", event.peerId,
                        "latestEvent", event,
                        "incidentCount", recentCount
                )
        );

        eventBus.publish(new GenericEvent("alert:trigger", alert));
        metricsRegistry.incrementCounter("peer.misbehavior.alerts");

        log.warn("Misbehavior alert generated for peer {}: {} incidents", event.peerId, recentCount);
    }

    private void handleSevereMisbehavior(MisbehaviorEvent event) {
        log.warn("Severe misbehavior detected for peer {}: {} [severity={}]",
                event.peerId, event.type, event.severity);

        eventBus.publish(new GenericEvent("peer:severe_misbehavior", event));
        metricsRegistry.incrementCounter("peer.misbehavior.severe");
    }

    private boolean shouldBan(String peerId) {
        List<MisbehaviorEvent> history = misbehaviorHistory.get(peerId);
        if (history == null) return false;

        long now = Time.currentMillis();
        long recentCount;

        synchronized (history) {
            recentCount = history.stream()
                    .filter(e -> now - e.timestamp < ALERT_WINDOW_MS)
                    .count();
        }

        return recentCount >= BAN_THRESHOLD;
    }

    private void banPeer(String peerId) {
        List<MisbehaviorEvent> history = misbehaviorHistory.get(peerId);
        int totalIncidents = history != null ? history.size() : 0;

        boolean banned = reputationService.banPeer(
                peerId,
                String.format("Excessive misbehavior: %d incidents", totalIncidents)
        );

        if (banned) {
            metricsRegistry.incrementCounter("peer.bans");
            log.warn("Peer {} banned due to excessive misbehavior", peerId);

            AlertProcessor.Alert alert = new AlertProcessor.Alert(
                    "peer_banned_" + peerId + "_" + Instant.now().toEpochMilli(),
                    AlertProcessor.Severity.CRITICAL,  // <-- Correct reference
                    String.format("Peer %s has been banned due to excessive misbehavior", peerId),
                    "PeerMisbehaviorAlertProcessor",
                    Instant.now(),
                    Map.of("peerId", peerId, "totalIncidents", totalIncidents)
            );

            eventBus.publish(new GenericEvent("alert:trigger", alert));
        }
    }

    public List<MisbehaviorEvent> getPeerMisbehaviorHistory(String peerId) {
        List<MisbehaviorEvent> history = misbehaviorHistory.get(peerId);
        return history != null ? new ArrayList<>(history) : List.of();
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    @Override
    public void close() {
        misbehaviorHistory.clear();
        log.info("PeerMisbehaviorAlertProcessor closed");
    }

    public static class MisbehaviorEvent {
        public final String peerId;
        public final MisbehaviorType type;
        public final int severity; // 1-10
        public final String description;
        public final long timestamp;

        public MisbehaviorEvent(String peerId, MisbehaviorType type, int severity,
                                String description, long timestamp) {
            this.peerId = peerId;
            this.type = type;
            this.severity = Math.max(1, Math.min(10, severity));
            this.description = description;
            this.timestamp = timestamp;
        }

        public enum MisbehaviorType {
            SPAM,
            INVALID_DATA,
            PROTOCOL_VIOLATION,
            TIMEOUT,
            MALICIOUS
        }
    }
}
