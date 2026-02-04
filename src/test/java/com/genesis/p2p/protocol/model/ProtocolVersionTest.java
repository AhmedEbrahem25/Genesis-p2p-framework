package com.genesis.p2p.protocol.model;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProtocolVersion Tests")
class ProtocolVersionTest extends BaseUnitTest {

    @Test
    @DisplayName("Should create version with major and minor")
    void testCreateMajorMinor() {
        ProtocolVersion version = new ProtocolVersion(1, 2);

        assertEquals(1, version.getMajor());
        assertEquals(2, version.getMinor());
        assertEquals(0, version.getPatch());
    }

    @Test
    @DisplayName("Should create version with major, minor, and patch")
    void testCreateMajorMinorPatch() {
        ProtocolVersion version = new ProtocolVersion(1, 2, 3);

        assertEquals(1, version.getMajor());
        assertEquals(2, version.getMinor());
        assertEquals(3, version.getPatch());
    }

    @Test
    @DisplayName("Should throw on negative version numbers")
    void testNegativeVersions() {
        assertThrows(IllegalArgumentException.class, () -> new ProtocolVersion(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolVersion(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolVersion(0, 0, -1));
    }

    @Test
    @DisplayName("Should get current version")
    void testCurrent() {
        ProtocolVersion current = ProtocolVersion.current();

        assertNotNull(current);
        assertTrue(current.getMajor() >= 1);
    }

    @Test
    @DisplayName("Should get minimum version")
    void testMinimum() {
        ProtocolVersion minimum = ProtocolVersion.minimum();

        assertNotNull(minimum);
        assertTrue(minimum.getMajor() >= 1);
    }

    @Test
    @DisplayName("Should parse version from string")
    void testParse() {
        ProtocolVersion v1 = ProtocolVersion.parse("1.2");
        assertEquals(1, v1.getMajor());
        assertEquals(2, v1.getMinor());

        ProtocolVersion v2 = ProtocolVersion.parse("2.3.4");
        assertEquals(2, v2.getMajor());
        assertEquals(3, v2.getMinor());
        assertEquals(4, v2.getPatch());
    }

    @Test
    @DisplayName("Should throw on invalid parse input")
    void testParseInvalid() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolVersion.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ProtocolVersion.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ProtocolVersion.parse("1"));
        assertThrows(IllegalArgumentException.class, () -> ProtocolVersion.parse("a.b"));
    }

    @Test
    @DisplayName("Should check compatibility - same major")
    void testCompatibleSameMajor() {
        ProtocolVersion v1 = new ProtocolVersion(2, 0);
        ProtocolVersion v2 = new ProtocolVersion(2, 5);

        assertTrue(v1.isCompatibleWith(v2));
        assertTrue(v2.isCompatibleWith(v1));
    }

    @Test
    @DisplayName("Should check incompatibility - different major")
    void testIncompatibleDifferentMajor() {
        ProtocolVersion v1 = new ProtocolVersion(1, 0);
        ProtocolVersion v2 = new ProtocolVersion(2, 0);

        assertFalse(v1.isCompatibleWith(v2));
        assertFalse(v2.isCompatibleWith(v1));
    }

    @Test
    @DisplayName("Should handle null in compatibility check")
    void testCompatibleWithNull() {
        ProtocolVersion v1 = new ProtocolVersion(1, 0);
        assertFalse(v1.isCompatibleWith(null));
    }

    @Test
    @DisplayName("Should compare versions")
    void testCompareTo() {
        ProtocolVersion v1 = new ProtocolVersion(1, 0, 0);
        ProtocolVersion v2 = new ProtocolVersion(1, 1, 0);
        ProtocolVersion v3 = new ProtocolVersion(2, 0, 0);

        assertTrue(v1.compareTo(v2) < 0);
        assertTrue(v2.compareTo(v1) > 0);
        assertTrue(v1.compareTo(v3) < 0);
        assertEquals(0, v1.compareTo(v1));
    }

    @Test
    @DisplayName("Should check equality")
    void testEquals() {
        ProtocolVersion v1 = new ProtocolVersion(1, 2, 3);
        ProtocolVersion v2 = new ProtocolVersion(1, 2, 3);
        ProtocolVersion v3 = new ProtocolVersion(1, 2, 4);

        assertEquals(v1, v2);
        assertNotEquals(v1, v3);
    }

    @Test
    @DisplayName("Should have consistent hashCode")
    void testHashCode() {
        ProtocolVersion v1 = new ProtocolVersion(1, 2, 3);
        ProtocolVersion v2 = new ProtocolVersion(1, 2, 3);

        assertEquals(v1.hashCode(), v2.hashCode());
    }

    @Test
    @DisplayName("Should convert to string")
    void testToString() {
        ProtocolVersion v1 = new ProtocolVersion(1, 2, 3);
        String str = v1.toString();

        assertNotNull(str);
        assertTrue(str.contains("1"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("3"));
    }
}

