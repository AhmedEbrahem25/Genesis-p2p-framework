package com.genesis.p2p.application;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.util.*;

/**
 * NodeBuilder - Fluent builder for P2P Node construction.
 *
 * Design Patterns Applied:
 * - Builder Pattern: Fluent API for node configuration
 * - Director Pattern: Pre-configured build strategies
 * - Prototype Pattern: Clone configurations
 * - Template Method: Validation hooks
 *
 * Provides a fluent API for configuring and building P2P nodes.
 *
 * Usage:
 * <pre>{@code
 * Node node = new NodeBuilder()
 *     .nodeId("peer-123")
 *     .port(8080)
 *     .dataDirectory("/data/p2p")
 *     .enableMetrics()
 *     .enableDiscovery()
 *     .addBootstrapPeer("bootstrap.example.com:8080")
 *     .build();
 * }</pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class NodeBuilder {

    private static final NodeLogger log = NodeLogger.getLogger(NodeBuilder.class);

    // Core configuration
    private String nodeId;
    private String hostname;
    private int port = 8080;
    private String dataDirectory = "./data";

    // Optional components
    private boolean metricsEnabled = true;
    private boolean discoveryEnabled = true;
    private boolean securityEnabled = true;
    private boolean autoStartEnabled = false;

    // Bootstrap configuration
    private final List<String> bootstrapPeers = new ArrayList<>();
    private int maxPeers = 100;
    private int minPeers = 3;

    // Transport configuration
    private String transportType = "TCP";
    private int connectionTimeout = 5000;
    private int readTimeout = 30000;

    // Advanced configuration
    private Map<String, Object> customProperties = new HashMap<>();
    private NodeConfig customConfig;

    // Validation hooks
    private final List<ValidationRule> validationRules = new ArrayList<>();

    /**
     * Creates a new NodeBuilder with default settings.
     */
    public NodeBuilder() {
        this.nodeId = generateDefaultNodeId();
        registerDefaultValidationRules();
    }

    /**
     * Private constructor for prototype pattern.
     */
    private NodeBuilder(NodeBuilder source) {
        this.nodeId = source.nodeId;
        this.hostname = source.hostname;
        this.port = source.port;
        this.dataDirectory = source.dataDirectory;
        this.metricsEnabled = source.metricsEnabled;
        this.discoveryEnabled = source.discoveryEnabled;
        this.securityEnabled = source.securityEnabled;
        this.autoStartEnabled = source.autoStartEnabled;
        this.bootstrapPeers.addAll(source.bootstrapPeers);
        this.maxPeers = source.maxPeers;
        this.minPeers = source.minPeers;
        this.transportType = source.transportType;
        this.connectionTimeout = source.connectionTimeout;
        this.readTimeout = source.readTimeout;
        this.customProperties.putAll(source.customProperties);
        this.customConfig = source.customConfig;
        this.validationRules.addAll(source.validationRules);
    }

    // ==================== Prototype Pattern ====================

    /**
     * Creates a copy of this builder.
     * Prototype Pattern - Clone configuration for variations.
     *
     * @return a new builder with same configuration
     */
    public NodeBuilder copy() {
        return new NodeBuilder(this);
    }

    // ==================== Core Configuration ====================

    /**
     * Sets the node ID.
     */
    public NodeBuilder nodeId(String nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /**
     * Sets the hostname.
     */
    public NodeBuilder hostname(String hostname) {
        this.hostname = hostname;
        return this;
    }

    /**
     * Sets the port.
     */
    public NodeBuilder port(int port) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        this.port = port;
        return this;
    }

    /**
     * Sets the data directory.
     */
    public NodeBuilder dataDirectory(String dataDirectory) {
        this.dataDirectory = dataDirectory;
        return this;
    }

    // ==================== Feature Toggles ====================

    /**
     * Enables metrics collection.
     */
    public NodeBuilder enableMetrics() {
        this.metricsEnabled = true;
        return this;
    }

    /**
     * Disables metrics collection.
     */
    public NodeBuilder disableMetrics() {
        this.metricsEnabled = false;
        return this;
    }

    /**
     * Enables peer discovery.
     */
    public NodeBuilder enableDiscovery() {
        this.discoveryEnabled = true;
        return this;
    }

    /**
     * Disables peer discovery.
     */
    public NodeBuilder disableDiscovery() {
        this.discoveryEnabled = false;
        return this;
    }

    /**
     * Enables security features.
     */
    public NodeBuilder enableSecurity() {
        this.securityEnabled = true;
        return this;
    }

    /**
     * Disables security features.
     */
    public NodeBuilder disableSecurity() {
        this.securityEnabled = false;
        return this;
    }

    /**
     * Enables automatic start after build.
     */
    public NodeBuilder autoStart() {
        this.autoStartEnabled = true;
        return this;
    }

    // ==================== Network Configuration ====================

    /**
     * Adds a bootstrap peer.
     */
    public NodeBuilder addBootstrapPeer(String peerAddress) {
        if (peerAddress != null && !peerAddress.isEmpty()) {
            this.bootstrapPeers.add(peerAddress);
        }
        return this;
    }

    /**
     * Adds multiple bootstrap peers.
     */
    public NodeBuilder addBootstrapPeers(String... peers) {
        if (peers != null) {
            Collections.addAll(bootstrapPeers, peers);
        }
        return this;
    }

    /**
     * Adds bootstrap peers from collection.
     */
    public NodeBuilder addBootstrapPeers(Collection<String> peers) {
        if (peers != null) {
            bootstrapPeers.addAll(peers);
        }
        return this;
    }

    /**
     * Sets maximum number of peers.
     */
    public NodeBuilder maxPeers(int maxPeers) {
        if (maxPeers <= 0) {
            throw new IllegalArgumentException("Max peers must be positive");
        }
        this.maxPeers = maxPeers;
        return this;
    }

    /**
     * Sets minimum number of peers.
     */
    public NodeBuilder minPeers(int minPeers) {
        if (minPeers < 0) {
            throw new IllegalArgumentException("Min peers cannot be negative");
        }
        this.minPeers = minPeers;
        return this;
    }

    // ==================== Transport Configuration ====================

    /**
     * Sets transport type (TCP, UDP, WebSocket, QUIC).
     */
    public NodeBuilder transportType(String transportType) {
        this.transportType = transportType;
        return this;
    }

    /**
     * Sets connection timeout in milliseconds.
     */
    public NodeBuilder connectionTimeout(int timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.connectionTimeout = timeoutMs;
        return this;
    }

    /**
     * Sets read timeout in milliseconds.
     */
    public NodeBuilder readTimeout(int timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.readTimeout = timeoutMs;
        return this;
    }

    // ==================== Custom Configuration ====================

    /**
     * Sets a custom property.
     */
    public NodeBuilder property(String key, Object value) {
        customProperties.put(key, value);
        return this;
    }

    /**
     * Sets multiple custom properties.
     */
    public NodeBuilder properties(Map<String, Object> properties) {
        if (properties != null) {
            customProperties.putAll(properties);
        }
        return this;
    }

    /**
     * Uses a custom NodeConfig.
     */
    public NodeBuilder config(NodeConfig config) {
        this.customConfig = config;
        return this;
    }

    // ==================== Director Pattern: Profiles ====================

    /**
     * Node profile interface.
     * Director Pattern - Pre-configured build strategies.
     */
    public interface NodeProfile {
        void configure(NodeBuilder builder);
        String getName();
    }

    /**
     * Development profile (relaxed security, verbose logging).
     */
    public static class DevelopmentProfile implements NodeProfile {
        @Override
        public void configure(NodeBuilder builder) {
            builder.securityEnabled = false;
            builder.metricsEnabled = true;
            builder.property("logging.level", "DEBUG");
            builder.property("security.strict", false);
        }

        @Override
        public String getName() {
            return "development";
        }
    }

    /**
     * Production profile (strict security, optimized performance).
     */
    public static class ProductionProfile implements NodeProfile {
        @Override
        public void configure(NodeBuilder builder) {
            builder.securityEnabled = true;
            builder.metricsEnabled = true;
            builder.property("logging.level", "INFO");
            builder.property("security.strict", true);
            builder.property("performance.optimized", true);
        }

        @Override
        public String getName() {
            return "production";
        }
    }

    /**
     * Testing profile (minimal dependencies, fast startup).
     */
    public static class TestingProfile implements NodeProfile {
        @Override
        public void configure(NodeBuilder builder) {
            builder.securityEnabled = false;
            builder.discoveryEnabled = false;
            builder.metricsEnabled = false;
            builder.property("logging.level", "WARN");
        }

        @Override
        public String getName() {
            return "testing";
        }
    }

    /**
     * High availability profile (redundancy, health checks).
     */
    public static class HighAvailabilityProfile implements NodeProfile {
        @Override
        public void configure(NodeBuilder builder) {
            builder.securityEnabled = true;
            builder.metricsEnabled = true;
            builder.discoveryEnabled = true;
            builder.minPeers = 5;
            builder.maxPeers = 200;
            builder.connectionTimeout = 3000;
            builder.property("health.check.interval", 10000);
            builder.property("reconnect.enabled", true);
            builder.property("failover.enabled", true);
        }

        @Override
        public String getName() {
            return "high-availability";
        }
    }

    /**
     * Applies a profile.
     *
     * @param profile the profile to apply
     * @return this builder
     */
    public NodeBuilder withProfile(NodeProfile profile) {
        profile.configure(this);
        log.debug("Profile applied", "profile", profile.getName());
        return this;
    }

    /**
     * Applies development profile.
     */
    public NodeBuilder developmentProfile() {
        return withProfile(new DevelopmentProfile());
    }

    /**
     * Applies production profile.
     */
    public NodeBuilder productionProfile() {
        return withProfile(new ProductionProfile());
    }

    /**
     * Applies testing profile.
     */
    public NodeBuilder testingProfile() {
        return withProfile(new TestingProfile());
    }

    /**
     * Applies high availability profile.
     */
    public NodeBuilder highAvailabilityProfile() {
        return withProfile(new HighAvailabilityProfile());
    }

    // ==================== Template Method: Validation ====================

    /**
     * Validation rule interface.
     */
    @FunctionalInterface
    public interface ValidationRule {
        ValidationResult validate(NodeBuilder builder);
    }

    /**
     * Validation result.
     */
    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }

    /**
     * Registers default validation rules.
     */
    private void registerDefaultValidationRules() {
        addValidationRule(b -> {
            if (b.nodeId == null || b.nodeId.isEmpty()) {
                return ValidationResult.error("Node ID is required");
            }
            return ValidationResult.ok();
        });

        addValidationRule(b -> {
            if (b.port <= 0 || b.port > 65535) {
                return ValidationResult.error("Invalid port: " + b.port);
            }
            return ValidationResult.ok();
        });

        addValidationRule(b -> {
            if (b.dataDirectory == null || b.dataDirectory.isEmpty()) {
                return ValidationResult.error("Data directory is required");
            }
            return ValidationResult.ok();
        });

        addValidationRule(b -> {
            if (b.minPeers > b.maxPeers) {
                return ValidationResult.error("Min peers cannot exceed max peers");
            }
            return ValidationResult.ok();
        });
    }

    /**
     * Adds a custom validation rule.
     *
     * @param rule the validation rule
     * @return this builder
     */
    public NodeBuilder addValidationRule(ValidationRule rule) {
        validationRules.add(rule);
        return this;
    }

    /**
     * Validates configuration before building.
     * Template Method - Allows custom validation hooks.
     */
    private void validate() {
        List<String> errors = new ArrayList<>();

        for (ValidationRule rule : validationRules) {
            ValidationResult result = rule.validate(this);
            if (!result.valid()) {
                errors.add(result.message());
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Validation failed: " + String.join("; ", errors));
        }

        // Hook for subclass validation
        customValidation();

        log.debug("Node configuration validated",
                "nodeId", nodeId,
                "port", port);
    }

    /**
     * Custom validation hook for subclasses.
     */
    protected void customValidation() {
        // Override in subclass for custom validation
    }

    // ==================== Build ====================

    /**
     * Builds the NodeConfig.
     */
    private NodeConfig buildConfig() {
        if (customConfig != null) {
            return customConfig;
        }

        // Build config using NodeConfig constructor
        return new NodeConfig(
                nodeId,
                port,                                    // listenPort
                port + 1,                                // tcpPort
                "239.255.0.1",                           // multicastGroup
                5000,                                    // multicastPort
                5001,                                    // broadcastPort
                "",                                      // preSharedKeyHex (empty for now)
                true,                                   // persistenceEnabled (default true)
                dataDirectory                            // persistenceDir (use builder field)
        );
    }

    /**
     * Builds and returns the Node.
     */
    public Node build() {
        validate();

        log.info("Building P2P node",
                "nodeId", nodeId,
                "port", port,
                "metricsEnabled", metricsEnabled,
                "discoveryEnabled", discoveryEnabled);

        try {
            NodeConfig config = buildConfig();
            Node node = new Node(config);

            if (autoStartEnabled) {
                log.info("Auto-starting node...");
                node.start();
            }

            log.info("Node built successfully", "nodeId", nodeId);
            return node;

        } catch (Exception e) {
            log.error("Failed to build node", e);
            throw new NodeBuildException("Failed to build node: " + e.getMessage(), e);
        }
    }

    /**
     * Builds and wraps in NodeRuntime for advanced lifecycle management.
     */
    public NodeRuntime buildRuntime() {
        Node node = build();
        return new NodeRuntime(node);
    }

    // ==================== Helper Methods ====================

    /**
     * Generates a default node ID.
     */
    private String generateDefaultNodeId() {
        return "node-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates a quick development node.
     */
    public static Node quickDev(int port) {
        return new NodeBuilder()
                .port(port)
                .developmentProfile()
                .build();
    }

    /**
     * Creates a production node.
     */
    public static Node production(String nodeId, int port, String dataDir) {
        return new NodeBuilder()
                .nodeId(nodeId)
                .port(port)
                .dataDirectory(dataDir)
                .productionProfile()
                .build();
    }

    /**
     * Creates a testing node.
     */
    public static Node testing(int port) {
        return new NodeBuilder()
                .port(port)
                .testingProfile()
                .build();
    }

    /**
     * Creates a builder from existing config.
     */
    public static NodeBuilder from(NodeConfig config) {
        return new NodeBuilder()
                .nodeId(config.nodeId())
                .port(config.listenPort())
                .config(config);
    }

    // ==================== Getters for inspection ====================

    public String getNodeId() { return nodeId; }
    public int getPort() { return port; }
    public boolean isMetricsEnabled() { return metricsEnabled; }
    public boolean isDiscoveryEnabled() { return discoveryEnabled; }
    public boolean isSecurityEnabled() { return securityEnabled; }
    public List<String> getBootstrapPeers() { return Collections.unmodifiableList(bootstrapPeers); }

    // ==================== Exception ====================

    /**
     * Exception thrown when node building fails.
     */
    public static class NodeBuildException extends RuntimeException {
        public NodeBuildException(String message) {
            super(message);
        }

        public NodeBuildException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
