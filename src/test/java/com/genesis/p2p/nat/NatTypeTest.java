package com.genesis.p2p.nat;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NatType Tests")
class NatTypeTest extends BaseUnitTest {

    @Test
    @DisplayName("Should have all NAT types")
    void testNatTypes() {
        NatType[] types = NatType.values();
        assertTrue(types.length > 0);
    }

    @Test
    @DisplayName("Should check if supports unsolicited packets")
    void testSupportsUnsolicitedPackets() {
        // Open NAT should support unsolicited packets
        assertTrue(NatType.OPEN.supportsUnsolicitedPackets());

        // Symmetric NAT typically doesn't
        assertFalse(NatType.SYMMETRIC.supportsUnsolicitedPackets());
    }

    @Test
    @DisplayName("Should check hole punching capability")
    void testCanHolePunch() {
        // Full cone NATs should be able to hole punch with each other
        assertTrue(NatType.canHolePunch(NatType.FULL_CONE, NatType.FULL_CONE));

        // Two symmetric NATs cannot hole punch
        assertFalse(NatType.canHolePunch(NatType.SYMMETRIC, NatType.SYMMETRIC));
    }

    @Test
    @DisplayName("Should get recommended strategy")
    void testRecommendedStrategy() {
        String strategy = NatType.OPEN.getRecommendedStrategy();
        assertNotNull(strategy);
        assertTrue(strategy.length() > 0);
    }

    @Test
    @DisplayName("Should have severity ordering")
    void testSeverityOrdering() {
        // OPEN should be less severe than SYMMETRIC
        assertTrue(NatType.OPEN.ordinal() < NatType.SYMMETRIC.ordinal());
    }
}

