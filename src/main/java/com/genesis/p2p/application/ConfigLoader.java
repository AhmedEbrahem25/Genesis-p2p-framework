package com.genesis.p2p.application;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * ConfigLoader - Multi-source configuration loading system.
 *
 * Design Patterns Applied:
 * - Strategy Pattern: Multiple config sources (file, env, properties)
 * - Chain of Responsibility: Cascading config resolution
 * - Builder Pattern: Fluent loader configuration
 * - Factory Pattern: Config source creation
 *
 * Configuration Sources (in priority order):
 * 1. Command-line arguments (highest priority)
 * 2. Environment variables
 * 3. System properties
 * 4. Config file (JSON/Properties)
 * 5. Default values (lowest priority)
 *
 * Environment Variables:
 * - GENESIS_NODE_ID
 * - GENESIS_LISTEN_PORT
 * - GENESIS_TCP_PORT
 * - GENESIS_MULTICAST_GROUP
 * - GENESIS_MULTICAST_PORT
 * - GENESIS_BROADCAST_PORT
 * - GENESIS_PRE_SHARED_KEY
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ConfigLoader {

    private static final NodeLogger log = NodeLogger.getLogger(ConfigLoader.class);
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // Environment variable prefix
    private static final String ENV_PREFIX = "GENESIS_";

    // Default config paths
    private static final String[] DEFAULT_CONFIG_PATHS = {
            "config/node_config.json",
            "config/agent_config.json",
            "default-config.json",     // ← Added: from resources
            "genesis.json",
            "config.json"
    };

    // ==================== Strategy Pattern: Config Sources ====================

    /**
     * Configuration source interface.
     * Strategy Pattern - Different ways to load configuration.
     */
    public interface ConfigSource {
        /**
         * Gets the source name.
         */
        String getName();

        /**
         * Gets the source priority (higher = more priority).
         */
        int getPriority();

        /**
         * Loads configuration as a map.
         */
        Map<String, Object> load() throws ConfigException;

        /**
         * Checks if source is available.
         */
        boolean isAvailable();
    }

    /**
     * JSON file configuration source.
     */
    public static class JsonFileSource implements ConfigSource {
        private final Path filePath;

        public JsonFileSource(String path) {
            this.filePath = Paths.get(path);
        }

        @Override
        public String getName() {
            return "JsonFile:" + filePath;
        }

        @Override
        public int getPriority() {
            return 10;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> load() throws ConfigException {
            try (Reader reader = Files.newBufferedReader(filePath)) {
                Map<String, Object> config = gson.fromJson(reader, Map.class);
                log.info("Loaded config from JSON file", "path", filePath);
                return config != null ? config : new HashMap<>();
            } catch (JsonSyntaxException e) {
                throw new ConfigException("Invalid JSON syntax in: " + filePath, e);
            } catch (IOException e) {
                throw new ConfigException("Failed to read config file: " + filePath, e);
            }
        }

        @Override
        public boolean isAvailable() {
            return Files.exists(filePath) && Files.isReadable(filePath);
        }
    }

    /**
     * Environment variables configuration source.
     */
    public static class EnvironmentSource implements ConfigSource {
        @Override
        public String getName() {
            return "Environment";
        }

        @Override
        public int getPriority() {
            return 30;
        }

        @Override
        public Map<String, Object> load() {
            Map<String, Object> config = new HashMap<>();

            mapEnvVar("NODE_ID", "nodeId", config);
            mapEnvVar("LISTEN_PORT", "listenPort", config);
            mapEnvVar("TCP_PORT", "tcpPort", config);
            mapEnvVar("MULTICAST_GROUP", "multicastGroup", config);
            mapEnvVar("MULTICAST_PORT", "multicastPort", config);
            mapEnvVar("BROADCAST_PORT", "broadcastPort", config);
            mapEnvVar("PRE_SHARED_KEY", "preSharedKeyHex", config);
            mapEnvVar("PERSISTENCE_ENABLED", "persistenceEnabled", config);
            mapEnvVar("PERSISTENCE_DIR", "persistenceDir", config);

            if (!config.isEmpty()) {
                log.info("Loaded config from environment", "count", config.size());
            }
            return config;
        }

        private void mapEnvVar(String envName, String configKey, Map<String, Object> config) {
            String value = System.getenv(ENV_PREFIX + envName);
            if (value != null && !value.isBlank()) {
                // Try to parse as number for port fields
                if (configKey.endsWith("Port")) {
                    try {
                        config.put(configKey, Integer.parseInt(value));
                    } catch (NumberFormatException e) {
                        config.put(configKey, value);
                    }
                }
                // Parse boolean for persistenceEnabled
                else if (configKey.equals("persistenceEnabled")) {
                    config.put(configKey, Boolean.parseBoolean(value));
                }
                // Everything else as string
                else {
                    config.put(configKey, value);
                }
            }
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /**
     * System properties configuration source.
     */
    public static class SystemPropertiesSource implements ConfigSource {
        private static final String PROP_PREFIX = "genesis.";

        @Override
        public String getName() {
            return "SystemProperties";
        }

        @Override
        public int getPriority() {
            return 20;
        }

        @Override
        public Map<String, Object> load() {
            Map<String, Object> config = new HashMap<>();

            Properties props = System.getProperties();
            props.forEach((key, value) -> {
                String keyStr = key.toString();
                if (keyStr.startsWith(PROP_PREFIX)) {
                    String configKey = keyStr.substring(PROP_PREFIX.length());
                    config.put(configKey, value);
                }
            });

            if (!config.isEmpty()) {
                log.info("Loaded config from system properties", "count", config.size());
            }
            return config;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /**
     * Command-line arguments configuration source.
     */
    public static class CommandLineSource implements ConfigSource {
        private final String[] args;

        public CommandLineSource(String[] args) {
            this.args = args != null ? args : new String[0];
        }

        @Override
        public String getName() {
            return "CommandLine";
        }

        @Override
        public int getPriority() {
            return 40;
        }

        @Override
        public Map<String, Object> load() {
            Map<String, Object> config = new HashMap<>();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                if (arg.startsWith("--")) {
                    String key = arg.substring(2);
                    String value = null;

                    int eqIndex = key.indexOf('=');
                    if (eqIndex > 0) {
                        value = key.substring(eqIndex + 1);
                        key = key.substring(0, eqIndex);
                    } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    }

                    if (value != null) {
                        // Convert to appropriate type
                        try {
                            config.put(key, Integer.parseInt(value));
                        } catch (NumberFormatException e) {
                            config.put(key, value);
                        }
                    }
                }
            }

            if (!config.isEmpty()) {
                log.info("Loaded config from command line", "count", config.size());
            }
            return config;
        }

        @Override
        public boolean isAvailable() {
            return args.length > 0;
        }
    }

    // ==================== Chain of Responsibility: Loader ====================

    /**
     * Configuration loader builder.
     */
    public static class Loader {
        private final List<ConfigSource> sources = new ArrayList<>();
        private boolean useDefaults = true;

        /**
         * Adds a configuration source.
         */
        public Loader addSource(ConfigSource source) {
            sources.add(source);
            return this;
        }

        /**
         * Adds JSON file source.
         */
        public Loader fromFile(String path) {
            return addSource(new JsonFileSource(path));
        }

        /**
         * Adds environment variables source.
         */
        public Loader fromEnvironment() {
            return addSource(new EnvironmentSource());
        }

        /**
         * Adds system properties source.
         */
        public Loader fromSystemProperties() {
            return addSource(new SystemPropertiesSource());
        }

        /**
         * Adds command line source.
         */
        public Loader fromCommandLine(String[] args) {
            return addSource(new CommandLineSource(args));
        }

        /**
         * Disables default values.
         */
        public Loader noDefaults() {
            this.useDefaults = false;
            return this;
        }

        /**
         * Loads and merges configuration from all sources.
         */
        public NodeConfig load() throws ConfigException {
            // Sort sources by priority (lower first, so higher overwrites)
            sources.sort(Comparator.comparingInt(ConfigSource::getPriority));

            // Merge configurations
            Map<String, Object> merged = new HashMap<>();

            // Start with defaults if enabled
            if (useDefaults) {
                merged.put("nodeId", "node-" + UUID.randomUUID().toString().substring(0, 8));
                merged.put("listenPort", NodeConfig.DEFAULT_LISTEN_PORT);
                merged.put("tcpPort", NodeConfig.DEFAULT_TCP_PORT);
                merged.put("multicastGroup", NodeConfig.DEFAULT_MULTICAST_GROUP);
                merged.put("multicastPort", NodeConfig.DEFAULT_MULTICAST_PORT);
                merged.put("broadcastPort", NodeConfig.DEFAULT_BROADCAST_PORT);
                merged.put("preSharedKeyHex", "");
            }

            // Apply each source in priority order
            for (ConfigSource source : sources) {
                if (source.isAvailable()) {
                    try {
                        Map<String, Object> config = source.load();
                        merged.putAll(config);
                    } catch (ConfigException e) {
                        log.warn("Config source failed", "source", source.getName(), "error", e.getMessage());
                    }
                }
            }

            return NodeConfig.fromMap(merged);
        }
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates a new loader.
     */
    public static Loader loader() {
        return new Loader();
    }

    /**
     * Loads configuration from file (legacy method).
     */
    public static NodeConfig load(String filePath) {
        return loader()
                .fromFile(filePath)
                .load();
    }

    /**
     * Loads configuration with all sources.
     */
    public static NodeConfig loadAll(String[] args) {
        Loader loader = loader()
                .fromEnvironment()
                .fromSystemProperties();

        // Add command line args if provided
        if (args != null && args.length > 0) {
            loader.fromCommandLine(args);
        }

        // Try default config paths
        for (String path : DEFAULT_CONFIG_PATHS) {
            if (Files.exists(Paths.get(path))) {
                loader.fromFile(path);
                break;
            }
        }

        return loader.load();
    }

    /**
     * Loads configuration from environment only.
     */
    public static NodeConfig loadFromEnvironment() {
        return loader()
                .fromEnvironment()
                .load();
    }

    /**
     * Loads with defaults only.
     */
    public static NodeConfig loadDefaults() {
        return loader().load();
    }

    /**
     * Auto-detects and loads configuration.
     */
    public static NodeConfig autoLoad() {
        return loadAll(null);
    }

    // ==================== Validation ====================

    /**
     * Validates a configuration.
     */
    public static ValidationResult validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();

        if (config.nodeId() == null || config.nodeId().isBlank()) {
            errors.add("nodeId is required");
        }

        if (config.tcpPort() < 0 || config.tcpPort() > 65535) {
            errors.add("tcpPort must be between 0 and 65535");
        }

        if (config.listenPort() < 0 || config.listenPort() > 65535) {
            errors.add("listenPort must be between 0 and 65535");
        }

        // Validate multicast group format
        if (config.multicastGroup() != null) {
            String group = config.multicastGroup();
            if (!group.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
                errors.add("multicastGroup must be a valid IP address");
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * Validation result.
     */
    public record ValidationResult(boolean valid, List<String> errors) {
        public String getErrorSummary() {
            return String.join("; ", errors);
        }
    }

    // ==================== Serialization ====================

    /**
     * Saves configuration to JSON file.
     */
    public static void save(NodeConfig config, String filePath) throws ConfigException {
        try {
            Path path = Paths.get(filePath);
            Files.createDirectories(path.getParent());

            try (Writer writer = Files.newBufferedWriter(path)) {
                gson.toJson(config.toMap(), writer);
            }

            log.info("Configuration saved", "path", filePath);

        } catch (IOException e) {
            throw new ConfigException("Failed to save configuration: " + filePath, e);
        }
    }

    /**
     * Converts config to JSON string.
     */
    public static String toJson(NodeConfig config) {
        return gson.toJson(config.toMap());
    }

    /**
     * Parses config from JSON string.
     */
    @SuppressWarnings("unchecked")
    public static NodeConfig fromJson(String json) {
        Map<String, Object> map = gson.fromJson(json, Map.class);
        return NodeConfig.fromMap(map);
    }

    // ==================== Exception ====================

    /**
     * Configuration loading exception.
     */
    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
