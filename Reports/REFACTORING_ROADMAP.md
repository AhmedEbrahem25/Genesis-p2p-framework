# Genesis P2P Framework - Complete Refactoring Roadmap
**Created:** 2025-12-18
**Status:** IN PROGRESS
**Purpose:** Systematic refactoring to fix all identified design pattern violations

---

## Progress Tracker

### ✅ COMPLETED (Session 1)
- [x] **Task 1:** Add SecurityConfig.Builder - DONE
- [x] **Task 2:** Add lifecycle support to MessageProcessor - DONE
- [x] **Task 3:** Centralize thread creation in BroadcastDiscovery - DONE

### 🔄 IN PROGRESS
- [ ] **Tasks 4-18:** Remaining refactorings (see detailed plan below)

### 📊 Overall Progress: 3/18 tasks complete (17%)

---

## PHASE 1: Thread Centralization (PRIORITY: HIGH, RISK: LOW)

### Task 4: Centralize thread creation in MulticastDiscovery.java
**File:** `src/main/java/com/genesis/p2p/discovery/MulticastDiscovery.java`
**Estimated Effort:** 15 minutes
**Risk Level:** LOW

**Current Code (Lines to Replace):**

**Location 1 (Around line 208):**
```java
// FIND:
discoveryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "multicast-discovery-" + config.nodeId());
    t.setDaemon(true);
    return t;
});

// REPLACE WITH:
discoveryScheduler = ThreadPoolFactory.createNamedScheduler(
    "multicast-discovery", config.nodeId());
```

**Location 2 (Around line 233):**
```java
// FIND:
listenerExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "multicast-listener-" + config.nodeId());
    t.setDaemon(true);
    return t;
});

// REPLACE WITH:
listenerExecutor = ThreadPoolFactory.createNamedExecutor(
    "multicast-listener", config.nodeId());
```

**Import to Add:**
```java
import com.genesis.p2p.util.threading.ThreadPoolFactory;
```

---

### Task 5: Centralize thread creation in CompositeDiscovery.java
**File:** `src/main/java/com/genesis/p2p/discovery/CompositeDiscovery.java`
**Estimated Effort:** 10 minutes
**Risk Level:** LOW

**Current Code (Around line 37):**
```java
// FIND:
executor = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "composite-discovery");
    t.setDaemon(true);
    return t;
});

// REPLACE WITH:
executor = ThreadPoolFactory.createCachedPool(
    "composite-discovery", "composite");
```

**Import to Add:**
```java
import com.genesis.p2p.util.threading.ThreadPoolFactory;
```

---

### Task 6: Centralize thread creation in PeerCleanupService.java
**File:** `src/main/java/com/genesis/p2p/core/peer/PeerCleanupService.java`
**Estimated Effort:** 10 minutes
**Risk Level:** LOW

**Current Code (Around line 97):**
```java
// FIND:
scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "peer-cleanup");
    t.setDaemon(true);
    return t;
});

// REPLACE WITH:
scheduler = ThreadPoolFactory.createNamedScheduler(
    "peer-cleanup", "system");
```

**Import to Add:**
```java
import com.genesis.p2p.util.threading.ThreadPoolFactory;
```

---

### Task 6b: Centralize thread creation in HealthCheckService.java
**File:** `src/main/java/com/genesis/p2p/core/peer/HealthCheckService.java`
**Estimated Effort:** 10 minutes
**Risk Level:** LOW

**Current Code (Around line 189):**
```java
// FIND:
healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "health-check");
    t.setDaemon(true);
    return t;
});

// REPLACE WITH:
healthCheckScheduler = ThreadPoolFactory.createNamedScheduler(
    "health-check", "system");
```

**Import to Add:**
```java
import com.genesis.p2p.util.threading.ThreadPoolFactory;
```

---

## PHASE 2: Factory Pattern Enforcement (PRIORITY: HIGH, RISK: MEDIUM)

