package com.genesis.p2p.core.peer;

import com.genesis.p2p.core.Peer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Metrics service for PeerManager integration.
 * Provides real-time statistics and performance insights.
 */
public class PeerMetricsService {

    private static final Logger log = LoggerFactory.getLogger(PeerMetricsService.class);

    private final PeerStore store;
    private final PeerQueryService queryService;
    private final PeerReputationService reputationService;

    private final AtomicLong totalPeersDiscovered;
    private final AtomicLong totalPeersRemoved;

    /**
     * Metrics data class.
     */
    public record PeerMetrics(
            int totalPeers,
            int trustedPeers,
            int onlinePeers,
            int totalDiscovered,
            int totalRemoved,
            int totalReputationUpdates,
            double averageReputation,
            double averageLatency
    ) {}

    public PeerMetricsService(PeerStore store, PeerQueryService queryService,
                              PeerReputationService reputationService) {
        this.store = store;
        this.queryService = queryService;
        this.reputationService = reputationService;
        this.totalPeersDiscovered = new AtomicLong(0);
        this.totalPeersRemoved = new AtomicLong(0);

        log.debug("PeerMetricsService initialized");
    }

    // ==================== Event Recording ====================

    public void recordPeerDiscovered() {
        totalPeersDiscovered.incrementAndGet();
    }

    public void recordPeerRemoved() {
        totalPeersRemoved.incrementAndGet();
    }

    // ==================== Metrics Collection ====================

    public PeerMetrics getMetrics() {
        Collection<Peer> allPeers = store.getAll();
        List<Peer> trustedPeers = queryService.getTrustedPeers();
        List<Peer> onlinePeers = queryService.getOnlinePeers();

        double avgReputation = calculateAverageReputation(allPeers);
        double avgLatency = calculateAverageLatency(onlinePeers);

        return new PeerMetrics(
                allPeers.size(),
                trustedPeers.size(),
                onlinePeers.size(),
                (int) totalPeersDiscovered.get(),
                (int) totalPeersRemoved.get(),
                reputationService.getTotalUpdates(),
                avgReputation,
                avgLatency
        );
    }

    private double calculateAverageReputation(Collection<Peer> peers) {
        return peers.stream()
                .mapToInt(Peer::reputation)
                .average()
                .orElse(0.0);
    }

    private double calculateAverageLatency(List<Peer> onlinePeers) {
        return onlinePeers.stream()
                .mapToLong(Peer::lastLatency)
                .average()
                .orElse(0.0);
    }
}