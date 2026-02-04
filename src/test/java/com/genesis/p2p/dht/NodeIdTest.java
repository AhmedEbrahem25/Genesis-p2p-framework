package com.genesis.p2p.dht;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NodeId Tests")
class NodeIdTest extends BaseUnitTest {

    @Test
    @DisplayName("Should create NodeId from bytes")
    void testCreateFromBytes() {
        byte[] bytes = new byte[NodeId.ID_LENGTH_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }

        NodeId nodeId = new NodeId(bytes);

        assertNotNull(nodeId);
        assertArrayEquals(bytes, nodeId.getBytes());
    }

    @Test
    @DisplayName("Should create random NodeId")
    void testRandom() {
        NodeId id1 = NodeId.random();
        NodeId id2 = NodeId.random();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }

    @Test
    @DisplayName("Should create NodeId from string")
    void testFromString() {
        NodeId id1 = NodeId.fromString("test-node-1");
        NodeId id2 = NodeId.fromString("test-node-1");
        NodeId id3 = NodeId.fromString("test-node-2");

        assertEquals(id1, id2);
        assertNotEquals(id1, id3);
    }

    @Test
    @DisplayName("Should throw on null bytes")
    void testNullBytes() {
        assertThrows(IllegalArgumentException.class, () -> new NodeId(null));
    }

    @Test
    @DisplayName("Should throw on invalid byte length")
    void testInvalidByteLength() {
        byte[] tooShort = new byte[10];
        byte[] tooLong = new byte[30];

        assertThrows(IllegalArgumentException.class, () -> new NodeId(tooShort));
        assertThrows(IllegalArgumentException.class, () -> new NodeId(tooLong));
    }

    @Test
    @DisplayName("Should calculate XOR distance")
    void testXorDistance() {
        NodeId id1 = NodeId.fromString("node-1");
        NodeId id2 = NodeId.fromString("node-2");

        NodeId distance = id1.xorDistance(id2);

        assertNotNull(distance);
        // XOR with self should be zero
        NodeId selfDistance = id1.xorDistance(id1);
        byte[] expected = new byte[NodeId.ID_LENGTH_BYTES];
        assertArrayEquals(expected, selfDistance.getBytes());
    }

    @Test
    @DisplayName("Should calculate common prefix length")
    void testCommonPrefixLength() {
        NodeId id1 = NodeId.fromString("node-1");

        // Same node should have max prefix length
        int selfPrefix = id1.commonPrefixLength(id1);
        assertEquals(NodeId.ID_LENGTH_BITS, selfPrefix);

        // Different nodes should have less prefix
        NodeId id2 = NodeId.fromString("node-2");
        int prefix = id1.commonPrefixLength(id2);
        assertTrue(prefix >= 0);
        assertTrue(prefix < NodeId.ID_LENGTH_BITS);
    }

    @Test
    @DisplayName("Should get bucket index")
    void testGetBucketIndex() {
        NodeId id1 = NodeId.fromString("node-1");
        NodeId id2 = NodeId.fromString("node-2");

        int bucketIndex = id1.getBucketIndex(id2);

        assertTrue(bucketIndex >= 0);
        assertTrue(bucketIndex < NodeId.ID_LENGTH_BITS);
    }

    @Test
    @DisplayName("Should convert to hex string")
    void testToHex() {
        NodeId nodeId = NodeId.random();
        String hex = nodeId.toHex();

        assertNotNull(hex);
        assertEquals(NodeId.ID_LENGTH_BYTES * 2, hex.length());
        assertTrue(hex.matches("[0-9a-f]+"));
    }

    @Test
    @DisplayName("Should compare NodeIds")
    void testCompareTo() {
        NodeId id1 = NodeId.fromString("aaa");
        NodeId id2 = NodeId.fromString("zzz");

        // Compare should be consistent
        int cmp1 = id1.compareTo(id2);
        int cmp2 = id2.compareTo(id1);

        assertTrue((cmp1 > 0 && cmp2 < 0) || (cmp1 < 0 && cmp2 > 0) || (cmp1 == 0 && cmp2 == 0));

        // Compare with self should be 0
        assertEquals(0, id1.compareTo(id1));
    }

    @Test
    @DisplayName("Should be equal for same bytes")
    void testEquals() {
        byte[] bytes = new byte[NodeId.ID_LENGTH_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 256);
        }

        NodeId id1 = new NodeId(bytes);
        NodeId id2 = new NodeId(bytes.clone());

        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());
    }

    @Test
    @DisplayName("Should have consistent hashCode")
    void testHashCode() {
        NodeId id = NodeId.fromString("test");
        int hash1 = id.hashCode();
        int hash2 = id.hashCode();

        assertEquals(hash1, hash2);
    }
}