### Task 7: Fix factory usage in Node.java
**File:** `src/main/java/com/genesis/p2p/application/Node.java`
**Lines:** 327-333
**Estimated Effort:** 20 minutes
**Risk Level:** MEDIUM

**Current Code:**
```java
// FIND (lines 327-333):
MulticastDiscovery multicast = new MulticastDiscovery(config, peerManager);
BroadcastDiscovery broadcast = new BroadcastDiscovery(config, peerManager);
BootstrapDiscovery bootstrap = new BootstrapDiscovery(
        config, peerManager, Collections.emptyList());

this.discoveryService = new CompositeDiscovery(
        multicast, broadcast, bootstrap);
log.info("✓ Composite discovery (3 strategies: multicast, broadcast, bootstrap)");

// REPLACE WITH:
this.discoveryService = DiscoveryFactory.createComposite(
        config, peerManager, Collections.emptyList());
log.info("✓ Composite discovery (3 strategies: multicast, broadcast, bootstrap)");
```

**Import to Add:**
```java
import com.genesis.p2p.discovery.DiscoveryFactory;
```

**Imports to Remove:**
```java
// These can be removed if not used elsewhere:
import com.genesis.p2p.discovery.MulticastDiscovery;
import com.genesis.p2p.discovery.BroadcastDiscovery;
import com.genesis.p2p.discovery.BootstrapDiscovery;
```

---

### Task 8: Fix factory usage in HybridDiscovery.java
**File:** `src/main/java/com/genesis/p2p/discovery/HybridDiscovery.java`
**Lines:** 43, 66, 71, 75
**Estimated Effort:** 25 minutes
**Risk Level:** MEDIUM

**Current Code - Multiple Locations:**

**Location 1 (Around line 43):**
```java
// FIND:
composite.add(new MulticastDiscovery(config, peerManager));
composite.add(new BroadcastDiscovery(config, peerManager));

// REPLACE WITH:
composite.add(DiscoveryFactory.createMulticast(config, peerManager));
composite.add(DiscoveryFactory.createBroadcast(config, peerManager));
```

**Location 2 (Around line 66):**
```java
// FIND:
strategies.add(new MulticastDiscovery(config, peerManager));

// REPLACE WITH:
strategies.add(DiscoveryFactory.createMulticast(config, peerManager));
```

**Location 3 (Around line 71):**
```java
// FIND:
strategies.add(new BroadcastDiscovery(config, peerManager));

// REPLACE WITH:
strategies.add(DiscoveryFactory.createBroadcast(config, peerManager));
```

**Location 4 (Around line 75):**
```java
// FIND:
strategies.add(new BootstrapDiscovery(config, peerManager, bootstrapPeers));

// REPLACE WITH:
strategies.add(DiscoveryFactory.createBootstrap(config, peerManager, bootstrapPeers));
```

**Import to Add:**
```java
import com.genesis.p2p.discovery.DiscoveryFactory;
```

---

### Task 9: Fix factory usage in NatAwareDiscovery.java
**File:** `src/main/java/com/genesis/p2p/nat/NatAwareDiscovery.java`
**Lines:** 37-38
**Estimated Effort:** 15 minutes
**Risk Level:** MEDIUM

**Current Code:**
```java
// FIND (lines 37-38 in initializeDiscoveryServices):
discoveryServices.add(new MulticastDiscovery(config, peerManager));
discoveryServices.add(new BroadcastDiscovery(config, peerManager));

// REPLACE WITH:
discoveryServices.add(DiscoveryFactory.createMulticast(config, peerManager));
discoveryServices.add(DiscoveryFactory.createBroadcast(config, peerManager));
```

**Import to Add:**
```java
import com.genesis.p2p.discovery.DiscoveryFactory;
```

---

## PHASE 3: Dependency Injection - Discovery Services (PRIORITY: MEDIUM, RISK: MEDIUM)

