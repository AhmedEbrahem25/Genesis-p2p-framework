package com.genesis.p2p.protocol.handshake;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visual handshake timeline for debugging and observability.
 *
 * Provides:
 * - Step-by-step tracing of handshake phases
 * - ASCII visualization for terminal output
 * - JSON export for structured logging/analysis
 * - Performance breakdown by phase
 *
 * Thread-safe for concurrent handshake tracking.
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class HandshakeTimeline {

    private static final NodeLogger log = NodeLogger.getLogger(HandshakeTimeline.class);
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .create();

    // Active timelines by handshake ID
    private final Map<String, Timeline> activeTimelines = new ConcurrentHashMap<>();

    // Completed timelines (limited history)
    private final LinkedList<Timeline> completedTimelines = new LinkedList<>();
    private static final int MAX_COMPLETED_HISTORY = 100;

    private final boolean enabled;
    private final String localNodeId;

    public HandshakeTimeline(String localNodeId, boolean enabled) {
        this.localNodeId = localNodeId;
        this.enabled = enabled;

        if (enabled) {
            log.info("HandshakeTimeline enabled", "nodeId", localNodeId);
        }
    }

    /**
     * Starts tracking a new handshake.
     */
    public String startHandshake(String peerId, boolean isInitiator) {
        if (!enabled) return null;

        String handshakeId = generateHandshakeId();
        Timeline timeline = new Timeline(handshakeId, localNodeId, peerId, isInitiator);
        timeline.addEvent(Phase.STARTED, "Handshake initiated");

        activeTimelines.put(handshakeId, timeline);

        log.debug("TIMELINE_STARTED",
                "handshakeId", handshakeId,
                "peerId", peerId,
                "role", isInitiator ? "initiator" : "responder");

        return handshakeId;
    }

    /**
     * Records a handshake phase event.
     */
    public void recordEvent(String handshakeId, Phase phase, String details) {
        if (!enabled || handshakeId == null) return;

        Timeline timeline = activeTimelines.get(handshakeId);
        if (timeline == null) {
            log.debug("Timeline not found", "handshakeId", handshakeId);
            return;
        }

        timeline.addEvent(phase, details);

        log.debug("TIMELINE_EVENT",
                "handshakeId", handshakeId,
                "phase", phase.name(),
                "details", details);
    }

    /**
     * Records an error in the timeline.
     */
    public void recordError(String handshakeId, String error, Throwable cause) {
        if (!enabled || handshakeId == null) return;

        Timeline timeline = activeTimelines.get(handshakeId);
        if (timeline == null) return;

        String details = error;
        if (cause != null) {
            details += " (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")";
        }

        timeline.addEvent(Phase.ERROR, details);
        timeline.setError(error);

        log.debug("TIMELINE_ERROR",
                "handshakeId", handshakeId,
                "error", error);
    }

    /**
     * Marks a handshake as complete.
     */
    public Timeline completeHandshake(String handshakeId, boolean success) {
        if (!enabled || handshakeId == null) return null;

        Timeline timeline = activeTimelines.remove(handshakeId);
        if (timeline == null) return null;

        timeline.addEvent(success ? Phase.COMPLETED : Phase.FAILED,
                success ? "Handshake successful" : "Handshake failed");
        timeline.complete(success);

        // Add to completed history
        synchronized (completedTimelines) {
            completedTimelines.addFirst(timeline);
            while (completedTimelines.size() > MAX_COMPLETED_HISTORY) {
                completedTimelines.removeLast();
            }
        }

        log.debug("TIMELINE_COMPLETED",
                "handshakeId", handshakeId,
                "success", success,
                "durationMs", timeline.getDuration().toMillis());

        return timeline;
    }

    /**
     * Gets the ASCII visualization for a handshake.
     */
    public String getAsciiTimeline(String handshakeId) {
        Timeline timeline = activeTimelines.get(handshakeId);
        if (timeline == null) {
            synchronized (completedTimelines) {
                timeline = completedTimelines.stream()
                        .filter(t -> t.handshakeId.equals(handshakeId))
                        .findFirst()
                        .orElse(null);
            }
        }

        if (timeline == null) {
            return "Timeline not found: " + handshakeId;
        }

        return timeline.toAscii();
    }

    /**
     * Gets the JSON representation for a handshake.
     */
    public String getJsonTimeline(String handshakeId) {
        Timeline timeline = activeTimelines.get(handshakeId);
        if (timeline == null) {
            synchronized (completedTimelines) {
                timeline = completedTimelines.stream()
                        .filter(t -> t.handshakeId.equals(handshakeId))
                        .findFirst()
                        .orElse(null);
            }
        }

        if (timeline == null) {
            return "{}";
        }

        return timeline.toJson();
    }

    /**
     * Gets all active timelines.
     */
    public List<Timeline> getActiveTimelines() {
        return new ArrayList<>(activeTimelines.values());
    }

    /**
     * Gets recent completed timelines.
     */
    public List<Timeline> getRecentCompletedTimelines(int limit) {
        synchronized (completedTimelines) {
            return completedTimelines.stream()
                    .limit(limit)
                    .toList();
        }
    }

    /**
     * Clears all timeline data.
     */
    public void clear() {
        activeTimelines.clear();
        synchronized (completedTimelines) {
            completedTimelines.clear();
        }
        log.info("Timeline data cleared");
    }

    private String generateHandshakeId() {
        return "hs-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ========================= Inner Classes =========================

    /**
     * Phases of a handshake.
     */
    public enum Phase {
        STARTED("⬤", "Started"),
        CONNECTING("◐", "Connecting"),
        REQUEST_SENT("→", "Request Sent"),
        REQUEST_RECEIVED("←", "Request Received"),
        VALIDATING("◑", "Validating"),
        KEY_EXCHANGE("🔑", "Key Exchange"),
        SESSION_CREATED("🔒", "Session Created"),
        RESPONSE_SENT("→", "Response Sent"),
        RESPONSE_RECEIVED("←", "Response Received"),
        AUTHENTICATED("✓", "Authenticated"),
        COMPLETED("✔", "Completed"),
        FAILED("✗", "Failed"),
        TIMEOUT("⏱", "Timeout"),
        RETRY("↻", "Retry"),
        DEDUPLICATED("⊜", "Deduplicated"),
        ERROR("!", "Error");

        private final String symbol;
        private final String description;

        Phase(String symbol, String description) {
            this.symbol = symbol;
            this.description = description;
        }

        public String getSymbol() {
            return symbol;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * A single event in the timeline.
     */
    public static class TimelineEvent {
        private final Instant timestamp;
        private final Phase phase;
        private final String details;
        private final long offsetMs; // Offset from timeline start

        public TimelineEvent(Instant timestamp, Phase phase, String details, long offsetMs) {
            this.timestamp = timestamp;
            this.phase = phase;
            this.details = details;
            this.offsetMs = offsetMs;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public Phase getPhase() {
            return phase;
        }

        public String getDetails() {
            return details;
        }

        public long getOffsetMs() {
            return offsetMs;
        }

        @Override
        public String toString() {
            return String.format("[+%4dms] %s %s: %s",
                    offsetMs, phase.symbol, phase.description, details);
        }
    }

    /**
     * Complete timeline for a handshake.
     */
    public static class Timeline {
        private final String handshakeId;
        private final String localNodeId;
        private final String remoteNodeId;
        private final boolean isInitiator;
        private final Instant startTime;
        private final List<TimelineEvent> events = new ArrayList<>();

        private volatile Instant endTime;
        private volatile boolean success;
        private volatile String error;

        public Timeline(String handshakeId, String localNodeId, String remoteNodeId, boolean isInitiator) {
            this.handshakeId = handshakeId;
            this.localNodeId = localNodeId;
            this.remoteNodeId = remoteNodeId;
            this.isInitiator = isInitiator;
            this.startTime = Instant.now();
        }

        public synchronized void addEvent(Phase phase, String details) {
            Instant now = Instant.now();
            long offsetMs = Duration.between(startTime, now).toMillis();
            events.add(new TimelineEvent(now, phase, details, offsetMs));
        }

        public void complete(boolean success) {
            this.endTime = Instant.now();
            this.success = success;
        }

        public void setError(String error) {
            this.error = error;
        }

        public Duration getDuration() {
            Instant end = endTime != null ? endTime : Instant.now();
            return Duration.between(startTime, end);
        }

        public String getHandshakeId() {
            return handshakeId;
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isComplete() {
            return endTime != null;
        }

        public List<TimelineEvent> getEvents() {
            return List.copyOf(events);
        }

        /**
         * Generates ASCII visualization of the timeline.
         */
        public String toAscii() {
            StringBuilder sb = new StringBuilder();

            // Header
            sb.append("╔════════════════════════════════════════════════════════════════╗\n");
            sb.append(String.format("║ HANDSHAKE TIMELINE: %-42s ║\n", handshakeId));
            sb.append("╠════════════════════════════════════════════════════════════════╣\n");

            // Metadata
            sb.append(String.format("║ Local:  %-55s ║\n", truncate(localNodeId, 55)));
            sb.append(String.format("║ Remote: %-55s ║\n", truncate(remoteNodeId, 55)));
            sb.append(String.format("║ Role:   %-55s ║\n", isInitiator ? "Initiator" : "Responder"));
            sb.append(String.format("║ Status: %-55s ║\n",
                    isComplete() ? (success ? "SUCCESS" : "FAILED") : "IN PROGRESS"));
            sb.append(String.format("║ Duration: %-53s ║\n", getDuration().toMillis() + "ms"));
            sb.append("╠════════════════════════════════════════════════════════════════╣\n");

            // Timeline visualization
            sb.append("║                                                                ║\n");

            long totalMs = getDuration().toMillis();
            int barWidth = 50;

            synchronized (events) {
                for (TimelineEvent event : events) {
                    // Calculate position on the bar
                    int position = totalMs > 0
                            ? (int) (event.offsetMs * barWidth / totalMs)
                            : 0;
                    position = Math.min(position, barWidth - 1);

                    // Draw timeline bar
                    sb.append("║ ");
                    sb.append(event.phase.symbol);
                    sb.append(" [");
                    for (int i = 0; i < barWidth; i++) {
                        if (i == position) {
                            sb.append("●");
                        } else if (i < position) {
                            sb.append("─");
                        } else {
                            sb.append("·");
                        }
                    }
                    sb.append("] ");
                    sb.append(String.format("%4dms", event.offsetMs));
                    sb.append(" ║\n");

                    // Event description
                    String desc = String.format("   └─ %s: %s",
                            event.phase.description, truncate(event.details, 40));
                    sb.append(String.format("║ %-62s ║\n", desc));
                }
            }

            // Error if any
            if (error != null) {
                sb.append("╠════════════════════════════════════════════════════════════════╣\n");
                sb.append(String.format("║ ERROR: %-56s ║\n", truncate(error, 56)));
            }

            sb.append("║                                                                ║\n");
            sb.append("╚════════════════════════════════════════════════════════════════╝\n");

            return sb.toString();
        }

        /**
         * Generates JSON representation of the timeline.
         */
        public String toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("handshakeId", handshakeId);
            json.put("localNodeId", localNodeId);
            json.put("remoteNodeId", remoteNodeId);
            json.put("role", isInitiator ? "initiator" : "responder");
            json.put("startTime", startTime.toString());
            json.put("endTime", endTime != null ? endTime.toString() : null);
            json.put("durationMs", getDuration().toMillis());
            json.put("success", success);
            json.put("error", error);

            List<Map<String, Object>> eventList = new ArrayList<>();
            synchronized (events) {
                for (TimelineEvent event : events) {
                    Map<String, Object> eventJson = new LinkedHashMap<>();
                    eventJson.put("timestamp", event.timestamp.toString());
                    eventJson.put("offsetMs", event.offsetMs);
                    eventJson.put("phase", event.phase.name());
                    eventJson.put("phaseSymbol", event.phase.symbol);
                    eventJson.put("details", event.details);
                    eventList.add(eventJson);
                }
            }
            json.put("events", eventList);

            return gson.toJson(json);
        }

        /**
         * Generates a compact one-line summary.
         */
        public String toOneLine() {
            StringBuilder sb = new StringBuilder();
            sb.append(handshakeId).append(" ");
            sb.append(isInitiator ? "→" : "←").append(" ");
            sb.append(truncate(remoteNodeId, 16)).append(" ");
            sb.append("[");

            synchronized (events) {
                for (TimelineEvent event : events) {
                    sb.append(event.phase.symbol);
                }
            }

            sb.append("] ");
            sb.append(getDuration().toMillis()).append("ms ");
            sb.append(isComplete() ? (success ? "OK" : "FAIL") : "...");

            return sb.toString();
        }

        private String truncate(String s, int maxLen) {
            if (s == null) return "";
            return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
        }
    }
}
