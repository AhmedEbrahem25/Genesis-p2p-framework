# Genesis P2P Framework - Observability Architecture

## Overview

The Genesis P2P Framework provides **production-grade observability** with end-to-end tracing, structured logging, multi-dimensional metrics, and lifecycle-scoped health indicators. All components are designed for zero-coupling and clean integration.

---

## Architecture Components

### 1. **ObservabilityContext** - Distributed Tracing Foundation

**Location**: `com.genesis.p2p.observability.context.ObservabilityContext`

**Purpose**: Thread-local context propagation for end-to-end correlation across component boundaries.

**Features**:
- **Trace ID**: Unique identifier for entire operation flow
- **Span ID**: Identifier for specific operation segment
- **Correlation ID**: Cross-component request tracking
- **Node ID**: Node identifier for multi-node correlation
- **Component**: Current component name (transport, discovery, security, etc.)
- **Baggage**: Key-value pairs propagated across boundaries

**Usage**:
```java
// Automatic context in single thread
ObservabilityContext.setComponent("transport");
String traceId = ObservabilityContext.getTraceId(); // Auto-generated if needed

// Cross-thread propagation
ObservabilityContext.ContextSnapshot snapshot = ObservabilityContext.snapshot();
executor.submit(() -> {
    ObservabilityContext.restore(snapshot);
    // All logging/metrics now correlated
});
```

---

### 2. **NodeLogger** - Structured Logging with Automatic Correlation

**Location**: `com.genesis.p2p.observability.logging.NodeLogger`

**Enhancement**: Automatically injects correlation IDs into **every log statement**.

**Auto-Injected Fields**:
- `traceId` - End-to-end trace identifier
- `spanId` - Current operation span
- `correlationId` - Cross-component correlation
- `nodeId` - Node identifier
- `component` - Component name

**Before**:
```java
log.info("Message sent", "size", 1024);
// Output: [INFO] Message sent {size=1024}
```

**After (Refined)**:
```java
log.info("Message sent", "size", 1024);
// Output: [INFO] Message sent {traceId=a1b2c3d4, spanId=x7y8z9, correlationId=m5n6,
//                               nodeId=node1, component=transport, size=1024}
```

**Key Benefits**:
- ✅ Zero code changes required in existing components
- ✅ Automatic correlation across all logs
- ✅ Compatible with log aggregation systems (ELK, Splunk, CloudWatch)
- ✅ MDC (Mapped Diagnostic Context) support for SLF4J

---

### 3. **TaggedMetricsRegistry** - Multi-Dimensional Metrics

**Location**: `com.genesis.p2p.observability.metrics.TaggedMetricsRegistry`

**Purpose**: Component-scoped metrics with tags for cross-component visibility.

**Metric Types**:
- **Counters**: Monotonically increasing values (messages sent, errors, etc.)
- **Gauges**: Current value snapshots (active connections, queue size, etc.)
- **Timers**: Duration tracking with min/max/average statistics

**Standard Tags**:
- `component`: transport, discovery, security, messaging
- `operation`: send, receive, process, discover
- `status`: success, failure, timeout
- `nodeId`: Node identifier

**Usage**:
```java
// Tagged counter
taggedMetrics.incrementCounter("messages.sent",
    "component", "transport",
    "protocol", "tcp",
    "status", "success");

// Tagged timer
taggedMetrics.recordTimer("message.processing.duration", 42,
    "component", "messaging",
    "type", "handshake");

// Query by tags
long tcpMessages = taggedMetrics.getCounter("messages.sent",
    "component", "transport", "protocol", "tcp");
```

**Metric Naming Convention**:
```
{component}.{operation}.{metric_type}

Examples:
- transport.tcp.messages.sent
- discovery.multicast.peers.found
- security.encryption.operations.count
- messaging.deduplication.duplicates.detected
```

---

### 4. **HealthIndicator** - Lifecycle-Scoped Health Checks

