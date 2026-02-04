package com.genesis.p2p.core;

import com.genesis.p2p.nat.NatType;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Base64;

/**
 * Peer entity representing a node in the P2P network.
 *
 * This record implements Serializable to support persistence via ObjectOutputStream.
 * All fields are serializable:
 * - Primitives (int, long, boolean)
 * - Strings
 * - Instant (implements Serializable)
 * - NatType (enum - automatically Serializable)
 *
 * Security fields (v2.1):
 * - identityPublicKey: Long-term identity key for signing (Base64 encoded)
 *   Used during secure channel negotiation to sign ephemeral keys
 *
 * @author Genesis P2P Framework
 * @version 2.1
 */
public record Peer(
        String id,
        String publicKey,
        String hostName,

        // Local addressing (behind NAT)
        String ip,
        int port,

        // Public addressing (from STUN detection)
        String publicIp,
        int publicPort,

        // NAT information
        NatType natType,
        boolean behindNat,

        boolean online,
        Instant lastSeen,
        long lastLatency,
        boolean trusted,
        int reputation, //If the node behaves incorrectly (spam, flooding, invalid HMAC), speech impairment will occur.
        String version,
        String os ,
        String agent,

        // Security: Identity public key for signature verification (Base64 encoded)
        String identityPublicKey
) implements Serializable {

    /**
     * Serial version UID for serialization compatibility.
     * Incremented to 2L for identityPublicKey field addition.
     */
    @java.io.Serial
    private static final long serialVersionUID = 2L;

    public Peer {
        // -------- ID validation ----------
        if (id == null || id.isEmpty())
            throw new IllegalArgumentException("Peer id cannot be empty.");

        // -------- Network validation ------
        if (ip == null || ip.isEmpty())
            throw new IllegalArgumentException("Peer IP cannot be empty.");

        if (port <= 0 || port > 65535)
            throw new IllegalArgumentException("Peer port is invalid.");

        // -------- Hostname safety ---------
        if (hostName == null)
            hostName = "unknown";

        // -------- Online status -----------
        if (lastSeen == null)
            lastSeen = Instant.now();

        // -------- Latency default ---------
        if (lastLatency < 0)
            lastLatency = 0;

        // -------- Public key default ------
        if (publicKey == null)
            publicKey = "";

        // -------- Version defaults --------
        if (version == null)
            version = "1.0";

        // -------- NAT defaults --------
        if (natType == null)
            natType = NatType.UNKNOWN;

        if (publicIp == null || publicIp.isEmpty())
            publicIp = ip; // Fallback to local IP if public not available

        if (publicPort <= 0 || publicPort > 65535)
            publicPort = port; // Fallback to local port if public not available

        if (os == null)
            os = "unknown";

        if (agent == null)
            agent = "genesis-agent";

        // -------- Reputation --------------
        if (reputation < 0)
            reputation = 0;

        // -------- Identity key default ------
        if (identityPublicKey == null)
            identityPublicKey = "";
    }

    /**
     * Gets the effective connection address based on NAT status.
     * Returns public endpoint if available, otherwise local endpoint.
     */
    public InetSocketAddress getConnectionAddress() {
        if (publicIp != null && !publicIp.isEmpty() && publicPort > 0) {
            return new InetSocketAddress(publicIp, publicPort);
        }
        return new InetSocketAddress(ip, port);
    }

    /**
     * Gets the local endpoint.
     */
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress(ip, port);
    }

    /**
     * Gets the public endpoint (if detected).
     */
    public InetSocketAddress getPublicAddress() {
        return new InetSocketAddress(publicIp, publicPort);
    }

    /**
     * Checks if this peer has public endpoint information.
     */
    public boolean hasPublicEndpoint() {
        return publicIp != null && !publicIp.isEmpty() &&
               publicPort > 0 && !publicIp.equals(ip);
    }

    /**
     * Checks if this peer is directly reachable (OPEN or FULL_CONE).
     */
    public boolean isDirectlyReachable() {
        return natType == NatType.OPEN || natType == NatType.FULL_CONE;
    }

    /**
     * Checks if this peer supports hole punching.
     */
    public boolean supportsHolePunching() {
        return natType != null && natType.supportsDirectP2P();
    }

    /**
     * Gets the identity public key as bytes.
     * Returns null if no identity key is set.
     */
    public byte[] getIdentityPublicKeyBytes() {
        if (identityPublicKey == null || identityPublicKey.isEmpty()) {
            return null;
        }
        return Base64.getDecoder().decode(identityPublicKey);
    }

    /**
     * Checks if this peer has an identity public key.
     */
    public boolean hasIdentityPublicKey() {
        return identityPublicKey != null && !identityPublicKey.isEmpty();
    }

    /**
     * Creates a Peer without NAT information (backward compatibility).
     * Uses local IP/port as public endpoint with UNKNOWN NAT type.
     */
    public static Peer withoutNat(
            String id,
            String publicKey,
            String hostName,
            String ip,
            int port,
            boolean online,
            Instant lastSeen,
            long lastLatency,
            boolean trusted,
            int reputation,
            String version,
            String os,
            String agent) {
        return new Peer(
            id, publicKey, hostName,
            ip, port,
            ip, port,  // Use same for public
            NatType.UNKNOWN,
            false,
            online, lastSeen, lastLatency, trusted, reputation,
            version, os, agent,
            ""  // No identity key
        );
    }

    /**
     * Creates a Peer without NAT information but with identity public key.
     */
    public static Peer withoutNat(
            String id,
            String publicKey,
            String hostName,
            String ip,
            int port,
            boolean online,
            Instant lastSeen,
            long lastLatency,
            boolean trusted,
            int reputation,
            String version,
            String os,
            String agent,
            String identityPublicKey) {
        return new Peer(
            id, publicKey, hostName,
            ip, port,
            ip, port,  // Use same for public
            NatType.UNKNOWN,
            false,
            online, lastSeen, lastLatency, trusted, reputation,
            version, os, agent,
            identityPublicKey
        );
    }

    /**
     * Creates a copy of this peer with a new identity public key.
     */
    public Peer withIdentityPublicKey(String newIdentityPublicKey) {
        return new Peer(
            id, publicKey, hostName,
            ip, port,
            publicIp, publicPort,
            natType, behindNat,
            online, lastSeen, lastLatency, trusted, reputation,
            version, os, agent,
            newIdentityPublicKey
        );
    }

    /**
     * Creates a copy of this peer with identity public key from bytes.
     */
    public Peer withIdentityPublicKey(byte[] newIdentityPublicKey) {
        String encoded = (newIdentityPublicKey != null)
                ? Base64.getEncoder().encodeToString(newIdentityPublicKey)
                : "";
        return withIdentityPublicKey(encoded);
    }
}
