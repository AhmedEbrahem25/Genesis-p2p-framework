package com.genesis.p2p.discovery;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Composite discovery service that combines multiple discovery mechanisms.
 *
 * Implements the Composite pattern to treat multiple discovery services
 * uniformly through the IDiscoveryService interface.
 *
 * Features:
 * - Combines multiple discovery services
 * - Parallel discovery execution
 * - Automatic deduplication of discovered peers
 * - Add/remove services at runtime
 */

public class CompositeDiscovery implements IDiscoveryService {

    private static final NodeLogger log = NodeLogger.getLogger(CompositeDiscovery.class);

    private final List<IDiscoveryService> services;
    private final ExecutorService executor;
    private IDiscoveryListener listener;

    /**
     * Creates an empty composite discovery service.
     */
    public CompositeDiscovery() {
        this.services = new CopyOnWriteArrayList<>();
        this.executor = ThreadPoolFactory.createCachedPool(
                "CompositeDiscovery-Worker", "composite");
        this.listener = null;
    }

    /**
     * Creates a composite discovery service with initial services.
     */
    public CompositeDiscovery(IDiscoveryService... services) {
        this();
        this.services.addAll(Arrays.asList(services));
    }

    /**
     * Adds a discovery service to the composite.
     */
    public void add(IDiscoveryService service) {
        if (service != null && !services.contains(service)) {
            services.add(service);

            // Set listener on new service
            if (listener != null) {
                service.setDiscoveryListener(listener);
            }

            log.info("Discovery service added",
                    "type", service.getClass().getSimpleName(),
                    "total", services.size());
        }
    }

    /**
     * Removes a discovery service from the composite.
     */
    public void remove(IDiscoveryService service) {
        if (services.remove(service)) {
            log.info("Discovery service removed",
                    "type", service.getClass().getSimpleName(),
                    "remaining", services.size());
        }
    }

    /**
     * Gets all discovery services in this composite.
     */
    public List<IDiscoveryService> getServices() {
        return new ArrayList<>(services);
    }

    @Override
    public void start() throws Exception {
        log.info("Starting composite discovery", "services", services.size());

        List<CompletableFuture<Void>> startFutures = new ArrayList<>();

        for (IDiscoveryService service : services) {
            startFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    service.start();
                    log.debug("Service started", "type", service.getClass().getSimpleName());
                } catch (Exception e) {
                    log.error("Failed to start service", e,
                            "type", service.getClass().getSimpleName());
                }
            }, executor));
        }

        // Wait for all services to start
        CompletableFuture.allOf(startFutures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        log.info("Composite discovery started");
    }

    @Override
    public void stop() {
        log.info("Stopping composite discovery");

        List<CompletableFuture<Void>> stopFutures = new ArrayList<>();

        for (IDiscoveryService service : services) {
            stopFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    service.stop();
                    log.debug("Service stopped", "type", service.getClass().getSimpleName());
                } catch (Exception e) {
                    log.error("Error stopping service", e,
                            "type", service.getClass().getSimpleName());
                }
            }, executor));
        }

        // Wait for all services to stop
        try {
            CompletableFuture.allOf(stopFutures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Error waiting for services to stop", e);
        }

        executor.shutdown();
        log.info("Composite discovery stopped");
    }

    @Override
    public boolean isRunning() {
        return services.stream().anyMatch(IDiscoveryService::isRunning);
    }

    @Override
    public CompletableFuture<List<Peer>> discoverPeers() {
        log.info("Discovering peers via all services", "services", services.size());

        List<CompletableFuture<List<Peer>>> futures = services.stream()
                .map(IDiscoveryService::discoverPeers)
                .collect(Collectors.toList());

        return aggregateResults(futures);
    }

    @Override
    public void announceSelf() {
        log.debug("Announcing via all services");

        for (IDiscoveryService service : services) {
            try {
                service.announceSelf();
            } catch (Exception e) {
                log.error("Error announcing via service", e,
                        "type", service.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void setDiscoveryListener(IDiscoveryListener listener) {
        this.listener = listener;

        // Set listener on all services
        for (IDiscoveryService service : services) {
            service.setDiscoveryListener(listener);
        }
    }

    // ========================= Private Methods =========================

    /**
     * Aggregates results from multiple discovery futures.
     */
    private CompletableFuture<List<Peer>> aggregateResults(
            List<CompletableFuture<List<Peer>>> futures) {

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Peer> allPeers = new ArrayList<>();

                    for (CompletableFuture<List<Peer>> future : futures) {
                        try {
                            List<Peer> peers = future.getNow(Collections.emptyList());
                            allPeers.addAll(peers);
                        } catch (Exception e) {
                            log.debug("Error getting peers from future", e);
                        }
                    }

                    // Deduplicate peers
                    List<Peer> deduplicated = deduplicatePeers(allPeers);

                    log.info("Peer discovery complete",
                            "total", allPeers.size(),
                            "unique", deduplicated.size());

                    return deduplicated;
                });
    }

    /**
     * Removes duplicate peers based on ID.
     */
    private List<Peer> deduplicatePeers(List<Peer> peers) {
        Map<String, Peer> uniquePeers = new LinkedHashMap<>();

        for (Peer peer : peers) {
            uniquePeers.putIfAbsent(peer.id(), peer);
        }

        return new ArrayList<>(uniquePeers.values());
    }
}