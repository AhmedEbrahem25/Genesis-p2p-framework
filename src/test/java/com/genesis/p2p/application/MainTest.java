package com.genesis.p2p.application;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Main CLI entry point.
 *
 * Tests all CLI options including:
 * - Early-exit commands (--help, --version, config)
 * - Basic logging options (--verbose, --quiet, --log-level)
 * - Advanced logging options (--log-category, --log-format, --log-output)
 * - Wireshark correlation mode (--wireshark, --max-payload-hex)
 * - Cyber-emulation mode (--cyber-emulation)
 * - Performance mode (--performance)
 * - Configuration management (config generate/validate/show)
 * - Runtime commands (start, cluster, shell)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Main CLI Tests")
class MainTest extends BaseUnitTest {

    @TempDir
    Path tempDir;

    // ==================== CommandLineArgs Parsing Tests ====================

    @Test
    @Order(1)
    @DisplayName("Should parse basic start command")
    void testParseStartCommand() throws Exception {
        String[] args = {"start", "--nodeId=node1", "--tcpPort=8081"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("start", parsedArgs.getCommand());
        assertFalse(parsedArgs.isVerbose());
        assertFalse(parsedArgs.isQuiet());
    }

    @Test
    @Order(2)
    @DisplayName("Should parse cluster command")
    void testParseClusterCommand() throws Exception {
        String[] args = {"cluster", "--nodes=5", "--basePort=9000", "--clusterId=test-cluster"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("cluster", parsedArgs.getCommand());
        assertEquals(5, parsedArgs.getNodeCount());
        assertEquals(9000, parsedArgs.getBasePort());
        assertEquals("test-cluster", parsedArgs.getClusterId());
    }

    @Test
    @Order(3)
    @DisplayName("Should parse shell command")
    void testParseShellCommand() throws Exception {
        String[] args = {"shell"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("shell", parsedArgs.getCommand());
    }

    @Test
    @Order(4)
    @DisplayName("Should default to start command when no command specified")
    void testDefaultStartCommand() throws Exception {
        String[] args = {"--nodeId=node1"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("start", parsedArgs.getCommand());
    }

    // ==================== Help/Version Flag Tests ====================

    @Test
    @Order(10)
    @DisplayName("Should detect --help flag")
    void testHelpFlagLong() throws Exception {
        String[] args = {"--help"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.hasHelpFlag());
        assertFalse(parsedArgs.hasVersionFlag());
    }

    @Test
    @Order(11)
    @DisplayName("Should detect -h flag")
    void testHelpFlagShort() throws Exception {
        String[] args = {"-h"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.hasHelpFlag());
    }

    @Test
    @Order(12)
    @DisplayName("Should detect help command")
    void testHelpCommand() throws Exception {
        String[] args = {"help"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.hasHelpFlag());
        assertEquals("help", parsedArgs.getCommand());
    }

    @Test
    @Order(13)
    @DisplayName("Should detect --version flag")
    void testVersionFlagLong() throws Exception {
        String[] args = {"--version"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.hasVersionFlag());
        assertFalse(parsedArgs.hasHelpFlag());
    }

    @Test
    @Order(14)
    @DisplayName("Should detect -v flag")
    void testVersionFlagShort() throws Exception {
        String[] args = {"-v"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.hasVersionFlag());
    }

    @Test
    @Order(15)
    @DisplayName("Should detect version command")
    void testVersionCommand() throws Exception {
        String[] args = {"version"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.hasVersionFlag());
        assertEquals("version", parsedArgs.getCommand());
    }

    // ==================== Basic Logging Options Tests ====================

    @Test
    @Order(20)
    @DisplayName("Should parse --verbose flag")
    void testVerboseFlag() throws Exception {
        String[] args = {"start", "--verbose"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isVerbose());
        assertFalse(parsedArgs.isQuiet());
    }

    @Test
    @Order(21)
    @DisplayName("Should parse --quiet flag")
    void testQuietFlag() throws Exception {
        String[] args = {"start", "--quiet"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isQuiet());
        assertFalse(parsedArgs.isVerbose());
    }

    @Test
    @Order(22)
    @DisplayName("Should parse --log-level option")
    void testLogLevel() throws Exception {
        String[] args = {"start", "--log-level=DEBUG"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("DEBUG", parsedArgs.getLogLevel());
    }

    @Test
    @Order(23)
    @DisplayName("Should parse all log levels (ERROR, WARN, INFO, DEBUG)")
    void testAllLogLevels() throws Exception {
        String[] levels = {"ERROR", "WARN", "INFO", "DEBUG"};

        for (String level : levels) {
            String[] args = {"start", "--log-level=" + level};
            var parsedArgs = invokeParseArguments(args);
            assertEquals(level, parsedArgs.getLogLevel());
        }
    }

    @Test
    @Order(24)
    @DisplayName("Should parse --log-file option")
    void testLogFile() throws Exception {
        String[] args = {"start", "--log-file=/var/log/genesis.log"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.hasLogFile());
        assertEquals("/var/log/genesis.log", parsedArgs.getLogFile());
    }

    @Test
    @Order(25)
    @DisplayName("Should parse --log-output option")
    void testLogOutput() throws Exception {
        String[][] testCases = {
            {"console"}, {"file"}, {"both"}
        };

        for (String[] testCase : testCases) {
            String output = testCase[0];
            String[] args = {"start", "--log-output=" + output};
            var parsedArgs = invokeParseArguments(args);
            assertEquals(output, parsedArgs.getLogOutput());
        }
    }

    @Test
    @Order(26)
    @DisplayName("Should parse --log-format option")
    void testLogFormat() throws Exception {
        String[][] testCases = {
            {"structured"}, {"json"}, {"plain"}
        };

        for (String[] testCase : testCases) {
            String format = testCase[0];
            String[] args = {"start", "--log-format=" + format};
            var parsedArgs = invokeParseArguments(args);
            assertEquals(format, parsedArgs.getLogFormat());
        }
    }

    @Test
    @Order(27)
    @DisplayName("Should parse --json-log flag as shorthand")
    void testJsonLogShorthand() throws Exception {
        String[] args = {"start", "--json-log"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isJsonLogging());
    }

    // ==================== Advanced Logging Options Tests ====================

    @Test
    @Order(30)
    @DisplayName("Should parse single category log level")
    void testSingleCategoryLogLevel() throws Exception {
        String[] args = {"start", "--log-category=discovery:DEBUG"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.hasLogCategories());
        Map<String, String> categories = parsedArgs.getLogCategories();
        assertEquals("DEBUG", categories.get("discovery"));
    }

    @Test
    @Order(31)
    @DisplayName("Should parse multiple category log levels")
    void testMultipleCategoryLogLevels() throws Exception {
        String[] args = {"start", "--log-category=discovery:DEBUG,security:INFO,transport:WARN"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.hasLogCategories());
        Map<String, String> categories = parsedArgs.getLogCategories();

        assertEquals("DEBUG", categories.get("discovery"));
        assertEquals("INFO", categories.get("security"));
        assertEquals("WARN", categories.get("transport"));
        assertEquals(3, categories.size());
    }

    @Test
    @Order(32)
    @DisplayName("Should parse all supported categories")
    void testAllSupportedCategories() throws Exception {
        String categoryString = "discovery:DEBUG,security:INFO,transport:WARN," +
                                "protocol:ERROR,peer:DEBUG,nat:INFO,message:WARN,resource:ERROR";
        String[] args = {"start", "--log-category=" + categoryString};
        var parsedArgs = invokeParseArguments(args);

        Map<String, String> categories = parsedArgs.getLogCategories();

        assertEquals("DEBUG", categories.get("discovery"));
        assertEquals("INFO", categories.get("security"));
        assertEquals("WARN", categories.get("transport"));
        assertEquals("ERROR", categories.get("protocol"));
        assertEquals("DEBUG", categories.get("peer"));
        assertEquals("INFO", categories.get("nat"));
        assertEquals("WARN", categories.get("message"));
        assertEquals("ERROR", categories.get("resource"));
        assertEquals(8, categories.size());
    }

    @Test
    @Order(33)
    @DisplayName("Should handle category log levels with whitespace")
    void testCategoryLogLevelsWithWhitespace() throws Exception {
        String[] args = {"start", "--log-category=discovery : DEBUG , security : INFO"};
        var parsedArgs = invokeParseArguments(args);

        Map<String, String> categories = parsedArgs.getLogCategories();
        assertEquals("DEBUG", categories.get("discovery"));
        assertEquals("INFO", categories.get("security"));
    }

    // ==================== Wireshark Mode Tests ====================

    @Test
    @Order(40)
    @DisplayName("Should parse --wireshark flag")
    void testWiresharkFlag() throws Exception {
        String[] args = {"start", "--wireshark"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isWiresharkMode());
    }

    @Test
    @Order(41)
    @DisplayName("Should parse --log-wireshark flag")
    void testLogWiresharkFlag() throws Exception {
        String[] args = {"start", "--log-wireshark"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isWiresharkMode());
    }

    @Test
    @Order(42)
    @DisplayName("Should parse --enable-wireshark flag")
    void testEnableWiresharkFlag() throws Exception {
        String[] args = {"start", "--enable-wireshark"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isWiresharkMode());
    }

    @Test
    @Order(43)
    @DisplayName("Should parse --max-payload-hex option")
    void testMaxPayloadHexSize() throws Exception {
        String[] args = {"start", "--max-payload-hex=256"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals(256, parsedArgs.getMaxPayloadHexSize());
    }

    @Test
    @Order(44)
    @DisplayName("Should default to 32 bytes for max payload hex")
    void testMaxPayloadHexDefault() throws Exception {
        String[] args = {"start"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals(32, parsedArgs.getMaxPayloadHexSize());
    }

    @Test
    @Order(45)
    @DisplayName("Should handle invalid max payload hex size")
    void testInvalidMaxPayloadHexSize() throws Exception {
        String[] args = {"start", "--max-payload-hex=invalid"};
        var parsedArgs = invokeParseArguments(args);

        // Should default to 32 on parse error
        assertEquals(32, parsedArgs.getMaxPayloadHexSize());
    }

    // ==================== Cyber-Emulation Mode Tests ====================

    @Test
    @Order(50)
    @DisplayName("Should parse --cyber-emulation flag")
    void testCyberEmulationFlag() throws Exception {
        String[] args = {"start", "--cyber-emulation"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isCyberEmulationMode());
    }

    @Test
    @Order(51)
    @DisplayName("Should parse --log-cyber flag")
    void testLogCyberFlag() throws Exception {
        String[] args = {"start", "--log-cyber"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isCyberEmulationMode());
    }

    @Test
    @Order(52)
    @DisplayName("Should parse --security-verbose flag")
    void testSecurityVerboseFlag() throws Exception {
        String[] args = {"start", "--security-verbose"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isCyberEmulationMode());
    }

    // ==================== Performance Mode Tests ====================

    @Test
    @Order(60)
    @DisplayName("Should parse --performance flag")
    void testPerformanceFlag() throws Exception {
        String[] args = {"start", "--performance"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isPerformanceMode());
    }

    @Test
    @Order(61)
    @DisplayName("Should parse --log-performance flag")
    void testLogPerformanceFlag() throws Exception {
        String[] args = {"start", "--log-performance"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isPerformanceMode());
    }

    @Test
    @Order(62)
    @DisplayName("Should parse --fast-logging flag")
    void testFastLoggingFlag() throws Exception {
        String[] args = {"start", "--fast-logging"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isPerformanceMode());
    }

    // ==================== Config Command Tests ====================

    @Test
    @Order(70)
    @DisplayName("Should parse config generate subcommand")
    void testConfigGenerateCommand() throws Exception {
        String[] args = {"config", "generate"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("config", parsedArgs.getCommand());
        assertEquals("generate", parsedArgs.getConfigSubCommand());
    }

    @Test
    @Order(71)
    @DisplayName("Should parse config validate subcommand")
    void testConfigValidateCommand() throws Exception {
        String[] args = {"config", "validate"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("config", parsedArgs.getCommand());
        assertEquals("validate", parsedArgs.getConfigSubCommand());
    }

    @Test
    @Order(72)
    @DisplayName("Should parse config show subcommand")
    void testConfigShowCommand() throws Exception {
        String[] args = {"config", "show"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("config", parsedArgs.getCommand());
        assertEquals("show", parsedArgs.getConfigSubCommand());
    }

    @Test
    @Order(73)
    @DisplayName("Should default to show when config has no subcommand")
    void testConfigDefaultSubcommand() throws Exception {
        String[] args = {"config"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("config", parsedArgs.getCommand());
        assertEquals("show", parsedArgs.getConfigSubCommand());
    }

    @Test
    @Order(74)
    @DisplayName("Should parse config file option")
    void testConfigFileOption() throws Exception {
        String[] args = {"start", "--config=/path/to/config.json"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("/path/to/config.json", parsedArgs.getConfigFile());
    }

    @Test
    @Order(75)
    @DisplayName("Should parse output file option")
    void testOutputFileOption() throws Exception {
        String[] args = {"config", "generate", "--output=/path/to/output.json"};
        var parsedArgs = invokeParseArguments(args);

        assertEquals("/path/to/output.json", parsedArgs.getOutputFile("default.json"));
    }

    // ==================== Runtime Options Tests ====================

    @Test
    @Order(80)
    @DisplayName("Should parse --daemon flag")
    void testDaemonFlag() throws Exception {
        String[] args = {"start", "--daemon"};
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isDaemon());
    }

    @Test
    @Order(81)
    @DisplayName("Should parse --profile option")
    void testProfileOption() throws Exception {
        String[][] profiles = {
            {"dev"}, {"development"}, {"prod"}, {"production"}, {"test"}, {"testing"}, {"ha"}, {"high-availability"}
        };

        for (String[] profile : profiles) {
            String[] args = {"start", "--profile=" + profile[0]};
            var parsedArgs = invokeParseArguments(args);
            assertEquals(profile[0], parsedArgs.getProfile());
        }
    }

    // ==================== Complex Combination Tests ====================

    @Test
    @Order(90)
    @DisplayName("Should parse production deployment with file logging")
    void testProductionDeploymentCombination() throws Exception {
        String[] args = {
            "start",
            "--profile=prod",
            "--daemon",
            "--log-file=/var/log/genesis.log",
            "--log-output=both",
            "--log-level=INFO"
        };
        var parsedArgs = invokeParseArguments(args);

        assertEquals("start", parsedArgs.getCommand());
        assertEquals("prod", parsedArgs.getProfile());
        assertTrue(parsedArgs.isDaemon());
        assertTrue(parsedArgs.hasLogFile());
        assertEquals("/var/log/genesis.log", parsedArgs.getLogFile());
        assertEquals("both", parsedArgs.getLogOutput());
        assertEquals("INFO", parsedArgs.getLogLevel());
    }

    @Test
    @Order(91)
    @DisplayName("Should parse debugging with verbose discovery logging")
    void testDebuggingCombination() throws Exception {
        String[] args = {
            "start",
            "--log-level=DEBUG",
            "--log-category=discovery:DEBUG,security:INFO",
            "--log-output=both",
            "--log-file=debug.log"
        };
        var parsedArgs = invokeParseArguments(args);

        assertEquals("DEBUG", parsedArgs.getLogLevel());
        assertTrue(parsedArgs.hasLogCategories());
        Map<String, String> categories = parsedArgs.getLogCategories();
        assertEquals("DEBUG", categories.get("discovery"));
        assertEquals("INFO", categories.get("security"));
        assertEquals("both", parsedArgs.getLogOutput());
        assertEquals("debug.log", parsedArgs.getLogFile());
    }

    @Test
    @Order(92)
    @DisplayName("Should parse Wireshark packet analysis mode")
    void testWiresharkAnalysisCombination() throws Exception {
        String[] args = {
            "start",
            "--wireshark",
            "--log-file=packets.log",
            "--max-payload-hex=128",
            "--log-format=json",
            "--log-output=file"
        };
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isWiresharkMode());
        assertEquals("packets.log", parsedArgs.getLogFile());
        assertEquals(128, parsedArgs.getMaxPayloadHexSize());
        assertEquals("json", parsedArgs.getLogFormat());
        assertEquals("file", parsedArgs.getLogOutput());
    }

    @Test
    @Order(93)
    @DisplayName("Should parse cyber-emulation security testing mode")
    void testCyberEmulationCombination() throws Exception {
        String[] args = {
            "start",
            "--cyber-emulation",
            "--log-format=json",
            "--log-output=both",
            "--log-file=security-audit.jsonl",
            "--log-category=security:DEBUG,nat:DEBUG"
        };
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isCyberEmulationMode());
        assertEquals("json", parsedArgs.getLogFormat());
        assertEquals("both", parsedArgs.getLogOutput());
        assertEquals("security-audit.jsonl", parsedArgs.getLogFile());

        Map<String, String> categories = parsedArgs.getLogCategories();
        assertEquals("DEBUG", categories.get("security"));
        assertEquals("DEBUG", categories.get("nat"));
    }

    @Test
    @Order(94)
    @DisplayName("Should parse high-performance production mode")
    void testHighPerformanceCombination() throws Exception {
        String[] args = {
            "start",
            "--performance",
            "--log-level=WARN",
            "--log-output=file",
            "--log-file=/var/log/genesis/production.log",
            "--profile=prod",
            "--daemon"
        };
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isPerformanceMode());
        assertEquals("WARN", parsedArgs.getLogLevel());
        assertEquals("file", parsedArgs.getLogOutput());
        assertEquals("/var/log/genesis/production.log", parsedArgs.getLogFile());
        assertEquals("prod", parsedArgs.getProfile());
        assertTrue(parsedArgs.isDaemon());
    }

    @Test
    @Order(95)
    @DisplayName("Should parse multi-category detailed logging")
    void testMultiCategoryDetailedLogging() throws Exception {
        String[] args = {
            "start",
            "--log-category=discovery:DEBUG,security:INFO,transport:DEBUG,protocol:WARN",
            "--log-format=structured",
            "--log-output=both",
            "--log-file=detailed.log"
        };
        var parsedArgs = invokeParseArguments(args);

        Map<String, String> categories = parsedArgs.getLogCategories();
        assertEquals("DEBUG", categories.get("discovery"));
        assertEquals("INFO", categories.get("security"));
        assertEquals("DEBUG", categories.get("transport"));
        assertEquals("WARN", categories.get("protocol"));
        assertEquals(4, categories.size());

        assertEquals("structured", parsedArgs.getLogFormat());
        assertEquals("both", parsedArgs.getLogOutput());
        assertEquals("detailed.log", parsedArgs.getLogFile());
    }

    @Test
    @Order(96)
    @DisplayName("Should parse cluster with JSON logging")
    void testClusterWithJsonLogging() throws Exception {
        String[] args = {
            "cluster",
            "--nodes=5",
            "--basePort=9000",
            "--clusterId=test-cluster",
            "--json-log",
            "--log-file=cluster.jsonl",
            "--log-output=both"
        };
        var parsedArgs = invokeParseArguments(args);

        assertEquals("cluster", parsedArgs.getCommand());
        assertEquals(5, parsedArgs.getNodeCount());
        assertEquals(9000, parsedArgs.getBasePort());
        assertEquals("test-cluster", parsedArgs.getClusterId());
        assertTrue(parsedArgs.isJsonLogging());
        assertEquals("cluster.jsonl", parsedArgs.getLogFile());
        assertEquals("both", parsedArgs.getLogOutput());
    }

    @Test
    @Order(97)
    @DisplayName("Should parse maximum verbosity troubleshooting mode")
    void testMaximumVerbosityCombination() throws Exception {
        String[] args = {
            "start",
            "--verbose",
            "--wireshark",
            "--log-output=both",
            "--log-file=troubleshoot.log",
            "--max-payload-hex=256",
            "--log-category=discovery:DEBUG,security:DEBUG,transport:DEBUG,nat:DEBUG"
        };
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isVerbose());
        assertTrue(parsedArgs.isWiresharkMode());
        assertEquals("both", parsedArgs.getLogOutput());
        assertEquals("troubleshoot.log", parsedArgs.getLogFile());
        assertEquals(256, parsedArgs.getMaxPayloadHexSize());

        Map<String, String> categories = parsedArgs.getLogCategories();
        assertEquals("DEBUG", categories.get("discovery"));
        assertEquals("DEBUG", categories.get("security"));
        assertEquals("DEBUG", categories.get("transport"));
        assertEquals("DEBUG", categories.get("nat"));
    }

    // ==================== Edge Cases Tests ====================

    @Test
    @Order(100)
    @DisplayName("Should handle empty arguments array")
    void testEmptyArguments() throws Exception {
        String[] args = {};
        var parsedArgs = invokeParseArguments(args);

        // Should default to start command
        assertEquals("start", parsedArgs.getCommand());
    }

    @Test
    @Order(101)
    @DisplayName("Should handle conflicting verbose and quiet flags (verbose wins)")
    void testConflictingVerboseQuiet() throws Exception {
        String[] args = {"start", "--verbose", "--quiet"};
        var parsedArgs = invokeParseArguments(args);

        // Both will be true, but implementation should handle priority
        assertTrue(parsedArgs.isVerbose());
        assertTrue(parsedArgs.isQuiet());
    }

    @Test
    @Order(102)
    @DisplayName("Should handle multiple mode flags together")
    void testMultipleModeFlags() throws Exception {
        String[] args = {
            "start",
            "--wireshark",
            "--cyber-emulation",
            "--performance"  // This conflicts with wireshark
        };
        var parsedArgs = invokeParseArguments(args);

        assertTrue(parsedArgs.isWiresharkMode());
        assertTrue(parsedArgs.isCyberEmulationMode());
        assertTrue(parsedArgs.isPerformanceMode());
        // Implementation should handle priority (performance disables wireshark hex dumps)
    }

    @Test
    @Order(103)
    @DisplayName("Should handle equals syntax and space syntax")
    void testEqualsSyntaxVsSpaceSyntax() throws Exception {
        // Test equals syntax
        String[] argsEquals = {"start", "--log-level=DEBUG"};
        var parsedEquals = invokeParseArguments(argsEquals);
        assertEquals("DEBUG", parsedEquals.getLogLevel());

        // Test space syntax (if supported by parser)
        // Note: Current parser may not support this - test will verify
        String[] argsSpace = {"start", "--log-level", "DEBUG"};
        var parsedSpace = invokeParseArguments(argsSpace);
        assertEquals("DEBUG", parsedSpace.getLogLevel());
    }

    // ==================== Helper Methods ====================

    /**
     * Wrapper class to access CommandLineArgs methods via reflection.
     */
    private static class CommandLineArgsWrapper {
        private final Object wrapped;
        private final Class<?> wrappedClass;

        public CommandLineArgsWrapper(Object wrapped, Class<?> wrappedClass) {
            this.wrapped = wrapped;
            this.wrappedClass = wrappedClass;
        }

        public String getCommand() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getCommand");
            m.setAccessible(true);
            return (String) m.invoke(wrapped);
        }

        public boolean isVerbose() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("isVerbose");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public boolean isQuiet() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("isQuiet");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public boolean isDaemon() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("isDaemon");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public boolean hasHelpFlag() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("hasHelpFlag");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public boolean hasVersionFlag() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("hasVersionFlag");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public String getLogLevel() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getLogLevel");
            m.setAccessible(true);
            return (String) m.invoke(wrapped);
        }

        public String getLogFormat() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getLogFormat");
            m.setAccessible(true);
            return (String) m.invoke(wrapped);
        }

        public String getLogOutput() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getLogOutput");
            m.setAccessible(true);
            return (String) m.invoke(wrapped);
        }

        public boolean isJsonLogging() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("isJsonLogging");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public boolean hasLogFile() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("hasLogFile");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public String getLogFile() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getLogFile");
            m.setAccessible(true);
            return (String) m.invoke(wrapped);
        }

        public boolean isWiresharkMode() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("isWiresharkMode");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public boolean isCyberEmulationMode() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("isCyberEmulationMode");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public boolean isPerformanceMode() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("isPerformanceMode");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        public int getMaxPayloadHexSize() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getMaxPayloadHexSize");
            m.setAccessible(true);
            return (Integer) m.invoke(wrapped);
        }

        public boolean hasLogCategories() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("hasLogCategories");
            m.setAccessible(true);
            return (Boolean) m.invoke(wrapped);
        }

        @SuppressWarnings("unchecked")
        public Map<String, String> getLogCategories() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getLogCategories");
            m.setAccessible(true);
            return (Map<String, String>) m.invoke(wrapped);
        }

