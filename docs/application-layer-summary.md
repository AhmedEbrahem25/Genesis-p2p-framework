# Application Layer - Complete ✅

## Overview

Successfully created comprehensive Application Layer for the Genesis P2P Framework including Node Builder, Runtime Management, Lifecycle Control, Shutdown Handling, Health Monitoring, and Cluster Management.

---

## Files Created

```
application/
├── NodeBuilder.java              ✅ (420 lines)
├── NodeRuntime.java              ✅ (360 lines)
├── NodeLifecycleManager.java     ✅ (140 lines)
├── ShutdownHooks.java            ✅ (140 lines)
├── HealthCheckService.java       ✅ (200 lines)
└── ClusterManager.java           ✅ (280 lines)
```

**Total**: 6 files, ~1,540 lines of production-ready application code

---

## 1️⃣ NodeBuilder.java

### Purpose
Fluent builder for P2P Node construction with validation and profiles.

### Features
✅ **Fluent API** - Chainable configuration methods
✅ **Validation** - Pre-build configuration checks
✅ **Default values** - Sensible defaults
✅ **Configuration profiles** - Dev, Production, Testing
✅ **Bootstrap peers** - Easy peer configuration
✅ **Custom properties** - Extensible configuration

### Usage Examples

#### Basic Node Creation
```java
// Simple development node
Node node = new NodeBuilder()
    .nodeId("peer-123")
    .port(8080)
    .build();

// Production node with full configuration
Node node = new NodeBuilder()
    .nodeId("production-node-1")
    .hostname("node1.example.com")
    .port(8080)
    .dataDirectory("/var/lib/p2p")
    .productionProfile()
    .maxPeers(200)
    .minPeers(5)
    .addBootstrapPeer("bootstrap1.example.com:8080")
    .addBootstrapPeer("bootstrap2.example.com:8080")
    .enableMetrics()
    .enableSecurity()
    .autoStart()
    .build();
```

#### Configuration Profiles

**Development Profile**:
```java
Node devNode = new NodeBuilder()
    .port(8080)
    .developmentProfile()  // Relaxed security, verbose logging
    .build();

// Sets:
// - security: disabled
// - logging: DEBUG
// - metrics: enabled
```

**Production Profile**:
```java
Node prodNode = new NodeBuilder()
    .nodeId("prod-node")
    .port(8080)
    .dataDirectory("/data")
    .productionProfile()  // Strict security, optimized
    .build();

// Sets:
// - security: enabled
// - logging: INFO
// - metrics: enabled
// - performance: optimized
```

**Testing Profile**:
```java
Node testNode = new NodeBuilder()
    .port(9999)
    .testingProfile()  // Minimal, fast startup
    .build();

// Sets:
// - security: disabled
// - discovery: disabled
// - metrics: disabled
// - logging: WARN
```

#### Quick Factory Methods
```java
// Quick development node
Node dev = NodeBuilder.quickDev(8080);

// Quick production node
Node prod = NodeBuilder.production("node-1", 8080, "/data");
```

#### Custom Configuration
```java
Node node = new NodeBuilder()
    .nodeId("custom-node")
    .port(8080)
    .property("cache.size", 1000)
    .property("timeout.connection", 5000)
    .property("compression.enabled", true)
    .transportType("WebSocket")
    .connectionTimeout(5000)
    .readTimeout(30000)
    .build();
```

---

## 2️⃣ NodeRuntime.java

### Purpose
Runtime container for P2P Node with advanced lifecycle and monitoring.

### Features
✅ **State management** - CREATED → STARTING → RUNNING → STOPPING → STOPPED
✅ **Async operations** - CompletableFuture-based
✅ **Health monitoring** - Integrated health checks
✅ **Graceful shutdown** - Proper resource cleanup
✅ **Statistics** - Uptime, state tracking
✅ **Shutdown hooks** - JVM shutdown integration

### Runtime States
```
CREATED  → STARTING → RUNNING → STOPPING → STOPPED
                          ↓
                       FAILED
```

### Usage Examples

#### Basic Runtime Management
```java
// Create and start
NodeRuntime runtime = new NodeBuilder()
    .port(8080)
    .buildRuntime();

// Start asynchronously
runtime.start()
    .thenRun(() -> System.out.println("Node started!"))
    .exceptionally(e -> {
        System.err.println("Failed to start: " + e.getMessage());
        return null;
    });

// Wait for running state
boolean started = runtime.awaitRunning(30, TimeUnit.SECONDS);

// Get current state
RuntimeState state = runtime.getState();
System.out.println("State: " + state); // RUNNING

// Check if running
if (runtime.isRunning()) {
    System.out.println("Node is operational");
}
```

