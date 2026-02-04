package com.genesis.p2p.util.common;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive time utility class for P2P operations.
 * Provides consistent time handling, formatting, and duration calculations
 * across the entire framework.
 *
 * Features:
 * - High-precision timing (nano and millisecond)
 * - Duration formatting and calculations
 * - Timeout and expiration checks
 * - ISO-8601 timestamp formatting
 * - Thread-safe operations
 *
 * All timestamp methods use System.currentTimeMillis() for wall-clock time
 * and System.nanoTime() for elapsed time measurements.
 */
public final class Time {

    // Formatters
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

    private static final DateTimeFormatter READABLE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // Time constants
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long MINUTES_PER_HOUR = 60L;
    private static final long HOURS_PER_DAY = 24L;

    private Time() {
        throw new AssertionError("No instances allowed");
    }

    // ==================== Current Time ====================

    /**
     * Gets current timestamp in milliseconds (wall-clock time).
     * Use for absolute timestamps and expiration checks.
     *
     * @return current time in milliseconds since epoch
     */
    public static long currentMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Gets current timestamp in nanoseconds (high-precision monotonic time).
     * Use for elapsed time measurements and performance timing.
     * NOT suitable for absolute timestamps.
     *
     * @return current value of JVM's high-resolution timer
     */
    public static long currentNanos() {
        return System.nanoTime();
    }

    /**
     * Gets current timestamp in seconds.
     *
     * @return current time in seconds since epoch
     */
    public static long currentSeconds() {
        return currentMillis() / MILLIS_PER_SECOND;
    }

    /**
     * Gets current Instant.
     */
    public static Instant now() {
        return Instant.now();
    }

    // ==================== Time Conversions ====================

    /**
     * Converts milliseconds to seconds.
     */
    public static long toSeconds(long millis) {
        return millis / MILLIS_PER_SECOND;
    }

    /**
     * Converts seconds to milliseconds.
     */
    public static long toMillis(long seconds) {
        return seconds * MILLIS_PER_SECOND;
    }

    /**
     * Converts nanoseconds to milliseconds.
     */
    public static long nanosToMillis(long nanos) {
        return nanos / NANOS_PER_MILLI;
    }

    /**
     * Converts milliseconds to nanoseconds.
     */
    public static long millisToNanos(long millis) {
        return millis * NANOS_PER_MILLI;
    }

    /**
     * Converts nanoseconds to seconds.
     */
    public static double nanosToSeconds(long nanos) {
        return (double) nanos / NANOS_PER_SECOND;
    }

    /**
     * Converts using TimeUnit (more flexible).
     */
    public static long convert(long duration, TimeUnit from, TimeUnit to) {
        return to.convert(duration, from);
    }

    // ==================== Expiration & Timeout ====================

    /**
     * Checks if timestamp has expired.
     *
     * @param timestampMillis the timestamp to check
     * @param timeoutMillis the timeout duration
     * @return true if current time exceeds timestamp + timeout
     */
    public static boolean hasExpired(long timestampMillis, long timeoutMillis) {
        return currentMillis() - timestampMillis > timeoutMillis;
    }

    /**
     * Checks if elapsed time exceeds threshold (using nanoTime).
     *
     * @param startNanos the start time from currentNanos()
     * @param thresholdNanos the threshold in nanoseconds
     * @return true if elapsed time exceeds threshold
     */
    public static boolean hasElapsed(long startNanos, long thresholdNanos) {
        return currentNanos() - startNanos > thresholdNanos;
    }

    /**
     * Checks if timestamp is still valid (not expired).
     */
    public static boolean isValid(long timestampMillis, long timeoutMillis) {
        return !hasExpired(timestampMillis, timeoutMillis);
    }

    /**
     * Calculates remaining time before expiration.
     *
     * @param timestampMillis the timestamp
     * @param timeoutMillis the timeout duration
     * @return remaining milliseconds (0 if already expired)
     */
    public static long remainingMillis(long timestampMillis, long timeoutMillis) {
        long remaining = (timestampMillis + timeoutMillis) - currentMillis();
        return Math.max(0, remaining);
    }