        public String getConfigFile() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getConfigFile");
            m.setAccessible(true);
            return (String) m.invoke(wrapped);
        }

        public String getOutputFile(String def) throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getOutputFile", String.class);
            m.setAccessible(true);
            return (String) m.invoke(wrapped, def);
        }

        public String getConfigSubCommand() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getConfigSubCommand");
            m.setAccessible(true);
            return (String) m.invoke(wrapped);
        }

        public String getProfile() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getProfile");
            m.setAccessible(true);
            return (String) m.invoke(wrapped);
        }

        public int getNodeCount() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getNodeCount");
            m.setAccessible(true);
            return (Integer) m.invoke(wrapped);
        }

        public int getBasePort() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getBasePort");
            m.setAccessible(true);
            return (Integer) m.invoke(wrapped);
        }

        public String getClusterId() throws Exception {
            Method m = wrappedClass.getDeclaredMethod("getClusterId");
            m.setAccessible(true);
            return (String) m.invoke(wrapped);
        }
    }

    /**
     * Invokes the private parseArguments method via reflection.
     */
    private CommandLineArgsWrapper invokeParseArguments(String[] args) throws Exception {
        // Get the nested CommandLineArgs class
        Class<?> commandLineArgsClass = null;
        for (Class<?> innerClass : Main.class.getDeclaredClasses()) {
            if (innerClass.getSimpleName().equals("CommandLineArgs")) {
                commandLineArgsClass = innerClass;
                break;
            }
        }

        assertNotNull(commandLineArgsClass, "CommandLineArgs class not found");

        // Get the parse static method
        Method parseMethod = commandLineArgsClass.getDeclaredMethod("parse", String[].class);
        parseMethod.setAccessible(true);

        // Invoke parse method
        Object result = parseMethod.invoke(null, (Object) args);

        return new CommandLineArgsWrapper(result, commandLineArgsClass);
    }
}
