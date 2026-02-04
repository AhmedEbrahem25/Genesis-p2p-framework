package com.genesis.p2p.protocol.handshake;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.io.PrintStream;
import java.time.Instant;
import java.util.*;

/**
 * Debug command for handshake subsystem.
 *
 * Provides CLI-style commands for:
 * - Viewing handshake status and metrics
 * - Inspecting active/completed handshakes
 * - Viewing handshake timelines
 * - Triggering debug operations
 *
 * Usage:
 *   genesis debug handshake status
 *   genesis debug handshake metrics
 *   genesis debug handshake timeline [handshake-id]
 *   genesis debug handshake peers
 *   genesis debug handshake pending
 *   genesis debug handshake history [limit]
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class HandshakeDebugCommand {

    private static final NodeLogger log = NodeLogger.getLogger(HandshakeDebugCommand.class);

    private final HandshakeProcessor processor;
    private final HandshakeMetrics metrics;
    private final HandshakeTimeline timeline;
    private final HandshakeRetryPolicy retryPolicy;
    private final HandshakeConfig config;
    private final PrintStream out;

    public HandshakeDebugCommand(
            HandshakeProcessor processor,
            HandshakeMetrics metrics,
            HandshakeTimeline timeline,
            HandshakeRetryPolicy retryPolicy,
            HandshakeConfig config) {
        this(processor, metrics, timeline, retryPolicy, config, System.out);
    }

    public HandshakeDebugCommand(
            HandshakeProcessor processor,
            HandshakeMetrics metrics,
            HandshakeTimeline timeline,
            HandshakeRetryPolicy retryPolicy,
            HandshakeConfig config,
            PrintStream out) {
        this.processor = processor;
        this.metrics = metrics;
        this.timeline = timeline;
        this.retryPolicy = retryPolicy;
        this.config = config;
        this.out = out;
    }

    /**
     * Executes a debug command.
     *
     * @param args command arguments
     * @return command result
     */
    public CommandResult execute(String... args) {
        if (args == null || args.length == 0) {
            return showHelp();
        }

        String subcommand = args[0].toLowerCase();
        String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];

        try {
            return switch (subcommand) {
                case "status" -> showStatus();
                case "metrics" -> showMetrics();
                case "prometheus" -> showPrometheus();
                case "timeline" -> showTimeline(subArgs);
                case "peers" -> showPeerMetrics();
                case "pending" -> showPending();
                case "history" -> showHistory(subArgs);
                case "config" -> showConfig();
                case "blacklist" -> showBlacklist();
                case "clear-blacklist" -> clearBlacklist(subArgs);
                case "help", "-h", "--help" -> showHelp();
                default -> {
                    out.println("Unknown subcommand: " + subcommand);
                    yield showHelp();
                }
            };
        } catch (Exception e) {
            log.error("Error executing debug command", e, "subcommand", subcommand);
            return CommandResult.failure("Error: " + e.getMessage());
        }
    }

    /**
     * Shows handshake subsystem status.
     */
    private CommandResult showStatus() {
        out.println();
        out.println("╔════════════════════════════════════════════════════════════════╗");
        out.println("║              HANDSHAKE SUBSYSTEM STATUS                        ║");
        out.println("╠════════════════════════════════════════════════════════════════╣");

        HandshakeMetrics.MetricsSummary summary = metrics.getSummary();
        HandshakeProcessor.HandshakeStats stats = processor.getStats();

        out.printf("║ Total Handshakes:     %-42d ║%n", summary.total());
        out.printf("║ Successful:           %-42d ║%n", summary.success());
        out.printf("║ Failed:               %-42d ║%n", summary.failed());
        out.printf("║ Timeouts:             %-42d ║%n", summary.timeouts());
        out.printf("║ Deduplicated:         %-42d ║%n", summary.deduplicated());
        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.printf("║ Success Rate:         %-42.1f%% ║%n", summary.successRate());
        out.printf("║ Avg Duration:         %-42.1f ms ║%n", summary.avgDurationMs());
        out.printf("║ P50 Latency:          %-42d ms ║%n", summary.p50LatencyMs());
        out.printf("║ P95 Latency:          %-42d ms ║%n", summary.p95LatencyMs());
        out.printf("║ P99 Latency:          %-42d ms ║%n", summary.p99LatencyMs());
        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.printf("║ Pending Handshakes:   %-42d ║%n", stats.getPendingHandshakes());
        out.printf("║ Tracked Peers:        %-42d ║%n", stats.getTrackedPeers());
        out.printf("║ Active Sessions:      %-42d ║%n", summary.sessions());
        out.printf("║ Retry Attempts:       %-42d ║%n", summary.retries());
        out.printf("║ Blacklisted Peers:    %-42d ║%n", retryPolicy.getBlacklistSize());
        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.printf("║ Uptime:               %-42s ║%n", formatDuration(summary.uptime()));
        out.println("╚════════════════════════════════════════════════════════════════╝");
        out.println();

        return CommandResult.success("Status displayed");
    }

    /**
     * Shows detailed metrics.
     */
    private CommandResult showMetrics() {
        out.println();
        out.println("╔════════════════════════════════════════════════════════════════╗");
        out.println("║                    HANDSHAKE METRICS                           ║");
        out.println("╠════════════════════════════════════════════════════════════════╣");

        HandshakeMetrics.MetricsSummary summary = metrics.getSummary();

        // Counters section
        out.println("║ COUNTERS                                                       ║");
        out.println("║ ─────────────────────────────────────────────────────────────  ║");
        out.printf("║   handshake_total:              %-30d ║%n", summary.total());
        out.printf("║   handshake_success_total:      %-30d ║%n", summary.success());
        out.printf("║   handshake_failed_total:       %-30d ║%n", summary.failed());
        out.printf("║   handshake_timeout_total:      %-30d ║%n", summary.timeouts());
        out.printf("║   handshake_deduplicated_total: %-30d ║%n", summary.deduplicated());
        out.printf("║   handshake_retry_total:        %-30d ║%n", summary.retries());

        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.println("║ GAUGES                                                         ║");
        out.println("║ ─────────────────────────────────────────────────────────────  ║");
        out.printf("║   handshake_pending:            %-30d ║%n", summary.pending());
        out.printf("║   handshake_sessions_active:    %-30d ║%n", summary.sessions());

        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.println("║ HISTOGRAM (handshake_duration_seconds)                         ║");
        out.println("║ ─────────────────────────────────────────────────────────────  ║");
        out.printf("║   avg:  %-53.3f s ║%n", summary.avgDurationMs() / 1000.0);
        out.printf("║   p50:  %-53.3f s ║%n", summary.p50LatencyMs() / 1000.0);
        out.printf("║   p95:  %-53.3f s ║%n", summary.p95LatencyMs() / 1000.0);
        out.printf("║   p99:  %-53.3f s ║%n", summary.p99LatencyMs() / 1000.0);

        // Failure reasons
        Map<String, Long> failureReasons = metrics.getFailureReasonCounts();
        if (!failureReasons.isEmpty()) {
            out.println("╠════════════════════════════════════════════════════════════════╣");
            out.println("║ FAILURE REASONS                                                ║");
            out.println("║ ─────────────────────────────────────────────────────────────  ║");
            failureReasons.forEach((reason, count) ->
                    out.printf("║   %-40s %18d ║%n", truncate(reason, 40), count));
        }

        out.println("╚════════════════════════════════════════════════════════════════╝");
        out.println();

        return CommandResult.success("Metrics displayed");
    }

    /**
     * Shows metrics in Prometheus format.
     */
    private CommandResult showPrometheus() {
        out.println(metrics.toPrometheusFormat());
        return CommandResult.success("Prometheus metrics displayed");
    }

    /**
     * Shows handshake timeline.
     */
    private CommandResult showTimeline(String[] args) {
        if (args.length > 0) {
            // Show specific timeline
            String handshakeId = args[0];
            String ascii = timeline.getAsciiTimeline(handshakeId);
            out.println(ascii);
            return CommandResult.success("Timeline displayed");
        }

        // Show active timelines
        var activeTimelines = timeline.getActiveTimelines();
        if (activeTimelines.isEmpty()) {
            out.println("No active handshakes.");
            out.println();

            // Show recent completed
            var recentCompleted = timeline.getRecentCompletedTimelines(5);
            if (!recentCompleted.isEmpty()) {
                out.println("Recent completed handshakes:");
                for (var t : recentCompleted) {
                    out.println("  " + t.toOneLine());
                }
            }
        } else {
            out.println("Active handshakes:");
            for (var t : activeTimelines) {
                out.println("  " + t.toOneLine());
            }
        }
        out.println();
        out.println("Use 'genesis debug handshake timeline <handshake-id>' for details.");
        out.println();

        return CommandResult.success("Timeline summary displayed");
    }

    /**
     * Shows per-peer metrics.
     */
    private CommandResult showPeerMetrics() {
        Map<String, HandshakeMetrics.PeerMetrics> peerMetrics = metrics.getPerPeerMetrics();

        out.println();
        out.println("╔════════════════════════════════════════════════════════════════╗");
        out.println("║                    PER-PEER METRICS                            ║");
        out.println("╠════════════════════════════════════════════════════════════════╣");

        if (peerMetrics.isEmpty()) {
            out.println("║ No peer metrics recorded.                                      ║");
        } else {
            out.println("║ Peer ID              Init  Recv  Succ  Fail  T/O   Retry Dedup ║");
            out.println("║ ─────────────────────────────────────────────────────────────  ║");

            peerMetrics.forEach((peerId, pm) ->
                    out.printf("║ %-20s %5d %5d %5d %5d %5d %5d %5d ║%n",
                            truncate(peerId, 20),
                            pm.initiated.sum(),
                            pm.received.sum(),
                            pm.success.sum(),
                            pm.failed.sum(),
                            pm.timeouts.sum(),
                            pm.retries.sum(),
                            pm.deduplicated.sum()));
        }

        out.println("╚════════════════════════════════════════════════════════════════╝");
        out.println();

        return CommandResult.success("Peer metrics displayed");
    }

    /**
     * Shows pending handshakes.
     */
    private CommandResult showPending() {
        out.println();
        out.println("╔════════════════════════════════════════════════════════════════╗");
        out.println("║                   PENDING HANDSHAKES                           ║");
        out.println("╠════════════════════════════════════════════════════════════════╣");

        HandshakeProcessor.HandshakeStats stats = processor.getStats();
        out.printf("║ Pending handshakes: %-43d ║%n", stats.getPendingHandshakes());
        out.printf("║ Tracked peers:      %-43d ║%n", stats.getTrackedPeers());

        var activeTimelines = timeline.getActiveTimelines();
        if (!activeTimelines.isEmpty()) {
            out.println("╠════════════════════════════════════════════════════════════════╣");
            out.println("║ Active Timeline IDs:                                           ║");
            for (var t : activeTimelines) {
                out.printf("║   %-60s ║%n", t.toOneLine());
            }
        }

        out.println("╚════════════════════════════════════════════════════════════════╝");
        out.println();

        return CommandResult.success("Pending handshakes displayed");
    }

    /**
     * Shows handshake history.
     */
    private CommandResult showHistory(String[] args) {
        int limit = 10;
        if (args.length > 0) {
            try {
                limit = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }

        var recentCompleted = timeline.getRecentCompletedTimelines(limit);

        out.println();
        out.println("╔════════════════════════════════════════════════════════════════╗");
        out.printf("║              RECENT HANDSHAKES (last %d)                       ║%n", limit);
        out.println("╠════════════════════════════════════════════════════════════════╣");

        if (recentCompleted.isEmpty()) {
            out.println("║ No completed handshakes in history.                            ║");
        } else {
            for (var t : recentCompleted) {
                out.printf("║ %-62s ║%n", t.toOneLine());
            }
        }

        out.println("╚════════════════════════════════════════════════════════════════╝");
        out.println();

        return CommandResult.success("History displayed");
    }

    /**
     * Shows current configuration.
     */
    private CommandResult showConfig() {
        out.println();
        out.println("╔════════════════════════════════════════════════════════════════╗");
        out.println("║                 HANDSHAKE CONFIGURATION                        ║");
        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.println("║ TIMEOUTS                                                       ║");
        out.printf("║   Connection timeout:     %-37s ║%n", config.getConnectionTimeout());
        out.printf("║   Response timeout:       %-37s ║%n", config.getResponseTimeout());
        out.printf("║   Total handshake timeout:%-37s ║%n", config.getTotalHandshakeTimeout());
        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.println("║ RETRY POLICY                                                   ║");
        out.printf("║   Max retry attempts:     %-37d ║%n", config.getMaxRetryAttempts());
        out.printf("║   Initial retry delay:    %-37s ║%n", config.getInitialRetryDelay());
        out.printf("║   Max retry delay:        %-37s ║%n", config.getMaxRetryDelay());
        out.printf("║   Backoff multiplier:     %-37.1f ║%n", config.getRetryBackoffMultiplier());
        out.printf("║   Jitter factor:          %-37.2f ║%n", config.getRetryJitterFactor());
        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.println("║ RATE LIMITING                                                  ║");
        out.printf("║   Max attempts/window:    %-37d ║%n", config.getMaxAttemptsPerWindow());
        out.printf("║   Rate limit window:      %-37s ║%n", config.getRateLimitWindow());
        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.println("║ OBSERVABILITY                                                  ║");
        out.printf("║   Timeline enabled:       %-37s ║%n", config.isTimelineEnabled());
        out.printf("║   Detailed metrics:       %-37s ║%n", config.isDetailedMetricsEnabled());
        out.println("╚════════════════════════════════════════════════════════════════╝");
        out.println();

        return CommandResult.success("Configuration displayed");
    }

    /**
     * Shows blacklisted peers.
     */
    private CommandResult showBlacklist() {
        int size = retryPolicy.getBlacklistSize();

        out.println();
        out.println("╔════════════════════════════════════════════════════════════════╗");
        out.println("║                   BLACKLISTED PEERS                            ║");
        out.println("╠════════════════════════════════════════════════════════════════╣");
        out.printf("║ Total blacklisted: %-44d ║%n", size);
        out.println("╚════════════════════════════════════════════════════════════════╝");
        out.println();

        return CommandResult.success("Blacklist displayed");
    }

    /**
     * Clears a peer from blacklist.
     */
    private CommandResult clearBlacklist(String[] args) {
        if (args.length == 0) {
            out.println("Usage: genesis debug handshake clear-blacklist <peer-id>");
            return CommandResult.failure("Missing peer ID");
        }

        String peerId = args[0];
        retryPolicy.removeFromBlacklist(peerId);
        out.println("Removed " + peerId + " from blacklist.");

        return CommandResult.success("Peer removed from blacklist");
    }

    /**
     * Shows help message.
     */
    private CommandResult showHelp() {
        out.println();
        out.println("Genesis P2P Framework - Handshake Debug Commands");
        out.println();
        out.println("Usage: genesis debug handshake <subcommand> [args]");
        out.println();
        out.println("Subcommands:");
        out.println("  status              Show handshake subsystem status");
        out.println("  metrics             Show detailed metrics");
        out.println("  prometheus          Show metrics in Prometheus format");
        out.println("  timeline [id]       Show handshake timeline (optional: specific ID)");
        out.println("  peers               Show per-peer metrics");
        out.println("  pending             Show pending handshakes");
        out.println("  history [limit]     Show recent handshake history");
        out.println("  config              Show current configuration");
        out.println("  blacklist           Show blacklisted peers");
        out.println("  clear-blacklist <id>  Remove peer from blacklist");
        out.println("  help                Show this help message");
        out.println();

        return CommandResult.success("Help displayed");
    }

    // ========================= Utility Methods =========================

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    private String formatDuration(java.time.Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        } else {
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }
    }

    // ========================= Command Result =========================

    /**
     * Result of a debug command.
     */
    public record CommandResult(
            boolean success,
            String message,
            Object data,
            Instant executedAt
    ) {
        public static CommandResult success(String message) {
            return new CommandResult(true, message, null, Instant.now());
        }

        public static CommandResult success(String message, Object data) {
            return new CommandResult(true, message, data, Instant.now());
        }

        public static CommandResult failure(String message) {
            return new CommandResult(false, message, null, Instant.now());
        }
    }
}
