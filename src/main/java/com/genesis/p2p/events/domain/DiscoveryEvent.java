// ============================================================================
// DISCOVERY EVENTS
// ============================================================================
package com.genesis.p2p.events.domain;

import com.genesis.p2p.core.Peer;
import com.genesis.p2p.events.core.AbstractEvent;
import java.util.List;
import java.util.Collections;

/**
 * Events related to peer discovery.
 */
public class DiscoveryEvent extends AbstractEvent {

    public static final String DISCOVERY_STARTED = "DISCOVERY_STARTED";
    public static final String DISCOVERY_COMPLETED = "DISCOVERY_COMPLETED";
    public static final String DISCOVERY_FAILED = "DISCOVERY_FAILED";

    private final List<Peer> discoveredPeers;
    private final String errorMessage;

    private DiscoveryEvent(String type, String source, List<Peer> peers, String error) {
        super(type, source, peers);
        this.discoveredPeers = peers != null ? peers : Collections.emptyList();
        this.errorMessage = error;
    }

    /**
     * Creates a discovery started event.
     */
    public static DiscoveryEvent started(String source) {
        return new DiscoveryEvent(DISCOVERY_STARTED, source, null, null);
    }

    /**
     * Creates a discovery completed event.
     */
    public static DiscoveryEvent completed(String source, List<Peer> peers) {
        return new DiscoveryEvent(DISCOVERY_COMPLETED, source, peers, null);
    }

    /**
     * Creates a discovery failed event.
     */
    public static DiscoveryEvent failed(String source, String error) {
        return new DiscoveryEvent(DISCOVERY_FAILED, source, null, error);
    }

    public List<Peer> getDiscoveredPeers() {
        return Collections.unmodifiableList(discoveredPeers);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getPeerCount() {
        return discoveredPeers.size();
    }
}