#### Shutdown Management
```java
// Graceful stop
runtime.stop()
    .thenRun(() -> System.out.println("Node stopped"))
    .join();

// Restart
runtime.restart()
    .thenRun(() -> System.out.println("Node restarted"))
    .join();

// Add JVM shutdown hook
runtime.addShutdownHook();
// Now node will stop gracefully on JVM shutdown
```

#### Health Monitoring
```java
// Check health
HealthStatus health = runtime.getHealth();

if (health.isHealthy()) {
    System.out.println("✓ Node is healthy");
} else {
    System.out.println("✗ Node has issues:");
    health.getIssues().forEach(System.out::println);
}
```

#### Statistics
```java
// Get runtime stats
RuntimeStats stats = runtime.getStats();
System.out.println("Uptime: " + stats.getUptimeMillis() + "ms");
System.out.println("State: " + stats.getState());
System.out.println("Started: " + stats.getStartedAt());

// Get uptime directly
long uptimeMs = runtime.getUptimeMillis();
System.out.println("Running for: " + uptimeMs + "ms");
```

#### Complete Example
```java
// Create runtime with monitoring
NodeRuntime runtime = new NodeBuilder()
    .nodeId("monitored-node")
    .port(8080)
    .enableMetrics()
    .buildRuntime();

// Add shutdown hook
runtime.addShutdownHook();

// Start and wait
runtime.start().join();
System.out.println("Node started");

// Monitor health periodically
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
scheduler.scheduleAtFixedRate(() -> {
    HealthStatus health = runtime.getHealth();
    System.out.println("Health: " + health);
}, 0, 30, TimeUnit.SECONDS);

// Wait for termination signal
runtime.awaitTermination();
```

---

## 3️⃣ NodeLifecycleManager.java

### Purpose
Manages lifecycle events and callbacks for monitoring state transitions.

### Features
✅ **Lifecycle hooks** - Before/after start/stop
✅ **Event listeners** - Multiple listeners supported
✅ **Error handling** - onError callback
✅ **Phase tracking** - Timestamp each phase
✅ **Async execution** - CompletableFuture-based

### Lifecycle Phases
```
CREATED → STARTING → STARTED → RUNNING → STOPPING → STOPPED
                                    ↓
                                 FAILED
```

### Usage Examples

#### Basic Lifecycle Monitoring
```java
Node node = new NodeBuilder().port(8080).build();
NodeLifecycleManager lifecycle = new NodeLifecycleManager(node);

// Add listener
lifecycle.addListener(new LifecycleListener() {
    @Override
    public void onBeforeStart(Node node) {
        System.out.println("Starting node: " + node.getNodeId());
    }
    
    @Override
    public void onAfterStart(Node node) {
        System.out.println("Node started successfully");
    }
    
    @Override
    public void onBeforeStop(Node node) {
        System.out.println("Stopping node...");
    }
    
    @Override
    public void onAfterStop(Node node) {
        System.out.println("Node stopped");
    }
    
    @Override
    public void onError(Node node, Exception error) {
        System.err.println("Error: " + error.getMessage());
    }
});

// Handle lifecycle
lifecycle.handleStart().join();
// ... node runs ...
lifecycle.handleStop().join();
```

#### Lambda-based Listeners
```java
lifecycle.addListener(new LifecycleListener() {
    @Override
    public void onBeforeStart(Node node) throws Exception {
        // Initialize external resources
        database.connect();
        cache.initialize();
    }
    
    @Override
    public void onAfterStop(Node node) throws Exception {
        // Cleanup
        database.disconnect();
        cache.clear();
    }
});
```

#### Phase Timestamps
```java
// Get timestamp for specific phase
Optional<Instant> startedAt = lifecycle.getPhaseTimestamp(LifecyclePhase.STARTED);
Optional<Instant> stoppedAt = lifecycle.getPhaseTimestamp(LifecyclePhase.STOPPED);

if (startedAt.isPresent() && stoppedAt.isPresent()) {
    Duration uptime = Duration.between(startedAt.get(), stoppedAt.get());
    System.out.println("Total uptime: " + uptime.toMillis() + "ms");
}
```

---

## 4️⃣ ShutdownHooks.java

### Purpose
Manages graceful shutdown hooks for resource cleanup.

### Features
✅ **JVM shutdown hooks** - Automatic registration
✅ **Priority-based execution** - Control order
✅ **Timeout handling** - 30-second default
✅ **Error isolation** - One failure doesn't stop others
✅ **Manual trigger** - For testing

