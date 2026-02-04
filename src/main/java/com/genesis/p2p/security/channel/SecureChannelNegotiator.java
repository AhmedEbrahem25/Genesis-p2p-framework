package com.genesis.p2p.security.channel;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.protocol.negotiation.ProtocolNegotiationService;
import com.genesis.p2p.security.KeyExchangeException;
import com.genesis.p2p.security.api.IKeyManager;
import com.genesis.p2p.security.api.ISessionManager;
import com.genesis.p2p.security.cert.CertificateManager;
import com.genesis.p2p.util.common.Time;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Secure Channel Negotiator.
 *
 * Orchestrates the complete secure channel establishment process including:
 * - Protocol negotiation
 * - Certificate exchange and validation
 * - ECDH key exchange
 * - Session establishment
 *
 * NEW in v2.1: Pre-handshake key exchange phase
 * - Phase 1: KEY_EXCHANGE_INIT/COMPLETE (signed ephemeral keys)
 * - Phase 2: HANDSHAKE_REQUEST/RESPONSE (over secure channel)
 *
 * This eliminates the chicken-and-egg problem where handshake messages
 * were previously sent in plaintext because no session existed yet.
 *
 * Security properties:
 * - Forward secrecy: Fresh ephemeral keys per connection
 * - MITM prevention: Ephemeral keys signed with identity keys
 * - Replay prevention: Timestamp + correlationId validation
 * - No plaintext exposure: All handshake messages encrypted
 *
 * Similar to TLS handshake but optimized for P2P scenarios.
 *
 * @author Genesis P2P Framework
 * @version 2.1
 */
public class SecureChannelNegotiator {

    private static final NodeLogger log = NodeLogger.getLogger(SecureChannelNegotiator.class);
    private static final long NEGOTIATION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long KEY_EXCHANGE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

    private final SecureChannelInitializer channelInitializer;
    private final ProtocolNegotiationService protocolNegotiator;
    private final Map<String, NegotiationState> activeNegotiations;

    // New fields for KEY_EXCHANGE phase
    private final String localNodeId;
    private final IKeyManager keyManager;
    private final ISessionManager sessionManager;
    private final String protocolVersion;

    // Track ongoing key exchange negotiations (Phase 1)
    private final Map<String, ChannelNegotiationState> pendingKeyExchanges;

    // Track established secure channels (before handshake)
    private final Map<String, String> establishedChannels; // peerId -> sessionId

    // CRITICAL: Bidirectional identity mapping to resolve discovery vs protocol ID mismatch
    // - nodeIdToDiscoveryId: maps protocol nodeId (from KEY_EXCHANGE) -> discovery peerId (PeerStore key)
    // - discoveryIdToNodeId: maps discovery peerId -> protocol nodeId
    // This eliminates the deadlock where KEY_EXCHANGE_COMPLETE contains sender's nodeId
    // but PeerStore keys by discovery-generated ID
    private final Map<String, String> nodeIdToDiscoveryId;
    private final Map<String, String> discoveryIdToNodeId;

    // SECURITY FIX (v2.6): Correlation ID Index for O(1) lookup
    // Maps correlationId -> peerId (the key used in pendingKeyExchanges)
    // This eliminates O(n) scanning in processKeyExchangeComplete when message nodeId
    // doesn't match the peerId we stored the state under.
    private final Map<String, String> correlationIdToPeerId;

    // Lock for atomic correlationId resolution
    private final Object correlationLock = new Object();

    // Metrics support (optional)
    private final MetricsRegistry metrics;

    // POST-EXCHANGE TRIGGER (v2.2): Atomic handshake dispatch upon successful KEY_EXCHANGE
    private volatile PostExchangeTrigger postExchangeTrigger;

    /**
     * Post-Exchange Trigger Callback Interface.
     *
     * Invoked atomically upon successful KEY_EXCHANGE_COMPLETE processing
     * to immediately dispatch a HandshakeRequest over the established secure channel.
     *
     * This eliminates the timing window where peers have a secure channel but
     * haven't initiated the application handshake, preventing TCP_ERROR_CLASSIFIED
     * deadlocks in simultaneous open scenarios.
     */
    @FunctionalInterface
    public interface PostExchangeTrigger {
        /**
         * Triggered when KEY_EXCHANGE completes successfully.
         *
         * @param result the successful KeyExchangeResult with resolved peerId and sessionId
         */
        void onKeyExchangeSuccess(KeyExchangeResult result);
    }

    // Metrics counters
    private volatile long keyExchangeInitiated = 0;
    private volatile long keyExchangeCompleted = 0;
    private volatile long keyExchangeFailed = 0;
    private volatile long signatureVerificationFailed = 0;
    private volatile long replayDetected = 0;

    /**
     * Creates a secure channel negotiator (legacy constructor).
     */
    public SecureChannelNegotiator(CertificateManager certificateManager,
                                  ProtocolNegotiationService protocolNegotiator) {
        this(null, null, null, certificateManager, protocolNegotiator, "1.0");
    }

    /**
     * Creates a secure channel negotiator with key exchange support.
     *
     * @param localNodeId the local node's identifier
     * @param keyManager the key manager for signing/verification
     * @param sessionManager the session manager for creating sessions
     * @param certificateManager the certificate manager (legacy)
     * @param protocolNegotiator the protocol negotiation service
     * @param protocolVersion the protocol version string
     */
    public SecureChannelNegotiator(String localNodeId,
                                  IKeyManager keyManager,
                                  ISessionManager sessionManager,
                                  CertificateManager certificateManager,
                                  ProtocolNegotiationService protocolNegotiator,
                                  String protocolVersion) {
        this(localNodeId, keyManager, sessionManager, certificateManager,
             protocolNegotiator, protocolVersion, null);
    }

    /**
     * Creates a secure channel negotiator with full metrics support.
     *
     * @param localNodeId the local node's identifier
     * @param keyManager the key manager for signing/verification
     * @param sessionManager the session manager for creating sessions
     * @param certificateManager the certificate manager (legacy)
     * @param protocolNegotiator the protocol negotiation service
     * @param protocolVersion the protocol version string
     * @param metrics the metrics registry (can be null)
     */
    public SecureChannelNegotiator(String localNodeId,
                                  IKeyManager keyManager,
                                  ISessionManager sessionManager,
                                  CertificateManager certificateManager,
                                  ProtocolNegotiationService protocolNegotiator,
                                  String protocolVersion,
                                  MetricsRegistry metrics) {
        this.localNodeId = localNodeId;
        this.keyManager = keyManager;
        this.sessionManager = sessionManager;
        this.channelInitializer = certificateManager != null
                ? new SecureChannelInitializer(certificateManager)
                : null;
        this.protocolNegotiator = protocolNegotiator;
        this.protocolVersion = protocolVersion != null ? protocolVersion : "1.0";
        this.metrics = metrics;
        this.activeNegotiations = new ConcurrentHashMap<>();
        this.pendingKeyExchanges = new ConcurrentHashMap<>();
        this.establishedChannels = new ConcurrentHashMap<>();
        this.nodeIdToDiscoveryId = new ConcurrentHashMap<>();
        this.discoveryIdToNodeId = new ConcurrentHashMap<>();
        this.correlationIdToPeerId = new ConcurrentHashMap<>();

        log.info("SecureChannelNegotiator initialized",
                "nodeId", localNodeId,
                "protocolVersion", this.protocolVersion,
                "keyExchangeEnabled", keyManager != null,
                "metricsEnabled", metrics != null);
    }

    /**
     * Sets the post-exchange trigger for atomic handshake dispatch.
     *
     * CRITICAL: This callback is invoked IMMEDIATELY after successful KEY_EXCHANGE
     * to dispatch the HandshakeRequest, eliminating the timing window that causes
     * deadlocks in simultaneous open scenarios.
     *
     * @param trigger the callback to invoke on successful KEY_EXCHANGE
     */
    public void setPostExchangeTrigger(PostExchangeTrigger trigger) {
        this.postExchangeTrigger = trigger;
        log.info("PostExchangeTrigger configured",
                "nodeId", localNodeId,
                "triggerClass", trigger != null ? trigger.getClass().getSimpleName() : "null");
    }