    /**
     * Calculates timeout timestamp (current time + duration).
     *
     * @param durationMillis duration from now
     * @return timestamp of timeout
     */
    public static long timeoutAt(long durationMillis) {
        return currentMillis() + durationMillis;
    }

    /**
     * Calculates deadline (current nanos + duration).
     * Use with hasElapsed() for high-precision timeouts.
     *
     * @param durationNanos duration in nanoseconds
     * @return deadline in nanos
     */
    public static long deadlineNanos(long durationNanos) {
        return currentNanos() + durationNanos;
    }

    // ==================== Elapsed Time ====================

    /**
     * Calculates elapsed time in milliseconds.
     *
     * @param startMillis start timestamp from currentMillis()
     * @return elapsed milliseconds
     */
    public static long elapsedMillis(long startMillis) {
        return currentMillis() - startMillis;
    }

    /**
     * Calculates elapsed time in nanoseconds.
     *
     * @param startNanos start time from currentNanos()
     * @return elapsed nanoseconds
     */
    public static long elapsedNanos(long startNanos) {
        return currentNanos() - startNanos;
    }

    /**
     * Calculates elapsed time in seconds.
     */
    public static long elapsedSeconds(long startMillis) {
        return elapsedMillis(startMillis) / MILLIS_PER_SECOND;
    }

    /**
     * Calculates elapsed time as Duration.
     */
    public static Duration elapsed(long startMillis) {
        return Duration.ofMillis(elapsedMillis(startMillis));
    }

    // ==================== Time Checks ====================

    /**
     * Checks if timestamp is in the future.
     */
    public static boolean isFuture(long timestampMillis) {
        return timestampMillis > currentMillis();
    }

    /**
     * Checks if timestamp is in the past.
     */
    public static boolean isPast(long timestampMillis) {
        return timestampMillis < currentMillis();
    }

    /**
     * Checks if timestamp is approximately now (within tolerance).
     *
     * @param timestampMillis the timestamp to check
     * @param toleranceMillis acceptable difference in milliseconds
     * @return true if timestamp is within tolerance of current time
     */
    public static boolean isNow(long timestampMillis, long toleranceMillis) {
        long diff = Math.abs(currentMillis() - timestampMillis);
        return diff <= toleranceMillis;
    }

    // ==================== Formatting ====================

    /**
     * Formats timestamp as ISO-8601 string.
     * Example: "2024-12-12T15:30:45Z"
     *
     * @param timestampMillis the timestamp
     * @return ISO-8601 formatted string
     */
    public static String formatIso(long timestampMillis) {
        return ISO_FORMATTER.format(Instant.ofEpochMilli(timestampMillis));
    }

    /**
     * Formats current time as ISO-8601 string.
     */
    public static String formatIsoNow() {
        return formatIso(currentMillis());
    }

    /**
     * Formats timestamp as readable string.
     * Example: "2024-12-12 15:30:45"
     */
    public static String formatReadable(long timestampMillis) {
        return READABLE_FORMATTER.format(Instant.ofEpochMilli(timestampMillis));
    }

    /**
     * Parses ISO-8601 string to timestamp.
     *
     * @param isoString ISO-8601 formatted string
     * @return timestamp in milliseconds
     * @throws java.time.format.DateTimeParseException if invalid format
     */
    public static long parseIso(String isoString) {
        return Instant.parse(isoString).toEpochMilli();
    }