**Location**: `com.genesis.p2p.observability.health.HealthIndicator`

**Purpose**: Component health checks with lifecycle awareness.

**Health Status**:
- `HEALTHY`: Component fully operational
- `DEGRADED`: Operational but experiencing issues
- `UNHEALTHY`: Not operational

**Implementation Example**:
```java
public class TransportHealthIndicator implements HealthIndicator {
    @Override
    public String getName() {
        return "tcp-transport";
    }

    @Override
    public HealthCheckResult check() {
        if (!transport.isRunning()) {
            return HealthCheckResult.unhealthy("Transport not running");
        }

        long activeConnections = transport.getActiveConnections();
        long maxConnections = transport.getMaxConnections();

        if (activeConnections >= maxConnections * 0.9) {
            return HealthCheckResult.degraded(
                "Connection pool near capacity",
                Map.of("active", activeConnections, "max", maxConnections)
            );
        }

        return HealthCheckResult.healthy("Transport operational",
            Map.of("connections", activeConnections));
    }
}
```

---

### 5. **ComponentHealthRegistry** - Aggregated Health Monitoring

**Location**: `com.genesis.p2p.observability.health.ComponentHealthRegistry`

**Features**:
- Component health aggregation
- Overall system health status
- Liveness and readiness probes (Kubernetes-compatible)
- Health check caching (configurable TTL)

**Probes**:
```java
// Liveness: Is the application running?
boolean alive = healthRegistry.isAlive();
// True if at least one component is not UNHEALTHY

// Readiness: Is the application ready to serve traffic?
boolean ready = healthRegistry.isReady();
// True only if ALL components are HEALTHY or DEGRADED

// Detailed health
AggregatedHealth health = healthRegistry.checkAll();
System.out.println("Overall: " + health.overallStatus());
System.out.println("Healthy: " + health.getHealthyCount());
System.out.println("Degraded: " + health.getDegradedCount());
System.out.println("Unhealthy: " + health.getUnhealthyCount());
```

---

### 6. **ObservabilityFacade** - Unified Integration Point

**Location**: `com.genesis.p2p.observability.ObservabilityFacade`

**Purpose**: Single entry point for all observability operations.

**Usage Pattern**:
```java
// 1. Initialize once per node
ObservabilityFacade observability = new ObservabilityFacade(nodeId);

// 2. Register health indicators
observability.registerHealthIndicator(transportHealth);
observability.registerHealthIndicator(discoveryHealth);

// 3. Set component context
observability.setComponent("transport");

// 4. Log with automatic correlation
NodeLogger log = observability.getLogger(TcpTransport.class);
log.info("Connection established", "peerId", peerId);
// Auto-includes: traceId, spanId, correlationId, nodeId, component

// 5. Record metrics
observability.recordMetric("connections.established", 1);
observability.recordTiming("handshake", durationMs);

// 6. Check health
boolean ready = observability.isReady();
AggregatedHealth health = observability.checkAllHealth();
```

---

## Cross-Component Visibility

### Transport Layer Observability

```java
// In TcpTransport
ObservabilityContext.setComponent("transport");
observability.recordMetric("messages.sent", 1);
log.info("TCP message sent", "size", message.length());
// Logs include: component=transport, traceId, correlationId, etc.
```

### Discovery Layer Observability

```java
// In MulticastDiscovery
ObservabilityContext.setComponent("discovery");
observability.recordMetric("peers.discovered", 1);
log.info("Peer discovered", "peerId", peer.id());
// Logs include: component=discovery, traceId, correlationId, etc.
```

### Security Layer Observability

```java
// In SecurityFacade
ObservabilityContext.setComponent("security");
observability.recordTiming("encryption", encryptionTimeMs);
log.info("Message encrypted", "algorithm", "AES-GCM");
// Logs include: component=security, traceId, correlationId, etc.
```

### Message Pipeline Observability