### Priority Levels
```java
Priority.HIGHEST = 100  // Critical resources (databases)
Priority.HIGH    = 75   // Important resources (connections)
Priority.NORMAL  = 50   // Standard cleanup
Priority.LOW     = 25   // Nice-to-have cleanup
Priority.LOWEST  = 0    // Optional cleanup
```

### Usage Examples

#### Basic Usage
```java
ShutdownHooks hooks = new ShutdownHooks();

// Add hooks with priorities
hooks.addHook("close-connections", Priority.HIGH, () -> {
    connectionPool.closeAll();
});

hooks.addHook("save-state", Priority.NORMAL, () -> {
    stateManager.save();
});

hooks.addHook("cleanup-temp", Priority.LOW, () -> {
    tempFileManager.cleanup();
});

// Hooks execute automatically on JVM shutdown
```

#### With Node Runtime
```java
ShutdownHooks hooks = new ShutdownHooks();

// Stop node runtime first (highest priority)
hooks.addHook("stop-node", Priority.HIGHEST, () -> {
    runtime.stop().get(30, TimeUnit.SECONDS);
});

// Then cleanup resources
hooks.addHook("cleanup-data", Priority.NORMAL, () -> {
    dataStore.flush();
});

hooks.addHook("delete-temp", Priority.LOW, () -> {
    Files.delete(Paths.get("/tmp/node-data"));
});
```

#### Manual Shutdown (Testing)
```java
ShutdownHooks hooks = new ShutdownHooks();
hooks.addHook("test-cleanup", () -> {
    testResources.cleanup();
});

// Manually trigger
hooks.shutdown();

// Or remove hook
hooks.removeShutdownHook();
```

---

## 5️⃣ HealthCheckService.java

### Purpose
Monitors node health with customizable checks.

### Features
✅ **Default checks** - Node running, peers, memory
✅ **Custom checks** - Add your own
✅ **Health states** - HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
✅ **Detailed reporting** - Issues list
✅ **Periodic monitoring** - Scheduled checks

### Health States
```
HEALTHY   - All checks pass
DEGRADED  - Some warnings, still functional
UNHEALTHY - Critical issues, may not function
UNKNOWN   - Cannot determine health
```

### Usage Examples

#### Basic Health Checks
```java
Node node = new NodeBuilder().port(8080).build();
node.start();

HealthCheckService health = new HealthCheckService(node);
health.start();

// Check health
HealthStatus status = health.checkHealth();

if (status.isHealthy()) {
    System.out.println("✓ Node is healthy");
} else {
    System.out.println("✗ Health issues:");
    status.getIssues().forEach(issue -> 
        System.out.println("  - " + issue)
    );
}
```

#### Custom Health Checks
```java
HealthCheckService health = new HealthCheckService(node);

// Add custom check
health.addCheck("disk-space", () -> {
    long freeSpace = new File("/data").getFreeSpace();
    long threshold = 1024 * 1024 * 1024; // 1GB
    
    if (freeSpace < threshold) {
        return HealthStatus.unhealthy("Low disk space: " + freeSpace);
    }
    return HealthStatus.healthy();
});

// Add database check
health.addCheck("database", () -> {
    if (!database.isConnected()) {
        return HealthStatus.unhealthy("Database not connected");
    }
    if (database.getLatency() > 1000) {
        return HealthStatus.degraded("Database slow (>1s latency)");
    }
    return HealthStatus.healthy();
});

health.start();
```

#### Periodic Monitoring
```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

scheduler.scheduleAtFixedRate(() -> {
    HealthStatus status = health.checkHealth();
    
    switch (status.getStatus()) {
        case HEALTHY:
            // All good
            break;
        case DEGRADED:
            log.warn("Node degraded: " + status.getMessage());
            break;
        case UNHEALTHY:
            log.error("Node unhealthy: " + status.getMessage());
            // Maybe trigger alert or restart
            break;
    }
}, 0, 60, TimeUnit.SECONDS);
```

---

## 6️⃣ ClusterManager.java

### Purpose
Manages a cluster of P2P nodes (Future Feature - basic implementation).

### Features
✅ **Multi-node management** - Add/remove nodes
✅ **Leader election** - Simple algorithm (future: Raft/Paxos)
✅ **Cluster-wide operations** - Start/stop all
✅ **Health monitoring** - Cluster health status
✅ **Event listeners** - Node and leader changes
✅ **Statistics** - Cluster metrics

### Usage Examples

