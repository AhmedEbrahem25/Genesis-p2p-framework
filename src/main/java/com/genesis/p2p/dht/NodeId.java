package com.genesis.p2p.dht;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class NodeId implements Comparable<NodeId> {
    public static final int ID_LENGTH_BITS = 160;
    public static final int ID_LENGTH_BYTES = ID_LENGTH_BITS / 8;
    private final byte[] id;

    public NodeId(byte[] id) {
        if (id == null || id.length != ID_LENGTH_BYTES) {
            throw new IllegalArgumentException("Node ID must be " + ID_LENGTH_BYTES + " bytes");
        }
        this.id = Arrays.copyOf(id, id.length);
    }

    public static NodeId fromString(String nodeIdString) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return new NodeId(sha1.digest(nodeIdString.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    public static NodeId random() {
        byte[] bytes = new byte[ID_LENGTH_BYTES];
        new java.security.SecureRandom().nextBytes(bytes);
        return new NodeId(bytes);
    }

    public byte[] getBytes() { return Arrays.copyOf(id, id.length); }

    public NodeId xorDistance(NodeId other) {
        byte[] result = new byte[ID_LENGTH_BYTES];
        for (int i = 0; i < ID_LENGTH_BYTES; i++) {
            result[i] = (byte) (this.id[i] ^ other.id[i]);
        }
        return new NodeId(result);
    }

    public int commonPrefixLength(NodeId other) {
        NodeId distance = xorDistance(other);
        for (int i = 0; i < ID_LENGTH_BYTES; i++) {
            int b = distance.id[i] & 0xFF;
            if (b == 0) continue;
            return i * 8 + Integer.numberOfLeadingZeros(b) - 24;
        }
        return ID_LENGTH_BITS;
    }

    public int getBucketIndex(NodeId other) {
        int prefixLen = commonPrefixLength(other);
        if (prefixLen >= ID_LENGTH_BITS) return ID_LENGTH_BITS - 1;
        return ID_LENGTH_BITS - 1 - prefixLen;
    }

    public String toHex() {
        StringBuilder sb = new StringBuilder(ID_LENGTH_BYTES * 2);
        for (byte b : id) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    @Override
    public int compareTo(NodeId other) {
        for (int i = 0; i < ID_LENGTH_BYTES; i++) {
            int cmp = Integer.compare(this.id[i] & 0xFF, other.id[i] & 0xFF);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Arrays.equals(id, ((NodeId) o).id);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(id); }

    @Override
    public String toString() { return toHex().substring(0, 16) + "..."; }
}

