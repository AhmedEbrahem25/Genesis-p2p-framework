package com.genesis.p2p.dht;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.dht.routing.RoutingTable;
import com.genesis.p2p.discovery.IDiscoveryService;
import com.genesis.p2p.discovery.IDiscoveryListener;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kademlia DHT Service for decentralized peer discovery.
 */
public class DHTService implements IDiscoveryService, AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(DHTService.class);

    private final NodeId localNodeId;
    private final DHTConfig config;
    private final PeerManager peerManager;
    private final MetricsRegistry metrics;

    private final RoutingTable routingTable;
    private final ConcurrentHashMap<NodeId, DHTValue> localStorage;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService lookupExecutor;

    private final AtomicBoolean running;
    private IDiscoveryListener listener;

    public DHTService(String nodeIdString, PeerManager peerManager,
                      DHTConfig config, MetricsRegistry metrics) {
        this.localNodeId = NodeId.fromString(nodeIdString);
        this.config = config;
        this.peerManager = peerManager;
        this.metrics = metrics;

        this.routingTable = new RoutingTable(localNodeId, config);
        this.localStorage = new ConcurrentHashMap<>();

        this.scheduler = ThreadPoolFactory.createNamedScheduler("DHT", "dht");
        this.lookupExecutor = ThreadPoolFactory.createFixedPool("DHT-Lookup", "dht", config.alpha());

        this.running = new AtomicBoolean(false);

        log.info("DHTService created", "nodeId", localNodeId.toString());
    }

    @Override
    public void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::refreshBuckets,
                    config.bucketRefreshInterval().toMillis(),
                    config.bucketRefreshInterval().toMillis(), TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::cleanupExpiredValues,
                    60_000, 60_000, TimeUnit.MILLISECONDS);
            log.info("DHTService started");
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("DHTService stopped");
        }
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public CompletableFuture<List<Peer>> discoverPeers() {
        return findNode(localNodeId).thenApply(nodeIds -> {
            List<Peer> peers = new ArrayList<>();
            for (NodeId nodeId : nodeIds) {
                routingTable.getPeer(nodeId).ifPresent(peers::add);
            }
            return peers;
        });
    }

    @Override
    public void announceSelf() {
        findNode(localNodeId).thenAccept(nodes ->
                log.debug("Self-announcement complete", "foundNodes", nodes.size()));
    }

    @Override
    public void setDiscoveryListener(IDiscoveryListener listener) {
        this.listener = listener;
    }

    public CompletableFuture<Integer> put(NodeId key, byte[] value) {
        DHTValue dhtValue = new DHTValue(key, value, localNodeId, Instant.now(),
                Instant.now().plus(config.valueExpiration()), 0);
        localStorage.put(key, dhtValue);
        return findNode(key).thenApply(nodes -> Math.min(config.replicationFactor(), nodes.size()));
    }

    public CompletableFuture<Optional<byte[]>> get(NodeId key) {
        DHTValue local = localStorage.get(key);
        if (local != null && !local.isExpired()) {
            return CompletableFuture.completedFuture(Optional.of(local.data()));
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    public CompletableFuture<List<NodeId>> findNode(NodeId target) {
        return CompletableFuture.supplyAsync(() -> {
            Set<NodeId> closest = ConcurrentHashMap.newKeySet();
            List<Peer> initial = routingTable.findClosestPeers(target, config.kBucketSize());
            for (Peer peer : initial) {
                closest.add(NodeId.fromString(peer.id()));
            }
            metrics.incrementCounter("dht.findnode.complete");
            return new ArrayList<>(closest);
        }, lookupExecutor);
    }

    private void refreshBuckets() {
        if (!running.get()) return;
        List<Integer> needRefresh = routingTable.getBucketsNeedingRefresh(config.bucketRefreshInterval());
        if (!needRefresh.isEmpty()) log.debug("Refreshed buckets", "count", needRefresh.size());
    }

    private void cleanupExpiredValues() {
        if (!running.get()) return;
        localStorage.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    public void addPeer(Peer peer) {
        NodeId nodeId = NodeId.fromString(peer.id());
        if (routingTable.addPeer(nodeId, peer) && listener != null) {
            listener.onPeerDiscovered(peer);
        }
    }

    public RoutingTable getRoutingTable() { return routingTable; }
    public NodeId getLocalNodeId() { return localNodeId; }
    public int getStoredValueCount() { return localStorage.size(); }

    @Override
    public void close() {
        stop();
        ThreadPoolFactory.shutdownGracefully(scheduler, "DHT", 5);
        ThreadPoolFactory.shutdownGracefully(lookupExecutor, "DHT-Lookup", 5);
    }

    public DHTStats getStats() {
        return new DHTStats(routingTable.getPeerCount(), localStorage.size(),
                routingTable.getStats().nonEmptyBuckets(), running.get());
    }

    public record DHTStats(int peers, int storedValues, int activeBuckets, boolean running) {}
}
