//package com.genesis.p2p.testutil.builders;
//
//import com.genesis.p2p.core.Peer;
//import com.genesis.p2p.testutil.TestConstants;
//
//import java.time.Instant;
//import java.util.UUID;
//
///**
// * Builder for creating test Peer objects with sensible defaults.
// * <p>
// * Design Pattern: Builder Pattern
// * Simplifies test data creation and improves test readability.
// * <p>
// * Features:
// * - Fluent API for easy customization
// * - Sensible defaults for all fields
// * - Convenience factory methods for common test scenarios
// * - Automatic generation of unique IDs
// * <p>
// * Usage:
// * <pre>
// * // Simple peer with defaults
// * Peer peer = TestPeerBuilder.aPeer().build();
// *
// * // Customized peer
// * Peer peer = TestPeerBuilder.aPeer()
// *     .withId("peer-1")
// *     .withIp("192.168.1.100")
// *     .withReputation(80)
// *     .build();
// *
// * // Pre-configured trusted peer
// * Peer trusted = TestPeerBuilder.aTrustedPeer().build();
// * </pre>
// *
// * @author Genesis P2P Framework
// * @version 2.0
// */
//public class TestPeerBuilder {
//
//    // Default values
//    private String id = TestConstants.TEST_PEER_ID_PREFIX + generateUniqueId();
//    private String publicKey = null; // Will be generated from ID
//    private String hostName = null; // Will default to "unknown"
//    private String ip = TestConstants.TEST_LOCALHOST;
//    private int port = 8080;
//    private boolean online = true;
//    private Instant lastSeen = Instant.now();
//    private long lastLatency = 0;
//    private boolean trusted = false;
//    private int reputation = TestConstants.DEFAULT_TEST_REPUTATION;
//    private String version = "2.0";
//    private String os = "Linux";
//    private String agent = "test-agent";
//
//    /**
//     * Creates a new builder with default values.
//     *
//     * @return a new TestPeerBuilder instance
//     */
//    public static TestPeerBuilder aPeer() {
//        return new TestPeerBuilder();
//    }
//
//    /**
//     * Creates a builder pre-configured for a trusted peer.
//     *
//     * @return a TestPeerBuilder configured for a trusted peer
//     */
//    public static TestPeerBuilder aTrustedPeer() {
//        return new TestPeerBuilder()
//            .withReputation(TestConstants.TRUSTED_REPUTATION)
//            .withTrusted(true)
//            .withOnline(true);
//    }
//
//    /**
//     * Creates a builder pre-configured for an untrusted peer.
//     *
//     * @return a TestPeerBuilder configured for an untrusted peer
//     */
//    public static TestPeerBuilder anUntrustedPeer() {
//        return new TestPeerBuilder()
//            .withReputation(TestConstants.UNTRUSTED_REPUTATION)
//            .withTrusted(false);
//    }
//
//    /**
//     * Creates a builder pre-configured for an offline peer.
//     *
//     * @return a TestPeerBuilder configured for an offline peer
//     */
//    public static TestPeerBuilder anOfflinePeer() {
//        return new TestPeerBuilder()
//            .withOnline(false)
//            .withLastSeen(Instant.now().minusSeconds(300)); // 5 minutes ago
//    }
//
//    /**
//     * Creates a builder pre-configured for a local peer.
//     *
//     * @return a TestPeerBuilder configured for a localhost peer
//     */
//    public static TestPeerBuilder aLocalPeer() {
//        return new TestPeerBuilder()
//            .withIp(TestConstants.TEST_LOCALHOST);
//    }
//
//    /**
//     * Sets the peer ID.
//     *
//     * @param id the peer ID
//     * @return this builder
//     */
//    public TestPeerBuilder withId(String id) {
//        this.id = id;
//        return this;
//    }
//
//    /**
//     * Sets the public key.
//     *
//     * @param publicKey the public key
//     * @return this builder
//     */
//    public TestPeerBuilder withPublicKey(String publicKey) {
//        this.publicKey = publicKey;
//        return this;
//    }
//
//    /**
//     * Sets the hostname.
//     *
//     * @param hostName the hostname
//     * @return this builder
//     */
//    public TestPeerBuilder withHostName(String hostName) {
//        this.hostName = hostName;
//        return this;
//    }
//
//    /**
//     * Sets the IP address.
//     *
//     * @param ip the IP address
//     * @return this builder
//     */
//    public TestPeerBuilder withIp(String ip) {
//        this.ip = ip;
//        return this;
//    }
//
//    /**
//     * Sets the port.
//     *
//     * @param port the port number
//     * @return this builder
//     */
//    public TestPeerBuilder withPort(int port) {
//        this.port = port;
//        return this;
//    }
//
//    /**
//     * Sets the online status.
//     *
//     * @param online whether the peer is online
//     * @return this builder
//     */
//    public TestPeerBuilder withOnline(boolean online) {
//        this.online = online;
//        return this;
//    }
//
//    /**
//     * Sets the last seen timestamp.
//     *
//     * @param lastSeen the last seen timestamp
//     * @return this builder
//     */
//    public TestPeerBuilder withLastSeen(Instant lastSeen) {
//        this.lastSeen = lastSeen;
//        return this;
//    }
//
//    /**
//     * Sets the last latency.
//     *
//     * @param lastLatency the last measured latency in milliseconds
//     * @return this builder
//     */
//    public TestPeerBuilder withLastLatency(long lastLatency) {
//        this.lastLatency = lastLatency;
//        return this;
//    }
//
//    /**
//     * Sets the trusted status.
//     *
//     * @param trusted whether the peer is trusted
//     * @return this builder
//     */
//    public TestPeerBuilder withTrusted(boolean trusted) {
//        this.trusted = trusted;
//        return this;
//    }
//
//    /**
//     * Sets the reputation.
//     *
//     * @param reputation the reputation value (0-100)
//     * @return this builder
//     */
//    public TestPeerBuilder withReputation(int reputation) {
//        this.reputation = reputation;
//        return this;
//    }
//
//    /**
//     * Sets the version.
//     *
//     * @param version the peer's version string
//     * @return this builder
//     */
//    public TestPeerBuilder withVersion(String version) {
//        this.version = version;
//        return this;
//    }
//
//    /**
//     * Sets the operating system.
//     *
//     * @param os the operating system
//     * @return this builder
//     */
//    public TestPeerBuilder withOs(String os) {
//        this.os = os;
//        return this;
//    }
//
//    /**
//     * Sets the agent.
//     *
//     * @param agent the agent string
//     * @return this builder
//     */
//    public TestPeerBuilder withAgent(String agent) {
//        this.agent = agent;
//        return this;
//    }
//
//    /**
//     * Builds the Peer instance.
//     *
//     * @return a new Peer with the configured values
//     */
//    public Peer build() {
//        // Generate public key from ID if not set
//        String finalPublicKey = (publicKey != null) ? publicKey : ("pubkey-" + id);
//
//        return new Peer(
//            id,
//            finalPublicKey,
//            hostName,
//            ip,
//            port,
//            online,
//            lastSeen,
//            lastLatency,
//            trusted,
//            reputation,
//            version,
//            os,
//            agent
//        );
//    }
//
//    /**
//     * Generates a unique short ID for testing.
//     *
//     * @return a unique 8-character ID
//     */
//    private static String generateUniqueId() {
//        return UUID.randomUUID().toString().substring(0, 8);
//    }
//}