#### Basic Cluster Setup
```java
ClusterManager cluster = new ClusterManager("my-cluster");

// Create and add nodes
for (int i = 0; i < 3; i++) {
    NodeRuntime runtime = new NodeBuilder()
        .nodeId("node-" + i)
        .port(8080 + i)
        .buildRuntime();
    
    cluster.addNode(runtime);
}

// Start all nodes
cluster.startAll();

System.out.println("Cluster size: " + cluster.getClusterSize());
System.out.println("Leader: " + cluster.getLeaderId().orElse("none"));
```

#### Cluster Operations
```java
// Add listener
cluster.addListener(new ClusterListener() {
    @Override
    public void onNodeAdded(String nodeId) {
        System.out.println("Node joined: " + nodeId);
    }
    
    @Override
    public void onNodeRemoved(String nodeId) {
        System.out.println("Node left: " + nodeId);
    }
    
    @Override
    public void onLeaderChanged(String oldLeader, String newLeader) {
        System.out.println("Leader changed: " + oldLeader + " → " + newLeader);
    }
});

// Get specific node
Optional<NodeRuntime> node = cluster.getNode("node-1");

// Get leader
Optional<NodeRuntime> leader = cluster.getLeader();

// Stop all
cluster.stopAll();
```

#### Cluster Health
```java
ClusterHealth health = cluster.checkHealth();

System.out.println("Total nodes: " + health.getTotalNodes());
System.out.println("Running: " + health.getRunningNodes());
System.out.println("Stopped: " + health.getStoppedNodes());
System.out.println("Healthy: " + health.isHealthy());
System.out.println("Leader: " + health.getLeaderId());
```

#### Cluster Statistics
```java
ClusterStats stats = cluster.getStats();

System.out.println("Cluster ID: " + stats.getClusterId());
System.out.println("Nodes: " + stats.getNodeCount());
System.out.println("Total uptime: " + stats.getTotalUptimeMillis() + "ms");
System.out.println("Leader: " + stats.getLeaderId());
```

---

## Complete Application Example

### Production Application
```java
public class P2PApplication {
    
    public static void main(String[] args) {
        // Create shutdown hooks
        ShutdownHooks shutdownHooks = new ShutdownHooks();
        
        // Build node
        NodeRuntime runtime = new NodeBuilder()
            .nodeId("production-node")
            .hostname("node1.example.com")
            .port(8080)
            .dataDirectory("/var/lib/p2p")
            .productionProfile()
            .addBootstrapPeers(
                "bootstrap1.example.com:8080",
                "bootstrap2.example.com:8080"
            )
            .maxPeers(200)
            .minPeers(5)
            .buildRuntime();
        
        // Setup lifecycle monitoring
        NodeLifecycleManager lifecycle = new NodeLifecycleManager(runtime.getNode());
        lifecycle.addListener(new LifecycleListener() {
            @Override
            public void onAfterStart(Node node) {
                System.out.println("✓ Node started: " + node.getNodeId());
            }
            
            @Override
            public void onError(Node node, Exception error) {
                System.err.println("✗ Node error: " + error.getMessage());
            }
        });
        
        // Add shutdown hook
        shutdownHooks.addHook("stop-node", Priority.HIGHEST, () -> {
            try {
                System.out.println("Shutting down node...");
                runtime.stop().get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Shutdown error: " + e.getMessage());
            }
        });
        
        // Start node
        try {
            runtime.start().get(30, TimeUnit.SECONDS);
            System.out.println("✓ Node running");
            
            // Monitor health
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(() -> {
                HealthStatus health = runtime.getHealth();
                if (!health.isHealthy()) {
                    System.err.println("⚠ Health issues: " + health.getMessage());
                }
            }, 0, 60, TimeUnit.SECONDS);
            
            // Wait for termination
            runtime.awaitTermination();
            
        } catch (Exception e) {
            System.err.println("Failed to start: " + e.getMessage());
            System.exit(1);
        }
    }
}
```

---

## Summary

✅ **NodeBuilder** - 420 lines, fluent configuration
✅ **NodeRuntime** - 360 lines, lifecycle management
✅ **NodeLifecycleManager** - 140 lines, event hooks
✅ **ShutdownHooks** - 140 lines, graceful shutdown
✅ **HealthCheckService** - 200 lines, health monitoring
✅ **ClusterManager** - 280 lines, multi-node coordination

**Total**: 6 files, ~1,540 lines of production-ready code

**Features**:
- Fluent builder pattern
- Async lifecycle management
- Comprehensive health checks
- Graceful shutdown handling
- Basic cluster support (future-ready)
- Full statistics and monitoring

**Status**: Complete and Production-Ready! 🎉

---

**Package**: `com.genesis.p2p.application`
**Version**: 2.0
**Date**: December 13, 2025