### Task 10: Inject MetricsRegistry in AbstractDiscoveryService
**Files to Modify:**
1. `src/main/java/com/genesis/p2p/discovery/AbstractDiscoveryService.java`
2. All subclasses:
   - `MulticastDiscovery.java`
   - `BroadcastDiscovery.java`
   - `BootstrapDiscovery.java`
   - `HybridDiscovery.java`
   - `NatAwareDiscovery.java`

**Estimated Effort:** 1-2 hours
**Risk Level:** MEDIUM
**Impact:** All discovery services

**Step-by-Step Instructions:**

**Step 1: Modify AbstractDiscoveryService.java**

**Current Constructor (line 31-38):**
```java
protected AbstractDiscoveryService(NodeConfig config, PeerManager peerManager) {
    this.config = config;
    this.peerManager = peerManager;
    this.metrics = new MetricsRegistry(); // ❌ CREATES OWN
    this.discoveryListeners = new CopyOnWriteArrayList<>();
    this.isRunning = new AtomicBoolean(false);
    this.log = NodeLogger.getLogger(getClass());
}
```

**Replace With:**
```java
protected AbstractDiscoveryService(
        NodeConfig config,
        PeerManager peerManager,
        MetricsRegistry metrics) {
    this.config = config;
    this.peerManager = peerManager;
    this.metrics = metrics; // ✅ INJECTED
    this.discoveryListeners = new CopyOnWriteArrayList<>();
    this.isRunning = new AtomicBoolean(false);
    this.log = NodeLogger.getLogger(getClass());
}
```

**Import to Add:**
```java
import com.genesis.p2p.observability.metrics.MetricsRegistry;
```

**Step 2: Update MulticastDiscovery.java Constructor**

**Current (lines vary):**
```java
public MulticastDiscovery(
        NodeConfig config,
        PeerManager peerManager,
        String multicastGroup,
        int multicastPort,
        Duration announceInterval) {
    super(config, peerManager);
    // ...
}
```

**Replace With:**
```java
public MulticastDiscovery(
        NodeConfig config,
        PeerManager peerManager,
        MetricsRegistry metrics,
        String multicastGroup,
        int multicastPort,
        Duration announceInterval) {
    super(config, peerManager, metrics);
    // ...
}
```

**Repeat for all constructors in MulticastDiscovery**

**Step 3: Update BroadcastDiscovery.java Constructor**

Same pattern - add `MetricsRegistry metrics` parameter and pass to super

**Step 4: Update BootstrapDiscovery.java Constructor**

Same pattern

**Step 5: Update HybridDiscovery.java Constructor**

Same pattern

**Step 6: Update NatAwareDiscovery.java Constructor**

Same pattern

**Step 7: Update DiscoveryFactory.java**

All factory methods need to pass MetricsRegistry. Example:

**Current:**
```java
public static MulticastDiscovery createMulticast(NodeConfig config, PeerManager peerManager) {
    return new MulticastDiscovery(
            config,
            peerManager,
            config.multicastGroup(),
            config.multicastPort(),
            Duration.ofSeconds(30)
    );
}
```

**Replace With:**
```java
public static MulticastDiscovery createMulticast(
        NodeConfig config,
        PeerManager peerManager,
        MetricsRegistry metrics) {
    return new MulticastDiscovery(
            config,
            peerManager,
            metrics,
            config.multicastGroup(),
            config.multicastPort(),
            Duration.ofSeconds(30)
    );
}
```

**Repeat for all factory methods**

**Step 8: Update Node.java**

When calling DiscoveryFactory, pass metricsRegistry:

```java
this.discoveryService = DiscoveryFactory.createComposite(
        config, peerManager, metricsRegistry, Collections.emptyList());
```

---

## PHASE 4: Pipeline Formalization (PRIORITY: MEDIUM, RISK: HIGH)