    /**
     * Formats duration as human-readable string.
     * Examples: "2d 5h", "3h 45m", "5m 30s", "45s"
     *
     * @param millis duration in milliseconds
     * @return formatted string
     */
    public static String formatDuration(long millis) {
        if (millis < 0) {
            return "-" + formatDuration(-millis);
        }

        long seconds = millis / MILLIS_PER_SECOND;
        long minutes = seconds / SECONDS_PER_MINUTE;
        long hours = minutes / MINUTES_PER_HOUR;
        long days = hours / HOURS_PER_DAY;

        if (days > 0) {
            return String.format("%dd %dh", days, hours % HOURS_PER_DAY);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes % MINUTES_PER_HOUR);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % SECONDS_PER_MINUTE);
        } else if (seconds > 0) {
            return String.format("%ds", seconds);
        } else {
            return String.format("%dms", millis);
        }
    }

    /**
     * Formats duration with full detail.
     * Example: "2d 5h 30m 45s"
     */
    public static String formatDurationFull(long millis) {
        if (millis < 0) {
            return "-" + formatDurationFull(-millis);
        }

        long seconds = millis / MILLIS_PER_SECOND;
        long minutes = seconds / SECONDS_PER_MINUTE;
        long hours = minutes / MINUTES_PER_HOUR;
        long days = hours / HOURS_PER_DAY;

        StringBuilder sb = new StringBuilder();

        if (days > 0) {
            sb.append(days).append("d ");
            hours %= HOURS_PER_DAY;
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
            minutes %= MINUTES_PER_HOUR;
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("m ");
            seconds %= SECONDS_PER_MINUTE;
        }
        sb.append(seconds).append("s");

        return sb.toString().trim();
    }

    /**
     * Formats nanoseconds as human-readable duration.
     * Example: "1.234ms", "123.456µs", "12.345s"
     */
    public static String formatNanos(long nanos) {
        if (nanos >= NANOS_PER_SECOND) {
            return String.format("%.3fs", nanos / (double) NANOS_PER_SECOND);
        } else if (nanos >= NANOS_PER_MILLI) {
            return String.format("%.3fms", nanos / (double) NANOS_PER_MILLI);
        } else if (nanos >= 1000) {
            return String.format("%.3fµs", nanos / 1000.0);
        } else {
            return nanos + "ns";
        }
    }

    // ==================== Duration Creation ====================

    /**
     * Creates Duration from milliseconds.
     */
    public static Duration ofMillis(long millis) {
        return Duration.ofMillis(millis);
    }

    /**
     * Creates Duration from seconds.
     */
    public static Duration ofSeconds(long seconds) {
        return Duration.ofSeconds(seconds);
    }

    /**
     * Creates Duration from minutes.
     */
    public static Duration ofMinutes(long minutes) {
        return Duration.ofMinutes(minutes);
    }

    /**
     * Creates Duration from hours.
     */
    public static Duration ofHours(long hours) {
        return Duration.ofHours(hours);
    }

    /**
     * Creates Duration from days.
     */
    public static Duration ofDays(long days) {
        return Duration.ofDays(days);
    }

    // ==================== Sleep ====================

    /**
     * Sleeps for specified milliseconds.
     * Wraps InterruptedException as RuntimeException.
     *
     * @param millis sleep duration in milliseconds
     * @throws RuntimeException if sleep is interrupted
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sleep interrupted", e);
        }
    }

    /**
     * Sleeps for specified duration.
     */
    public static void sleep(Duration duration) {
        sleep(duration.toMillis());
    }

    /**
     * Sleeps for specified time without throwing exception on interrupt.
     * Returns true if sleep completed, false if interrupted.
     *
     * @param millis sleep duration
     * @return true if sleep completed normally
     */
    public static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== Timing Utilities ====================

    /**
     * Measures execution time of a runnable in nanoseconds.
     *
     * @param task the task to measure
     * @return elapsed time in nanoseconds
     */
    public static long measureNanos(Runnable task) {
        long start = currentNanos();
        task.run();
        return elapsedNanos(start);
    }

    /**
     * Measures execution time of a runnable in milliseconds.
     */
    public static long measureMillis(Runnable task) {
        long start = currentMillis();
        task.run();
        return elapsedMillis(start);
    }

    /**
     * Simple stopwatch for timing operations.
     */
    public static class Stopwatch {
        private long startNanos;
        private long stopNanos;
        private boolean running;

        public Stopwatch() {
            reset();
        }

        public static Stopwatch createStarted() {
            Stopwatch sw = new Stopwatch();
            sw.start();
            return sw;
        }

        public void start() {
            startNanos = currentNanos();
            running = true;
        }

        public void stop() {
            stopNanos = currentNanos();
            running = false;
        }

        public void reset() {
            startNanos = 0;
            stopNanos = 0;
            running = false;
        }

        public long elapsedNanos() {
            if (running) {
                return currentNanos() - startNanos;
            }
            return stopNanos - startNanos;
        }

        public long elapsedMillis() {
            return nanosToMillis(elapsedNanos());
        }

        public String toString() {
            return formatNanos(elapsedNanos());
        }
    }
}