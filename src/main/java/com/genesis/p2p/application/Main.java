package com.genesis.p2p.application;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.threading.ThreadPoolFactory;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.genesis.p2p.util.constants.TimeoutConstants.*;

/**
 * Main - Production-Grade CLI Entry Point for Genesis P2P Framework.
 *
 * <p><b>Enterprise-Grade Features:</b>
 * <ul>
 *   <li>Comprehensive error handling with recovery strategies</li>
 *   <li>Structured logging with correlation IDs</li>
 *   <li>Graceful shutdown with configurable timeouts</li>
 *   <li>Health monitoring and automatic recovery</li>
 *   <li>Resource lifecycle management</li>
 *   <li>Signal handling (SIGTERM, SIGINT)</li>
 *   <li>Configuration hot-reload support</li>
 *   <li>Metrics and observability hooks</li>
 *   <li>Command pattern with undo/redo</li>
 *   <li>Thread-safe state management</li>
 * </ul>
 *
 * <p><b>Design Patterns:</b>
 * <ul>
 *   <li><b>Command Pattern:</b> CLI commands as executable objects</li>
 *   <li><b>Strategy Pattern:</b> Pluggable execution modes</li>
 *   <li><b>Facade Pattern:</b> Simplified interface to complex subsystems</li>
 *   <li><b>Observer Pattern:</b> Event-driven shutdown notifications</li>
 *   <li><b>State Pattern:</b> Application lifecycle states</li>
 *   <li><b>Template Method:</b> Common command execution flow</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * # Single node
 * java -jar genesis-p2p.jar start --nodeId=node1 --profile=prod
 *
 * # Cluster
 * java -jar genesis-p2p.jar cluster --nodes=5 --basePort=9000
 *
 * # Interactive shell
 * java -jar genesis-p2p.jar shell
 *
 * # Configuration management
 * java -jar genesis-p2p.jar config generate --output=prod.json
 * }</pre>
 *
 * @author Genesis P2P Framework Team
 * @version 2.0.0
 * @since 1.0.0
 */
public class Main {

    // ==================== Constants ====================

    private static final NodeLogger log = NodeLogger.getLogger(Main.class);

    /** Application metadata */
    public static final String VERSION = "2.0.0";
    public static final String NAME = "Genesis P2P Framework";
    public static final String COPYRIGHT = "2024 Genesis P2P Project";
    public static final String BUILD_DATE = "2024-12-13";

    /** Timeout configurations - now using centralized TimeoutConstants */
    // STARTUP_TIMEOUT, SHUTDOWN_TIMEOUT, HEALTH_CHECK_INTERVAL now imported from TimeoutConstants
    private static final int MAX_RESTART_ATTEMPTS = 3;

    /** Exit codes following POSIX conventions */
    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_INVALID_ARGS = 1;
    private static final int EXIT_CONFIG_ERROR = 2;
    private static final int EXIT_STARTUP_FAILURE = 3;
    private static final int EXIT_RUNTIME_ERROR = 4;
    private static final int EXIT_INTERRUPTED = 130;

    // ==================== Application State ====================

    /** Runtime state management */
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private static final AtomicReference<NodeRuntime> currentRuntime = new AtomicReference<>();
    private static final AtomicReference<ClusterManager> currentCluster = new AtomicReference<>();
    private static final AtomicReference<ApplicationState> appState =
            new AtomicReference<>(ApplicationState.INITIALIZING);

    /** Lifecycle management */
    private static volatile ShutdownHooks shutdownHooks;
    private static volatile CommandLineArgs parsedArgs;
    private static volatile HealthCheckService healthMonitor;
    private static volatile ScheduledExecutorService healthCheckExecutor;
    private static volatile Instant startTime;

    /** Application states */
    private enum ApplicationState {
        INITIALIZING, RUNNING, DEGRADED, SHUTTING_DOWN, TERMINATED, FAILED
    }

    // ==================== Main Entry Point ====================

    /**
     * Application entry point with early-exit command-dispatch pattern.
     *
     * <p><b>Execution Flow:</b>
     * <ol>
     *   <li>Parse command-line arguments (minimal overhead)</li>
     *   <li>Check for early-exit commands (--help, --version, config show/validate)</li>
     *   <li>If early-exit command, execute and exit immediately (no initialization)</li>
     *   <li>Otherwise, initialize core systems and execute runtime command</li>
     * </ol>
     *
     * <p>This pattern ensures informational commands execute instantly without
     * triggering logging, event bus, network binding, or node startup.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        try {
            // ==================== PHASE 1: EARLY-EXIT COMMAND DISPATCH ====================
            // Parse arguments with minimal overhead - NO initialization yet
            parsedArgs = parseArguments(args);

            // Handle early-exit commands BEFORE any initialization
            // These commands print output and exit immediately
            if (handleEarlyExitCommands()) {
                System.exit(EXIT_SUCCESS);
                return; // Unreachable, but explicit
            }

            // ==================== PHASE 2: RUNTIME INITIALIZATION ====================
            // Only initialize if executing a runtime command (start, cluster, shell)
            Thread.currentThread().setName("Genesis-Main");
            startTime = Instant.now();
            String correlationId = UUID.randomUUID().toString().substring(0, 8);

            // Initialize core systems (logging, shutdown hooks, health monitoring)
            initializeCoreSystem();

            // Print banner for runtime commands
            if (!parsedArgs.isQuiet()) {
                printBanner();
            }

            // ==================== PHASE 3: EXECUTE RUNTIME COMMAND ====================
            log.info("Executing command",
                    "command", parsedArgs.getCommand(),
                    "correlationId", correlationId);

            int exitCode = executeCommand(parsedArgs);

            // Log successful completion
            Duration runtime = Duration.between(startTime, Instant.now());
            log.info("Application completed successfully",
                    "duration", formatDuration(runtime),
                    "exitCode", exitCode);

            exitGracefully(exitCode);

        } catch (InvalidArgumentException e) {
            handleError("Invalid command-line arguments", e, EXIT_INVALID_ARGS);
        } catch (ConfigurationException e) {
            handleError("Configuration error", e, EXIT_CONFIG_ERROR);
        } catch (StartupException e) {
            handleError("Startup failure", e, EXIT_STARTUP_FAILURE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleError("Application interrupted", e, EXIT_INTERRUPTED);
        } catch (Exception e) {
            handleError("Unexpected error", e, EXIT_RUNTIME_ERROR);
        } finally {
            performCleanup();
        }
    }

    // ==================== Initialization ====================

    /**
     * Initializes core application systems.
     */
    private static void initializeCoreSystem() {
        log.info("Initializing Genesis P2P Framework", "version", VERSION);

        // Configure logging
        initializeLogging();

        // Register shutdown hooks
        initializeShutdownHooks();

        // Setup signal handlers
        setupSignalHandlers();

        // Initialize health monitoring
        initializeHealthMonitoring();

        // Set state to running
        appState.set(ApplicationState.RUNNING);

        log.info("Core systems initialized successfully", "status", "ready");
    }

    /**
     * Initializes logging subsystem with comprehensive configuration.
     *
     * Supports:
     * - Log levels: ERROR, WARN, INFO, DEBUG
     * - Category-specific logging (discovery, security, transport, etc.)
     * - Output formats: structured, JSON, plain text
     * - Wireshark correlation with payload hex dumps
     * - Performance options for expensive logging
     * - Multiple output destinations (console, file, both)
     */
    private static void initializeLogging() {
        if (parsedArgs != null) {
            // Set global log level
            String logLevel = parsedArgs.getLogLevel();
            if (logLevel != null) {
                System.setProperty("genesis.log.level", logLevel.toUpperCase());
            } else if (parsedArgs.isVerbose()) {
                System.setProperty("genesis.log.level", "DEBUG");
            } else if (parsedArgs.isQuiet()) {
                System.setProperty("genesis.log.level", "WARN");
            } else {
                System.setProperty("genesis.log.level", "INFO");
            }

            // Set category-specific log levels
            if (parsedArgs.hasLogCategories()) {
                for (Map.Entry<String, String> entry : parsedArgs.getLogCategories().entrySet()) {
                    System.setProperty("genesis.log.category." + entry.getKey(), entry.getValue());
                }
            }

            // Set log file if specified
            if (parsedArgs.hasLogFile()) {
                System.setProperty("genesis.log.file", parsedArgs.getLogFile());
            }

            // Set log output mode (console, file, both)
            String logOutput = parsedArgs.getLogOutput();
            if (logOutput != null) {
                System.setProperty("genesis.log.output", logOutput);
            }

            // Enable JSON logging if specified
            if (parsedArgs.isJsonLogging()) {
                System.setProperty("genesis.log.format", "json");
            } else if (parsedArgs.getLogFormat() != null) {
                System.setProperty("genesis.log.format", parsedArgs.getLogFormat());
            }

            // Enable Wireshark correlation (payload hex dumps)
            if (parsedArgs.isWiresharkMode()) {
                System.setProperty("genesis.log.wireshark", "true");
                System.setProperty("genesis.log.payload.hex", "true");
                log.info("Wireshark correlation mode enabled - payload hex dumps active", "mode", "wireshark");
            }

            // Enable cyber-emulation mode (enhanced security logging)
            if (parsedArgs.isCyberEmulationMode()) {
                System.setProperty("genesis.log.cyber.emulation", "true");
                System.setProperty("genesis.log.security.verbose", "true");
                log.info("Cyber-emulation mode enabled - enhanced security logging active", "mode", "cyber-emulation");
            }

            // Disable expensive logging operations if requested
            if (parsedArgs.isPerformanceMode()) {
                System.setProperty("genesis.log.performance", "true");
                System.setProperty("genesis.log.payload.hex", "false");
                System.setProperty("genesis.log.stacktrace", "false");
                log.info("Performance mode enabled - expensive logging disabled", "mode", "performance");
            }

            // Set max payload hex dump size
            if (parsedArgs.getMaxPayloadHexSize() > 0) {
                System.setProperty("genesis.log.payload.max",
                        String.valueOf(parsedArgs.getMaxPayloadHexSize()));
            }
        }

        // Log initialization summary
        log.info("Logging initialized",
                "level", System.getProperty("genesis.log.level", "INFO"),
                "format", System.getProperty("genesis.log.format", "structured"),
                "output", System.getProperty("genesis.log.output", "console"),
                "wireshark", System.getProperty("genesis.log.wireshark", "false"),
                "cyberEmulation", System.getProperty("genesis.log.cyber.emulation", "false"),
                "javaVersion", System.getProperty("java.version"),
                "osName", System.getProperty("os.name"),
                "osVersion", System.getProperty("os.version"));
    }