### Task 11: Create Pipeline Interfaces
**Files to Create:**
1. `src/main/java/com/genesis/p2p/core/pipeline/MessagePipelineStage.java`
2. `src/main/java/com/genesis/p2p/core/pipeline/MessagePipeline.java`
3. `src/main/java/com/genesis/p2p/core/pipeline/ProcessingContext.java`

**Estimated Effort:** 2-3 hours
**Risk Level:** HIGH (new architecture)

**File 1: MessagePipelineStage.java**
```java
package com.genesis.p2p.core.pipeline;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.ProcessingResult;

/**
 * Represents a single stage in the message processing pipeline.
 */
public interface MessagePipelineStage {
    /**
     * Process a message at this stage.
     *
     * @param message The message to process
     * @param context The processing context
     * @return The result of processing
     */
    ProcessingResult process(Message message, ProcessingContext context);

    /**
     * Get the name of this pipeline stage.
     */
    String getName();

    /**
     * Get the priority/order of this stage.
     * Lower numbers execute first.
     */
    default int getOrder() {
        return 100;
    }
}
```

**File 2: ProcessingContext.java**
```java
package com.genesis.p2p.core.pipeline;

import com.genesis.p2p.core.handlers.ProcessingResult;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context passed through the pipeline, allowing stages to share data.
 */
public class ProcessingContext {
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final Map<String, ProcessingResult> stageResults = new HashMap<>();
    private final long startTime = System.currentTimeMillis();

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public <T> T getAttribute(String key, Class<T> type) {
        return type.cast(attributes.get(key));
    }

    public void recordStageResult(String stageName, ProcessingResult result) {
        stageResults.put(stageName, result);
    }

    public Map<String, ProcessingResult> getStageResults() {
        return new HashMap<>(stageResults);
    }

    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }
}
```

**File 3: MessagePipeline.java**
```java
package com.genesis.p2p.core.pipeline;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.ProcessingResult;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pipeline for processing messages through multiple stages.
 */
public class MessagePipeline {
    private static final NodeLogger log = NodeLogger.getLogger(MessagePipeline.class);

    private final List<MessagePipelineStage> stages;
    private final MetricsRegistry metrics;

    public MessagePipeline(MetricsRegistry metrics, List<MessagePipelineStage> stages) {
        this.metrics = metrics;
        this.stages = new ArrayList<>(stages);
        this.stages.sort(Comparator.comparingInt(MessagePipelineStage::getOrder));
    }

    public ProcessingResult execute(Message message) {
        ProcessingContext context = new ProcessingContext();

        for (MessagePipelineStage stage : stages) {
            log.debug("Executing pipeline stage: {}", stage.getName());
            metrics.incrementCounter("pipeline.stage." + stage.getName() + ".executed");

            long stageStart = System.currentTimeMillis();
            ProcessingResult result = stage.process(message, context);
            long stageDuration = System.currentTimeMillis() - stageStart;

            metrics.recordTiming("pipeline.stage." + stage.getName() + ".duration", stageDuration);
            context.recordStageResult(stage.getName(), result);

            if (!result.isSuccess() || result.shouldTerminatePipeline()) {
                log.debug("Pipeline terminated at stage: {} with result: {}",
                        stage.getName(), result.getStatus());
                return result;
            }
        }

        return ProcessingResult.success("Pipeline completed");
    }

    public int getStageCount() {
        return stages.size();
    }
}
```

---

### Task 12: Implement Pipeline Stages
**Files to Create:**
1. `src/main/java/com/genesis/p2p/core/pipeline/stages/ValidationStage.java`
2. `src/main/java/com/genesis/p2p/core/pipeline/stages/DeduplicationStage.java`
3. `src/main/java/com/genesis/p2p/core/pipeline/stages/BackpressureStage.java`
4. `src/main/java/com/genesis/p2p/core/pipeline/stages/CircuitBreakerStage.java`
5. `src/main/java/com/genesis/p2p/core/pipeline/stages/PeerUpdateStage.java`
6. `src/main/java/com/genesis/p2p/core/pipeline/stages/ProcessingStage.java`

