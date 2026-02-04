package com.genesis.p2p.core.peer;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves peer identities across different ID spaces.
 * FIX #7: UNIFIED IDENTITY MANAGEMENT
 */
public class PeerIdentityResolver {

    private static final NodeLogger log = NodeLogger.getLogger(PeerIdentityResolver.class);

    private final MetricsRegistry metrics;
    private final ConcurrentHashMap<String, String> protocolToCanonical = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> canonicalToProtocol = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> addressToCanonical = new ConcurrentHashMap<>();

    public PeerIdentityResolver(MetricsRegistry metrics) {
        this.metrics = metrics;
        log.info("PeerIdentityResolver created");
    }

    public void registerMapping(String canonicalId, String protocolId, InetSocketAddress address) {
        if (canonicalId == null || protocolId == null) {
            log.warn("Cannot register mapping with null IDs", "canonicalId", canonicalId, "protocolId", protocolId);
            return;
        }

        String oldProtocol = protocolToCanonical.put(protocolId, canonicalId);
        String oldCanonical = canonicalToProtocol.put(canonicalId, protocolId);

        if (address != null) {
            String addressStr = address.getAddress().getHostAddress();
            addressToCanonical.put(addressStr, canonicalId);
        }

        if (oldProtocol != null && !oldProtocol.equals(canonicalId)) {
            log.warn("Overwriting existing protocol→canonical mapping",
                    "protocolId", protocolId,
                    "oldCanonical", oldProtocol,
                    "newCanonical", canonicalId);
            metrics.incrementCounter("identity.mapping_conflict");
        } else {
            log.info("Identity mapping registered",
                    "canonicalId", canonicalId,
                    "protocolId", protocolId);
            metrics.incrementCounter("identity.mapping_registered");
        }
    }

    public String resolveToCanonical(String anyId) {
        if (anyId == null) {
            return null;
        }

        String canonical = protocolToCanonical.get(anyId);
        if (canonical != null) {
            return canonical;
        }

        if (canonicalToProtocol.containsKey(anyId)) {
            return anyId;
        }

        canonical = addressToCanonical.get(anyId);
        if (canonical != null) {
            return canonical;
        }

        return anyId;
    }

    public String resolveToProtocol(String canonicalId) {
        if (canonicalId == null) {
            return null;
        }
        return canonicalToProtocol.get(canonicalId);
    }

    public boolean hasMappingFor(String anyId) {
        if (anyId == null) {
            return false;
        }
        return protocolToCanonical.containsKey(anyId) ||
               canonicalToProtocol.containsKey(anyId) ||
               addressToCanonical.containsKey(anyId);
    }

    public void removeMapping(String canonicalId) {
        if (canonicalId == null) {
            return;
        }

        String protocolId = canonicalToProtocol.remove(canonicalId);
        if (protocolId != null) {
            protocolToCanonical.remove(protocolId);
        }

        addressToCanonical.values().remove(canonicalId);

        log.debug("Identity mapping removed", "canonicalId", canonicalId, "protocolId", protocolId);
        metrics.incrementCounter("identity.mapping_removed");
    }

    public IdentityStatistics getStatistics() {
        return new IdentityStatistics(
            protocolToCanonical.size(),
            canonicalToProtocol.size(),
            addressToCanonical.size());
    }

    public static class IdentityStatistics {
        public final int protocolMappings;
        public final int canonicalMappings;
        public final int addressMappings;

        public IdentityStatistics(int protocolMappings, int canonicalMappings, int addressMappings) {
            this.protocolMappings = protocolMappings;
            this.canonicalMappings = canonicalMappings;
            this.addressMappings = addressMappings;
        }

        @Override
        public String toString() {
            return String.format(
                "IdentityStatistics{protocol=%d, canonical=%d, address=%d}",
                protocolMappings, canonicalMappings, addressMappings);
        }
    }
}

