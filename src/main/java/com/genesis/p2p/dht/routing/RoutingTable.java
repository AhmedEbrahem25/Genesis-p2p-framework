package com.genesis.p2p.dht.routing;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.dht.DHTConfig;
import com.genesis.p2p.dht.NodeId;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class RoutingTable {
    private final NodeId localNodeId;
    private final DHTConfig config;
    private final KBucket[] buckets;

    public RoutingTable(NodeId localNodeId, DHTConfig config) {
        this.localNodeId = localNodeId;
        this.config = config;
        this.buckets = new KBucket[NodeId.ID_LENGTH_BITS];
        for (int i = 0; i < NodeId.ID_LENGTH_BITS; i++) {
            buckets[i] = new KBucket(config.kBucketSize(), i);
        }
    }

    public boolean addPeer(NodeId nodeId, Peer peer) {
        if (nodeId.equals(localNodeId)) return false;
        int bucketIndex = localNodeId.getBucketIndex(nodeId);
        return buckets[bucketIndex].addPeer(nodeId, peer);
    }

    public boolean removePeer(NodeId nodeId) {
        int bucketIndex = localNodeId.getBucketIndex(nodeId);
        return buckets[bucketIndex].removePeer(nodeId);
    }

    public List<Peer> findClosestPeers(NodeId target, int count) {
        List<PeerDistance> allPeers = new ArrayList<>();
        for (KBucket bucket : buckets) {
            for (KBucket.KBucketEntry entry : bucket.getEntries()) {
                NodeId distance = entry.nodeId().xorDistance(target);
                allPeers.add(new PeerDistance(entry.peer(), entry.nodeId(), distance));
            }
        }
        return allPeers.stream()
                .sorted(Comparator.comparing(pd -> pd.distance))
                .limit(count)
                .map(pd -> pd.peer)
                .collect(Collectors.toList());
    }

    public Optional<Peer> getPeer(NodeId nodeId) {
        int bucketIndex = localNodeId.getBucketIndex(nodeId);
        return buckets[bucketIndex].getPeer(nodeId);
    }

    public void markFailed(NodeId nodeId) {
        int bucketIndex = localNodeId.getBucketIndex(nodeId);
        buckets[bucketIndex].markFailed(nodeId);
    }

    public List<Integer> getBucketsNeedingRefresh(Duration maxAge) {
        List<Integer> needRefresh = new ArrayList<>();
        Instant threshold = Instant.now().minus(maxAge);
        for (int i = 0; i < buckets.length; i++) {
            if (!buckets[i].isEmpty() && buckets[i].getLastUpdated().isBefore(threshold)) {
                needRefresh.add(i);
            }
        }
        return needRefresh;
    }

    public List<Peer> getAllPeers() {
        List<Peer> allPeers = new ArrayList<>();
        for (KBucket bucket : buckets) allPeers.addAll(bucket.getPeers());
        return allPeers;
    }

    public int getPeerCount() {
        int count = 0;
        for (KBucket bucket : buckets) count += bucket.size();
        return count;
    }

    public RoutingTableStats getStats() {
        int total = 0, nonEmpty = 0;
        for (KBucket bucket : buckets) {
            int size = bucket.size();
            total += size;
            if (size > 0) nonEmpty++;
        }
        return new RoutingTableStats(total, nonEmpty, NodeId.ID_LENGTH_BITS, config.kBucketSize());
    }

    public NodeId getLocalNodeId() { return localNodeId; }

    private record PeerDistance(Peer peer, NodeId nodeId, NodeId distance) {}
    public record RoutingTableStats(int totalPeers, int nonEmptyBuckets, int totalBuckets, int kBucketSize) {}
}