**Estimated Effort:** 3-4 hours
**Risk Level:** HIGH

**Example: ValidationStage.java**
```java
package com.genesis.p2p.core.pipeline.stages;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.handlers.ProcessingResult;
import com.genesis.p2p.core.pipeline.MessagePipelineStage;
import com.genesis.p2p.core.pipeline.ProcessingContext;
import com.genesis.p2p.observability.metrics.MetricsRegistry;

public class ValidationStage implements MessagePipelineStage {
    private final MetricsRegistry metrics;

    public ValidationStage(MetricsRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    public ProcessingResult process(Message message, ProcessingContext context) {
        // Validation logic from MessageHandler
        if (message == null) {
            metrics.incrementCounter("validation.null_message");
            return ProcessingResult.failure("Null message");
        }

        if (message.header() == null) {
            metrics.incrementCounter("validation.null_header");
            return ProcessingResult.failure("Null header");
        }

        if (message.body() == null) {
            metrics.incrementCounter("validation.null_body");
            return ProcessingResult.failure("Null body");
        }

        metrics.incrementCounter("validation.passed");
        return ProcessingResult.success("Validation passed");
    }

    @Override
    public String getName() {
        return "validation";
    }

    @Override
    public int getOrder() {
        return 10; // First stage
    }
}
```

**Repeat similar pattern for:**
- DeduplicationStage (order: 20)
- BackpressureStage (order: 30)
- CircuitBreakerStage (order: 40)
- PeerUpdateStage (order: 50)
- ProcessingStage (order: 60)

---

### Task 13: Refactor MessageHandler to use Pipeline
**File:** `src/main/java/com/genesis/p2p/core/MessageHandler.java`
**Estimated Effort:** 2-3 hours
**Risk Level:** HIGH (breaks existing logic flow)

**Current handleMessage() method (lines 162-280):**
```java
public ProcessingResult handleMessage(Message message) {
    // Stage 1: Validate
    // Stage 2: Deduplication
    // Stage 3: Backpressure
    // Stage 4: Circuit Breaker
    // Stage 5: Process
}
```

**Replace With:**
```java
public ProcessingResult handleMessage(Message message) {
    return pipeline.execute(message);
}
```

**Add field:**
```java
private final MessagePipeline pipeline;
```

**Update constructor to build pipeline:**
```java
public MessageHandler(String nodeId, PeerManager peerManager, MetricsRegistry metrics) {
    this.nodeId = nodeId;
    this.peerManager = peerManager;
    this.metrics = metrics;

    // Build pipeline
    this.pipeline = new MessagePipeline(metrics, List.of(
        new ValidationStage(metrics),
        new DeduplicationStage(deduplicationService, metrics),
        new BackpressureStage(backpressureManager, metrics),
        new CircuitBreakerStage(circuitBreakerManager, metrics),
        new PeerUpdateStage(peerManager),
        new ProcessingStage(asyncProcessor, processorRegistry)
    ));
}
```

---

## PHASE 5: Full Dependency Injection (PRIORITY: HIGH, RISK: VERY HIGH)

### Task 14: Apply Dependency Injection to MessageHandler
**File:** `src/main/java/com/genesis/p2p/core/MessageHandler.java`
**Lines:** 84-143
**Estimated Effort:** 4-6 hours
**Risk Level:** VERY HIGH (breaks construction pattern)

**Current Constructor:**
```java
public MessageHandler(String nodeId, PeerManager peerManager) {
    this.nodeId = nodeId;
    this.peerManager = peerManager;
    this.metrics = new MetricsRegistry(nodeId); // ❌ CREATES
    this.tracker = new MessageLifecycleTracker(); // ❌ CREATES
    this.processorRegistry = new ProcessorRegistry(); // ❌ CREATES
    this.deduplicationService = new DeduplicationService(...); // ❌ CREATES
    // ... more creations
}
```

