package com.genesis.p2p.core.handlers.processors.alert;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerHealthMonitor;
import com.genesis.p2p.core.peer.PeerStore;
import com.genesis.p2p.events.core.EventBus;
import com.genesis.p2p.events.core.GenericEvent;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.util.threading.ThreadPoolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors and generates health alerts for nodes automatically.
 * Integrates with PeerHealthMonitor and PeerStore.
 */
public class NodeHealthAlertProcessor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NodeHealthAlertProcessor.class);
    private static final long HEALTH_CHECK_INTERVAL_MS = 30000; // 30 seconds
    private static final long HEARTBEAT_TIMEOUT_MS = 60000; // 1 minute
    private static final double FAILURE_RATE_THRESHOLD = 0.5; // 50%
    private static final long HIGH_LATENCY_THRESHOLD_MS = 5000; // 5 seconds

    private final EventBus eventBus;
    private final MetricsRegistry metricsRegistry;
    private final PeerHealthMonitor peerHealthMonitor;
    private final PeerStore peerStore;
    private final Set<String> unhealthyNodes;
    private final ScheduledExecutorService scheduler;

    public NodeHealthAlertProcessor(EventBus eventBus, MetricsRegistry metricsRegistry,
                                    PeerHealthMonitor peerHealthMonitor, PeerStore peerStore) {
        this.eventBus = eventBus;
        this.metricsRegistry = metricsRegistry;
        this.peerHealthMonitor = peerHealthMonitor;
        this.peerStore = peerStore;
        this.unhealthyNodes = ConcurrentHashMap.newKeySet();
        this.scheduler = ThreadPoolFactory.createNamedScheduler("NodeHealthAlert", "alert");
        startHealthMonitoring();
        log.info("NodeHealthAlertProcessor initialized");
    }

    private void startHealthMonitoring() {
        scheduler.scheduleAtFixedRate(
                this::checkAllNodesHealth,
                HEALTH_CHECK_INTERVAL_MS,
                HEALTH_CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        log.debug("Health monitoring started [interval={}ms]", HEALTH_CHECK_INTERVAL_MS);
    }

    private void checkAllNodesHealth() {
        try {
            peerStore.stream().forEach(peer -> {
                try {
                    evaluateNodeHealth(peer);
                } catch (Exception e) {
                    log.error("Error evaluating health for peer {}: {}", peer.id(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Error during health check: {}", e.getMessage(), e);
        }
    }

    private void evaluateNodeHealth(Peer peer) {
        List<String> issues = new ArrayList<>();
        String nodeId = peer.id();

        // Check failure rate
        double failureRate = peerHealthMonitor.getFailureRate(nodeId);
        if (failureRate > FAILURE_RATE_THRESHOLD) {
            issues.add(String.format("High failure rate: %.1f%%", failureRate * 100));
        }

        // Check heartbeat
        long timeSinceLastSeen = Instant.now().toEpochMilli() - peer.lastSeen().toEpochMilli();
        if (timeSinceLastSeen > HEARTBEAT_TIMEOUT_MS) {
            issues.add(String.format("Missed heartbeat (%d ms)", timeSinceLastSeen));
        }

        // Check if peer is offline
        if (!peer.online()) {
            issues.add("Peer offline");
        }

        // Check latency
        if (peer.lastLatency() > HIGH_LATENCY_THRESHOLD_MS) {
            issues.add(String.format("High latency: %d ms", peer.lastLatency()));
        }

        // Check reputation
        if (peer.reputation() < 20) {
            issues.add(String.format("Low reputation: %d", peer.reputation()));
        }

        // Generate or clear alerts
        if (!issues.isEmpty()) {
            generateHealthAlert(nodeId, issues);
        } else if (unhealthyNodes.contains(nodeId)) {
            generateRecoveryAlert(nodeId);
            unhealthyNodes.remove(nodeId);
        }
    }

    private void generateHealthAlert(String nodeId, List<String> issues) {
        unhealthyNodes.add(nodeId);
        Instant now = Instant.now();

        AlertProcessor.Alert alert = new AlertProcessor.Alert(
                "node_health_" + nodeId + "_" + now.toEpochMilli(),
                AlertProcessor.Severity.HIGH,
                String.format("Node %s health issues: %s", nodeId, String.join(", ", issues)),
                "NodeHealthAlertProcessor",
                now,
                Map.of("nodeId", nodeId, "issues", issues)
        );

        eventBus.publish(new GenericEvent("alert:trigger", alert));
        metricsRegistry.incrementCounter("node.health.alerts");
        metricsRegistry.incrementCounter("node.health.alerts." + sanitizeId(nodeId));

        log.warn("Health alert generated for node {}: {}", nodeId, issues);
    }

    private void generateRecoveryAlert(String nodeId) {
        Instant now = Instant.now();
        AlertProcessor.Alert alert = new AlertProcessor.Alert(
                "node_recovery_" + nodeId + "_" + now.toEpochMilli(),
                AlertProcessor.Severity.LOW,
                String.format("Node %s recovered", nodeId),
                "NodeHealthAlertProcessor",
                now,
                Map.of("nodeId", nodeId)
        );

        eventBus.publish(new GenericEvent("alert:trigger", alert));
        metricsRegistry.incrementCounter("node.health.recoveries");

        log.info("Recovery alert generated for node {}", nodeId);
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        unhealthyNodes.clear();
        log.info("NodeHealthAlertProcessor closed");
    }
}
