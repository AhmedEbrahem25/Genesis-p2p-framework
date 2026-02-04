package com.genesis.p2p.security.policy;

import com.genesis.p2p.core.peer.PeerStore.PeerState;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defines security requirements for each message type.
 *
 * Categories:
 * - BOOTSTRAP: Allowed in plaintext during key exchange (KEY_EXCHANGE_INIT, KEY_EXCHANGE_COMPLETE)
 * - DISCOVERY: Allowed without encryption (multicast/broadcast discovery)
 * - HANDSHAKE: REQUIRES encryption and valid session (sent over secure channel)
 * - SYSTEM: REQUIRES encryption (PING, PONG, HEARTBEAT)
 * - APPLICATION: REQUIRES encryption and AUTHENTICATED state
 *
 * This class enforces the principle that non-key-exchange messages
 * MUST NOT be processed before secure channel establishment.
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public class MessageSecurityPolicy {

    /**
     * Security level for message types.
     */
    public enum SecurityLevel {
        /** Message can be sent in plaintext (bootstrap/discovery only) */
        PLAINTEXT_ALLOWED,

        /** Encryption is optional (legacy compatibility mode) */
        ENCRYPTION_OPTIONAL,

        /** Message MUST be encrypted - reject if plaintext */
        ENCRYPTION_REQUIRED
    }

    /**
     * Message category for grouping related message types.
     */
    public enum MessageCategory {
        /** Key exchange bootstrap (plaintext OK) */
        BOOTSTRAP,

        /** Peer discovery (plaintext OK) */
        DISCOVERY,

        /** Application handshake (requires secure channel) */
        HANDSHAKE,

        /** System messages like PING/PONG (requires secure channel) */
        SYSTEM,

        /** All other application messages (requires authentication) */
        APPLICATION
    }

    /**
     * Security requirement for a message type.
     */
    public record SecurityRequirement(
            SecurityLevel level,
            MessageCategory category,
            Set<PeerState> allowedStates
    ) {
        /**
         * Checks if this requirement allows plaintext transmission.
         */
        public boolean allowsPlaintext() {
            return level == SecurityLevel.PLAINTEXT_ALLOWED;
        }

        /**
         * Checks if this requirement mandates encryption.
         */
        public boolean requiresEncryption() {
            return level == SecurityLevel.ENCRYPTION_REQUIRED;
        }

        /**
         * Checks if the given peer state is allowed for this message type.
         */
        public boolean isStateAllowed(PeerState state) {
            return state != null && allowedStates.contains(state);
        }
    }

    // Message type -> Security requirements
    private final Map<String, SecurityRequirement> requirements = new ConcurrentHashMap<>();

    // Default requirement for unknown message types (strict by default)
    private static final SecurityRequirement DEFAULT = new SecurityRequirement(
            SecurityLevel.ENCRYPTION_REQUIRED,
            MessageCategory.APPLICATION,
            EnumSet.of(PeerState.AUTHENTICATED)
    );

    /**
     * Creates a MessageSecurityPolicy with default rules.
     */
    public MessageSecurityPolicy() {
        initializeDefaultPolicies();
    }

    /**
     * Initializes the default security policies for all known message types.
     */
    private void initializeDefaultPolicies() {
        // ═══════════════════════════════════════════════════════════════════════════
        // BOOTSTRAP messages - plaintext allowed, early states
        // These are the ONLY messages allowed before secure channel establishment
        // ═══════════════════════════════════════════════════════════════════════════
        register("KEY_EXCHANGE_INIT", SecurityLevel.PLAINTEXT_ALLOWED,
                MessageCategory.BOOTSTRAP,
                EnumSet.of(PeerState.DISCOVERED, PeerState.CHANNEL_NEGOTIATING, PeerState.DISCONNECTED));

        register("KEY_EXCHANGE_COMPLETE", SecurityLevel.PLAINTEXT_ALLOWED,
                MessageCategory.BOOTSTRAP,
                EnumSet.of(PeerState.CHANNEL_NEGOTIATING));

        // ═══════════════════════════════════════════════════════════════════════════
        // DISCOVERY messages - plaintext allowed (UDP multicast/broadcast)
        // ═══════════════════════════════════════════════════════════════════════════
        register("DISCOVERY_REQUEST", SecurityLevel.PLAINTEXT_ALLOWED,
                MessageCategory.DISCOVERY,
                EnumSet.allOf(PeerState.class));

        register("DISCOVERY_RESPONSE", SecurityLevel.PLAINTEXT_ALLOWED,
                MessageCategory.DISCOVERY,
                EnumSet.allOf(PeerState.class));

        register("PEER_ANNOUNCE", SecurityLevel.PLAINTEXT_ALLOWED,
                MessageCategory.DISCOVERY,
                EnumSet.allOf(PeerState.class));

        // ═══════════════════════════════════════════════════════════════════════════
        // HANDSHAKE messages - require secure channel (sent encrypted)
        // ═══════════════════════════════════════════════════════════════════════════
        register("HANDSHAKE_REQUEST", SecurityLevel.ENCRYPTION_REQUIRED,
                MessageCategory.HANDSHAKE,
                EnumSet.of(PeerState.CHANNEL_ESTABLISHED, PeerState.CONNECTING));

        register("HANDSHAKE_RESPONSE", SecurityLevel.ENCRYPTION_REQUIRED,
                MessageCategory.HANDSHAKE,
                EnumSet.of(PeerState.CONNECTING, PeerState.CONNECTED));

        register("HANDSHAKE_REJECT", SecurityLevel.ENCRYPTION_REQUIRED,
                MessageCategory.HANDSHAKE,
                EnumSet.of(PeerState.CHANNEL_ESTABLISHED, PeerState.CONNECTING, PeerState.CONNECTED));

        // ═══════════════════════════════════════════════════════════════════════════
        // SYSTEM messages - require at least CONNECTED state
        // ═══════════════════════════════════════════════════════════════════════════
        Set<PeerState> connectedOrAuthenticated = EnumSet.of(PeerState.CONNECTED, PeerState.AUTHENTICATED);

        register("HELLO", SecurityLevel.ENCRYPTION_REQUIRED,
                MessageCategory.SYSTEM, connectedOrAuthenticated);

        register("WELCOME", SecurityLevel.ENCRYPTION_REQUIRED,
                MessageCategory.SYSTEM, connectedOrAuthenticated);

        register("GOODBYE", SecurityLevel.ENCRYPTION_REQUIRED,
                MessageCategory.SYSTEM, connectedOrAuthenticated);

        register("PING", SecurityLevel.ENCRYPTION_REQUIRED,
                MessageCategory.SYSTEM, connectedOrAuthenticated);

        register("PONG", SecurityLevel.ENCRYPTION_REQUIRED,
                MessageCategory.SYSTEM, connectedOrAuthenticated);

        register("HEARTBEAT", SecurityLevel.ENCRYPTION_REQUIRED,
                MessageCategory.SYSTEM, connectedOrAuthenticated);
    }

    /**
     * Registers a security requirement for a message type.
     *
     * @param messageType the message type (e.g., "KEY_EXCHANGE_INIT")
     * @param level the security level requirement
     * @param category the message category
     * @param allowedStates the peer states in which this message is allowed
     */
    public void register(String messageType, SecurityLevel level,
                         MessageCategory category, Set<PeerState> allowedStates) {
        if (messageType == null || messageType.isEmpty()) {
            throw new IllegalArgumentException("Message type cannot be null or empty");
        }
        requirements.put(messageType, new SecurityRequirement(level, category, allowedStates));
    }

    /**
     * Gets the security requirement for a message type.
     * Returns the default (strict) requirement for unknown types.
     *
     * @param messageType the message type
     * @return the security requirement
     */
    public SecurityRequirement getRequirement(String messageType) {
        if (messageType == null) {
            return DEFAULT;
        }
        return requirements.getOrDefault(messageType, DEFAULT);
    }

    /**
     * Checks if plaintext is allowed for this message type.
     *
     * @param messageType the message type
     * @return true if plaintext is allowed
     */
    public boolean isPlaintextAllowed(String messageType) {
        return getRequirement(messageType).allowsPlaintext();
    }

    /**
     * Checks if encryption is required for this message type.
     *
     * @param messageType the message type
     * @return true if encryption is required
     */
    public boolean requiresEncryption(String messageType) {
        return getRequirement(messageType).requiresEncryption();
    }

    /**
     * Checks if a message type is in the bootstrap category.
     *
     * @param messageType the message type
     * @return true if this is a bootstrap message
     */
    public boolean isBootstrapMessage(String messageType) {
        return getRequirement(messageType).category() == MessageCategory.BOOTSTRAP;
    }

    /**
     * Checks if a message type is in the discovery category.
     *
     * @param messageType the message type
     * @return true if this is a discovery message
     */
    public boolean isDiscoveryMessage(String messageType) {
        return getRequirement(messageType).category() == MessageCategory.DISCOVERY;
    }

    /**
     * Checks if a peer state allows a specific message type.
     *
     * @param messageType the message type
     * @param state the current peer state
     * @return true if the state allows this message type
     */
    public boolean isStateAllowed(String messageType, PeerState state) {
        return getRequirement(messageType).isStateAllowed(state);
    }

    /**
     * Returns the number of registered message type policies.
     *
     * @return the policy count
     */
    public int size() {
        return requirements.size();
    }
}
