package com.genesis.p2p.nat;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NatType Tests")
class NatTypeEnumTest extends BaseUnitTest {

    @Test
    @DisplayName("Should have correct severity ordering")
    void testSeverityOrdering() {
        assertTrue(NatType.OPEN.getSeverity() < NatType.FULL_CONE.getSeverity());
        assertTrue(NatType.FULL_CONE.getSeverity() < NatType.RESTRICTED_CONE.getSeverity());
        assertTrue(NatType.RESTRICTED_CONE.getSeverity() < NatType.PORT_RESTRICTED_CONE.getSeverity());
        assertTrue(NatType.PORT_RESTRICTED_CONE.getSeverity() < NatType.SYMMETRIC.getSeverity());
        assertTrue(NatType.SYMMETRIC.getSeverity() < NatType.UNKNOWN.getSeverity());
    }

    @Test
    @DisplayName("Should have description")
    void testDescription() {
        for (NatType type : NatType.values()) {
            assertNotNull(type.getDescription());
            assertFalse(type.getDescription().isEmpty());
        }
    }

    @Test
    @DisplayName("OPEN should support direct P2P")
    void testOpenSupportsDirectP2P() {
        assertTrue(NatType.OPEN.supportsDirectP2P());
        assertTrue(NatType.OPEN.supportsUnsolicitedPackets());
    }

    @Test
    @DisplayName("FULL_CONE should support direct P2P")
    void testFullConeSupportsDirectP2P() {
        assertTrue(NatType.FULL_CONE.supportsDirectP2P());
        assertTrue(NatType.FULL_CONE.supportsUnsolicitedPackets());
    }

    @Test
    @DisplayName("RESTRICTED_CONE should support direct P2P but not unsolicited")
    void testRestrictedCone() {
        assertTrue(NatType.RESTRICTED_CONE.supportsDirectP2P());
        assertFalse(NatType.RESTRICTED_CONE.supportsUnsolicitedPackets());
    }

    @Test
    @DisplayName("SYMMETRIC should not support direct P2P")
    void testSymmetric() {
        assertFalse(NatType.SYMMETRIC.supportsDirectP2P());
        assertFalse(NatType.SYMMETRIC.supportsUnsolicitedPackets());
    }

    @Test
    @DisplayName("UNKNOWN should not support P2P features")
    void testUnknown() {
        assertFalse(NatType.UNKNOWN.supportsDirectP2P());
        assertFalse(NatType.UNKNOWN.supportsUnsolicitedPackets());
    }

    @Test
    @DisplayName("Should be able to hole punch with OPEN")
    void testHolePunchWithOpen() {
        assertTrue(NatType.canHolePunch(NatType.OPEN, NatType.SYMMETRIC));
        assertTrue(NatType.canHolePunch(NatType.SYMMETRIC, NatType.OPEN));
        assertTrue(NatType.canHolePunch(NatType.OPEN, NatType.OPEN));
    }

    @Test
    @DisplayName("Should be able to hole punch with FULL_CONE")
    void testHolePunchWithFullCone() {
        assertTrue(NatType.canHolePunch(NatType.FULL_CONE, NatType.SYMMETRIC));
        assertTrue(NatType.canHolePunch(NatType.SYMMETRIC, NatType.FULL_CONE));
    }

    @Test
    @DisplayName("Two SYMMETRIC cannot hole punch")
    void testSymmetricCannotHolePunch() {
        assertFalse(NatType.canHolePunch(NatType.SYMMETRIC, NatType.SYMMETRIC));
    }

    @Test
    @DisplayName("All enum values should be accessible")
    void testAllValues() {
        NatType[] values = NatType.values();
        assertEquals(6, values.length);

        assertNotNull(NatType.valueOf("OPEN"));
        assertNotNull(NatType.valueOf("FULL_CONE"));
        assertNotNull(NatType.valueOf("RESTRICTED_CONE"));
        assertNotNull(NatType.valueOf("PORT_RESTRICTED_CONE"));
        assertNotNull(NatType.valueOf("SYMMETRIC"));
        assertNotNull(NatType.valueOf("UNKNOWN"));
    }
}

