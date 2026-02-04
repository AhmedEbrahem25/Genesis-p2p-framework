package com.genesis.p2p.protocol.model;

import java.util.Objects;

/**
 * Protocol version management for the Genesis P2P framework.
 *
 * Version format: MAJOR.MINOR.PATCH
 * - MAJOR: Incompatible protocol changes
 * - MINOR: Backward-compatible feature additions
 * - PATCH: Backward-compatible bug fixes
 *
 * Compatibility rules:
 * - Same MAJOR version = compatible
 * - Higher MINOR version can talk to lower MINOR (with feature negotiation)
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ProtocolVersion implements Comparable<ProtocolVersion> {

    // Current protocol version
    public static final int CURRENT_MAJOR = 2;
    public static final int CURRENT_MINOR = 0;
    public static final int CURRENT_PATCH = 0;

    // Minimum supported version
    public static final int MIN_MAJOR = 1;
    public static final int MIN_MINOR = 0;

    private final int major;
    private final int minor;
    private final int patch;

    /**
     * Creates a protocol version.
     */
    public ProtocolVersion(int major, int minor) {
        this(major, minor, 0);
    }

    /**
     * Creates a protocol version with patch level.
     */
    public ProtocolVersion(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version numbers cannot be negative");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    // ==================== Factory Methods ====================

    /**
     * Gets the current protocol version.
     */
    public static ProtocolVersion current() {
        return new ProtocolVersion(CURRENT_MAJOR, CURRENT_MINOR, CURRENT_PATCH);
    }

    /**
     * Gets the minimum supported version.
     */
    public static ProtocolVersion minimum() {
        return new ProtocolVersion(MIN_MAJOR, MIN_MINOR);
    }

    /**
     * Parses version from string format "MAJOR.MINOR" or "MAJOR.MINOR.PATCH".
     */
    public static ProtocolVersion parse(String version) {
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("Version string cannot be null or empty");
        }

        String[] parts = version.split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("Invalid version format: " + version);
        }

        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
            return new ProtocolVersion(major, minor, patch);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid version format: " + version, e);
        }
    }

    // ==================== Compatibility Checking ====================

    /**
     * Checks if this version is compatible with another version.
     *
     * Versions are compatible if they have the same major version
     * and this version's minor is >= the other's minor.
     */
    public boolean isCompatibleWith(ProtocolVersion other) {
        if (other == null) {
            return false;
        }
        // Same major version = compatible
        return this.major == other.major;
    }

    /**
     * Checks if this version can communicate with another version.
     *
     * Returns true if:
     * - Versions are compatible (same major)
     * - Both versions are above minimum supported version
     */
    public boolean canCommunicateWith(ProtocolVersion other) {
        if (other == null) {
            return false;
        }
        // Must be compatible
        if (!isCompatibleWith(other)) {
            return false;
        }
        // Both must be at or above minimum
        return this.isAtLeast(minimum()) && other.isAtLeast(minimum());
    }

    /**
     * Checks if this version is at least the specified version.
     */
    public boolean isAtLeast(ProtocolVersion other) {
        return compareTo(other) >= 0;
    }

    /**
     * Checks if this version is newer than the specified version.
     */
    public boolean isNewerThan(ProtocolVersion other) {
        return compareTo(other) > 0;
    }

    /**
     * Checks if this version is older than the specified version.
     */
    public boolean isOlderThan(ProtocolVersion other) {
        return compareTo(other) < 0;
    }

    /**
     * Checks if this is the current version.
     */
    public boolean isCurrent() {
        return major == CURRENT_MAJOR && minor == CURRENT_MINOR && patch == CURRENT_PATCH;
    }

    /**
     * Negotiates the effective version for communication.
     * Returns the lower version of the two (for compatibility).
     */
    public ProtocolVersion negotiate(ProtocolVersion other) {
        if (!isCompatibleWith(other)) {
            throw new IllegalArgumentException("Cannot negotiate incompatible versions: " + this + " vs " + other);
        }
        // Use lower version for compatibility
        return compareTo(other) <= 0 ? this : other;
    }

    // ==================== Feature Support ====================

    /**
     * Checks if this version supports encryption.
     */
    public boolean supportsEncryption() {
        return major >= 1;
    }

    /**
     * Checks if this version supports compression.
     */
    public boolean supportsCompression() {
        return major >= 2 || (major == 1 && minor >= 1);
    }

    /**
     * Checks if this version supports fragmentation.
     */
    public boolean supportsFragmentation() {
        return major >= 2;
    }

    /**
     * Checks if this version supports NAT traversal.
     */
    public boolean supportsNatTraversal() {
        return major >= 2;
    }

    /**
     * Checks if this version supports key rotation.
     */
    public boolean supportsKeyRotation() {
        return major >= 2;
    }

    // ==================== Getters ====================

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getPatch() {
        return patch;
    }

    // ==================== Comparable ====================

    @Override
    public int compareTo(ProtocolVersion other) {
        if (other == null) {
            return 1;
        }
        int result = Integer.compare(this.major, other.major);
        if (result != 0) return result;

        result = Integer.compare(this.minor, other.minor);
        if (result != 0) return result;

        return Integer.compare(this.patch, other.patch);
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ProtocolVersion that = (ProtocolVersion) obj;
        return major == that.major && minor == that.minor && patch == that.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    @Override
    public String toString() {
        if (patch == 0) {
            return major + "." + minor;
        }
        return major + "." + minor + "." + patch;
    }

    /**
     * Returns version string in "vMAJOR.MINOR" format.
     */
    public String toDisplayString() {
        return "v" + toString();
    }
}
