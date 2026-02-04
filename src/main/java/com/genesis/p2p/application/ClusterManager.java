package com.genesis.p2p.application;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * ClusterManager - Manages a cluster of P2P nodes.
 *
 * Design Patterns Applied:
 * - Singleton Pattern: Single cluster manager instance per cluster ID
 * - Observer Pattern: Listeners for cluster events
 * - Strategy Pattern: Pluggable leader election strategies
 * - Template Method: Cluster operations with hooks
 *
 * Provides cluster-wide coordination:
 * - Multiple node management
 * - Leader election (pluggable strategies)
 * - Load balancing
 * - Cluster health monitoring
 * - Node discovery within cluster
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ClusterManager {

    private static final NodeLogger log = NodeLogger.getLogger(ClusterManager.class);

    // Singleton instances per cluster ID
    private static final Map<String, ClusterManager> instances = new ConcurrentHashMap<>();

    private final String clusterId;
    private final Map<String, NodeRuntime> nodes;
    private final List<ClusterListener> listeners;
    private final ReentrantReadWriteLock lock;
    private volatile String leaderId;

    // Strategy Pattern: Pluggable leader election
    private LeaderElectionStrategy electionStrategy;

    /**
     * Private constructor for Singleton pattern.
     */
    private ClusterManager(String clusterId) {
        this.clusterId = clusterId;
        this.nodes = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.lock = new ReentrantReadWriteLock();
        this.electionStrategy = new SimpleLeaderElection(); // Default strategy

        log.info("ClusterManager created", "clusterId", clusterId);
    }

    // ==================== Singleton Pattern ====================

    /**
     * Gets or creates a ClusterManager instance for the given cluster ID.
     * Singleton Pattern - One instance per cluster.
     *
     * @param clusterId the cluster identifier
     * @return the cluster manager instance
     */
    public static ClusterManager getInstance(String clusterId) {
        return instances.computeIfAbsent(clusterId, ClusterManager::new);
    }

    /**
     * Removes a cluster manager instance.
     *
     * @param clusterId the cluster identifier
     */
    public static void removeInstance(String clusterId) {
        ClusterManager removed = instances.remove(clusterId);
        if (removed != null) {
            removed.stopAll();
            log.info("ClusterManager instance removed", "clusterId", clusterId);
        }
    }

    /**
     * Gets all active cluster IDs.
     *
     * @return set of active cluster IDs
     */
    public static Set<String> getActiveClusterIds() {
        return Collections.unmodifiableSet(instances.keySet());
    }

    // ==================== Strategy Pattern: Leader Election ====================

    /**
     * Sets the leader election strategy.
     * Strategy Pattern - Allows pluggable election algorithms.
     *
     * @param strategy the election strategy to use
     */
    public void setElectionStrategy(LeaderElectionStrategy strategy) {
        this.electionStrategy = strategy != null ? strategy : new SimpleLeaderElection();
        log.info("Election strategy changed", "strategy", strategy.getClass().getSimpleName());
    }

    /**
     * Leader election strategy interface.
     * Strategy Pattern - Defines election algorithm contract.
     */
    public interface LeaderElectionStrategy {
        /**
         * Elects a leader from the available nodes.
         *
         * @param nodes the available nodes
         * @return the elected leader ID, or empty if no leader
         */
        Optional<String> electLeader(Map<String, NodeRuntime> nodes);

        /**
         * Gets the strategy name.
         */
        String getName();
    }

    /**
     * Simple leader election - first node alphabetically.
     */
    public static class SimpleLeaderElection implements LeaderElectionStrategy {
        @Override
        public Optional<String> electLeader(Map<String, NodeRuntime> nodes) {
            return nodes.keySet().stream()
                    .filter(id -> nodes.get(id).isRunning())
                    .sorted()
                    .findFirst();
        }

        @Override
        public String getName() {
            return "SimpleAlphabetical";
        }
    }

    /**
     * Round-robin leader election.
     */
    public static class RoundRobinLeaderElection implements LeaderElectionStrategy {
        private int currentIndex = 0;

        @Override
        public Optional<String> electLeader(Map<String, NodeRuntime> nodes) {
            List<String> runningNodes = nodes.entrySet().stream()
                    .filter(e -> e.getValue().isRunning())
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();

            if (runningNodes.isEmpty()) {
                return Optional.empty();
            }

            currentIndex = (currentIndex + 1) % runningNodes.size();
            return Optional.of(runningNodes.get(currentIndex));
        }

        @Override
        public String getName() {
            return "RoundRobin";
        }
    }

    /**
     * Highest uptime leader election - node with longest uptime becomes leader.
     */
    public static class LongestUptimeLeaderElection implements LeaderElectionStrategy {
        @Override
        public Optional<String> electLeader(Map<String, NodeRuntime> nodes) {
            return nodes.entrySet().stream()
                    .filter(e -> e.getValue().isRunning())
                    .max(Comparator.comparingLong(e -> e.getValue().getUptimeMillis()))
                    .map(Map.Entry::getKey);
        }

        @Override
        public String getName() {
            return "LongestUptime";
        }
    }

    // ==================== Node Management ====================

    /**
     * Adds a node to the cluster.
     *
     * @param nodeRuntime the node runtime to add
     */
    public void addNode(NodeRuntime nodeRuntime) {
        lock.writeLock().lock();
        try {
            String nodeId = nodeRuntime.getNode().getNodeId();
            nodes.put(nodeId, nodeRuntime);

            notifyListeners(l -> l.onNodeAdded(nodeId));
            log.info("Node added to cluster", "nodeId", nodeId, "clusterSize", nodes.size());

            // Trigger leader election if needed
            if (leaderId == null) {
                electLeader();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a node from the cluster.
     *
     * @param nodeId the node ID to remove
     */
    public void removeNode(String nodeId) {
        lock.writeLock().lock();
        try {
            NodeRuntime removed = nodes.remove(nodeId);
            if (removed != null) {
                notifyListeners(l -> l.onNodeRemoved(nodeId));
                log.info("Node removed from cluster", "nodeId", nodeId);

                // Re-elect leader if removed node was leader
                if (nodeId.equals(leaderId)) {
                    electLeader();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets a node by ID.
     *
     * @param nodeId the node ID
     * @return the node runtime if found
     */
    public Optional<NodeRuntime> getNode(String nodeId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(nodes.get(nodeId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets all nodes in the cluster.
     *
     * @return collection of all node runtimes
     */
    public Collection<NodeRuntime> getAllNodes() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(nodes.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets cluster size.
     *
     * @return number of nodes in cluster
     */
    public int getClusterSize() {
        return nodes.size();
    }

    // ==================== Cluster Operations ====================

    /**
     * Starts all nodes in the cluster.
     * Template Method Pattern - Provides hooks for customization.
     */
    public void startAll() {
        log.info("Starting all cluster nodes...", "count", nodes.size());

        beforeStartAll(); // Hook

        nodes.values().parallelStream().forEach(runtime -> {
            try {
                beforeNodeStart(runtime); // Hook
                runtime.start().join();
                afterNodeStart(runtime); // Hook
            } catch (Exception e) {
                log.error("Failed to start node", e,
                        "nodeId", runtime.getNode().getNodeId());
                onNodeStartError(runtime, e); // Hook
            }
        });

        afterStartAll(); // Hook
        log.info("All cluster nodes started");
    }

    /**
     * Stops all nodes in the cluster.
     * Template Method Pattern - Provides hooks for customization.
     */
    public void stopAll() {
        log.info("Stopping all cluster nodes...", "count", nodes.size());

        beforeStopAll(); // Hook

        nodes.values().parallelStream().forEach(runtime -> {
            try {
                beforeNodeStop(runtime); // Hook
                runtime.stop().join();
                afterNodeStop(runtime); // Hook
            } catch (Exception e) {
                log.error("Failed to stop node", e,
                        "nodeId", runtime.getNode().getNodeId());
            }
        });

        afterStopAll(); // Hook
        log.info("All cluster nodes stopped");
    }

    // ==================== Template Method Hooks ====================

    /**
     * Called before starting all nodes.
     */
    protected void beforeStartAll() {
        // Override in subclass for custom behavior
    }

    /**
     * Called after starting all nodes.
     */
    protected void afterStartAll() {
        electLeader();
    }

    /**
     * Called before starting a specific node.
     */
    protected void beforeNodeStart(NodeRuntime runtime) {
        // Override in subclass for custom behavior
    }

    /**
     * Called after starting a specific node.
     */
    protected void afterNodeStart(NodeRuntime runtime) {
        // Override in subclass for custom behavior
    }

    /**
     * Called when a node fails to start.
     */
    protected void onNodeStartError(NodeRuntime runtime, Exception e) {
        // Override in subclass for custom error handling
    }

    /**
     * Called before stopping all nodes.
     */
    protected void beforeStopAll() {
        // Override in subclass for custom behavior
    }

    /**
     * Called after stopping all nodes.
     */
    protected void afterStopAll() {
        leaderId = null;
    }

    /**
     * Called before stopping a specific node.
     */
    protected void beforeNodeStop(NodeRuntime runtime) {
        // Override in subclass for custom behavior
    }

    /**
     * Called after stopping a specific node.
     */
    protected void afterNodeStop(NodeRuntime runtime) {
        // Override in subclass for custom behavior
    }

    // ==================== Leader Election ====================

    /**
     * Performs leader election using the configured strategy.
     */
    public void electLeader() {
        lock.writeLock().lock();
        try {
            String oldLeader = leaderId;
            Optional<String> newLeader = electionStrategy.electLeader(nodes);

            if (newLeader.isPresent()) {
                leaderId = newLeader.get();

                if (!leaderId.equals(oldLeader)) {
                    notifyListeners(l -> l.onLeaderChanged(oldLeader, leaderId));
                    log.info("Leader elected",
                            "leaderId", leaderId,
                            "strategy", electionStrategy.getName());
                }
            } else {
                leaderId = null;
                if (oldLeader != null) {
                    notifyListeners(l -> l.onLeaderChanged(oldLeader, null));
                }
                log.warn("No leader - cluster has no running nodes");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets current leader ID.
     *
     * @return the leader ID if present
     */
    public Optional<String> getLeaderId() {
        return Optional.ofNullable(leaderId);
    }

    /**
     * Gets leader node runtime.
     *
     * @return the leader node runtime if present
     */
    public Optional<NodeRuntime> getLeader() {
        return leaderId != null ? getNode(leaderId) : Optional.empty();
    }

    /**
     * Checks if a specific node is the leader.
     *
     * @param nodeId the node ID to check
     * @return true if the node is the leader
     */
    public boolean isLeader(String nodeId) {
        return nodeId != null && nodeId.equals(leaderId);
    }

    // ==================== Health & Stats ====================

    /**
     * Checks cluster health.
     *
     * @return cluster health status
     */
    public ClusterHealth checkHealth() {
        lock.readLock().lock();
        try {
            int total = nodes.size();
            int running = (int) nodes.values().stream()
                    .filter(NodeRuntime::isRunning)
                    .count();
            int stopped = (int) nodes.values().stream()
                    .filter(NodeRuntime::isStopped)
                    .count();

            boolean healthy = running == total && total > 0;

            return new ClusterHealth(total, running, stopped, healthy, leaderId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets cluster statistics.
     *
     * @return cluster statistics
     */
    public ClusterStats getStats() {
        lock.readLock().lock();
        try {
            long totalUptime = nodes.values().stream()
                    .mapToLong(NodeRuntime::getUptimeMillis)
                    .sum();

            return new ClusterStats(
                    clusterId,
                    nodes.size(),
                    totalUptime,
                    leaderId,
                    electionStrategy.getName()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== Observer Pattern: Listeners ====================

    /**
     * Adds a cluster listener.
     *
     * @param listener the listener to add
     */
    public void addListener(ClusterListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a cluster listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(ClusterListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(Consumer<ClusterListener> action) {
        for (ClusterListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.error("Cluster listener error", e);
            }
        }
    }

    /**
     * Cluster listener interface.
     * Observer Pattern - Observers of cluster events.
     */
    public interface ClusterListener {
        default void onNodeAdded(String nodeId) {}
        default void onNodeRemoved(String nodeId) {}
        default void onLeaderChanged(String oldLeader, String newLeader) {}
        default void onClusterHealthChanged(ClusterHealth health) {}
    }

    // ==================== Data Classes ====================

    /**
     * Cluster health status.
     */
    public record ClusterHealth(
            int totalNodes,
            int runningNodes,
            int stoppedNodes,
            boolean healthy,
            String leaderId
    ) {
        @Override
        public String toString() {
            return String.format("ClusterHealth[total=%d, running=%d, healthy=%s, leader=%s]",
                    totalNodes, runningNodes, healthy, leaderId);
        }
    }

    /**
     * Cluster statistics.
     */
    public record ClusterStats(
            String clusterId,
            int nodeCount,
            long totalUptimeMillis,
            String leaderId,
            String electionStrategy
    ) {
        @Override
        public String toString() {
            return String.format("ClusterStats[id=%s, nodes=%d, uptime=%dms, leader=%s, strategy=%s]",
                    clusterId, nodeCount, totalUptimeMillis, leaderId, electionStrategy);
        }
    }

    /**
     * Gets the cluster ID.
     *
     * @return the cluster ID
     */
    public String getClusterId() {
        return clusterId;
    }

    @Override
    public String toString() {
        return String.format("ClusterManager[id=%s, nodes=%d, leader=%s, strategy=%s]",
                clusterId, nodes.size(), leaderId, electionStrategy.getName());
    }
}