    // ========================= KEY EXCHANGE PHASE 1 =========================

    /**
     * Initiates secure channel establishment with a peer.
     * This is Phase 1 of the two-phase bootstrap protocol.
     *
     * MUST be called BEFORE HandshakeProcessor.initiateHandshake().
     *
     * @param peerId target peer's ID
     * @param peerIdentityPublicKey peer's known identity public key (from discovery/trust)
     * @return KeyExchangeInit message to send to the peer
     * @throws KeyExchangeException if initiation fails
     */
    public KeyExchangeInit initiateKeyExchange(String peerId, byte[] peerIdentityPublicKey)
            throws KeyExchangeException {

        if (keyManager == null) {
            throw new KeyExchangeException("KeyManager not configured - key exchange disabled");
        }

        // Check if already negotiating with this peer
        ChannelNegotiationState existing = pendingKeyExchanges.get(peerId);
        if (existing != null && !existing.isExpired()) {
            throw new KeyExchangeException("Key exchange already in progress for peer: " + peerId);
        }

        try {
            log.info("CHANNEL_NEGOTIATION_INIT",
                    "nodeId", localNodeId,
                    "peerId", peerId,
                    "phase", "KEY_EXCHANGE_INIT");

            // Generate ephemeral keypair for this connection
            EphemeralKeyPair ephemeralKeys = EphemeralKeyPair.generate();

            // Get our identity public key
            byte[] identityPublicKey = keyManager.getIdentityPublicKey();

            // Create and sign the KEY_EXCHANGE_INIT message
            KeyExchangeInit init = KeyExchangeInit.create(
                    localNodeId,
                    ephemeralKeys,
                    identityPublicKey,
                    data -> {
                        try {
                            return keyManager.signWithIdentityKey(data);
                        } catch (Exception e) {
                            throw new RuntimeException("Signing failed: " + e.getMessage(), e);
                        }
                    },
                    protocolVersion
            );

            // Create negotiation state
            ChannelNegotiationState state = new ChannelNegotiationState(
                    peerId,
                    init.getCorrelationId(),
                    ChannelNegotiationState.Role.INITIATOR,
                    ephemeralKeys
            );

            // Store peer's identity public key for verification of their response
            state.setPeerIdentityPublicKey(peerIdentityPublicKey);

            // Track the negotiation
            pendingKeyExchanges.put(peerId, state);

            // SECURITY FIX (v2.6): Index by correlationId for O(1) lookup in processKeyExchangeComplete
            correlationIdToPeerId.put(init.getCorrelationId(), peerId);

            keyExchangeInitiated++;

            log.debug("KEY_EXCHANGE_INIT created",
                    "nodeId", localNodeId,
                    "peerId", peerId,
                    "correlationId", init.getCorrelationId(),
                    "ephemeralKeyId", ephemeralKeys.getId());

            return init;

        } catch (Exception e) {
            keyExchangeFailed++;
            log.error("KEY_EXCHANGE_INIT_FAILED",
                    "nodeId", localNodeId,
                    "peerId", peerId,
                    "error", e.getMessage());
            throw new KeyExchangeException("Failed to initiate key exchange: " + e.getMessage(), e);
        }
    }

