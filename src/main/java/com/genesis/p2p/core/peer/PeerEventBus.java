package com.genesis.p2p.core.peer;

import com.genesis.p2p.core.Peer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simplified event bus for PeerManager integration.
 * Handles synchronous event notifications to registered listeners.
 *
 * This is a lightweight version that integrates with the existing
 * modular PeerManager architecture.
 */
public class PeerEventBus {

    private static final Logger log = LoggerFactory.getLogger(PeerEventBus.class);

    private final CopyOnWriteArrayList<PeerEventListener> listeners;

    /**
     * Event listener interface for peer lifecycle events.
     */
    public interface PeerEventListener {
        void onPeerAdded(Peer peer);
        void onPeerUpdated(Peer oldPeer, Peer newPeer);
        void onPeerRemoved(Peer peer, String reason);
        void onPeerStateChanged(Peer peer, PeerStore.PeerState oldState, PeerStore.PeerState newState);
        void onReputationChanged(Peer peer, int oldReputation, int newReputation);
    }

    public PeerEventBus() {
        this.listeners = new CopyOnWriteArrayList<>();
        log.debug("PeerEventBus initialized");
    }

    // ==================== Listener Management ====================

    public void register(PeerEventListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            log.debug("Registered event listener");
        }
    }

    public void unregister(PeerEventListener listener) {
        if (listener != null && listeners.remove(listener)) {
            log.debug("Unregistered event listener");
        }
    }

    public int getListenerCount() {
        return listeners.size();
    }

    // ==================== Event Firing ====================

    public void firePeerAdded(Peer peer) {
        for (PeerEventListener listener : listeners) {
            try {
                listener.onPeerAdded(peer);
            } catch (Exception e) {
                log.error("Error in peer added listener", e);
            }
        }
    }

    public void firePeerUpdated(Peer oldPeer, Peer newPeer) {
        for (PeerEventListener listener : listeners) {
            try {
                listener.onPeerUpdated(oldPeer, newPeer);
            } catch (Exception e) {
                log.error("Error in peer updated listener", e);
            }
        }
    }

    public void firePeerRemoved(Peer peer, String reason) {
        for (PeerEventListener listener : listeners) {
            try {
                listener.onPeerRemoved(peer, reason);
            } catch (Exception e) {
                log.error("Error in peer removed listener", e);
            }
        }
    }

    public void fireStateChanged(Peer peer, PeerStore.PeerState oldState, PeerStore.PeerState newState) {
        for (PeerEventListener listener : listeners) {
            try {
                listener.onPeerStateChanged(peer, oldState, newState);
            } catch (Exception e) {
                log.error("Error in state changed listener", e);
            }
        }
    }

    public void fireReputationChanged(Peer peer, int oldReputation, int newReputation) {
        for (PeerEventListener listener : listeners) {
            try {
                listener.onReputationChanged(peer, oldReputation, newReputation);
            } catch (Exception e) {
                log.error("Error in reputation changed listener", e);
            }
        }
    }
}