```java
// Message enters pipeline - set traceId
ObservabilityContext.setTraceId(message.getTraceId());
ObservabilityContext.setComponent("messaging");

// Each stage logs with same traceId
log.info("Validation stage", "messageId", msgId);
log.info("Deduplication stage", "duplicate", false);
log.info("Processing stage", "processorType", "handshake");

// All logs correlated by traceId across all stages
```

---

## End-to-End Correlation Example

### Scenario: Message Send from Node A to Node B

**Node A (Sender)**:
```java
// 1. Generate trace ID
String traceId = ObservabilityContext.getTraceId();

// 2. Include in message
message.setTraceId(traceId);

// 3. Log send operation
log.info("Sending message", "recipient", nodeB);
// Output: [traceId=a1b2, correlationId=c3d4, component=transport, ...]

// 4. Record metrics
observability.recordMetric("messages.sent", 1);
```

**Node B (Receiver)**:
```java
// 1. Extract trace ID from message
String traceId = message.getTraceId();
ObservabilityContext.setTraceId(traceId);

// 2. Log receive operation
log.info("Message received", "sender", nodeA);
// Output: [traceId=a1b2, correlationId=e5f6, component=transport, ...]
// Same traceId as sender!

// 3. Process through pipeline
ObservabilityContext.setComponent("messaging");
messageHandler.handle(message);
// All pipeline logs share same traceId

// 4. Record metrics
observability.recordMetric("messages.received", 1);
```

**Query Logs**:
```
# Find all logs for specific message flow
traceId:a1b2

# Results show complete end-to-end flow:
# Node A - transport - Message sent
# Node B - transport - Message received
# Node B - messaging - Validation
# Node B - messaging - Deduplication
# Node B - messaging - Processing
```

---

## Production Deployment Best Practices

### 1. **Log Aggregation Configuration**

**Logback Configuration** (`logback.xml`):
```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <includeMdcKeyName>correlationId</includeMdcKeyName>
            <includeMdcKeyName>nodeId</includeMdcKeyName>
            <includeMdcKeyName>component</includeMdcKeyName>
        </encoder>
    </appender>
</configuration>
```

### 2. **Metrics Export**

Metrics can be exported to:
- **Prometheus**: Tag-based metrics align with Prometheus data model
- **Graphite**: Hierarchical metric names supported
- **CloudWatch**: Custom metrics with dimensions

### 3. **Health Check Endpoints**

```java
// HTTP endpoint for Kubernetes
@GetMapping("/health/liveness")
public ResponseEntity<String> liveness() {
    return observability.isAlive() ?
        ResponseEntity.ok("UP") :
        ResponseEntity.status(503).body("DOWN");
}

@GetMapping("/health/readiness")
public ResponseEntity<String> readiness() {
    return observability.isReady() ?
        ResponseEntity.ok("READY") :
        ResponseEntity.status(503).body("NOT_READY");
}
```

---

## Zero-Coupling Guarantees

✅ **No Direct Dependencies**: Components never directly depend on observability classes
✅ **Interface-Based**: All integration through ObservabilityFacade interface
✅ **Backward Compatible**: Legacy MetricsRegistry still supported
✅ **Optional**: Components function without observability
✅ **Thread-Safe**: All observability components are thread-safe

---

## Summary

The refined observability architecture provides:

| Feature | Before | After |
|---------|--------|-------|
| **Correlation** | Manual traceId management | Automatic injection in all logs |
| **Metrics** | Flat counters only | Multi-dimensional with tags |
| **Health** | Basic status checks | Lifecycle-scoped with aggregation |
| **Cross-Component** | Per-component silos | Unified end-to-end visibility |
| **Integration** | Scattered APIs | Single ObservabilityFacade |
| **Production** | Basic monitoring | Full distributed tracing |

**Result**: Production-grade operational insight with zero coupling violations and clean integration across all framework components.