**Replace With:**
```java
public MessageHandler(
        String nodeId,
        PeerManager peerManager,
        MetricsRegistry metrics,
        ProcessorRegistry processorRegistry,
        DeduplicationService deduplicationService,
        BackpressureManager backpressureManager,
        CircuitBreakerManager circuitBreakerManager,
        AsyncMessageProcessor asyncProcessor,
        MessageLifecycleTracker tracker) {
    this.nodeId = nodeId;
    this.peerManager = peerManager;
    this.metrics = metrics;
    this.processorRegistry = processorRegistry;
    this.deduplicationService = deduplicationService;
    this.backpressureManager = backpressureManager;
    this.circuitBreakerManager = circuitBreakerManager;
    this.asyncProcessor = asyncProcessor;
    this.tracker = tracker;
}
```

**Impact:** This breaks Node.java which creates MessageHandler

**Update Node.java (line 254):**

**Current:**
```java
this.messageHandler = new MessageHandler(config.nodeId(), peerManager);
```

**Replace With:**
```java
// Create MessageHandler dependencies
ProcessorRegistry processorRegistry = new ProcessorRegistry();
DeduplicationService deduplicationService = new DeduplicationService(
    Duration.ofMinutes(5).toMillis(), 10000);
BackpressureManager backpressureManager = new BackpressureManager(1000, Duration.ofSeconds(10).toMillis());
CircuitBreakerManager circuitBreakerManager = new CircuitBreakerManager(Duration.ofSeconds(30).toMillis(), 100, 0.5);
AsyncMessageProcessor asyncProcessor = new AsyncMessageProcessor(threadPoolManager.getExecutor());
MessageLifecycleTracker tracker = new MessageLifecycleTracker();

this.messageHandler = new MessageHandler(
    config.nodeId(),
    peerManager,
    metricsRegistry,
    processorRegistry,
    deduplicationService,
    backpressureManager,
    circuitBreakerManager,
    asyncProcessor,
    tracker
);
```

---

### Task 15: Apply Dependency Injection to Node Constructor
**File:** `src/main/java/com/genesis/p2p/application/Node.java`
**Lines:** 202-334
**Estimated Effort:** 8-16 hours
**Risk Level:** VERY HIGH (massive architectural change)

**This is the biggest refactoring task and requires careful planning.**

**Current Pattern:**
```java
public Node(NodeConfig config, SecurityConfig securityConfig, ProtocolConfig protocolConfig) {
    // Creates ALL dependencies internally
    this.metricsRegistry = new MetricsRegistry(...);
    this.threadPoolManager = new ThreadPoolManager(...);
    this.eventBus = new EventBus(...);
    // ... 15+ more creations
}
```

**Proposed Approach - Option 1: Builder-Based DI**

Enhance NodeBuilder to support full dependency injection:

```java
public class NodeBuilder {
    // Existing config builders...

    // Add dependency injection support
    private MetricsRegistry metricsRegistry;
    private ThreadPoolManager threadPoolManager;
    private EventBus eventBus;
    // ... for all dependencies

    public NodeBuilder withMetricsRegistry(MetricsRegistry metrics) {
        this.metricsRegistry = metrics;
        return this;
    }

    // ... more withXxx methods

    public Node build() {
        // Create dependencies if not provided (backward compatibility)
        if (metricsRegistry == null) {
            metricsRegistry = new MetricsRegistry(config.nodeId());
        }
        // ... for all dependencies

        // Pass all dependencies to Node
        return new Node(config, securityConfig, protocolConfig,
            metricsRegistry, threadPoolManager, eventBus, ...);
    }
}
```

**Proposed Approach - Option 2: Service Locator Pattern**

Create a DependencyContainer:

```java
public class NodeDependencies {
    private final MetricsRegistry metricsRegistry;
    private final ThreadPoolManager threadPoolManager;
    // ... all dependencies

    private NodeDependencies(Builder builder) {
        this.metricsRegistry = builder.metricsRegistry != null
            ? builder.metricsRegistry
            : new MetricsRegistry(builder.config.nodeId());
        // ... for all dependencies
    }

    public static class Builder {
        // Builder pattern for dependencies
    }
}

public Node(NodeConfig config, NodeDependencies dependencies) {
    this.config = config;
    this.metricsRegistry = dependencies.getMetricsRegistry();
    // ... use all dependencies
}
```

**Recommendation:** Use Option 1 (enhance NodeBuilder) for backward compatibility.

**Steps:**
1. Add fields to NodeBuilder for all dependencies
2. Add withXxx() methods for each dependency
3. Modify Node constructor to accept all dependencies
4. Update NodeBuilder.build() to create missing dependencies
5. Ensure backward compatibility (existing code still works)

**Breaking Changes:**
- Direct Node construction will be discouraged
- Users should use NodeBuilder instead
- Add deprecation warnings to Node constructor

---

### Task 16: Simplify CompositeDiscovery and HybridDiscovery
**Files:**
- `src/main/java/com/genesis/p2p/discovery/CompositeDiscovery.java`
- `src/main/java/com/genesis/p2p/discovery/HybridDiscovery.java`

**Estimated Effort:** 1-2 hours
**Risk Level:** MEDIUM

**Analysis:**
- HybridDiscovery wraps CompositeDiscovery with hardcoded multicast + broadcast
- CompositeDiscovery can do everything HybridDiscovery does

**Option 1: Merge into CompositeDiscovery**

Add factory methods to CompositeDiscovery:

```java
public class CompositeDiscovery {
    // Existing code...

    public static CompositeDiscovery hybrid(NodeConfig config, PeerManager peerManager) {
        return new CompositeDiscovery(
            DiscoveryFactory.createMulticast(config, peerManager),
            DiscoveryFactory.createBroadcast(config, peerManager)
        );
    }
}
```

Deprecate HybridDiscovery:

```java
@Deprecated(since = "0.2.0", forRemoval = true)
public class HybridDiscovery extends CompositeDiscovery {
    public HybridDiscovery(NodeConfig config, PeerManager peerManager) {
        super(
            DiscoveryFactory.createMulticast(config, peerManager),
            DiscoveryFactory.createBroadcast(config, peerManager)
        );
    }
}
```

**Option 2: Keep but clarify purpose**

Document the specific use case for HybridDiscovery vs CompositeDiscovery.

**Recommendation:** Option 1 - merge into CompositeDiscovery with static factory method.

---

## PHASE 6: Verification (PRIORITY: CRITICAL, RISK: N/A)

### Task 17: Compile and Verify All Changes
**Command:**
```bash
cd F:\Projects\genesis-p2p-framework
mvn clean compile
```

**Expected Output:** BUILD SUCCESS with 0 errors

**If Compilation Fails:**
1. Review error messages
2. Fix issues one at a time
3. Re-compile after each fix
4. Document any unexpected issues

---

### Task 18: Run Tests to Ensure Behavior Preserved
**Command:**
```bash
cd F:\Projects\genesis-p2p-framework
mvn test
```

**Test Strategy:**
1. Fix any test compilation errors first
2. Run existing unit tests
3. Run integration tests
4. Verify all tests pass
5. Add new tests for refactored components

**Known Test Issues:**
- 6 anonymous IDiscoveryListener implementations missing `onPeerLost()` method
- These need to be fixed before tests will compile

**Fix for Test Issues:**
Add to each failing test:
```java
@Override
public void onPeerLost(Peer peer) {
    // Not used in this test
}
```

---

## Implementation Guidelines

### General Principles
1. **One task at a time** - Complete and test each task before moving to next
2. **Compile frequently** - After each file change, compile to catch errors early
3. **Backup before major changes** - Git commit before starting PHASE 4 or 5
4. **Test incrementally** - Don't wait until the end to run tests
5. **Document issues** - If something unexpected happens, document it