    /**
     * Processes incoming KEY_EXCHANGE_INIT message from a peer.
     * Verifies the signature, performs ECDH, and prepares the response.
     *
     * @param init the received KEY_EXCHANGE_INIT message
     * @return KeyExchangeComplete response to send back
     * @throws KeyExchangeException if processing fails
     */
    public KeyExchangeComplete processKeyExchangeInit(KeyExchangeInit init)
            throws KeyExchangeException {

        if (keyManager == null) {
            throw new KeyExchangeException("KeyManager not configured - key exchange disabled");
        }

        String peerId = init.getNodeId();

        try {
            log.info("CHANNEL_NEGOTIATION_PROCESS_INIT",
                    "nodeId", localNodeId,
                    "peerId", peerId,
                    "correlationId", init.getCorrelationId());

            // ═════════════════════════════════════════════════════════════════════════
            // DETERMINISTIC CONFLICT RESOLUTION - Simultaneous Initiation (v2.5)
            // ═════════════════════════════════════════════════════════════════════════
            // CRITICAL: Handle simultaneous INIT race condition using lexicographical tie-breaking
            // When both nodes send KEY_EXCHANGE_INIT at the same time:
            //   - Lower nodeId wins and maintains INITIATOR role (SILENTLY drops incoming INIT)
            //   - Higher nodeId loses and becomes RESPONDER (processes incoming INIT)
            // This prevents deadlock where both wait for the other's COMPLETE response.
            //
            // IMPORTANT FIX (v2.4): When winning, do NOT send rejection!
            // The losing peer will process our INIT as responder and send COMPLETE.
            // Sending rejection causes the losing peer to disconnect prematurely.
            //
            // CRITICAL FIX (v2.5): Identity-aware duel detection!
            // Problem: pendingKeyExchanges may be keyed by discovery_peerId, but init.getNodeId()
            // returns protocol_nodeId. If these differ, duel detection FAILS and both peers
            // become responders, causing correlation mismatches and deadlock.
            // Solution: Check both direct lookup AND identity mapping for existing state.
            synchronized (correlationLock) {
                ChannelNegotiationState existingState = pendingKeyExchanges.get(peerId);
                String resolvedPendingKey = peerId;  // Track which key we found the state at

                // v2.5 FIX: If direct lookup fails, try identity mapping
                // This handles the case where we initiated using discovery_peerId but
                // received INIT with protocol_nodeId
                if (existingState == null) {
                    String mappedDiscoveryId = nodeIdToDiscoveryId.get(peerId);
                    if (mappedDiscoveryId != null) {
                        existingState = pendingKeyExchanges.get(mappedDiscoveryId);
                        if (existingState != null) {
                            resolvedPendingKey = mappedDiscoveryId;
                            log.debug("DUEL_DETECTION_IDENTITY_RESOLVED",
                                    "nodeId", localNodeId,
                                    "protocolPeerId", peerId,
                                    "discoveryPeerId", mappedDiscoveryId,
                                    "foundPendingState", true);
                        }
                    }
                }

                // v2.5 FIX: Multi-strategy fallback for duel detection
                // This catches edge cases where identity mapping wasn't registered yet
                if (existingState == null) {
                    byte[] incomingIdentityKey = init.getIdentityPublicKeyBytes();

                    for (Map.Entry<String, ChannelNegotiationState> entry : pendingKeyExchanges.entrySet()) {
                        ChannelNegotiationState candidateState = entry.getValue();

                        // Only consider states in INIT_SENT (we initiated to this peer)
                        if (candidateState.getState() != ChannelNegotiationState.State.INIT_SENT) {
                            continue;
                        }

                        String entryDiscoveryId = entry.getKey();
                        boolean matched = false;
                        String matchReason = null;

                        // Strategy 1: Match by reverse identity mapping
                        String entryNodeId = discoveryIdToNodeId.get(entryDiscoveryId);
                        if (peerId.equals(entryNodeId)) {
                            matched = true;
                            matchReason = "REVERSE_IDENTITY_MAPPING";
                        }

                        // Strategy 2: Match by identity public key (most robust)
                        // The peer's identity key from the INIT should match what we stored
                        if (!matched && incomingIdentityKey != null) {
                            byte[] storedIdentityKey = candidateState.getPeerIdentityPublicKey();
                            if (storedIdentityKey != null &&
                                java.util.Arrays.equals(incomingIdentityKey, storedIdentityKey)) {
                                matched = true;
                                matchReason = "IDENTITY_PUBLIC_KEY";
                            }
                        }

                        // SECURITY FIX (v2.6): Strategy 3 (substring matching) REMOVED
                        // Previously matched by: entryDiscoveryId.contains(peerId) || peerId.contains(entryDiscoveryId)
                        // This was too permissive and could match unrelated peers.
                        // Example: "node-abc" would match "node-abc-xyz" or "1node-abc2"
                        // Now rely only on explicit identity mappings (Strategy 1) or
                        // cryptographic identity verification (Strategy 2).

                        if (matched) {
                            existingState = candidateState;
                            resolvedPendingKey = entryDiscoveryId;
                            // Register the mapping for future lookups
                            nodeIdToDiscoveryId.put(peerId, entryDiscoveryId);
                            discoveryIdToNodeId.put(entryDiscoveryId, peerId);
                            log.info("DUEL_DETECTION_RESOLVED_BY_FALLBACK",
                                    "nodeId", localNodeId,
                                    "protocolPeerId", peerId,
                                    "discoveryPeerId", entryDiscoveryId,
                                    "matchStrategy", matchReason,
                                    "mappingCreated", true);
                            break;
                        }
                    }
                }

                if (existingState != null && existingState.getState() == ChannelNegotiationState.State.INIT_SENT) {
                    // THE DUEL: Both nodes sent INIT simultaneously
                    int comparison = localNodeId.compareTo(peerId);

                    if (comparison < 0) {
                        // WE WIN: localNodeId < peerId
                        // Maintain INITIATOR role, SILENTLY drop incoming INIT (no response!)
                        // The peer will process our INIT as responder and send us COMPLETE.
                        log.warn("SIMULTANEOUS_INIT_DETECTED_WE_WIN",
                                "nodeId", localNodeId,
                                "peerId", peerId,
                                "decision", "MAINTAIN_INITIATOR_SILENT_DROP",
                                "ourCorrelationId", existingState.getCorrelationId(),
                                "theirCorrelationId", init.getCorrelationId(),
                                "lexicalComparison", "localNodeId < peerId",
                                "action", "Waiting for peer to process our INIT and send COMPLETE");

                        if (metrics != null) {
                            metrics.incrementCounter("security.key_exchange.simultaneous_init_win");
                        }

                        // Mark that we detected simultaneous init - for timeout tracking
                        existingState.markSimultaneousInitDetected();

                        // Return null to indicate silent drop - NO RESPONSE SENT
                        // This is the correct behavior: peer will receive our INIT,
                        // see that they lose the duel, and respond with COMPLETE.
                        return null;

                    } else if (comparison > 0) {
                        // WE LOSE: localNodeId > peerId
                        // Transition to RESPONDER role, process incoming INIT
                        log.warn("SIMULTANEOUS_INIT_DETECTED_WE_LOSE",
                                "nodeId", localNodeId,
                                "peerId", peerId,
                                "resolvedPendingKey", resolvedPendingKey,
                                "decision", "BECOME_RESPONDER_PROCESS_INCOMING",
                                "droppingOurCorrelationId", existingState.getCorrelationId(),
                                "processingTheirCorrelationId", init.getCorrelationId(),
                                "lexicalComparison", "localNodeId > peerId");

                        // Cleanup our INITIATOR state (destroy ephemeral keys)
                        // CRITICAL v2.5: Use resolvedPendingKey, not peerId!
                        // The state may be stored at discovery_peerId, not protocol_nodeId
                        existingState.cleanup();
                        pendingKeyExchanges.remove(resolvedPendingKey);

                        // Also register identity mapping for future resolution
                        if (!peerId.equals(resolvedPendingKey)) {
                            registerIdentityMapping(peerId, resolvedPendingKey);
                        }

                        if (metrics != null) {
                            metrics.incrementCounter("security.key_exchange.simultaneous_init_lose");
                        }

                        // MANDATORY RESPONDER ENFORCEMENT (v2.5):
                        // Mark that we MUST send COMPLETE - this peer won the duel and
                        // is waiting for our response. Even if subsequent validation fails,
                        // we must send a rejection COMPLETE to prevent deadlock.
                        // The responder processing below will handle this.

                        // Continue processing as RESPONDER (fall through to normal logic)

                    } else {
                        // IMPOSSIBLE: localNodeId == peerId (same node)
                        log.error("SELF_CONNECTION_DETECTED",
                                "nodeId", localNodeId,
                                "peerId", peerId);
                        return KeyExchangeComplete.createRejection(
                                localNodeId,
                                init.getCorrelationId(),
                                "SELF_CONNECTION: Cannot connect to self"
                        );
                    }
                }
            } // End synchronized block

            // 1. Validate timestamp freshness (replay prevention)
            if (!init.isTimestampFresh()) {
                replayDetected++;
                log.warn("KEY_EXCHANGE_TIMESTAMP_EXPIRED",
                        "nodeId", localNodeId,
                        "peerId", peerId,
                        "correlationId", init.getCorrelationId());
                return KeyExchangeComplete.timestampExpired(localNodeId, init.getCorrelationId());
            }

            // 2. Verify ephemeral public key is valid
            byte[] peerEphemeralPubKey = init.getEphemeralPublicKeyBytes();
            if (!EphemeralKeyPair.verifyPublicKey(peerEphemeralPubKey)) {
                log.warn("KEY_EXCHANGE_INVALID_EPHEMERAL_KEY",
                        "nodeId", localNodeId,
                        "peerId", peerId);
                return KeyExchangeComplete.createRejection(localNodeId, init.getCorrelationId(),
                        "INVALID_EPHEMERAL_KEY");
            }

            // 3. Verify signature with peer's identity public key
            byte[] peerIdentityPubKey = init.getIdentityPublicKeyBytes();
            byte[] signedData = init.getSignedData();
            byte[] signature = init.getSignatureBytes();

            boolean signatureValid = keyManager.verifyIdentitySignature(
                    signedData, signature, peerIdentityPubKey);

            if (!signatureValid) {
                signatureVerificationFailed++;
                log.warn("KEY_EXCHANGE_INVALID_SIGNATURE",
                        "nodeId", localNodeId,
                        "peerId", peerId,
                        "correlationId", init.getCorrelationId());
                return KeyExchangeComplete.invalidSignature(localNodeId, init.getCorrelationId());
            }

            // 4. Generate our ephemeral keypair
            EphemeralKeyPair ourEphemeralKeys = EphemeralKeyPair.generate();

            // 5. Perform ECDH to derive shared secret
            byte[] sharedSecret = ourEphemeralKeys.deriveSharedSecret(peerEphemeralPubKey);

            // 6. Create session with derived key
            String sessionId = createSessionFromSharedSecret(peerId, sharedSecret);

            // 7. Store peer's identity public key
            keyManager.storeIdentityPublicKey(peerId, peerIdentityPubKey);

            // 8. Create negotiation state
            ChannelNegotiationState state = new ChannelNegotiationState(
                    peerId,
                    init.getCorrelationId(),
                    ChannelNegotiationState.Role.RESPONDER,
                    ourEphemeralKeys
            );
            state.setPeerEphemeralPublicKey(peerEphemeralPubKey);
            state.setPeerIdentityPublicKey(peerIdentityPubKey);
            state.setDerivedSharedSecret(sharedSecret);
            state.markCompleteSent();
            state.markEstablished(sessionId);

            pendingKeyExchanges.put(peerId, state);
            establishedChannels.put(peerId, sessionId);

            // 9. Create and sign the response
            byte[] ourIdentityPubKey = keyManager.getIdentityPublicKey();
            KeyExchangeComplete complete = KeyExchangeComplete.createAccepted(
                    localNodeId,
                    ourEphemeralKeys,
                    ourIdentityPubKey,
                    data -> {
                        try {
                            return keyManager.signWithIdentityKey(data);
                        } catch (Exception e) {
                            throw new RuntimeException("Signing failed: " + e.getMessage(), e);
                        }
                    },
                    init.getCorrelationId()
            );

            keyExchangeCompleted++;

            log.info("KEY_EXCHANGE_INIT_PROCESSED",
                    "nodeId", localNodeId,
                    "peerId", peerId,
                    "correlationId", init.getCorrelationId(),
                    "sessionId", sessionId,
                    "channelEstablished", true);

            return complete;

        } catch (Exception e) {
            keyExchangeFailed++;
            log.error("KEY_EXCHANGE_INIT_PROCESS_FAILED",
                    "nodeId", localNodeId,
                    "peerId", peerId,
                    "error", e.getMessage());
            return KeyExchangeComplete.createRejection(localNodeId, init.getCorrelationId(),
                    "INTERNAL_ERROR: " + e.getMessage());
        }
    }

