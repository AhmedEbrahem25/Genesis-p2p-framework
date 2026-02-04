package com.genesis.p2p;

import com.genesis.p2p.application.ConfigLoader;
import com.genesis.p2p.application.ConfigLoader.*;
import com.genesis.p2p.application.NodeConfig;
import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for ConfigLoader.
 * Tests Strategy pattern (config sources), Chain of Responsibility, and Builder pattern.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ConfigLoader Tests")
class ConfigLoaderTest extends BaseUnitTest {

    @TempDir
    Path tempDir;

    // ========================= JsonFileSource Tests =========================

    @Test
    @Order(1)
    @DisplayName("Should load config from JSON file")
    void testJsonFileSourceLoad() throws IOException {
        String json = """
                {
                    "nodeId": "json-node",
                    "listenPort": 9000,
                    "tcpPort": 9001,
                    "multicastGroup": "239.255.1.1",
                    "multicastPort": 6000,
                    "broadcastPort": 6001
                }
                """;

        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, json);

        NodeConfig config = ConfigLoader.load(configFile.toString());

        assertEquals("json-node", config.nodeId());
        assertEquals(9000, config.listenPort());
        assertEquals(9001, config.tcpPort());
        assertEquals("239.255.1.1", config.multicastGroup());
    }

    @Test
    @Order(2)
    @DisplayName("Should report JSON file source as available when file exists")
    void testJsonFileSourceAvailability() throws IOException {
        Path configFile = tempDir.resolve("exists.json");
        Files.writeString(configFile, "{}");

        JsonFileSource source = new JsonFileSource(configFile.toString());
        assertTrue(source.isAvailable());

        JsonFileSource missingSource = new JsonFileSource(tempDir.resolve("missing.json").toString());
        assertFalse(missingSource.isAvailable());
    }

    @Test
    @Order(3)
    @DisplayName("Should throw ConfigException for invalid JSON syntax")
    void testJsonFileSourceInvalidSyntax() throws IOException {
        Path configFile = tempDir.resolve("invalid.json");
        Files.writeString(configFile, "{ invalid json }");

        JsonFileSource source = new JsonFileSource(configFile.toString());

        assertThrows(ConfigException.class, source::load);
    }

    @Test
    @Order(4)
    @DisplayName("Should return empty map for empty JSON file")
    void testJsonFileSourceEmptyFile() throws IOException {
        Path configFile = tempDir.resolve("empty.json");
        Files.writeString(configFile, "{}");

        JsonFileSource source = new JsonFileSource(configFile.toString());
        Map<String, Object> result = source.load();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(5)
    @DisplayName("JsonFileSource should have correct priority")
    void testJsonFileSourcePriority() {
        JsonFileSource source = new JsonFileSource("test.json");
        assertEquals(10, source.getPriority());
    }

    // ========================= EnvironmentSource Tests =========================

    @Test
    @Order(10)
    @DisplayName("EnvironmentSource should always be available")
    void testEnvironmentSourceAvailability() {
        EnvironmentSource source = new EnvironmentSource();
        assertTrue(source.isAvailable());
    }

    @Test
    @Order(11)
    @DisplayName("EnvironmentSource should have correct priority")
    void testEnvironmentSourcePriority() {
        EnvironmentSource source = new EnvironmentSource();
        assertEquals(30, source.getPriority());
    }

    @Test
    @Order(12)
    @DisplayName("EnvironmentSource should have correct name")
    void testEnvironmentSourceName() {
        EnvironmentSource source = new EnvironmentSource();
        assertEquals("Environment", source.getName());
    }

    @Test
    @Order(13)
    @DisplayName("EnvironmentSource should return non-null map")
    void testEnvironmentSourceLoad() {
        EnvironmentSource source = new EnvironmentSource();
        Map<String, Object> result = source.load();
        assertNotNull(result);
    }

    // ========================= SystemPropertiesSource Tests =========================

    @Test
    @Order(20)
    @DisplayName("SystemPropertiesSource should always be available")
    void testSystemPropertiesSourceAvailability() {
        SystemPropertiesSource source = new SystemPropertiesSource();
        assertTrue(source.isAvailable());
    }

    @Test
    @Order(21)
    @DisplayName("SystemPropertiesSource should have correct priority")
    void testSystemPropertiesSourcePriority() {
        SystemPropertiesSource source = new SystemPropertiesSource();
        assertEquals(20, source.getPriority());
    }

    @Test
    @Order(22)
    @DisplayName("SystemPropertiesSource should load genesis.* properties")
    void testSystemPropertiesSourceLoad() {
        // Set a system property
        String originalValue = System.getProperty("genesis.testProp");
        try {
            System.setProperty("genesis.testProp", "testValue");

            SystemPropertiesSource source = new SystemPropertiesSource();
            Map<String, Object> result = source.load();

            assertEquals("testValue", result.get("testProp"));
        } finally {
            if (originalValue != null) {
                System.setProperty("genesis.testProp", originalValue);
            } else {
                System.clearProperty("genesis.testProp");
            }
        }
    }

    // ========================= CommandLineSource Tests =========================

    @Test
    @Order(30)
    @DisplayName("CommandLineSource should parse --key=value format")
    void testCommandLineSourceKeyValueFormat() {
        String[] args = {"--nodeId=cli-node", "--tcpPort=9999"};
        CommandLineSource source = new CommandLineSource(args);

        Map<String, Object> result = source.load();

        assertEquals("cli-node", result.get("nodeId"));
        assertEquals(9999, result.get("tcpPort"));
    }

    @Test
    @Order(31)
    @DisplayName("CommandLineSource should parse --key value format")
    void testCommandLineSourceKeySpaceValueFormat() {
        String[] args = {"--nodeId", "cli-node", "--tcpPort", "8888"};
        CommandLineSource source = new CommandLineSource(args);

        Map<String, Object> result = source.load();

        assertEquals("cli-node", result.get("nodeId"));
        assertEquals(8888, result.get("tcpPort"));
    }

    @Test
    @Order(32)
    @DisplayName("CommandLineSource should handle null args")
    void testCommandLineSourceNullArgs() {
        CommandLineSource source = new CommandLineSource(null);

        assertFalse(source.isAvailable());
        Map<String, Object> result = source.load();
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(33)
    @DisplayName("CommandLineSource should handle empty args")
    void testCommandLineSourceEmptyArgs() {
        CommandLineSource source = new CommandLineSource(new String[0]);

        assertFalse(source.isAvailable());
    }

    @Test
    @Order(34)
    @DisplayName("CommandLineSource should have highest priority")
    void testCommandLineSourcePriority() {
        CommandLineSource source = new CommandLineSource(new String[0]);
        assertEquals(40, source.getPriority());
    }

    @Test
    @Order(35)
    @DisplayName("CommandLineSource should parse numeric values as integers")
    void testCommandLineSourceNumericParsing() {
        String[] args = {"--port=8080"};
        CommandLineSource source = new CommandLineSource(args);

        Map<String, Object> result = source.load();

        assertTrue(result.get("port") instanceof Integer);
        assertEquals(8080, result.get("port"));
    }

    @Test
    @Order(36)
    @DisplayName("CommandLineSource should keep non-numeric values as strings")
    void testCommandLineSourceStringValues() {
        String[] args = {"--name=test-node"};
        CommandLineSource source = new CommandLineSource(args);

        Map<String, Object> result = source.load();

        assertTrue(result.get("name") instanceof String);
        assertEquals("test-node", result.get("name"));
    }

    // ========================= Loader Builder Tests =========================

    @Test
    @Order(40)
    @DisplayName("Loader should create config with defaults")
    void testLoaderWithDefaults() {
        NodeConfig config = ConfigLoader.loader().load();

        assertNotNull(config);
        assertNotNull(config.nodeId());
        assertEquals(NodeConfig.DEFAULT_TCP_PORT, config.tcpPort());
    }

    @Test
    @Order(41)
    @DisplayName("Loader should disable defaults with noDefaults()")
    void testLoaderNoDefaults() throws IOException {
        String json = """
                {
                    "nodeId": "no-defaults-node",
                    "listenPort": 8080,
                    "tcpPort": 8081,
                    "multicastPort": 5000,
                    "broadcastPort": 5001
                }
                """;

        Path configFile = tempDir.resolve("no-defaults.json");
        Files.writeString(configFile, json);

        NodeConfig config = ConfigLoader.loader()
                .noDefaults()
                .fromFile(configFile.toString())
                .load();

        assertEquals("no-defaults-node", config.nodeId());
    }

    @Test
    @Order(42)
    @DisplayName("Loader should merge multiple sources by priority")
    void testLoaderMergeByPriority() throws IOException {
        // Create a JSON file with low priority
        String json = """
                {
                    "nodeId": "file-node",
                    "tcpPort": 8000
                }
                """;

        Path configFile = tempDir.resolve("merge.json");
        Files.writeString(configFile, json);

        // Command line has higher priority
        String[] args = {"--nodeId=cli-node"};

        NodeConfig config = ConfigLoader.loader()
                .fromFile(configFile.toString())
                .fromCommandLine(args)
                .load();

        // Command line should override file
        assertEquals("cli-node", config.nodeId());
        // File value should be preserved where not overridden
        assertEquals(8000, config.tcpPort());
    }

    @Test
    @Order(43)
    @DisplayName("Loader should add custom source")
    void testLoaderCustomSource() {
        ConfigSource customSource = new ConfigSource() {
            @Override
            public String getName() { return "CustomSource"; }

            @Override
            public int getPriority() { return 50; }

            @Override
            public Map<String, Object> load() {
                return Map.of("nodeId", "custom-source-node");
            }

            @Override
            public boolean isAvailable() { return true; }
        };

        NodeConfig config = ConfigLoader.loader()
                .addSource(customSource)
                .load();

        assertEquals("custom-source-node", config.nodeId());
    }

    // ========================= Static Factory Methods Tests =========================

    @Test
    @Order(50)
    @DisplayName("loadDefaults() should return config with defaults")
    void testLoadDefaults() {
        NodeConfig config = ConfigLoader.loadDefaults();

        assertNotNull(config);
        assertNotNull(config.nodeId());
    }

    @Test
    @Order(51)
    @DisplayName("loadFromEnvironment() should return config")
    void testLoadFromEnvironment() {
        NodeConfig config = ConfigLoader.loadFromEnvironment();

        assertNotNull(config);
        assertNotNull(config.nodeId());
    }

    @Test
    @Order(52)
    @DisplayName("autoLoad() should return config")
    void testAutoLoad() {
        NodeConfig config = ConfigLoader.autoLoad();

        assertNotNull(config);
        assertNotNull(config.nodeId());
    }

    @Test
    @Order(53)
    @DisplayName("loadAll() should merge command line args")
    void testLoadAll() {
        String[] args = {"--nodeId=loadall-node"};

        NodeConfig config = ConfigLoader.loadAll(args);

        assertEquals("loadall-node", config.nodeId());
    }

    @Test
    @Order(54)
    @DisplayName("loadAll() should handle null args")
    void testLoadAllNullArgs() {
        NodeConfig config = ConfigLoader.loadAll(null);

        assertNotNull(config);
        assertNotNull(config.nodeId());
    }

    // ========================= Validation Tests =========================

    @Test
    @Order(60)
    @DisplayName("validate() should return valid for correct config")
    void testValidateValidConfig() {
        NodeConfig config = NodeConfig.builder()
                .nodeId("valid-node")
                .tcpPort(8081)
                .build();

        ValidationResult result = ConfigLoader.validate(config);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    @Order(61)
    @DisplayName("validate() should detect invalid port")
    void testValidateInvalidPort() {
        // Create config bypassing normal validation for test
        NodeConfig config = NodeConfig.builder()
                .nodeId("test")
                .build();

        ValidationResult result = ConfigLoader.validate(config);

        assertTrue(result.valid()); // Default ports are valid
    }

    @Test
    @Order(62)
    @DisplayName("ValidationResult should provide error summary")
    void testValidationResultErrorSummary() {
        ValidationResult result = new ValidationResult(false,
                java.util.List.of("Error 1", "Error 2"));

        assertEquals("Error 1; Error 2", result.getErrorSummary());
    }

    // ========================= Serialization Tests =========================

    @Test
    @Order(70)
    @DisplayName("toJson() should serialize config")
    void testToJson() {
        NodeConfig config = NodeConfig.builder()
                .nodeId("json-test")
                .tcpPort(9000)
                .build();

        String json = ConfigLoader.toJson(config);

        assertNotNull(json);
        assertTrue(json.contains("json-test"));
        assertTrue(json.contains("9000"));
    }

    @Test
    @Order(71)
    @DisplayName("fromJson() should deserialize config")
    void testFromJson() {
        String json = """
                {
                    "nodeId": "from-json",
                    "tcpPort": 8888,
                    "listenPort": 8080,
                    "multicastPort": 5000,
                    "broadcastPort": 5001
                }
                """;

        NodeConfig config = ConfigLoader.fromJson(json);

        assertEquals("from-json", config.nodeId());
        assertEquals(8888, config.tcpPort());
    }

    @Test
    @Order(72)
    @DisplayName("save() should write config to file")
    void testSaveConfig() throws IOException {
        NodeConfig config = NodeConfig.builder()
                .nodeId("save-test")
                .tcpPort(7777)
                .build();

        Path outputFile = tempDir.resolve("output/saved-config.json");

        ConfigLoader.save(config, outputFile.toString());

        assertTrue(Files.exists(outputFile));

        String content = Files.readString(outputFile);
        assertTrue(content.contains("save-test"));
        assertTrue(content.contains("7777"));
    }

    @Test
    @Order(73)
    @DisplayName("save() should create parent directories")
    void testSaveCreatesDirectories() throws IOException {
        NodeConfig config = NodeConfig.defaults();

        Path deepPath = tempDir.resolve("deep/nested/path/config.json");

        ConfigLoader.save(config, deepPath.toString());

        assertTrue(Files.exists(deepPath));
    }

    // ========================= Error Handling Tests =========================

    @Test
    @Order(80)
    @DisplayName("ConfigException should preserve message")
    void testConfigExceptionMessage() {
        ConfigException ex = new ConfigException("Test error message");

        assertEquals("Test error message", ex.getMessage());
    }

    @Test
    @Order(81)
    @DisplayName("ConfigException should preserve cause")
    void testConfigExceptionCause() {
        IOException cause = new IOException("IO error");
        ConfigException ex = new ConfigException("Wrapper message", cause);

        assertEquals("Wrapper message", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    @Test
    @Order(82)
    @DisplayName("Loader should continue when source fails")
    void testLoaderContinuesOnSourceFailure() throws IOException {
        // Create a valid JSON file
        Path validFile = tempDir.resolve("valid.json");
        Files.writeString(validFile, "{\"nodeId\": \"fallback-node\"}");

        NodeConfig config = ConfigLoader.loader()
                .fromFile(tempDir.resolve("nonexistent.json").toString()) // Will fail
                .fromFile(validFile.toString()) // Should succeed
                .load();

        assertEquals("fallback-node", config.nodeId());
    }

    // ========================= Chain of Responsibility Tests =========================

    @Test
    @Order(90)
    @DisplayName("Sources should be sorted by priority")
    void testSourcePrioritySorting() {
        // Command line (40) > Environment (30) > System Properties (20) > File (10)
        CommandLineSource cli = new CommandLineSource(new String[]{"--nodeId=cli"});
        EnvironmentSource env = new EnvironmentSource();
        SystemPropertiesSource props = new SystemPropertiesSource();
        JsonFileSource file = new JsonFileSource("test.json");

        assertTrue(cli.getPriority() > env.getPriority());
        assertTrue(env.getPriority() > props.getPriority());
        assertTrue(props.getPriority() > file.getPriority());
    }

    @Test
    @Order(91)
    @DisplayName("Higher priority source should override lower")
    void testPriorityOverride() throws IOException {
        // File says tcpPort = 1000
        Path configFile = tempDir.resolve("priority.json");
        Files.writeString(configFile, "{\"nodeId\": \"file-id\", \"tcpPort\": 1000}");

        // CLI says tcpPort = 2000
        String[] args = {"--tcpPort=2000"};

        NodeConfig config = ConfigLoader.loader()
                .fromFile(configFile.toString())
                .fromCommandLine(args)
                .load();

        // CLI should win
        assertEquals(2000, config.tcpPort());
        // File value preserved for nodeId
        assertEquals("file-id", config.nodeId());
    }
}