### Risk Mitigation
- **LOW RISK tasks (1-6):** Can be done confidently without extensive testing
- **MEDIUM RISK tasks (7-10, 16):** Compile and run quick smoke tests after each
- **HIGH RISK tasks (11-13):** Requires careful testing, consider feature branch
- **VERY HIGH RISK tasks (14-15):** Requires extensive testing, definitely use feature branch

### Git Strategy
```bash
# Before starting
git checkout -b refactoring/design-patterns

# After completing each phase
git add .
git commit -m "Phase X: [description]"

# Before VERY HIGH RISK tasks
git checkout -b refactoring/node-di
# ... do work ...
git commit -m "WIP: Node DI refactoring"
```

### Rollback Strategy
If a refactoring breaks functionality:
```bash
git stash  # Save current work
git checkout main  # Return to stable state
git checkout -b refactoring/alternative-approach
# Try different approach
```

---

## Estimated Timeline

### Session 1 (COMPLETED): ~1 hour
- ✅ Task 1: SecurityConfig.Builder (15 min)
- ✅ Task 2: MessageProcessor lifecycle (15 min)
- ✅ Task 3: BroadcastDiscovery threads (15 min)
- ✅ Documentation (15 min)

### Session 2 (RECOMMENDED): ~2 hours
- Task 4: MulticastDiscovery threads (15 min)
- Task 5: CompositeDiscovery threads (10 min)
- Task 6: Other services threads (20 min)
- Task 7: Node.java factory usage (20 min)
- Task 8: HybridDiscovery factory usage (25 min)
- Task 9: NatAwareDiscovery factory usage (15 min)
- Compile & quick test (15 min)

### Session 3 (RECOMMENDED): ~3 hours
- Task 10: MetricsRegistry injection (2 hours)
- Compile & test (30 min)
- Fix any issues (30 min)

### Session 4 (RECOMMENDED): ~6 hours
- Task 11: Pipeline interfaces (2 hours)
- Task 12: Pipeline stages (3 hours)
- Task 13: MessageHandler refactor (1 hour)
- Compile & test thoroughly

### Session 5 (RECOMMENDED): ~8 hours
- Task 14: MessageHandler DI (4 hours)
- Task 15: Node DI (4 hours)
- Extensive testing

### Session 6 (RECOMMENDED): ~2 hours
- Task 16: Simplify discovery (1 hour)
- Task 17: Final compilation (15 min)
- Task 18: Full test suite (45 min)

**TOTAL ESTIMATED EFFORT: 22+ hours across 6 sessions**

---

## Success Criteria

### Must Have (Blocking)
- [x] SecurityConfig.Builder added
- [x] MessageProcessor has lifecycle methods
- [ ] All thread creation uses ThreadPoolFactory
- [ ] All discovery services created via DiscoveryFactory
- [ ] All tests pass

### Should Have (Important)
- [ ] MetricsRegistry injected (not created)
- [ ] Pipeline formalized with stage objects
- [ ] MessageHandler dependencies injected
- [ ] Node dependencies injected via builder

### Nice to Have (Optional)
- [ ] HybridDiscovery merged into CompositeDiscovery
- [ ] Full test coverage for new pipeline stages
- [ ] Performance benchmarks show no regression

---

## Current Status

**Last Updated:** 2025-12-18 20:15
**Tasks Completed:** 3/18
**Progress:** 17%
**Current Phase:** Phase 1 - Thread Centralization
**Next Task:** Task 4 - MulticastDiscovery thread centralization

**Notes:**
- Initial changes compile successfully
- No test failures introduced so far
- Backward compatibility maintained

---

## Contact & Questions

For questions about this refactoring roadmap, refer to:
- **Design Pattern Audit:** `DESIGN_PATTERN_AUDIT_REPORT.md`
- **Integration Verification:** `INTEGRATION_VERIFICATION_REPORT.md`

All identified issues are documented with specific file locations and line numbers.