    /**
     * Processes incoming KEY_EXCHANGE_COMPLETE message from a peer.
     * Verifies the signature, completes ECDH, and establishes the session.
     *
     * CRITICAL: Returns KeyExchangeResult containing the RESOLVED peerId.
     * The caller MUST use result.getResolvedPeerId() for state transitions,
     * NOT message.from() which may differ due to discovery ID format.
     *
     * @param complete the received KEY_EXCHANGE_COMPLETE message
     * @return KeyExchangeResult with success/failure and resolved peerId
     */
    public KeyExchangeResult processKeyExchangeComplete(KeyExchangeComplete complete) {
        String messageNodeId = complete.getNodeId();  // Sender's self-announced nodeId
        String correlationId = complete.getCorrelationId();

        try {
            log.info("CHANNEL_NEGOTIATION_PROCESS_COMPLETE",
                    "nodeId", localNodeId,
                    "messageNodeId", messageNodeId,
                    "correlationId", correlationId,
                    "accepted", complete.isAccepted());

            // ATOMIC IDENTITY RESOLUTION - prevents race conditions
            ChannelNegotiationState state;
            String resolvedPeerId;

            synchronized (correlationLock) {
                // Strategy 1: Direct lookup by message nodeId
                state = pendingKeyExchanges.get(messageNodeId);
                resolvedPeerId = messageNodeId;

                if (state == null) {
                    // Strategy 2: Try identity mapping (nodeId -> discoveryPeerId)
                    String mappedDiscoveryId = nodeIdToDiscoveryId.get(messageNodeId);
                    if (mappedDiscoveryId != null) {
                        state = pendingKeyExchanges.get(mappedDiscoveryId);
                        if (state != null) {
                            resolvedPeerId = mappedDiscoveryId;
                            log.info("KEY_EXCHANGE_RESOLVED_BY_IDENTITY_MAP",
                                    "nodeId", localNodeId,
                                    "messageNodeId", messageNodeId,
                                    "resolvedPeerId", resolvedPeerId);
                        }
                    }
                }

                if (state == null) {
                    // Strategy 3 (v2.6): O(1) lookup via correlationId index
                    // This replaces the previous O(n) scan over all pending key exchanges
                    String indexedPeerId = correlationIdToPeerId.get(correlationId);
                    if (indexedPeerId != null) {
                        state = pendingKeyExchanges.get(indexedPeerId);
                        if (state != null) {
                            resolvedPeerId = indexedPeerId;

                            // CRITICAL: Register identity mapping for future lookups
                            if (!messageNodeId.equals(resolvedPeerId)) {
                                registerIdentityMapping(messageNodeId, resolvedPeerId);
                            }

                            log.info("KEY_EXCHANGE_RESOLVED_BY_CORRELATION_INDEX",
                                    "nodeId", localNodeId,
                                    "messageNodeId", messageNodeId,
                                    "resolvedPeerId", resolvedPeerId,
                                    "correlationId", correlationId,
                                    "lookupMethod", "O(1)_INDEX");
                        }
                    }

                    // Fallback: O(n) scan (should rarely be needed)
                    if (state == null) {
                        log.debug("KEY_EXCHANGE_CORRELATION_INDEX_MISS - trying O(n) fallback",
                                "nodeId", localNodeId,
                                "messageNodeId", messageNodeId,
                                "correlationId", correlationId,
                                "pendingCount", pendingKeyExchanges.size());

                        for (java.util.Map.Entry<String, ChannelNegotiationState> entry : pendingKeyExchanges.entrySet()) {
                            if (correlationId.equals(entry.getValue().getCorrelationId())) {
                                state = entry.getValue();
                                resolvedPeerId = entry.getKey();

                                // CRITICAL: Register this mapping for future lookups
                                // This prevents future deadlocks for this peer
                                registerIdentityMapping(messageNodeId, resolvedPeerId);

                                // Also update the index for consistency
                                correlationIdToPeerId.put(correlationId, resolvedPeerId);

                                log.info("KEY_EXCHANGE_RESOLVED_BY_CORRELATION_SCAN",
                                        "nodeId", localNodeId,
                                        "messageNodeId", messageNodeId,
                                        "resolvedPeerId", resolvedPeerId,
                                        "correlationId", correlationId,
                                        "identityMappingCreated", true,
                                        "indexUpdated", true);
                                break;
                            }
                        }
                    }
                }

                if (state == null) {
                    log.warn("KEY_EXCHANGE_NO_PENDING_STATE",
                            "nodeId", localNodeId,
                            "messageNodeId", messageNodeId,
                            "correlationId", correlationId,
                            "identityMappingExists", nodeIdToDiscoveryId.containsKey(messageNodeId),
                            "hint", "Check if peerId matches between discovery and KEY_EXCHANGE");
                    return KeyExchangeResult.failure(messageNodeId, KeyExchangeResult.FailureReason.NO_PENDING_STATE,
                            "No pending negotiation found for peerId, identity mapping, or correlationId");
                }
            } // End synchronized block

            // 2. Verify correlationId matches (only if not already matched via fallback)
            if (!correlationId.equals(state.getCorrelationId())) {
                log.warn("KEY_EXCHANGE_CORRELATION_MISMATCH",
                        "nodeId", localNodeId,
                        "peerId", resolvedPeerId,
                        "expected", state.getCorrelationId(),
                        "received", correlationId);
                return KeyExchangeResult.failure(resolvedPeerId, KeyExchangeResult.FailureReason.CORRELATION_MISMATCH,
                        "Expected: " + state.getCorrelationId() + ", received: " + correlationId);
            }

            // 3. Check if already established
            if (state.isEstablished()) {
                log.debug("KEY_EXCHANGE_ALREADY_ESTABLISHED",
                        "nodeId", localNodeId,
                        "peerId", resolvedPeerId);
                return KeyExchangeResult.success(resolvedPeerId, state.getSessionId());
            }

            // 4. Check if rejected
            if (!complete.isAccepted()) {
                state.markFailed(ChannelNegotiationState.FailureReason.PEER_REJECTED,
                        complete.getRejectionReason());
                keyExchangeFailed++;
                log.warn("KEY_EXCHANGE_REJECTED_BY_PEER",
                        "nodeId", localNodeId,
                        "peerId", resolvedPeerId,
                        "reason", complete.getRejectionReason());
                return KeyExchangeResult.failure(resolvedPeerId, KeyExchangeResult.FailureReason.PEER_REJECTED,
                        complete.getRejectionReason());
            }

            // 5. Validate timestamp freshness
            if (!complete.isTimestampFresh()) {
                replayDetected++;
                state.markFailed(ChannelNegotiationState.FailureReason.STALE_TIMESTAMP, "Timestamp expired");
                log.warn("KEY_EXCHANGE_TIMESTAMP_EXPIRED",
                        "nodeId", localNodeId,
                        "peerId", resolvedPeerId);
                return KeyExchangeResult.failure(resolvedPeerId, KeyExchangeResult.FailureReason.STALE_TIMESTAMP,
                        "KEY_EXCHANGE_COMPLETE timestamp expired (>30s)");
            }

            // 6. Verify ephemeral public key
            byte[] peerEphemeralPubKey = complete.getEphemeralPublicKeyBytes();
            if (!EphemeralKeyPair.verifyPublicKey(peerEphemeralPubKey)) {
                state.markFailed(ChannelNegotiationState.FailureReason.KEY_DERIVATION_FAILED,
                        "Invalid ephemeral key");
                log.warn("KEY_EXCHANGE_INVALID_EPHEMERAL_KEY",
                        "nodeId", localNodeId,
                        "peerId", resolvedPeerId);
                return KeyExchangeResult.failure(resolvedPeerId, KeyExchangeResult.FailureReason.INVALID_EPHEMERAL_KEY,
                        "Peer ephemeral public key failed EC point validation");
            }

            // 7. Verify signature
            byte[] peerIdentityPubKey = complete.getIdentityPublicKeyBytes();
            byte[] signedData = complete.getSignedData();
            byte[] signature = complete.getSignatureBytes();

            // If we have a stored identity key, verify it matches
            byte[] knownIdentityKey = state.getPeerIdentityPublicKey();
            if (knownIdentityKey != null) {
                if (!java.util.Arrays.equals(knownIdentityKey, peerIdentityPubKey)) {
                    state.markFailed(ChannelNegotiationState.FailureReason.INVALID_SIGNATURE,
                            "Identity key mismatch");
                    signatureVerificationFailed++;
                    log.warn("KEY_EXCHANGE_IDENTITY_MISMATCH",
                            "nodeId", localNodeId,
                            "peerId", resolvedPeerId);
                    return KeyExchangeResult.failure(resolvedPeerId, KeyExchangeResult.FailureReason.IDENTITY_MISMATCH,
                            "Identity public key changed between discovery and KEY_EXCHANGE");
                }
            }

            boolean signatureValid = keyManager.verifyIdentitySignature(
                    signedData, signature, peerIdentityPubKey);

            if (!signatureValid) {
                state.markFailed(ChannelNegotiationState.FailureReason.INVALID_SIGNATURE,
                        "Signature verification failed");
                signatureVerificationFailed++;
                log.warn("KEY_EXCHANGE_INVALID_SIGNATURE",
                        "nodeId", localNodeId,
                        "peerId", resolvedPeerId);
                return KeyExchangeResult.failure(resolvedPeerId, KeyExchangeResult.FailureReason.INVALID_SIGNATURE,
                        "ECDSA signature verification failed");
            }

            // 8. Perform ECDH to derive shared secret
            EphemeralKeyPair ourKeys = state.getLocalEphemeralKeys();
            if (ourKeys == null || ourKeys.isDestroyed()) {
                state.markFailed(ChannelNegotiationState.FailureReason.KEY_DERIVATION_FAILED,
                        "Ephemeral keys destroyed");
                keyExchangeFailed++;
                log.error("KEY_EXCHANGE_KEYS_DESTROYED",
                        "nodeId", localNodeId,
                        "peerId", resolvedPeerId);
                return KeyExchangeResult.failure(resolvedPeerId, KeyExchangeResult.FailureReason.KEY_DERIVATION_FAILED,
                        "Local ephemeral keys were destroyed before ECDH");
            }

            byte[] sharedSecret = ourKeys.deriveSharedSecret(peerEphemeralPubKey);

            // 9. Create session using resolvedPeerId (consistent with pending state key)
            String sessionId = createSessionFromSharedSecret(resolvedPeerId, sharedSecret);

            // 10. Store peer's identity key
            keyManager.storeIdentityPublicKey(resolvedPeerId, peerIdentityPubKey);

            // 11. Update state
            state.setPeerEphemeralPublicKey(peerEphemeralPubKey);
            state.setPeerIdentityPublicKey(peerIdentityPubKey);
            state.setDerivedSharedSecret(sharedSecret);
            state.markEstablished(sessionId);

            establishedChannels.put(resolvedPeerId, sessionId);
            keyExchangeCompleted++;

            log.info("KEY_EXCHANGE_COMPLETE_PROCESSED",
                    "nodeId", localNodeId,
                    "peerId", resolvedPeerId,
                    "correlationId", correlationId,
                    "sessionId", sessionId,
                    "channelEstablished", true);

            KeyExchangeResult result = KeyExchangeResult.success(resolvedPeerId, sessionId);

            // ═════════════════════════════════════════════════════════════════════════
            // ATOMIC POST-EXCHANGE TRIGGER: Immediate HandshakeRequest Dispatch
            // ═════════════════════════════════════════════════════════════════════════
            // Invoke callback IMMEDIATELY after successful KEY_EXCHANGE to eliminate
            // timing window that causes TCP_ERROR_CLASSIFIED deadlocks in simultaneous
            // open scenarios. The callback will dispatch HandshakeRequest over the
            // newly established secure channel.
            if (postExchangeTrigger != null) {
                try {
                    log.debug("INVOKING_POST_EXCHANGE_TRIGGER",
                            "nodeId", localNodeId,
                            "peerId", resolvedPeerId,
                            "sessionId", sessionId);

                    postExchangeTrigger.onKeyExchangeSuccess(result);

                    if (metrics != null) {
                        metrics.incrementCounter("security.key_exchange.post_trigger_invoked");
                    }

                    log.info("POST_EXCHANGE_TRIGGER_SUCCESS",
                            "nodeId", localNodeId,
                            "peerId", resolvedPeerId,
                            "handshakeDispatched", true);

                } catch (Exception triggerEx) {
                    // Log but don't fail KEY_EXCHANGE - handshake can be retried
                    log.error("POST_EXCHANGE_TRIGGER_FAILED",
                            "nodeId", localNodeId,
                            "peerId", resolvedPeerId,
                            "error", triggerEx.getMessage());

                    if (metrics != null) {
                        metrics.incrementCounter("security.key_exchange.post_trigger_failed");
                    }
                    // Don't return failure - channel is still established
                }
            } else {
                log.debug("POST_EXCHANGE_TRIGGER_NOT_CONFIGURED",
                        "nodeId", localNodeId,
                        "peerId", resolvedPeerId,
                        "hint", "Manual handshake initiation required");
            }

            return result;

        } catch (Exception e) {
            // Try to find and mark state as failed using identity resolution
            String lookupId = nodeIdToDiscoveryId.getOrDefault(messageNodeId, messageNodeId);
            ChannelNegotiationState failedState = pendingKeyExchanges.get(lookupId);
            if (failedState != null) {
                failedState.markFailed(ChannelNegotiationState.FailureReason.INTERNAL_ERROR, e.getMessage());
            }
            keyExchangeFailed++;
            log.error("KEY_EXCHANGE_COMPLETE_PROCESS_FAILED",
                    "nodeId", localNodeId,
                    "messageNodeId", messageNodeId,
                    "error", e.getMessage());
            return KeyExchangeResult.failure(messageNodeId, KeyExchangeResult.FailureReason.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Checks if a secure channel is established with a peer.
     * HandshakeProcessor should check this before processing handshakes.
     *
     * @param peerId the peer to check
     * @return true if secure channel exists
     */
    public boolean hasSecureChannel(String peerId) {
        return establishedChannels.containsKey(peerId);
    }

    /**
     * Gets the session ID for a peer's secure channel.
     *
     * @param peerId the peer ID
     * @return session ID, or null if no channel established
     */
    public String getChannelSessionId(String peerId) {
        return establishedChannels.get(peerId);
    }

    /**
     * Resolves a correlationId to the stored peerId.
     *
     * This is useful when you have a correlationId from a KEY_EXCHANGE message
     * but need to find the peerId as stored in PeerStore (which may differ
     * from message.from() in the KEY_EXCHANGE response).
     *
     * @param correlationId the correlation ID from KEY_EXCHANGE
     * @return the peerId as stored in pendingKeyExchanges, or null if not found
     */
    public String resolveCorrelationToPeerId(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        synchronized (correlationLock) {
            for (java.util.Map.Entry<String, ChannelNegotiationState> entry : pendingKeyExchanges.entrySet()) {
                if (correlationId.equals(entry.getValue().getCorrelationId())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    // ========================= IDENTITY MAPPING =========================

    /**
     * Registers a bidirectional identity mapping between a protocol nodeId
     * and a discovery-generated peerId.
     *
     * CRITICAL: This method MUST be called when:
     * 1. Discovery finds a new peer (with their announced nodeId)
     * 2. KEY_EXCHANGE_INIT is received (mapping message.from() to the peerId we'll use)
     *
     * This solves the fundamental identity mismatch where:
     * - PeerStore keys by discovery ID (e.g., "peer-10.0.0.5:8080")
     * - KEY_EXCHANGE messages contain sender's nodeId (e.g., "node-abc-xyz")
     *
     * SECURITY FIX (v2.6): Reject conflicting mappings to prevent identity hijacking.
     * Previously, conflicting mappings were logged but still updated, allowing an
     * attacker to register their nodeId with a victim's discoveryPeerId and intercept
     * messages intended for the legitimate peer.
     *
     * @param nodeId the protocol nodeId (from KEY_EXCHANGE or peer announcement)
     * @param discoveryPeerId the discovery-generated peerId (PeerStore key)
     * @throws SecurityException if a conflicting mapping already exists (identity hijack attempt)
     */
    public void registerIdentityMapping(String nodeId, String discoveryPeerId) {
        if (nodeId == null || discoveryPeerId == null) {
            log.warn("Cannot register null identity mapping",
                    "nodeId", nodeId, "discoveryPeerId", discoveryPeerId);
            return;
        }

        // SECURITY FIX: Use putIfAbsent to atomically check and set
        // This prevents race conditions where two threads try to register conflicting mappings
        String existingDiscovery = nodeIdToDiscoveryId.putIfAbsent(nodeId, discoveryPeerId);

        if (existingDiscovery != null && !existingDiscovery.equals(discoveryPeerId)) {
            // CRITICAL: Reject conflicting mapping - possible identity hijack attempt
            log.error("IDENTITY_MAPPING_CONFLICT_REJECTED: Possible identity hijack attempt detected",
                    "nodeId", nodeId,
                    "existingDiscoveryId", existingDiscovery,
                    "attemptedDiscoveryId", discoveryPeerId,
                    "action", "REJECTED");

            if (metrics != null) {
                metrics.incrementCounter("security.identity_mapping.conflict_rejected");
            }

            throw new SecurityException(
                    "Identity mapping conflict detected for nodeId=" + nodeId +
                    ". Existing mapping to " + existingDiscovery +
                    " cannot be changed to " + discoveryPeerId +
                    ". This may indicate an identity hijack attempt.");
        }

        // If existingDiscovery is null, the mapping was successfully added
        // If existingDiscovery equals discoveryPeerId, this is a duplicate registration (safe)
        if (existingDiscovery == null) {
            // Also register reverse mapping atomically
            String existingNodeId = discoveryIdToNodeId.putIfAbsent(discoveryPeerId, nodeId);

            if (existingNodeId != null && !existingNodeId.equals(nodeId)) {
                // Conflict on reverse mapping - rollback forward mapping and reject
                nodeIdToDiscoveryId.remove(nodeId, discoveryPeerId);

                log.error("IDENTITY_MAPPING_CONFLICT_REJECTED: Reverse mapping conflict detected",
                        "discoveryPeerId", discoveryPeerId,
                        "existingNodeId", existingNodeId,
                        "attemptedNodeId", nodeId,
                        "action", "REJECTED_AND_ROLLED_BACK");

                if (metrics != null) {
                    metrics.incrementCounter("security.identity_mapping.reverse_conflict_rejected");
                }

                throw new SecurityException(
                        "Reverse identity mapping conflict detected for discoveryPeerId=" + discoveryPeerId +
                        ". Existing mapping to " + existingNodeId +
                        " cannot be changed to " + nodeId);
            }

            log.debug("Identity mapping registered",
                    "nodeId", nodeId,
                    "discoveryPeerId", discoveryPeerId);
        } else {
            log.debug("Identity mapping already exists (duplicate registration)",
                    "nodeId", nodeId,
                    "discoveryPeerId", discoveryPeerId);
        }
    }

    /**
     * Resolves a protocol nodeId to its discovery peerId.
     *
     * Use this when you receive a message.from() and need the PeerStore key.
     *
     * @param nodeId the protocol nodeId
     * @return discovery peerId, or nodeId itself if no mapping exists
     */
    public String resolveNodeIdToDiscoveryId(String nodeId) {
        if (nodeId == null) return null;
        return nodeIdToDiscoveryId.getOrDefault(nodeId, nodeId);
    }

    /**
     * Resolves a discovery peerId to its protocol nodeId.
     *
     * Use this when you need to find a peer's self-announced identity.
     *
     * @param discoveryPeerId the discovery peerId
     * @return protocol nodeId, or discoveryPeerId itself if no mapping exists
     */
    public String resolveDiscoveryIdToNodeId(String discoveryPeerId) {
        if (discoveryPeerId == null) return null;
        return discoveryIdToNodeId.getOrDefault(discoveryPeerId, discoveryPeerId);
    }

    /**
     * Removes identity mapping for a peer.
     *
     * @param discoveryPeerId the discovery peerId to remove
     */
    public void removeIdentityMapping(String discoveryPeerId) {
        String nodeId = discoveryIdToNodeId.remove(discoveryPeerId);
        if (nodeId != null) {
            nodeIdToDiscoveryId.remove(nodeId);
            log.debug("Identity mapping removed", "discoveryPeerId", discoveryPeerId);
        }
    }

    /**
     * Gets the negotiation state for a peer's key exchange.
     *
     * @param peerId the peer ID
     * @return negotiation state, or null if none
     */
    public ChannelNegotiationState getKeyExchangeState(String peerId) {
        return pendingKeyExchanges.get(peerId);
    }

    /**
     * Removes a secure channel (e.g., when peer disconnects).
     * Also cleans up associated identity mappings.
     *
     * @param peerId the peer ID
     */
    public void removeSecureChannel(String peerId) {
        establishedChannels.remove(peerId);
        ChannelNegotiationState state = pendingKeyExchanges.remove(peerId);
        if (state != null) {
            // SECURITY FIX (v2.6): Clean up correlationId index
            String correlationId = state.getCorrelationId();
            if (correlationId != null) {
                correlationIdToPeerId.remove(correlationId);
            }
            state.cleanup();
        }
        // Also clean up identity mapping
        removeIdentityMapping(peerId);
        log.debug("Secure channel removed", "peerId", peerId);
    }

    /**
     * Creates a session from a shared secret using HKDF.
     */
    private String createSessionFromSharedSecret(String peerId, byte[] sharedSecret) throws Exception {
        if (sessionManager != null) {
            return sessionManager.createSession(localNodeId, peerId, sharedSecret).getSessionId();
        } else {
            // Fallback for legacy mode
            return "session-" + peerId + "-" + System.currentTimeMillis();
        }
    }

    /**
     * Cleans up expired key exchange negotiations.
     *
     * @return number of expired negotiations cleaned up
     */
    public int cleanupExpiredKeyExchanges() {
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger(0);

        pendingKeyExchanges.entrySet().removeIf(entry -> {
            ChannelNegotiationState state = entry.getValue();
            if (state.isExpired() && !state.isEstablished()) {
                // SECURITY FIX (v2.6): Clean up correlationId index
                String correlationId = state.getCorrelationId();
                if (correlationId != null) {
                    correlationIdToPeerId.remove(correlationId);
                }
                state.cleanup();
                log.debug("Expired key exchange cleaned up", "peerId", entry.getKey());
                removed.incrementAndGet();
                return true;
            }
            return false;
        });

        int count = removed.get();
        if (count > 0) {
            log.info("Expired key exchanges cleaned up", "count", count);
        }
        return count;
    }

    // ========================= RETRY MECHANISM (v2.4) =========================

    /**
     * Callback for retrying KEY_EXCHANGE_INIT when no response received.
     * Called by PeerConnectionOrchestrator's scheduled cleanup task.
     */
    @FunctionalInterface
    public interface KeyExchangeRetryCallback {
        /**
         * Called when a KEY_EXCHANGE_INIT should be retried.
         *
         * @param peerId the peer to retry
         * @param init the new KEY_EXCHANGE_INIT message to send
         */
        void retryKeyExchangeInit(String peerId, KeyExchangeInit init);
    }

    private volatile KeyExchangeRetryCallback retryCallback;

    /**
     * Sets the callback for retrying KEY_EXCHANGE_INIT messages.
     *
     * @param callback the retry callback
     */
    public void setRetryCallback(KeyExchangeRetryCallback callback) {
        this.retryCallback = callback;
        log.info("KeyExchangeRetryCallback configured",
                "nodeId", localNodeId,
                "callbackClass", callback != null ? callback.getClass().getSimpleName() : "null");
    }

    /**
     * Gets all peer IDs that need retry (stuck in INIT_SENT, retry interval elapsed).
     * This is called by PeerConnectionOrchestrator to trigger retries.
     *
     * @return list of peer IDs needing retry
     */
    public java.util.List<String> getPeersNeedingRetry() {
        java.util.List<String> needingRetry = new java.util.ArrayList<>();

        for (Map.Entry<String, ChannelNegotiationState> entry : pendingKeyExchanges.entrySet()) {
            ChannelNegotiationState state = entry.getValue();
            if (state.needsRetry()) {
                needingRetry.add(entry.getKey());
            }
        }

        return needingRetry;
    }

    /**
     * Attempts to retry a KEY_EXCHANGE for a stuck negotiation.
     * Creates a fresh KEY_EXCHANGE_INIT with same correlationId.
     *
     * @param peerId the peer to retry
     * @return true if retry was attempted, false if not applicable
     */
    public boolean retryKeyExchange(String peerId) {
        if (keyManager == null) {
            log.warn("Cannot retry KEY_EXCHANGE - keyManager not configured",
                    "peerId", peerId);
            return false;
        }

        ChannelNegotiationState state = pendingKeyExchanges.get(peerId);
        if (state == null) {
            log.debug("Cannot retry KEY_EXCHANGE - no pending state",
                    "peerId", peerId);
            return false;
        }

        if (!state.needsRetry()) {
            log.debug("KEY_EXCHANGE retry not needed",
                    "peerId", peerId,
                    "state", state.getState(),
                    "retryCount", state.getRetryCount(),
                    "maxRetries", state.getMaxRetries());
            return false;
        }

        // Check if max retries exceeded
        if (!state.canRetry()) {
            log.warn("KEY_EXCHANGE max retries exceeded - failing negotiation",
                    "peerId", peerId,
                    "retryCount", state.getRetryCount(),
                    "maxRetries", state.getMaxRetries());
            state.markFailed(ChannelNegotiationState.FailureReason.MAX_RETRIES_EXCEEDED,
                    "Max retry attempts exceeded without receiving KEY_EXCHANGE_COMPLETE");

            if (metrics != null) {
                metrics.incrementCounter("security.key_exchange.max_retries_exceeded");
            }
            return false;
        }

        try {
            // Get our identity public key
            byte[] identityPublicKey = keyManager.getIdentityPublicKey();

            // Get peer's known identity key from state
            byte[] peerIdentityKey = state.getPeerIdentityPublicKey();

            // Create fresh KEY_EXCHANGE_INIT with new ephemeral key but same correlationId
            // NOTE: We MUST NOT reuse the old ephemeral key for forward secrecy
            EphemeralKeyPair newEphemeralKeys = EphemeralKeyPair.generate();

            // Create new INIT with SAME correlationId for idempotency
            KeyExchangeInit retryInit = KeyExchangeInit.createWithCorrelation(
                    localNodeId,
                    newEphemeralKeys,
                    identityPublicKey,
                    data -> {
                        try {
                            return keyManager.signWithIdentityKey(data);
                        } catch (Exception e) {
                            throw new RuntimeException("Signing failed: " + e.getMessage(), e);
                        }
                    },
                    protocolVersion,
                    state.getCorrelationId()  // Reuse correlationId for retry tracking
            );

            // Cleanup old ephemeral keys and set new ones
            EphemeralKeyPair oldKeys = state.getLocalEphemeralKeys();
            if (oldKeys != null && !oldKeys.isDestroyed()) {
                oldKeys.close();
            }

            // Update state with new keys - need to use reflection or add setter
            // For now, we'll create the retry message and let the callback handle it

            // Record retry attempt
            state.recordRetryAttempt();

            log.info("KEY_EXCHANGE_INIT retry prepared",
                    "nodeId", localNodeId,
                    "peerId", peerId,
                    "correlationId", state.getCorrelationId(),
                    "retryCount", state.getRetryCount(),
                    "simultaneousInitWaiting", state.isWaitingAfterSimultaneousInit());

            if (metrics != null) {
                metrics.incrementCounter("security.key_exchange.retry_attempted");
            }

            // Invoke retry callback if configured
            if (retryCallback != null) {
                retryCallback.retryKeyExchangeInit(peerId, retryInit);
                log.debug("KEY_EXCHANGE_INIT retry callback invoked",
                        "peerId", peerId);
            } else {
                log.warn("KEY_EXCHANGE_INIT retry callback not configured - retry will not be sent",
                        "peerId", peerId);
            }

            return true;

        } catch (Exception e) {
            log.error("Failed to create KEY_EXCHANGE_INIT retry",
                    "peerId", peerId,
                    "error", e.getMessage());

            if (metrics != null) {
                metrics.incrementCounter("security.key_exchange.retry_failed");
            }
            return false;
        }
    }

    /**
     * Processes all pending negotiations and attempts retries where needed.
     * Should be called periodically by a scheduler.
     *
     * @return number of retries attempted
     */
    public int processRetries() {
        java.util.List<String> needingRetry = getPeersNeedingRetry();
        int retried = 0;

        for (String peerId : needingRetry) {
            if (retryKeyExchange(peerId)) {
                retried++;
            }
        }

        if (retried > 0) {
            log.info("KEY_EXCHANGE retries processed",
                    "retriedCount", retried,
                    "pendingCount", pendingKeyExchanges.size());
        }

        return retried;
    }

    /**
     * Checks if a peer has an active KEY_EXCHANGE negotiation.
     * Used by orchestrator to detect stuck CHANNEL_NEGOTIATING states.
     *
     * @param peerId the peer ID
     * @return true if active KEY_EXCHANGE exists and not expired
     */
    public boolean hasActiveNegotiation(String peerId) {
        ChannelNegotiationState state = pendingKeyExchanges.get(peerId);
        return state != null && !state.isExpired() && !state.isFailed();
    }

    /**
     * Gets all peer IDs with established secure channels.
     *
     * @return set of peer IDs
     */
    public Set<String> getEstablishedChannelPeers() {
        return Set.copyOf(establishedChannels.keySet());
    }

    /**
     * Gets key exchange metrics.
     */
    public KeyExchangeMetrics getKeyExchangeMetrics() {
        return new KeyExchangeMetrics(
                keyExchangeInitiated,
                keyExchangeCompleted,
                keyExchangeFailed,
                signatureVerificationFailed,
                replayDetected,
                pendingKeyExchanges.size(),
                establishedChannels.size()
        );
    }

    /**
     * Key exchange metrics record.
     */
    public record KeyExchangeMetrics(
            long initiated,
            long completed,
            long failed,
            long signatureVerificationFailed,
            long replayDetected,
            int pendingCount,
            int establishedCount
    ) {}

    /**
     * Cancels an ongoing key exchange negotiation.
     * Used when connection fails before KEY_EXCHANGE_COMPLETE is received.
     *
     * @param peerId the peer ID
     */
    public void cancelNegotiation(String peerId) {
        ChannelNegotiationState state = pendingKeyExchanges.remove(peerId);
        if (state != null) {
            // SECURITY FIX (v2.6): Clean up correlationId index
            String correlationId = state.getCorrelationId();
            if (correlationId != null) {
                correlationIdToPeerId.remove(correlationId);
            }
            state.markFailed(ChannelNegotiationState.FailureReason.TIMEOUT, "Cancelled by orchestrator");
            state.cleanup();
            keyExchangeFailed++;
            log.debug("Key exchange negotiation cancelled", "peerId", peerId);
        }
    }

    // ========================= LEGACY NEGOTIATION METHODS =========================

    /**
     * Starts secure channel negotiation (client side).
     *
     * @param remotePeerId the remote peer identifier
     * @return negotiation handshake data to send
     * @throws SecureChannelInitializer.SecureChannelException if negotiation fails
     */
    public NegotiationHandshake startNegotiation(String remotePeerId)
            throws SecureChannelInitializer.SecureChannelException {

        log.info("Starting secure channel negotiation", "remotePeer", remotePeerId);

        // Initiate channel
        SecureChannelInitializer.ChannelInitiation initiation =
                channelInitializer.initiateChannel(remotePeerId);

        // Create negotiation state
        NegotiationState state = new NegotiationState(
                remotePeerId,
                Time.currentMillis(),
                NegotiationPhase.INITIATED
        );
        activeNegotiations.put(remotePeerId, state);

        // Create handshake with protocol versions
        NegotiationHandshake handshake = new NegotiationHandshake(
                initiation,
                protocolNegotiator.getSupportedVersions(),
                protocolNegotiator.getLocalCapabilities()
        );

        log.debug("Negotiation started", "remotePeer", remotePeerId);

        return handshake;
    }

    /**
     * Accepts and responds to negotiation (server side).
     *
     * @param handshake the handshake received from peer
     * @return negotiation response to send back
     * @throws SecureChannelInitializer.SecureChannelException if acceptance fails
     */
    public NegotiationResponse acceptNegotiation(NegotiationHandshake handshake)
            throws SecureChannelInitializer.SecureChannelException {

        String remotePeerId = handshake.getInitiation().getCertificate().getNodeId();
        log.info("Accepting secure channel negotiation", "remotePeer", remotePeerId);

        // Negotiate protocol
        var protocolResult = protocolNegotiator.negotiate(
                remotePeerId,
                handshake.getProtocolVersions(),
                handshake.getCapabilities()
        );

        if (!protocolResult.isSuccess()) {
            throw new SecureChannelInitializer.SecureChannelException(
                    "Protocol negotiation failed: " + protocolResult.getErrorMessage());
        }

        // Accept channel
        SecureChannelInitializer.ChannelResponse channelResponse =
                channelInitializer.acceptChannel(handshake.getInitiation());

        // Create negotiation state
        NegotiationState state = new NegotiationState(
                remotePeerId,
                Time.currentMillis(),
                NegotiationPhase.ACCEPTED
        );
        activeNegotiations.put(remotePeerId, state);

        NegotiationResponse response = new NegotiationResponse(
                channelResponse,
                protocolResult.getVersion(),
                protocolResult.getCapabilities()
        );

        log.info("Negotiation accepted",
                "remotePeer", remotePeerId,
                "protocol", protocolResult.getVersion());

        return response;
    }

    /**
     * Completes negotiation (client side after receiving response).
     *
     * @param response the response from peer
     * @return established secure channel
     * @throws SecureChannelInitializer.SecureChannelException if completion fails
     */
    public SecureChannel completeNegotiation(NegotiationResponse response)
            throws SecureChannelInitializer.SecureChannelException {

        String remotePeerId = response.getChannelResponse().getCertificate().getNodeId();
        log.info("Completing secure channel negotiation", "remotePeer", remotePeerId);

        // Complete channel establishment
        SecureChannel channel = channelInitializer.completeChannel(response.getChannelResponse());

        // Update negotiation state
        NegotiationState state = activeNegotiations.get(remotePeerId);
        if (state != null) {
            state.phase = NegotiationPhase.COMPLETED;
        }

        log.info("Negotiation completed",
                "remotePeer", remotePeerId,
                "sessionId", channel.getSessionId());

        return channel;
    }

    /**
     * Gets negotiation state for a peer.
     */
    public NegotiationState getNegotiationState(String peerId) {
        return activeNegotiations.get(peerId);
    }

    /**
     * Cleans up expired negotiations.
     */
    public int cleanupExpiredNegotiations() {
        long now = Time.currentMillis();
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger(0);

        activeNegotiations.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().timestamp) > NEGOTIATION_TIMEOUT_MS;
            if (expired) {
                log.debug("Removing expired negotiation", "peerId", entry.getKey());
                removed.incrementAndGet();
            }
            return expired;
        });

        int count = removed.get();
        if (count > 0) {
            log.info("Expired negotiations cleaned up", "count", count);
        }

        return count;
    }

    // ==================== Data Classes ====================

    /**
     * Negotiation handshake data.
     */
    public static class NegotiationHandshake {
        private final SecureChannelInitializer.ChannelInitiation initiation;
        private final java.util.List<com.genesis.p2p.protocol.model.ProtocolVersion> protocolVersions;
        private final Map<String, String> capabilities;

        public NegotiationHandshake(SecureChannelInitializer.ChannelInitiation initiation,
                                   java.util.List<com.genesis.p2p.protocol.model.ProtocolVersion> protocolVersions,
                                   Map<String, String> capabilities) {
            this.initiation = initiation;
            this.protocolVersions = protocolVersions;
            this.capabilities = capabilities;
        }

        public SecureChannelInitializer.ChannelInitiation getInitiation() { return initiation; }
        public java.util.List<com.genesis.p2p.protocol.model.ProtocolVersion> getProtocolVersions() { return protocolVersions; }
        public Map<String, String> getCapabilities() { return capabilities; }
    }

    /**
     * Negotiation response data.
     */
    public static class NegotiationResponse {
        private final SecureChannelInitializer.ChannelResponse channelResponse;
        private final com.genesis.p2p.protocol.model.ProtocolVersion negotiatedVersion;
        private final Map<String, String> negotiatedCapabilities;

        public NegotiationResponse(SecureChannelInitializer.ChannelResponse channelResponse,
                                  com.genesis.p2p.protocol.model.ProtocolVersion negotiatedVersion,
                                  Map<String, String> negotiatedCapabilities) {
            this.channelResponse = channelResponse;
            this.negotiatedVersion = negotiatedVersion;
            this.negotiatedCapabilities = negotiatedCapabilities;
        }

        public SecureChannelInitializer.ChannelResponse getChannelResponse() { return channelResponse; }
        public com.genesis.p2p.protocol.model.ProtocolVersion getNegotiatedVersion() { return negotiatedVersion; }
        public Map<String, String> getNegotiatedCapabilities() { return negotiatedCapabilities; }
    }

    /**
     * Negotiation state.
     */
    public static class NegotiationState {
        private final String peerId;
        private final long timestamp;
        private NegotiationPhase phase;

        public NegotiationState(String peerId, long timestamp, NegotiationPhase phase) {
            this.peerId = peerId;
            this.timestamp = timestamp;
            this.phase = phase;
        }

        public String getPeerId() { return peerId; }
        public long getTimestamp() { return timestamp; }
        public NegotiationPhase getPhase() { return phase; }
    }

    /**
     * Negotiation phases.
     */
    public enum NegotiationPhase {
        INITIATED,
        ACCEPTED,
        COMPLETED,
        FAILED
    }
}

