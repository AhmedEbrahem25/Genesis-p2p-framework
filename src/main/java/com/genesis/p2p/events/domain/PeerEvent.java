package com.genesis.p2p.events.domain;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.core.peer.PeerStore;
import com.genesis.p2p.events.core.AbstractEvent;

/**
 * Events related to peer lifecycle and state changes.
 */
public class PeerEvent extends AbstractEvent {

    public static final String PEER_DISCOVERED = "PEER_DISCOVERED";
    public static final String PEER_CONNECTED = "PEER_CONNECTED";
    public static final String PEER_DISCONNECTED = "PEER_DISCONNECTED";
    public static final String PEER_STATE_CHANGED = "PEER_STATE_CHANGED";
    public static final String PEER_REPUTATION_CHANGED = "PEER_REPUTATION_CHANGED";

    private final String peerId;
    private final PeerStore.PeerState peerState;
    private final PeerStore.PeerState oldState;

    private PeerEvent(String type, String source, Peer peer,
                      PeerStore.PeerState state, PeerStore.PeerState oldState) {
        super(type, source, peer);
        this.peerId = peer.id();
        this.peerState = state;
        this.oldState = oldState;
    }

    /**
     * Creates a peer discovered event.
     */
    public static PeerEvent discovered(String source, Peer peer) {
        return new PeerEvent(PEER_DISCOVERED, source, peer,
                PeerStore.PeerState.DISCOVERED, null);
    }

    /**
     * Creates a peer connected event.
     */
    public static PeerEvent connected(String source, Peer peer) {
        return new PeerEvent(PEER_CONNECTED, source, peer,
                PeerStore.PeerState.CONNECTED, null);
    }

    /**
     * Creates a peer disconnected event.
     */
    public static PeerEvent disconnected(String source, Peer peer) {
        return new PeerEvent(PEER_DISCONNECTED, source, peer,
                PeerStore.PeerState.DISCONNECTED, null);
    }

    /**
     * Creates a peer state changed event.
     */
    public static PeerEvent stateChanged(String source, Peer peer,
                                         PeerStore.PeerState oldState,
                                         PeerStore.PeerState newState) {
        return new PeerEvent(PEER_STATE_CHANGED, source, peer, newState, oldState);
    }

    /**
     * Creates a peer reputation changed event.
     */
    public static PeerEvent reputationChanged(String source, Peer peer,
                                              int oldReputation, int newReputation) {
        PeerEvent event = new PeerEvent(PEER_REPUTATION_CHANGED, source, peer, null, null);
        event.metadata.put("oldReputation", oldReputation);
        event.metadata.put("newReputation", newReputation);
        return event;
    }

    public String getPeerId() {
        return peerId;
    }

    public Peer getPeer() {
        return (Peer) payload;
    }

    public PeerStore.PeerState getPeerState() {
        return peerState;
    }

    public PeerStore.PeerState getOldState() {
        return oldState;
    }

    // Metadata storage
    private final java.util.Map<String, Object> metadata = new java.util.HashMap<>();

    public java.util.Map<String, Object> getMetadata() {
        return metadata;
    }
}