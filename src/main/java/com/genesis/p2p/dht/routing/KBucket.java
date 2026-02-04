package com.genesis.p2p.dht.routing;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.dht.NodeId;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class KBucket {
    private final int k;
    private final int bucketIndex;
    private final ConcurrentLinkedDeque<KBucketEntry> entries;
    private volatile Instant lastUpdated;

    public KBucket(int k, int bucketIndex) {
        this.k = k;
        this.bucketIndex = bucketIndex;
        this.entries = new ConcurrentLinkedDeque<>();
        this.lastUpdated = Instant.now();
    }

    public synchronized boolean addPeer(NodeId nodeId, Peer peer) {
        for (KBucketEntry entry : entries) {
            if (entry.nodeId.equals(nodeId)) {
                entries.remove(entry);
                entries.addLast(entry.refreshed());
                lastUpdated = Instant.now();
                return true;
            }
        }
        if (entries.size() < k) {
            entries.addLast(new KBucketEntry(nodeId, peer, Instant.now(), 0));
            lastUpdated = Instant.now();
            return true;
        }
        KBucketEntry oldest = entries.peekFirst();
        if (oldest != null && oldest.failedPings > 2) {
            entries.removeFirst();
            entries.addLast(new KBucketEntry(nodeId, peer, Instant.now(), 0));
            lastUpdated = Instant.now();
            return true;
        }
        return false;
    }

    public synchronized boolean removePeer(NodeId nodeId) {
        return entries.removeIf(e -> e.nodeId.equals(nodeId));
    }

    public List<Peer> getPeers() {
        List<Peer> peers = new ArrayList<>();
        for (KBucketEntry entry : entries) peers.add(entry.peer);
        return peers;
    }

    public List<KBucketEntry> getEntries() { return new ArrayList<>(entries); }

    public Optional<Peer> getPeer(NodeId nodeId) {
        for (KBucketEntry entry : entries) {
            if (entry.nodeId.equals(nodeId)) return Optional.of(entry.peer);
        }
        return Optional.empty();
    }

    public synchronized void markFailed(NodeId nodeId) {
        for (KBucketEntry entry : entries) {
            if (entry.nodeId.equals(nodeId)) {
                entries.remove(entry);
                entries.addLast(entry.failed());
                return;
            }
        }
    }

    public int size() { return entries.size(); }
    public boolean isEmpty() { return entries.isEmpty(); }
    public boolean isFull() { return entries.size() >= k; }
    public int getBucketIndex() { return bucketIndex; }
    public Instant getLastUpdated() { return lastUpdated; }

    public record KBucketEntry(NodeId nodeId, Peer peer, Instant lastSeen, int failedPings) {
        public KBucketEntry refreshed() { return new KBucketEntry(nodeId, peer, Instant.now(), 0); }
        public KBucketEntry failed() { return new KBucketEntry(nodeId, peer, lastSeen, failedPings + 1); }
    }
}