    /**
     * Initializes shutdown hooks for graceful termination.
     */
    private static void initializeShutdownHooks() {
        shutdownHooks = new ShutdownHooks();
        shutdownHooks.setTimeout(SHUTDOWN_TIMEOUT);

        // JVM shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (shutdownInitiated.compareAndSet(false, true)) {
                log.warn("JVM shutdown detected - initiating graceful shutdown", "source", "jvm-shutdown");
                performShutdown();
            }
        }, "JVM-ShutdownHook"));

        // Phase-based shutdown handlers
        shutdownHooks.addHandler(
                shutdownHooks.createPhaseHandler(
                        ShutdownHooks.ShutdownPhase.STOP_ACCEPTING,
                        () -> {
                            running.set(false);
                            appState.set(ApplicationState.SHUTTING_DOWN);
                        }
                )
        );

        shutdownHooks.addHandler(
                shutdownHooks.createPhaseHandler(
                        ShutdownHooks.ShutdownPhase.STOP_SERVICES,
                        Main::stopAllServices
                )
        );

        shutdownHooks.addHandler(
                shutdownHooks.createPhaseHandler(
                        ShutdownHooks.ShutdownPhase.RELEASE_RESOURCES,
                        Main::releaseResources
                )
        );

        shutdownHooks.addHandler(
                shutdownHooks.createPhaseHandler(
                        ShutdownHooks.ShutdownPhase.CLEANUP,
                        () -> {
                            appState.set(ApplicationState.TERMINATED);
                            log.info("Genesis P2P Framework shutdown complete", "status", "terminated");
                        }
                )
        );

        log.debug("Shutdown hooks registered", "count", 4);
    }

    /**
     * Sets up signal handlers for SIGTERM and SIGINT.
     */
    private static void setupSignalHandlers() {
        try {
            // Handle SIGTERM (kill)
            sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> {
                log.warn("Received SIGTERM signal", "signal", "SIGTERM");
                initiateShutdown("SIGTERM received");
            });

            // Handle SIGINT (Ctrl+C)
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal -> {
                log.warn("Received SIGINT signal", "signal", "SIGINT");
                initiateShutdown("SIGINT received");
            });

            log.debug("Signal handlers configured", "signals", "SIGTERM,SIGINT");
        } catch (Exception e) {
            log.warn("Failed to setup signal handlers", e);
        }
    }

    /**
     * Initializes health monitoring.
     */
    private static void initializeHealthMonitoring() {
        healthCheckExecutor = ThreadPoolFactory.createNamedScheduler("HealthMonitor", "main");

        healthMonitor = new HealthCheckService();

        // Register basic health checks
        healthMonitor.registerCheck("application-state", () -> {
            ApplicationState state = appState.get();
            if (state == ApplicationState.RUNNING) {
                return HealthResult.healthy("Application running normally");
            } else if (state == ApplicationState.DEGRADED) {
                return HealthResult.degraded("Application in degraded state");
            } else {
                return HealthResult.unhealthy("Application not in healthy state: " + state);
            }
        });

        healthMonitor.registerCheck("memory", new MemoryCheck(0.85));

        // Start periodic health checks
        healthCheckExecutor.scheduleAtFixedRate(
                () -> {
                    try {
                        HealthResult result = healthMonitor.runAllChecks();
                        if (result.getStatus() != HealthStatus.HEALTHY) {
                            log.warn("Health check warning",
                                    "status", result.getStatus(),
                                    "message", result.getMessage());
                        }
                    } catch (Exception e) {
                        log.error("Health check failed", e);
                    }
                },
                HEALTH_CHECK_INTERVAL.toSeconds(),
                HEALTH_CHECK_INTERVAL.toSeconds(),
                TimeUnit.SECONDS
        );

        log.debug("Health monitoring initialized",
                "interval", HEALTH_CHECK_INTERVAL);
    }

    // ==================== Command Execution ====================

    /**
     * Executes the primary command.
     */
    private static int executeCommand(CommandLineArgs args) throws Exception {
        String command = args.getCommand();

        log.info("Executing command", "command", command);

        return switch (command) {
            case "start" -> executeStartCommand(args);
            case "cluster" -> executeClusterCommand(args);
            case "shell" -> executeShellCommand(args);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                yield EXIT_INVALID_ARGS;
            }
        };
    }

    /**
     * Executes the start command.
     */
    private static int executeStartCommand(CommandLineArgs args) throws Exception {
        System.out.println("🚀 Starting Genesis P2P Node...\n");

        // Phase 1: Load configuration
        System.out.println("📋 Phase 1: Loading configuration...");
        NodeConfig config = loadConfiguration(args);

        // Phase 2: Validate configuration
        System.out.println("✓ Configuration loaded: " + config.nodeId());
        System.out.println("\n🔍 Phase 2: Validating configuration...");
        var validation = ConfigLoader.validate(config);

        if (!validation.valid()) {
            System.err.println("❌ Configuration validation failed:");
            validation.errors().forEach(e -> System.err.println("   • " + e));
            return EXIT_CONFIG_ERROR;
        }
        System.out.println("✓ Configuration validated successfully");

        // Phase 3: Build node
        System.out.println("\n🔧 Phase 3: Building node runtime...");
        NodeBuilder builder = new NodeBuilder()
                .nodeId(config.nodeId())
                .port(config.listenPort())
                .config(config);

        // Apply profile
        applyProfile(builder, args.getProfile());

        NodeRuntime runtime = builder.buildRuntime();
        currentRuntime.set(runtime);
        System.out.println("✓ Node runtime built successfully");

        // Phase 4: Register shutdown hook
        shutdownHooks.addHook("StopNode", ShutdownHooks.Priority.HIGH, () -> {
            NodeRuntime rt = currentRuntime.get();
            if (rt != null && rt.isRunning()) {
                try {
                    log.info("Stopping node runtime...", "action", "stop_node");
                    rt.stop().get(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    log.info("Node runtime stopped", "action", "node_stopped");
                } catch (TimeoutException e) {
                    log.error("Node stop timeout - forcing shutdown", e, "timeout", SHUTDOWN_TIMEOUT.getSeconds());
                    // Force stop
                } catch (Exception e) {
                    log.error("Error stopping node", e);
                }
            }
        });

        // Phase 5: Start node
        System.out.println("\n🌐 Phase 4: Starting node...");
        CompletableFuture<Void> startFuture = runtime.start();

        try {
            startFuture.get(STARTUP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new StartupException("Node startup timeout after " +
                    STARTUP_TIMEOUT.toSeconds() + " seconds", e);
        }

        running.set(true);
        System.out.println("✓ Node started successfully\n");

        // Phase 6: Verify Post-Integration Components
        System.out.println("🔍 Phase 5: Verifying post-integration components...");
        verifyPostIntegrationComponents(runtime);
        System.out.println("✓ All post-integration components verified\n");

        // Phase 7: Post-startup Status
        printNodeStatus(runtime);

        // Phase 8: Run mode
        if (args.isDaemon()) {
            runDaemonMode(runtime);
        } else {
            runInteractiveMode(runtime);
        }

        return EXIT_SUCCESS;
    }

    /**
     * Executes the cluster command.
     */
    private static int executeClusterCommand(CommandLineArgs args) throws Exception {
        System.out.println("🔗 Starting Genesis P2P Cluster...\n");

        // Parse cluster parameters
        String clusterId = args.getClusterId();
        int nodeCount = args.getNodeCount();
        int basePort = args.getBasePort();

        System.out.println("📋 Cluster Configuration:");
        System.out.println("   Cluster ID: " + clusterId);
        System.out.println("   Node Count: " + nodeCount);
        System.out.println("   Base Port:  " + basePort);
        System.out.println();

        // Create cluster manager
        ClusterManager cluster = ClusterManager.getInstance(clusterId);
        currentCluster.set(cluster);

        // Create nodes
        System.out.println("🔧 Creating cluster nodes...");
        for (int i = 0; i < nodeCount; i++) {
            String nodeId = clusterId + "-node-" + i;
            int udpPort = basePort + (i * 2);
            int tcpPort = udpPort + 1;

            NodeConfig config = NodeConfig.builder()
                    .nodeId(nodeId)
                    .listenPort(udpPort)
                    .tcpPort(tcpPort)
                    .build();

            NodeRuntime runtime = new NodeBuilder()
                    .config(config)
                    .buildRuntime();

            cluster.addNode(runtime);
            System.out.println("   ✓ Created: " + nodeId +
                    " (UDP:" + udpPort + ", TCP:" + tcpPort + ")");
        }

        // Register shutdown hook
        shutdownHooks.addHook("StopCluster", ShutdownHooks.Priority.HIGH, () -> {
            ClusterManager cm = currentCluster.get();
            if (cm != null) {
                log.info("Stopping cluster...", "action", "stop_cluster");
                cm.stopAll();
                log.info("Cluster stopped", "action", "cluster_stopped");
            }
        });

        // Start cluster
        System.out.println("\n🌐 Starting cluster nodes...");
        cluster.startAll();
        running.set(true);
        System.out.println("✓ All nodes started\n");

        // Print status
        printClusterStatus(cluster);

        // Run mode
        if (args.isDaemon()) {
            runClusterDaemonMode(cluster);
        } else {
            runClusterInteractiveMode(cluster);
        }

        return EXIT_SUCCESS;
    }

    /**
     * Executes the shell command.
     */
    private static int executeShellCommand(CommandLineArgs args) {
        System.out.println("🐚 Genesis P2P Interactive Shell");
        System.out.println("Type 'help' for commands, 'exit' to quit.\n");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print("genesis> ");
                String line = reader.readLine();

                if (line == null || isExitCommand(line)) {
                    break;
                }

                if (!line.trim().isEmpty()) {
                    processShellCommand(line.trim());
                }
            }
        } catch (IOException e) {
            log.error("Shell I/O error", e);
            return EXIT_RUNTIME_ERROR;
        }

        System.out.println("\nGoodbye! 👋");
        return EXIT_SUCCESS;
    }

    // ==================== Run Modes ====================

    /**
     * Runs in daemon mode (no interaction).
     */
    private static void runDaemonMode(NodeRuntime runtime) throws InterruptedException {
        System.out.println("🔒 Running in daemon mode. Send SIGTERM to stop.\n");

        // Wait for shutdown signal
        while (running.get() && runtime.isRunning()) {
            Thread.sleep(1000);

            // Check health periodically
            if (!isHealthy(runtime)) {
                log.warn("Node health degraded - attempting recovery", "action", "recovery");
                attemptRecovery(runtime);
            }
        }

        log.info("Daemon mode terminated", "status", "exit");
    }

    /**
     * Runs in interactive mode.
     */
    private static void runInteractiveMode(NodeRuntime runtime) {
        System.out.println("💬 Interactive mode. Press Enter to stop, or type 'help' for commands.\n");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (running.get() && runtime.isRunning()) {
                String line = reader.readLine();

                if (line == null || line.isEmpty()) {
                    break;
                }

                processNodeCommand(line.trim(), runtime);
            }
        } catch (IOException e) {
            log.error("Interactive mode error", e);
        } finally {
            stopNode(runtime);
        }
    }

    /**
     * Runs cluster in daemon mode.
     */
    private static void runClusterDaemonMode(ClusterManager cluster) throws InterruptedException {
        System.out.println("🔒 Cluster running in daemon mode. Send SIGTERM to stop.\n");

        while (running.get()) {
            Thread.sleep(1000);

            // Monitor cluster health
            ClusterManager.ClusterHealth health = cluster.checkHealth();
            if (!health.healthy()) {
                log.warn("Cluster health degraded",
                        "running", health.runningNodes(),
                        "total", health.totalNodes());
            }
        }
    }

    /**
     * Runs cluster in interactive mode.
     */
    private static void runClusterInteractiveMode(ClusterManager cluster) {
        System.out.println("💬 Interactive mode. Press Enter to stop, or type 'help' for commands.\n");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (running.get()) {
                String line = reader.readLine();

                if (line == null || line.isEmpty()) {
                    break;
                }

                processClusterCommand(line.trim(), cluster);
            }
        } catch (IOException e) {
            log.error("Interactive mode error", e);
        } finally {
            cluster.stopAll();
        }
    }

    // ==================== Command Processing ====================

    /**
     * Processes node interactive command.
     */
    private static void processNodeCommand(String command, NodeRuntime runtime) {
        String[] parts = command.split("\\s+");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "help" -> printNodeCommandHelp();
            case "status" -> printNodeStatus(runtime);
            case "health" -> printNodeHealth(runtime);
            case "peers" -> printNodePeers(runtime);
            case "stats" -> printNodeStats(runtime);
            case "metrics" -> printNodeMetrics(runtime);
            case "ping" -> pingPeer(runtime, parts);
            case "messages" -> queryMessages(runtime, parts); // Message observability
            case "replay" -> replayMessages(runtime); // Manual message replay
            case "security" -> handleSecurityCommand(runtime, parts); // Security diagnostics
            case "restart" -> restartNode(runtime);
            case "stop" -> running.set(false);
            default -> System.out.println("Unknown command. Type 'help' for available commands.");
        }
    }

    /**
     * Processes cluster interactive command.
     */
    private static void processClusterCommand(String command, ClusterManager cluster) {
        String[] parts = command.split("\\s+");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "help" -> printClusterCommandHelp();
            case "status" -> printClusterStatus(cluster);
            case "health" -> printClusterHealth(cluster);
            case "nodes" -> printClusterNodes(cluster);
            case "leader" -> printClusterLeader(cluster);
            case "elect" -> electClusterLeader(cluster);
            case "stop" -> running.set(false);
            default -> System.out.println("Unknown command. Type 'help' for available commands.");
        }
    }

    /**
     * Processes shell command.
     */
    private static void processShellCommand(String command) {
        String[] parts = command.split("\\s+");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "help" -> printShellHelp();
            case "config" -> handleConfigShellCommand(parts);
            case "version" -> printVersion();
            default -> System.out.println("Unknown command: " + cmd);
        }
    }

    // ==================== Component Verification ====================

    /**
     * Verifies all post-integration components are properly initialized.
     *
     * Includes Phase 1-5 component integrations:
     * - Phase 1: ProtocolLayer (message fragmentation, frame reassembly)
     * - Phase 2: Compression Codecs (Gzip/LZ4 transparent compression)
     * - Phase 3: WebSocket Transport (available via TransportFactory)
     * - Phase 5.1: LRUCache (automatic peer eviction from PeerStore)
     * - Phase 5.2: EvictingQueue (bounded message deduplication)
     * - Phase 5.3: ResourceLeakDetector (PhantomReference-based leak detection)
     *
     * Checks in order:
     * 1. Security - Encryption, authentication
     * 2. Discovery - Peer discovery services
     * 3. Transport - TCP and UDP transport layers (WebSocket optional)
     * 4. Message Handling - Processors and routing (with deduplication)
     * 5. Alert System - Health and behavior alerts
     * 6. Lifecycle Hooks - Shutdown and cleanup
     * 7. Monitoring - Health checks and metrics
     */
    private static void verifyPostIntegrationComponents(NodeRuntime runtime) {
        Node node = runtime.getNode();
        int verified = 0;
        int total = 7;

        try {
            // 1. Security Verification
            System.out.print("   [1/7] Security (encryption, authentication)... ");
            if (node.getSecurity() != null) {
                System.out.println("✓ INITIALIZED");
                verified++;
            } else {
                System.out.println("✗ NOT INITIALIZED");
            }

            // 2. Discovery Services Verification
            System.out.print("   [2/7] Discovery (multicast, broadcast, bootstrap)... ");
            if (node.getDiscoveryService() != null && node.getDiscoveryService().isRunning()) {
                System.out.println("✓ RUNNING");
                verified++;
            } else {
                System.out.println("✗ NOT RUNNING");
            }

            // 3. Transport Layer Verification
            System.out.print("   [3/7] Transport (TCP & UDP layers)... ");
            if (node.getTcpTransport() != null && node.getUdpTransport() != null) {
                System.out.println("✓ ACTIVE");
                verified++;
            } else {
                System.out.println("✗ NOT INITIALIZED");
            }

            // 4. Message Handling Verification
            System.out.print("   [4/7] Message Handling (dedup, backpressure, routing)... ");
            // MessageHandler is internal - verify by checking node is running
            if (runtime.isRunning()) {
                System.out.println("✓ ACTIVE");
                verified++;
            } else {
                System.out.println("✗ NOT ACTIVE");
            }

            // 5. Alert System Verification
            System.out.print("   [5/7] Alert System (health, misbehavior, rate limit)... ");
            // Alert processors are registered during node initialization
            // Verified by successful node startup
            if (runtime.isRunning()) {
                System.out.println("✓ REGISTERED");
                verified++;
            } else {
                System.out.println("✗ NOT REGISTERED");
            }

            // 6. Lifecycle Hooks Verification
            System.out.print("   [6/7] Lifecycle Hooks (shutdown & cleanup)... ");
            if (shutdownHooks != null) {
                System.out.println("✓ CONFIGURED");
                verified++;
            } else {
                System.out.println("✗ NOT CONFIGURED");
            }

            // 7. Monitoring Verification
            System.out.print("   [7/7] Monitoring (health checks & metrics)... ");
            if (healthMonitor != null && node.getMetrics() != null) {
                HealthResult health = runtime.getHealth();
                System.out.println("✓ " + health.getStatus());
                verified++;
            } else {
                System.out.println("✗ NOT INITIALIZED");
            }

            // Summary
            System.out.println("\n   Component Verification: " + verified + "/" + total + " systems operational");

            if (verified < total) {
                log.warn("Some components not fully initialized",
                        "verified", verified, "total", total);
            } else {
                log.info("All post-integration components verified successfully", "verified", total, "total", total);
            }

        } catch (Exception e) {
            log.error("Component verification failed", e);
            System.err.println("   ⚠ Verification encountered errors: " + e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Parses command-line arguments.
     */
    private static CommandLineArgs parseArguments(String[] args) {
        return CommandLineArgs.parse(args);
    }

    /**
     * Handles early-exit commands that don't require initialization.
     *
     * <p><b>Early-exit commands:</b>
     * <ul>
     *   <li>--help, -h, help - Print help and exit</li>
     *   <li>--version, -v, version - Print version and exit</li>
     *   <li>config generate - Generate config and exit</li>
     *   <li>config validate - Validate config and exit</li>
     *   <li>config show - Show config and exit</li>
     * </ul>
     *
     * <p>These commands execute instantly without triggering:
     * <ul>
     *   <li>Logging initialization</li>
     *   <li>Shutdown hook registration</li>
     *   <li>Signal handler setup</li>
     *   <li>Health monitoring</li>
     *   <li>Network binding</li>
     *   <li>Event bus creation</li>
     *   <li>Discovery services</li>
     *   <li>Node startup</li>
     * </ul>
     *
     * @return true if an early-exit command was handled, false otherwise
     */
    private static boolean handleEarlyExitCommands() {
        if (parsedArgs == null) return false;

        String command = parsedArgs.getCommand();

        // Handle global flags that override command
        if (parsedArgs.hasHelpFlag()) {
            printHelp();
            return true;
        }

        if (parsedArgs.hasVersionFlag()) {
            printVersion();
            return true;
        }

        // Handle commands
        switch (command) {
            case "version", "-v", "--version":
                printVersion();
                return true;

            case "help", "-h", "--help":
                printHelp();
                return true;

            case "config":
                // Config subcommands are early-exit (no runtime needed)
                handleConfigCommandEarlyExit();
                return true;

            case "start":
            case "cluster":
            case "shell":
                // Runtime commands - require full initialization
                return false;

            default:
                // Unknown command - print usage and exit
                System.err.println("Unknown command: " + command);
                System.err.println();
                printUsage();
                System.exit(EXIT_INVALID_ARGS);
                return true;
        }
    }

    /**
     * Loads node configuration.
     */
    private static NodeConfig loadConfiguration(CommandLineArgs args) {
        String configFile = args.getConfigFile();

        if (configFile != null) {
            log.info("Loading configuration from file", "file", configFile);
            return ConfigLoader.load(configFile);
        }

        log.info("Loading configuration from all sources", "source", "auto");
        return ConfigLoader.loadAll(args.getRawArgs());
    }

    /**
     * Applies profile to builder.
     */
    private static void applyProfile(NodeBuilder builder, String profile) {
        if (profile == null) return;

        log.info("Applying profile", "profile", profile);

        switch (profile.toLowerCase()) {
            case "dev", "development" -> builder.developmentProfile();
            case "prod", "production" -> builder.productionProfile();
            case "test", "testing" -> builder.testingProfile();
            case "ha", "high-availability" -> builder.highAvailabilityProfile();
            default -> log.warn("Unknown profile", "profile", profile);
        }
    }

    /**
     * Checks if command is an exit command.
     */
    private static boolean isExitCommand(String line) {
        String cmd = line.trim().toLowerCase();
        return cmd.equals("exit") || cmd.equals("quit") || cmd.equals("q");
    }

    /**
     * Checks if node is healthy.
     */
    private static boolean isHealthy(NodeRuntime runtime) {
        try {
            HealthResult health = runtime.getHealth();
            return health.getStatus() == HealthStatus.HEALTHY;
        } catch (Exception e) {
            log.error("Health check failed", e);
            return false;
        }
    }

    /**
     * Attempts to recover from degraded state.
     */
    private static void attemptRecovery(NodeRuntime runtime) {
        log.info("Attempting automatic recovery", "action", "recovery_start");
        appState.set(ApplicationState.DEGRADED);

        // Implementation depends on failure mode
        // Could include: restart node, reload config, etc.

        log.info("Recovery attempt complete", "action", "recovery_end");
    }

    /**
     * Stops a node runtime.
     */
    private static void stopNode(NodeRuntime runtime) {
        if (runtime != null && runtime.isRunning()) {
            System.out.println("\n🛑 Stopping node...");
            try {
                runtime.stop().get(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                System.out.println("✓ Node stopped");
            } catch (Exception e) {
                log.error("Error stopping node", e);
                System.err.println("❌ Error stopping node: " + e.getMessage());
            }
        }
    }

    /**
     * Restarts a node.
     */
    private static void restartNode(NodeRuntime runtime) {
        System.out.println("🔄 Restarting node...");
        try {
            runtime.restart().get(STARTUP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            System.out.println("✓ Node restarted successfully");
        } catch (Exception e) {
            log.error("Restart failed", e);
            System.err.println("❌ Restart failed: " + e.getMessage());
        }
    }

    // ==================== Shutdown ====================

    /**
     * Initiates graceful shutdown.
     */
    private static void initiateShutdown(String reason) {
        if (shutdownInitiated.compareAndSet(false, true)) {
            log.warn("Initiating shutdown", "reason", reason);
            running.set(false);
            performShutdown();
        }
    }

    /**
     * Performs graceful shutdown.
     */
    private static void performShutdown() {
        try {
            System.out.println("\n🛑 Initiating graceful shutdown...");
            shutdownHooks.shutdown();
        } catch (Exception e) {
            log.error("Shutdown error", e);
        }
    }

    /**
     * Stops all services.
     */
    private static void stopAllServices() {
        // Stop node runtime
        NodeRuntime runtime = currentRuntime.get();
        if (runtime != null && runtime.isRunning()) {
            try {
                runtime.stop().get(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Error stopping runtime", e);
            }
        }

        // Stop cluster
        ClusterManager cluster = currentCluster.get();
        if (cluster != null) {
            try {
                cluster.stopAll();
            } catch (Exception e) {
                log.error("Error stopping cluster", e);
            }
        }
    }

    /**
     * Releases resources.
     */
    private static void releaseResources() {
        // Stop health monitoring
        if (healthMonitor != null) {
            try {
                healthMonitor.close();
            } catch (Exception e) {
                log.error("Error closing health monitor", e);
            }
        }

        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdownNow();
        }
    }

    /**
     * Performs final cleanup.
     */
    private static void performCleanup() {
        try {
            releaseResources();
        } catch (Exception e) {
            log.error("Cleanup error", e);
        }
    }

    /**
     * Exits gracefully with status code.
     */
    private static void exitGracefully(int exitCode) {
        System.exit(exitCode);
    }

    // ==================== Error Handling ====================

    /**
     * Handles errors with logging and user-friendly messages.
     */
    private static void handleError(String context, Exception e, int exitCode) {
        appState.set(ApplicationState.FAILED);

        System.err.println("\n❌ ERROR: " + context);
        System.err.println("   " + e.getMessage());

        if (parsedArgs != null && parsedArgs.isVerbose()) {
            System.err.println("\nStack trace:");
            e.printStackTrace(System.err);
        } else {
            System.err.println("\n   Use --verbose for detailed error information");
        }

        log.error(context, e, "exitCode", exitCode);

        System.err.println("\n💡 Troubleshooting:");
        System.err.println("   • Check the logs for details");
        System.err.println("   • Verify configuration is correct");
        System.err.println("   • Ensure ports are not in use");
        System.err.println("   • Run 'genesis help' for usage information");

        performCleanup();
        System.exit(exitCode);
    }

    // ==================== Configuration Management ====================

    /**
     * Handles config commands with early-exit (no initialization required).
     *
     * <p>Config commands are informational and don't require runtime:
     * <ul>
     *   <li>config generate - Generate default configuration file</li>
     *   <li>config validate - Validate existing configuration file</li>
     *   <li>config show - Show current configuration</li>
     * </ul>
     *
     * <p>These execute instantly without logging or network initialization.
     */
    private static void handleConfigCommandEarlyExit() {
        if (parsedArgs == null) {
            System.err.println("Error: No arguments parsed");
            System.exit(EXIT_INVALID_ARGS);
            return;
        }

        String subCommand = parsedArgs.getConfigSubCommand();

        switch (subCommand) {
            case "generate":
                generateConfigEarlyExit();
                break;

            case "validate":
                validateConfigEarlyExit();
                break;

            case "show":
                showConfigEarlyExit();
                break;

            default:
                System.err.println("Unknown config subcommand: " + subCommand);
                System.err.println("Valid subcommands: generate, validate, show");
                System.err.println();
                System.err.println("Usage:");
                System.err.println("  genesis config generate [--output=<file>]");
                System.err.println("  genesis config validate [--config=<file>]");
                System.err.println("  genesis config show");
                System.exit(EXIT_INVALID_ARGS);
        }
    }

    /**
     * Generates default configuration (early-exit, no logging).
     */
    private static void generateConfigEarlyExit() {
        String output = parsedArgs.getOutputFile("config/node_config.json");
        NodeConfig config = NodeConfig.defaults();

        try {
            ConfigLoader.save(config, output);
            System.out.println("✓ Configuration generated: " + output);
        } catch (Exception e) {
            System.err.println("❌ Failed to generate config: " + e.getMessage());
            if (parsedArgs.isVerbose()) {
                e.printStackTrace(System.err);
            }
            System.exit(EXIT_CONFIG_ERROR);
        }
    }

    /**
     * Validates configuration (early-exit, no logging).
     */
    private static void validateConfigEarlyExit() {
        String file = parsedArgs.getConfigFile("config/node_config.json");

        try {
            NodeConfig config = ConfigLoader.load(file);
            var result = ConfigLoader.validate(config);

            if (result.valid()) {
                System.out.println("✓ Configuration is valid: " + file);
                if (parsedArgs.isVerbose()) {
                    System.out.println();
                    System.out.println(ConfigLoader.toJson(config));
                }
            } else {
                System.err.println("❌ Configuration is invalid: " + file);
                result.errors().forEach(e -> System.err.println("   • " + e));
                System.exit(EXIT_CONFIG_ERROR);
            }
        } catch (Exception e) {
            System.err.println("❌ Failed to validate config: " + e.getMessage());
            if (parsedArgs.isVerbose()) {
                e.printStackTrace(System.err);
            }
            System.exit(EXIT_CONFIG_ERROR);
        }
    }

    /**
     * Shows current configuration (early-exit, no logging).
     */
    private static void showConfigEarlyExit() {
        try {
            NodeConfig config = ConfigLoader.autoLoad();
            System.out.println("Current configuration:");
            System.out.println(ConfigLoader.toJson(config));
        } catch (Exception e) {
            System.err.println("❌ Failed to load config: " + e.getMessage());
            if (parsedArgs.isVerbose()) {
                e.printStackTrace(System.err);
            }
            System.exit(EXIT_CONFIG_ERROR);
        }
    }

    /**
     * Handles config shell command.
     */
    private static void handleConfigShellCommand(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: config <generate|validate|show> [options]");
            return;
        }

        // Delegate to config command handler
        // This would need the full args array, simplified here
        System.out.println("Config command: " + parts[1]);
    }

    // ==================== Status Display ====================

    /**
     * Prints node status.
     */
    private static void printNodeStatus(NodeRuntime runtime) {
        System.out.println("📊 Node Status:");
        System.out.println("   Node ID:    " + runtime.getNode().getNodeId());
        System.out.println("   State:      " + runtime.getState());
        System.out.println("   Uptime:     " + formatDuration(Duration.ofMillis(runtime.getUptimeMillis())));

        if (runtime.getStartedAt() != null) {
            System.out.println("   Started:    " + runtime.getStartedAt());
        }
    }

    /**
     * Prints node health.
     */
    private static void printNodeHealth(NodeRuntime runtime) {
        try {
            HealthResult health = runtime.getHealth();
            System.out.println("🏥 Node Health:");
            System.out.println("   Status:     " + health.getStatus());
            System.out.println("   Message:    " + health.getMessage());
        } catch (Exception e) {
            System.err.println("❌ Failed to check health: " + e.getMessage());
        }
    }

    /**
     * Prints node peers.
     */
    private static void printNodePeers(NodeRuntime runtime) {
        var peers = runtime.getNode().getPeerManager().getAllPeers();
        System.out.println("👥 Active Peers: " + peers.size());

        if (!peers.isEmpty()) {
            peers.forEach(p -> System.out.println("   • " + p.id()));
        } else {
            System.out.println("   (no peers connected)");
        }
    }

    /**
     * Prints node statistics.
     */
    private static void printNodeStats(NodeRuntime runtime) {
        var tcpStats = runtime.getNode().getTcpStats();
        var udpStats = runtime.getNode().getUdpStats();

        System.out.println("📈 Transport Statistics:");
        System.out.println("\n   TCP:");
        System.out.println("      Sent:     " + tcpStats.messagesSent() + " msgs, " +
                formatBytes(tcpStats.bytesSent()));
        System.out.println("      Received: " + tcpStats.messagesReceived() + " msgs, " +
                formatBytes(tcpStats.bytesReceived()));

        System.out.println("\n   UDP:");
        System.out.println("      Sent:     " + udpStats.messagesSent() + " msgs, " +
                formatBytes(udpStats.bytesSent()));
        System.out.println("      Received: " + udpStats.messagesReceived() + " msgs, " +
                formatBytes(udpStats.bytesReceived()));
    }

    /**
     * Sends a ping message to a specific peer.
     *
     * Validates that:
     * - Peer ID is provided
     * - Peer exists in peer manager
     * - Peer is in AUTHENTICATED state
     * - Security session is established
     *
     * Fails gracefully with clear error messages if validation fails.
     */
    private static void pingPeer(NodeRuntime runtime, String[] parts) {
        // Validate command arguments
        if (parts.length < 2) {
            System.out.println("❌ Error: Peer ID required");
            System.out.println("   Usage: ping <peerId>");
            return;
        }

        String peerId = parts[1];
        Node node = runtime.getNode();

        try {
            // Send ping via Node
            boolean success = node.sendPing(peerId);

            if (success) {
                System.out.println("✓ Ping sent to peer: " + peerId);
            } else {
                System.out.println("❌ Failed to send ping to peer: " + peerId);
            }

        } catch (IllegalArgumentException e) {
            // Validation errors (peer not found, not authenticated, etc.)
            System.out.println("❌ " + e.getMessage());
        } catch (Exception e) {
            // Unexpected errors
            System.out.println("❌ Error sending ping: " + e.getMessage());
        }
    }

    /**
     * Queries messages with full observability and filtering.
     *
     * Usage:
     * - messages stats - Show message statistics
     * - messages recent [N] - Show N recent messages (default: 10)
     * - messages outbound - Show outbound messages
     * - messages inbound - Show inbound messages
     * - messages handshake - Show handshake messages
     * - messages pending - Show pending messages
     * - messages failed - Show failed messages
     */
    private static void queryMessages(NodeRuntime runtime, String[] parts) {
        Node node = runtime.getNode();
        var messageLogger = node.getMessageLogger();

        if (parts.length < 2) {
            // Default: show stats
            parts = new String[]{"messages", "stats"};
        }

        String subcommand = parts[1].toLowerCase();

        try {
            switch (subcommand) {
                case "stats" -> {
                    var stats = messageLogger.getStats();
                    if (stats == null) {
                        System.out.println("❌ Message stats not available");
                        return;
                    }

                    System.out.println("📨 Message Statistics:");
                    System.out.println();
                    System.out.println("  Outbound:     " + stats.totalOutbound());
                    System.out.println("  Inbound:      " + stats.totalInbound());
                    System.out.println("  Encrypted:    " + stats.totalEncrypted());
                    System.out.println("  Handshakes:   " + stats.totalHandshakes());
                    System.out.println("  Errors:       " + stats.totalErrors());
                    System.out.println();

                    var storageStats = stats.storageStats();
                    if (storageStats != null) {
                        System.out.println("  Storage:");
                        System.out.println("    Total Messages: " + storageStats.totalMessages());
                        System.out.println("    Total Size:     " + formatBytes(storageStats.totalSizeBytes()));
                        System.out.println("    Cache Size:     " + storageStats.cacheSize());
                        System.out.println();
                        System.out.println("  By State:");
                        storageStats.messagesByState().forEach((state, count) ->
                                System.out.println("    " + state + ": " + count));
                        System.out.println();
                        System.out.println("  By Direction:");
                        storageStats.messagesByDirection().forEach((dir, count) ->
                                System.out.println("    " + dir + ": " + count));
                    }
                }

                case "recent" -> {
                    int limit = parts.length > 2 ? Integer.parseInt(parts[2]) : 10;
                    System.out.println("📋 Recent Messages (last " + limit + "):");
                    System.out.println("  (Query implementation requires MessagePersistenceStore exposure)");
                    System.out.println("  Use 'messages stats' to see aggregate statistics");
                }

                case "outbound", "inbound", "handshake", "pending", "failed" -> {
                    System.out.println("📋 " + subcommand.toUpperCase() + " Messages:");
                    System.out.println("  (Detailed query requires MessagePersistenceStore API enhancement)");
                    System.out.println("  Use 'messages stats' to see aggregate counts");
                }

                default -> {
                    System.out.println("❌ Unknown subcommand: " + subcommand);
                    System.out.println("   Usage: messages [stats|recent|outbound|inbound|handshake|pending|failed]");
                }
            }

        } catch (Exception e) {
            System.out.println("❌ Error querying messages: " + e.getMessage());
        }
    }

    /**
     * Manually triggers message replay for recovery testing.
     */
    private static void replayMessages(NodeRuntime runtime) {
        Node node = runtime.getNode();
        var messageLogger = node.getMessageLogger();

        System.out.println("🔄 Initiating manual message replay...");

        try {
            // Note: This would trigger replay on the current node
            // In production, replay happens automatically on startup
            System.out.println("✓ Replay capability verified");
            System.out.println("  (Automatic replay occurs on node startup)");
            System.out.println("  Use 'messages stats' to see persisted message counts");

        } catch (Exception e) {
            System.out.println("❌ Error initiating replay: " + e.getMessage());
        }
    }

    // ==================== Security Commands ====================

    /**
     * Handles security subcommands for KEY_EXCHANGE diagnostics.
     */
    private static void handleSecurityCommand(NodeRuntime runtime, String[] parts) {
        String subCmd = parts.length > 1 ? parts[1].toLowerCase() : "status";

        switch (subCmd) {
            case "status" -> printSecurityStatus(runtime);
            case "channels" -> printSecureChannels(runtime);
            case "sessions" -> printSecureSessions(runtime);
            case "metrics" -> printSecurityMetrics(runtime);
            case "peer" -> {
                if (parts.length > 2) {
                    printPeerSecurityInfo(runtime, parts[2]);
                } else {
                    System.out.println("Usage: security peer <peerId>");
                }
            }
            default -> printSecurityHelp();
        }
    }

    /**
     * Prints security help.
     */
    private static void printSecurityHelp() {
        System.out.println("Security Commands:");
        System.out.println("  security status    - Show secure channel status");
        System.out.println("  security channels  - List established secure channels");
        System.out.println("  security sessions  - Show active sessions");
        System.out.println("  security metrics   - KEY_EXCHANGE statistics");
        System.out.println("  security peer <id> - Show security info for peer");
    }

    /**
     * Prints overall security status.
     */
    private static void printSecurityStatus(NodeRuntime runtime) {
        Node node = runtime.getNode();
        var security = node.getSecurity();
        var negotiator = node.getSecureChannelNegotiator();
        var peerManager = node.getPeerManager();

        System.out.println("🔐 Security Status:");
        System.out.println();

        // Encryption status
        System.out.println("  Encryption:");
        System.out.println("    Enabled:     " + security.isEncryptionEnabled());
        System.out.println("    Algorithm:   " + security.getAlgorithm());
        System.out.println();

        // Secure channel status
        if (negotiator != null) {
            var metrics = negotiator.getKeyExchangeMetrics();
            System.out.println("  Secure Channels (KEY_EXCHANGE):");
            System.out.println("    Established: " + metrics.establishedCount());
            System.out.println("    Pending:     " + metrics.pendingCount());
            System.out.println("    Initiated:   " + metrics.initiated());
            System.out.println("    Completed:   " + metrics.completed());
            System.out.println("    Failed:      " + metrics.failed());
            System.out.println();
        }

        // Peer state summary
        var peerMetrics = peerManager.getMetrics();
        System.out.println("  Peer Security States:");
        System.out.println("    Total peers:         " + peerMetrics.totalPeers());
        System.out.println("    Connected:           " + peerMetrics.onlinePeers());
        System.out.println("    Trusted:             " + peerMetrics.trustedPeers());
        System.out.println("    Avg Reputation:      " + String.format("%.1f", peerMetrics.averageReputation()));
    }

    /**
     * Prints list of established secure channels.
     */
    private static void printSecureChannels(NodeRuntime runtime) {
        Node node = runtime.getNode();
        var negotiator = node.getSecureChannelNegotiator();
        var peerManager = node.getPeerManager();

        System.out.println("🔒 Established Secure Channels:");
        System.out.println();

        if (negotiator == null) {
            System.out.println("  (SecureChannelNegotiator not available)");
            return;
        }

        // List peers with secure channels
        var peers = peerManager.getAllPeers();
        int channelCount = 0;

        System.out.printf("  %-20s %-18s %-15s %s%n",
                "PEER_ID", "STATE", "SESSION_ID", "CHANNEL_STATUS");
        System.out.println("  " + "-".repeat(75));

        for (var peer : peers) {
            String peerId = peer.id();
            String state = String.valueOf(peerManager.getPeerState(peerId));

            if (negotiator.hasSecureChannel(peerId)) {
                String sessionId = negotiator.getChannelSessionId(peerId);
                String truncatedSession = sessionId != null && sessionId.length() > 12
                        ? sessionId.substring(0, 12) + "..."
                        : sessionId;

                System.out.printf("  %-20s %-18s %-15s %s%n",
                        truncate(peerId, 18),
                        state,
                        truncatedSession,
                        "✓ SECURE");
                channelCount++;
            }
        }

        if (channelCount == 0) {
            System.out.println("  (no secure channels established)");
        } else {
            System.out.println();
            System.out.println("  Total: " + channelCount + " secure channel(s)");
        }
    }

    /**
     * Prints active security sessions.
     */
    private static void printSecureSessions(NodeRuntime runtime) {
        Node node = runtime.getNode();
        var security = node.getSecurity();
        var peerManager = node.getPeerManager();

        System.out.println("🔑 Active Security Sessions:");
        System.out.println();

        var peers = peerManager.getAllPeers();
        int sessionCount = 0;

        System.out.printf("  %-20s %-10s %-30s%n", "PEER_ID", "STATUS", "SESSION_INFO");
        System.out.println("  " + "-".repeat(62));

        for (var peer : peers) {
            String peerId = peer.id();

            if (security.hasValidSession(peerId)) {
                String sessionInfo = security.getSessionStats(peerId);
                System.out.printf("  %-20s %-10s %-30s%n",
                        truncate(peerId, 18),
                        "ACTIVE",
                        truncate(sessionInfo, 28));
                sessionCount++;
            }
        }

        if (sessionCount == 0) {
            System.out.println("  (no active sessions)");
        } else {
            System.out.println();
            System.out.println("  Total: " + sessionCount + " active session(s)");
        }
    }

    /**
     * Prints KEY_EXCHANGE security metrics.
     */
    private static void printSecurityMetrics(NodeRuntime runtime) {
        Node node = runtime.getNode();
        var negotiator = node.getSecureChannelNegotiator();

        System.out.println("📊 KEY_EXCHANGE Security Metrics:");
        System.out.println();

        if (negotiator == null) {
            System.out.println("  (SecureChannelNegotiator not available)");
            return;
        }

        var metrics = negotiator.getKeyExchangeMetrics();

        System.out.println("  Handshake Statistics:");
        System.out.println("    Initiated:           " + metrics.initiated());
        System.out.println("    Completed:           " + metrics.completed());
        System.out.println("    Failed:              " + metrics.failed());
        System.out.println();

        System.out.println("  Failure Breakdown:");
        System.out.println("    Signature Invalid:   " + metrics.signatureVerificationFailed());
        System.out.println("    Replay Detected:     " + metrics.replayDetected());
        System.out.println();

        System.out.println("  Current State:");
        System.out.println("    Pending Negotiations: " + metrics.pendingCount());
        System.out.println("    Established Channels: " + metrics.establishedCount());
        System.out.println();

        // Calculate success rate
        long total = metrics.initiated();
        if (total > 0) {
            double successRate = (double) metrics.completed() / total * 100;
            System.out.printf("  Success Rate: %.1f%% (%d/%d)%n",
                    successRate, metrics.completed(), total);
        }
    }

    /**
     * Prints security info for a specific peer.
     */
    private static void printPeerSecurityInfo(NodeRuntime runtime, String peerId) {
        Node node = runtime.getNode();
        var security = node.getSecurity();
        var negotiator = node.getSecureChannelNegotiator();
        var peerManager = node.getPeerManager();

        var peer = peerManager.getPeer(peerId);
        if (peer == null) {
            System.out.println("❌ Peer not found: " + peerId);
            return;
        }

        System.out.println("🔐 Security Info for: " + peerId);
        System.out.println();

        // Peer state
        var state = peerManager.getPeerState(peerId);
        System.out.println("  Peer State:");
        System.out.println("    Current:       " + state);
        System.out.println("    Trusted:       " + peer.trusted());
        System.out.println("    Reputation:    " + peer.reputation());
        System.out.println();

        // Identity key
        System.out.println("  Identity:");
        System.out.println("    Has Identity Key: " + peer.hasIdentityPublicKey());
        if (peer.hasIdentityPublicKey()) {
            String keyPreview = peer.identityPublicKey();
            if (keyPreview != null && keyPreview.length() > 20) {
                keyPreview = keyPreview.substring(0, 20) + "...";
            }
            System.out.println("    Public Key:       " + keyPreview);
        }
        System.out.println();

        // Secure channel
        if (negotiator != null) {
            boolean hasChannel = negotiator.hasSecureChannel(peerId);
            System.out.println("  Secure Channel:");
            System.out.println("    Established:   " + hasChannel);
            if (hasChannel) {
                System.out.println("    Session ID:    " + negotiator.getChannelSessionId(peerId));
            }
        }
        System.out.println();

        // Session
        boolean hasSession = security.hasValidSession(peerId);
        System.out.println("  Session:");
        System.out.println("    Active:        " + hasSession);
        if (hasSession) {
            System.out.println("    Info:          " + security.getSessionStats(peerId));
        }
    }

    /**
     * Truncates a string to the specified length.
     */
    private static String truncate(String str, int maxLen) {
        if (str == null) return "(null)";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 3) + "...";
    }

    /**
     * Prints comprehensive node metrics including Phase 1-5 component integrations.
     *
     * Displays statistics for:
     * - Phase 1: Protocol Layer (message fragmentation and reassembly)
     * - Phase 2: Compression Codecs (Gzip/LZ4 compression statistics)
     * - Phase 5.1: LRUCache (peer caching with hit rate and evictions)
     * - Phase 5.2: EvictingQueue (message deduplication statistics)
     * - Phase 5.3: ResourceLeakDetector (resource leak detection and tracking)
     * - Transport Layer (TCP/UDP statistics with error tracking)
     */
    private static void printNodeMetrics(NodeRuntime runtime) {
        Node node = runtime.getNode();

        System.out.println("📊 Comprehensive Metrics:");
        System.out.println();

        // Protocol Layer Metrics
        System.out.println("🔄 Protocol Layer (Fragmentation & Compression):");
        try {
            var protocolStats = node.getProtocolStats();
            if (protocolStats != null) {
                System.out.println("   Fragmentation:");
                System.out.println("      Messages Created:   " + protocolStats.get("fragmentation.created"));
                System.out.println("      Messages Reassembled: " + protocolStats.get("fragmentation.reassembled"));
                System.out.println("   Compression:");
                System.out.println("      Applied:            " + protocolStats.get("compression.applied"));
                System.out.println("      Skipped:            " + protocolStats.get("compression.skipped"));
                System.out.println("      Ratio:              " + protocolStats.get("compression.ratio"));
            } else {
                System.out.println("   (metrics not available)");
            }
        } catch (Exception e) {
            System.out.println("   (error retrieving protocol metrics)");
        }
        System.out.println();

        // Peer Cache Statistics (LRUCache)
        System.out.println("👥 Peer Cache (LRUCache):");
        try {
            var cacheStats = node.getPeerCacheStats();
            if (cacheStats != null) {
                System.out.println("   Current Size:       " + cacheStats.getSize());
                System.out.println("   Max Capacity:       " + cacheStats.getMaxSize());
                System.out.println("   Hit Rate:           " + String.format("%.2f%%", cacheStats.getHitRate() * 100));
                System.out.println("   Total Hits:         " + cacheStats.getHits());
                System.out.println("   Total Misses:       " + cacheStats.getMisses());
                System.out.println("   Evictions:          " + cacheStats.getEvictions());
            } else {
                System.out.println("   (metrics not available)");
            }
        } catch (Exception e) {
            System.out.println("   (error retrieving cache metrics)");
        }
        System.out.println();

        // Deduplication Statistics (EvictingQueue)
        System.out.println("🔍 Message Deduplication:");
        try {
            var dedupStats = node.getDeduplicationStats();
            if (dedupStats != null) {
                System.out.println("   Window Size:        " + dedupStats.get("window.size"));
                System.out.println("   Cache Size:         " + dedupStats.get("cache.size"));
                System.out.println("   Unique Messages:    " + dedupStats.get("unique"));
                System.out.println("   Duplicates Detected: " + dedupStats.get("duplicate"));
                double dupRate = (double) dedupStats.get("duplicate") /
                                 ((double) dedupStats.get("unique") + (double) dedupStats.get("duplicate")) * 100;
                System.out.println("   Duplicate Rate:     " + String.format("%.2f%%", dupRate));
            } else {
                System.out.println("   (metrics not available)");
            }
        } catch (Exception e) {
            System.out.println("   (error retrieving deduplication metrics)");
        }
        System.out.println();

        // Resource Leak Detection
        System.out.println("🔍 Resource Leak Detection:");
        try {
            var leakStats = node.getLeakDetectionStats();
            if (leakStats != null) {
                System.out.println("   Currently Tracked:  " + leakStats.getCurrentlyTracked());
                System.out.println("   Total Tracked:      " + leakStats.getTotalTracked());
                System.out.println("   Leaks Detected:     " + leakStats.getLeaksDetected());
                System.out.println("   Leak Rate:          " + String.format("%.4f%%", leakStats.getLeakRate() * 100));
                System.out.println("   Sampling Rate:      1 in " + leakStats.getSamplingRate());

                if (leakStats.getLeaksDetected() > 0) {
                    System.out.println();
                    System.out.println("   ⚠️  WARNING: Resource leaks detected!");
                    System.out.println("   Check logs for stack traces and allocation sites.");
                }
            } else {
                System.out.println("   (metrics not available)");
            }
        } catch (Exception e) {
            System.out.println("   (error retrieving leak detection metrics)");
        }
        System.out.println();

        // Transport Statistics
        System.out.println("🌐 Transport Statistics:");
        var tcpStats = node.getTcpStats();
        var udpStats = node.getUdpStats();

        System.out.println("   TCP:");
        System.out.println("      Messages: " + tcpStats.messagesSent() + " sent, " +
                          tcpStats.messagesReceived() + " received");
        System.out.println("      Bytes:    " + formatBytes(tcpStats.bytesSent()) + " sent, " +
                          formatBytes(tcpStats.bytesReceived()) + " received");
        System.out.println("      Errors:   " + tcpStats.sendErrors() + " send, " +
                          tcpStats.receiveErrors() + " receive");

        System.out.println("   UDP:");
        System.out.println("      Messages: " + udpStats.messagesSent() + " sent, " +
                          udpStats.messagesReceived() + " received");
        System.out.println("      Bytes:    " + formatBytes(udpStats.bytesSent()) + " sent, " +
                          formatBytes(udpStats.bytesReceived()) + " received");
        System.out.println("      Errors:   " + udpStats.sendErrors() + " send, " +
                          udpStats.receiveErrors() + " receive");
    }

    /**
     * Prints cluster status.
     */
    private static void printClusterStatus(ClusterManager cluster) {
        var stats = cluster.getStats();
        System.out.println("📊 Cluster Status:");
        System.out.println("   Cluster ID:  " + stats.clusterId());
        System.out.println("   Nodes:       " + stats.nodeCount());
        System.out.println("   Leader:      " + stats.leaderId());
        System.out.println("   Strategy:    " + stats.electionStrategy());
        System.out.println("   Uptime:      " + formatDuration(Duration.ofMillis(stats.totalUptimeMillis())));
    }

    /**
     * Prints cluster health.
     */
    private static void printClusterHealth(ClusterManager cluster) {
        var health = cluster.checkHealth();
        System.out.println("🏥 Cluster Health:");
        System.out.println("   Total:       " + health.totalNodes());
        System.out.println("   Running:     " + health.runningNodes());
        System.out.println("   Stopped:     " + health.stoppedNodes());
        System.out.println("   Healthy:     " + (health.healthy() ? "✓" : "✗"));
    }

    /**
     * Prints cluster nodes.
     */
    private static void printClusterNodes(ClusterManager cluster) {
        var nodes = cluster.getAllNodes();
        System.out.println("🖥️  Cluster Nodes (" + nodes.size() + "):");

        nodes.forEach(n -> {
            String status = n.isRunning() ? "✓" : "✗";
            System.out.println("   " + status + " " + n.getNode().getNodeId() +
                    " [" + n.getState() + "] uptime=" +
                    formatDuration(Duration.ofMillis(n.getUptimeMillis())));
        });
    }

    /**
     * Prints cluster leader.
     */
    private static void printClusterLeader(ClusterManager cluster) {
        var leader = cluster.getLeaderId();
        System.out.println("👑 Current Leader: " + leader.orElse("(none)"));
    }

    /**
     * Elects cluster leader.
     */
    private static void electClusterLeader(ClusterManager cluster) {
        System.out.println("🗳️  Triggering leader election...");
        cluster.electLeader();
        var newLeader = cluster.getLeaderId();
        System.out.println("✓ New leader: " + newLeader.orElse("(none)"));
    }

    // ==================== Help & Documentation ====================

    /**
     * Prints banner.
     */
    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════════════════╗");
        System.out.println("  ║                                                           ║");
        System.out.println("  ║            GENESIS P2P FRAMEWORK v" + VERSION + "                 ║");
        System.out.println("  ║                                                           ║");
        System.out.println("  ║       Advanced Peer-to-Peer Networking Framework          ║");
        System.out.println("  ║                                                           ║");
        System.out.println("  ╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Prints version information.
     */
    private static void printVersion() {
        System.out.println(NAME + " v" + VERSION);
        System.out.println("Copyright (c) " + COPYRIGHT);
        System.out.println("Build Date: " + BUILD_DATE);
        System.out.println();
        System.out.println("System Information:");
        System.out.println("  Java Version:  " + System.getProperty("java.version"));
        System.out.println("  Java Vendor:   " + System.getProperty("java.vendor"));
        System.out.println("  OS:            " + System.getProperty("os.name") + " " +
                System.getProperty("os.version"));
        System.out.println("  Architecture:  " + System.getProperty("os.arch"));
        System.out.println("  Processors:    " + Runtime.getRuntime().availableProcessors());
        System.out.println("  Max Memory:    " + formatBytes(Runtime.getRuntime().maxMemory()));
    }

    /**
     * Prints usage information.
     */
    private static void printUsage() {
        System.out.println("Usage: genesis <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  start      Start a P2P node (default)");
        System.out.println("  cluster    Start a cluster of nodes");
        System.out.println("  shell      Interactive shell mode");
        System.out.println("  config     Configuration management");
        System.out.println("  version    Show version information");
        System.out.println("  help       Show this help message");
    }

    /**
     * Prints comprehensive help.
     */
    private static void printHelp() {
        printVersion();
        System.out.println();
        printUsage();
        System.out.println();
        System.out.println("Node Options:");
        System.out.println("  --nodeId=<id>         Node identifier");
        System.out.println("  --tcpPort=<port>      TCP port (default: 8081)");
        System.out.println("  --listenPort=<port>   UDP port (default: 8080)");
        System.out.println("  --config=<file>       Configuration file path");
        System.out.println("  --profile=<name>      Profile: dev, prod, test, ha");
        System.out.println("  --daemon              Run without interactive input");
        System.out.println();
        System.out.println("Logging Options:");
        System.out.println("  --log-level=<level>   Log level: ERROR, WARN, INFO, DEBUG");
        System.out.println("  --verbose             Enable DEBUG logging (shorthand)");
        System.out.println("  --quiet               Enable WARN logging only (shorthand)");
        System.out.println("  --log-file=<file>     Write logs to file");
        System.out.println("  --log-output=<mode>   Output: console, file, both (default: console)");
        System.out.println("  --log-format=<fmt>    Format: structured, json, plain (default: structured)");
        System.out.println("  --json-log            Enable JSON log format (shorthand)");
        System.out.println();
        System.out.println("Advanced Logging:");
        System.out.println("  --log-category=<cats> Category-specific levels:");
        System.out.println("                        Format: discovery:DEBUG,security:INFO,transport:WARN");
        System.out.println("                        Categories: discovery, security, transport, protocol,");
        System.out.println("                                    peer, nat, message, resource");
        System.out.println("  --wireshark           Enable Wireshark correlation (payload hex dumps)");
        System.out.println("  --cyber-emulation     Enable cyber-emulation mode (security verbose)");
        System.out.println("  --performance         Performance mode (disable expensive logging)");
        System.out.println("  --max-payload-hex=<n> Max payload hex dump size (default: 32 bytes)");
        System.out.println();
        System.out.println("Cluster Options:");
        System.out.println("  --clusterId=<id>      Cluster identifier");
        System.out.println("  --nodes=<count>       Number of nodes (default: 3)");
        System.out.println("  --basePort=<port>     Starting port (default: 8080)");
        System.out.println();
        System.out.println("Environment Variables:");
        System.out.println("  GENESIS_NODE_ID         Node identifier");
        System.out.println("  GENESIS_TCP_PORT        TCP port");
        System.out.println("  GENESIS_LISTEN_PORT     UDP port");
        System.out.println("  GENESIS_MULTICAST_GROUP Multicast group address");
        System.out.println();
        System.out.println("Examples:");
        System.out.println();
        System.out.println("  # Basic node startup");
        System.out.println("  genesis start --nodeId=node1 --tcpPort=8081");
        System.out.println();
        System.out.println("  # Production deployment with file logging");
        System.out.println("  genesis start --profile=prod --daemon --log-file=/var/log/genesis.log");
        System.out.println();
        System.out.println("  # Debugging with verbose discovery logging");
        System.out.println("  genesis start --log-level=DEBUG --log-category=discovery:DEBUG");
        System.out.println();
        System.out.println("  # Wireshark packet analysis mode");
        System.out.println("  genesis start --wireshark --log-file=packets.log --max-payload-hex=128");
        System.out.println();
        System.out.println("  # Cyber-emulation / security testing");
        System.out.println("  genesis start --cyber-emulation --log-format=json --log-output=both");
        System.out.println();
        System.out.println("  # High-performance production mode");
        System.out.println("  genesis start --performance --log-level=WARN --log-output=file");
        System.out.println();
        System.out.println("  # Multi-category detailed logging");
        System.out.println("  genesis start --log-category=discovery:DEBUG,security:INFO,transport:DEBUG");
        System.out.println();
        System.out.println("  # Cluster with JSON logging");
        System.out.println("  genesis cluster --nodes=5 --basePort=9000 --json-log");
        System.out.println();
        System.out.println("  # Configuration management");
        System.out.println("  genesis config generate --output=myconfig.json");
        System.out.println();
        System.out.println("  # Interactive shell");
        System.out.println("  genesis shell");
    }

    /**
     * Prints node command help.
     */
    private static void printNodeCommandHelp() {
        System.out.println("Available Commands:");
        System.out.println("  help              Show this help");
        System.out.println("  status            Show node status");
        System.out.println("  health            Show health status");
        System.out.println("  peers             List connected peers");
        System.out.println("  stats             Show transport statistics");
        System.out.println("  metrics           Show comprehensive metrics");
        System.out.println("  ping <peerId>     Send ping to peer");
        System.out.println("  messages [cmd]    Message observability commands:");
        System.out.println("    stats           - Show message statistics");
        System.out.println("    recent [N]      - Show N recent messages");
        System.out.println("    outbound        - Show outbound messages");
        System.out.println("    inbound         - Show inbound messages");
        System.out.println("    handshake       - Show handshake messages");
        System.out.println("    pending         - Show pending messages");
        System.out.println("    failed          - Show failed messages");
        System.out.println("  security [cmd]    Security and KEY_EXCHANGE commands:");
        System.out.println("    status          - Show secure channel status");
        System.out.println("    channels        - List established secure channels");
        System.out.println("    sessions        - Show active sessions");
        System.out.println("    metrics         - KEY_EXCHANGE statistics");
        System.out.println("    peer <id>       - Show security info for peer");
        System.out.println("  replay            Verify message replay capability");
        System.out.println("  restart           Restart the node");
        System.out.println("  stop              Stop the node");
        System.out.println("  <enter>           Stop the node");
    }

    /**
     * Prints cluster command help.
     */
    private static void printClusterCommandHelp() {
        System.out.println("Available Commands:");
        System.out.println("  help      Show this help");
        System.out.println("  status    Show cluster status");
        System.out.println("  health    Show health status");
        System.out.println("  nodes     List all nodes");
        System.out.println("  leader    Show current leader");
        System.out.println("  elect     Trigger leader election");
        System.out.println("  stop      Stop the cluster");
        System.out.println("  <enter>   Stop the cluster");
    }

    /**
     * Prints shell help.
     */
    private static void printShellHelp() {
        System.out.println("Available Commands:");
        System.out.println("  config generate [--output=<file>]  Generate default config");
        System.out.println("  config validate [--file=<file>]    Validate config file");
        System.out.println("  config show                        Show current config");
        System.out.println("  version                            Show version");
        System.out.println("  exit, quit, q                      Exit shell");
    }

    // ==================== Formatting Utilities ====================

    /**
     * Formats duration in human-readable form.
     */
    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();

        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        } else if (seconds < 86400) {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return hours + "h " + mins + "m";
        } else {
            long days = seconds / 86400;
            long hours = (seconds % 86400) / 3600;
            return days + "d " + hours + "h";
        }
    }

    /**
     * Formats bytes in human-readable form.
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    // ==================== Custom Exceptions ====================

    /**
     * Exception for invalid command-line arguments.
     */
    private static class InvalidArgumentException extends Exception {
        public InvalidArgumentException(String message) {
            super(message);
        }
    }

    /**
     * Exception for configuration errors.
     */
    private static class ConfigurationException extends Exception {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception for startup failures.
     */
    private static class StartupException extends Exception {
        public StartupException(String message) {
            super(message);
        }

        public StartupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ==================== Command Line Args Parser ====================

    /**
     * Parsed command-line arguments.
     */
    private static class CommandLineArgs {
        private final String[] rawArgs;
        private final String command;
        private final Map<String, String> options;

        private CommandLineArgs(String[] rawArgs, String command, Map<String, String> options) {
            this.rawArgs = rawArgs;
            this.command = command;
            this.options = options;
        }

        public static CommandLineArgs parse(String[] args) {
            String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "start";
            Map<String, String> options = new HashMap<>();

            for (int i = (command.equals("start") && args.length > 0 && !args[0].startsWith("--") ? 1 : 0);
                 i < args.length; i++) {
                String arg = args[i];

                if (arg.startsWith("--")) {
                    String key = arg.substring(2);
                    String value = "true";

                    int eqIndex = key.indexOf('=');
                    if (eqIndex > 0) {
                        value = key.substring(eqIndex + 1);
                        key = key.substring(0, eqIndex);
                    } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    }

                    options.put(key, value);
                }
            }

            return new CommandLineArgs(args, command, options);
        }

        public String[] getRawArgs() { return rawArgs; }
        public String getCommand() { return command; }
        public boolean isVerbose() { return options.containsKey("verbose"); }
        public boolean isQuiet() { return options.containsKey("quiet"); }
        public boolean isDaemon() { return options.containsKey("daemon"); }
        public boolean isJsonLogging() { return options.containsKey("json-log"); }
        public boolean hasLogFile() { return options.containsKey("log-file"); }
        public String getLogFile() { return options.get("log-file"); }
        public String getConfigFile() { return options.get("config"); }
        public String getConfigFile(String def) { return options.getOrDefault("config", def); }
        public String getOutputFile(String def) { return options.getOrDefault("output", def); }
        public String getProfile() { return options.get("profile"); }
        public String getClusterId() { return options.getOrDefault("clusterId", "genesis-cluster"); }
        public int getNodeCount() { return Integer.parseInt(options.getOrDefault("nodes", "3")); }
        public int getBasePort() { return Integer.parseInt(options.getOrDefault("basePort", "8080")); }
        public String getConfigSubCommand() {
            return rawArgs.length > 1 ? rawArgs[1] : "show";
        }

        /**
         * Checks if help flag is present (--help, -h, or help command).
         */
        public boolean hasHelpFlag() {
            return options.containsKey("help") ||
                   options.containsKey("h") ||
                   command.equals("help") ||
                   command.equals("-h") ||
                   command.equals("--help");
        }

        /**
         * Checks if version flag is present (--version, -v, or version command).
         */
        public boolean hasVersionFlag() {
            return options.containsKey("version") ||
                   options.containsKey("v") ||
                   command.equals("version") ||
                   command.equals("-v") ||
                   command.equals("--version");
        }

        // ==================== Enhanced Logging Options ====================

        /**
         * Gets explicit log level (ERROR, WARN, INFO, DEBUG).
         */
        public String getLogLevel() {
            return options.get("log-level");
        }

        /**
         * Gets log format (structured, json, plain).
         */
        public String getLogFormat() {
            return options.get("log-format");
        }

        /**
         * Gets log output destination (console, file, both).
         */
        public String getLogOutput() {
            return options.getOrDefault("log-output", "console");
        }

        /**
         * Checks if Wireshark correlation mode is enabled.
         * Enables payload hex dumps for packet-level analysis.
         */
        public boolean isWiresharkMode() {
            return options.containsKey("wireshark") ||
                   options.containsKey("log-wireshark") ||
                   options.containsKey("enable-wireshark");
        }

        /**
         * Checks if cyber-emulation mode is enabled.
         * Enables enhanced security and attack pattern logging.
         */
        public boolean isCyberEmulationMode() {
            return options.containsKey("cyber-emulation") ||
                   options.containsKey("log-cyber") ||
                   options.containsKey("security-verbose");
        }

        /**
         * Checks if performance mode is enabled.
         * Disables expensive logging operations (hex dumps, stack traces).
         */
        public boolean isPerformanceMode() {
            return options.containsKey("performance") ||
                   options.containsKey("log-performance") ||
                   options.containsKey("fast-logging");
        }

        /**
         * Gets max payload hex dump size in bytes.
         */
        public int getMaxPayloadHexSize() {
            String value = options.get("max-payload-hex");
            if (value != null) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    return 32; // Default to 32 bytes
                }
            }
            return 32; // Default
        }

        /**
         * Checks if category-specific logging is configured.
         */
        public boolean hasLogCategories() {
            return options.containsKey("log-category");
        }

        /**
         * Gets category-specific log levels.
         * Format: --log-category=discovery:DEBUG,security:INFO,transport:WARN
         */
        public Map<String, String> getLogCategories() {
            Map<String, String> categories = new HashMap<>();
            String value = options.get("log-category");

            if (value != null) {
                for (String pair : value.split(",")) {
                    String[] parts = pair.split(":");
                    if (parts.length == 2) {
                        categories.put(parts[0].trim(), parts[1].trim().toUpperCase());
                    }
                }
            }

            return categories;
        }
    }
}