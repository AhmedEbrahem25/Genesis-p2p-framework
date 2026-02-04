package com.genesis.p2p.security;

import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.peer.StateTimeoutManager;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import com.genesis.p2p.security.channel.SecureChannelNegotiator;
import com.genesis.p2p.transport.core.TransportConnectionListener;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages security bootstrap on TCP connection establishment.
 * FIX #2: CONNECTION LISTENER IMPLEMENTATION
 */
public class SecurityBootstrapManager implements TransportConnectionListener {

    private static final NodeLogger log = NodeLogger.getLogger(SecurityBootstrapManager.class);

    private final String nodeId;
    private final PeerManager peerManager;
    private final SecureChannelNegotiator negotiator;
    private final StateTimeoutManager timeoutManager;
    private final MetricsRegistry metrics;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public SecurityBootstrapManager(
            String nodeId,
            PeerManager peerManager,
            SecureChannelNegotiator negotiator,
            StateTimeoutManager timeoutManager,
            MetricsRegistry metrics) {
        this.nodeId = nodeId;
        this.peerManager = peerManager;
        this.negotiator = negotiator;
        this.timeoutManager = timeoutManager;
        this.metrics = metrics;

        log.info("SecurityBootstrapManager created", "nodeId", nodeId);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("SecurityBootstrapManager started");
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("SecurityBootstrapManager stopped");
        }
    }

    @Override
    public void onConnectionEstablished(String peerId, InetSocketAddress address, boolean isInbound)
            throws Exception {

        if (!running.get()) {
            throw new RuntimeException("SecurityBootstrapManager not running");
        }

        log.info("SECURITY_BOOTSTRAP_CONNECTION_ESTABLISHED",
                "nodeId", nodeId,
                "peerId", peerId,
                "address", address,
                "isInbound", isInbound);

        try {
            boolean marked = peerManager.markChannelNegotiating(peerId);

            if (!marked) {
                log.warn("Failed to mark peer as CHANNEL_NEGOTIATING", "peerId", peerId);
                throw new RuntimeException("Failed to transition peer to CHANNEL_NEGOTIATING");
            }

            log.debug("Peer transitioned to CHANNEL_NEGOTIATING", "peerId", peerId);

            timeoutManager.scheduleChannelNegotiatingTimeout(peerId);
            log.debug("Timeout scheduled for KEY_EXCHANGE", "peerId", peerId, "timeoutMs", 10_000);

            if (!isInbound) {
                log.info("Outbound connection detected, triggering KEY_EXCHANGE_INIT", "peerId", peerId);

                try {
                    initiateKeyExchange(peerId, address);
                    log.info("KEY_EXCHANGE_INIT auto-triggered by connection listener", "peerId", peerId);
                    metrics.incrementCounter("security.bootstrap.auto_triggered");
                } catch (Exception e) {
                    log.error("Failed to auto-trigger KEY_EXCHANGE_INIT", e, "peerId", peerId);
                    peerManager.markDisconnected(peerId);
                    negotiator.cancelNegotiation(peerId);
                    timeoutManager.cancelTimeout(peerId);
                    metrics.incrementCounter("security.bootstrap.auto_trigger_failed");
                    throw e;
                }
            } else {
                log.info("Inbound connection detected, waiting for peer's KEY_EXCHANGE_INIT", "peerId", peerId);
                metrics.incrementCounter("security.bootstrap.inbound_waiting");
            }

            log.info("SECURITY_BOOTSTRAP_CONNECTION_READY",
                    "nodeId", nodeId,
                    "peerId", peerId,
                    "isInbound", isInbound);

        } catch (Exception e) {
            log.error("Security bootstrap on connection failed", e, "peerId", peerId, "address", address);
            metrics.incrementCounter("security.bootstrap.failed");
            throw e;
        }
    }

    @Override
    public void onConnectionClosed(String peerId, String reason) {
        if (!running.get()) {
            return;
        }

        log.info("SECURITY_BOOTSTRAP_CONNECTION_CLOSED",
                "nodeId", nodeId,
                "peerId", peerId,
                "reason", reason);

        try {
            timeoutManager.cancelTimeout(peerId);
            negotiator.cancelNegotiation(peerId);
            peerManager.markDisconnected(peerId);

            log.info("Security bootstrap cleanup completed", "peerId", peerId, "reason", reason);
            metrics.incrementCounter("security.bootstrap.connection_closed");

        } catch (Exception e) {
            log.debug("Error during security bootstrap cleanup", e, "peerId", peerId);
        }
    }

    private void initiateKeyExchange(String peerId, InetSocketAddress address) throws Exception {
        try {
            var keyExchangeInit = negotiator.initiateKeyExchange(peerId, null);
            log.info("KEY_EXCHANGE_INIT created by bootstrap manager",
                    "peerId", peerId,
                    "correlationId", keyExchangeInit.getCorrelationId());
        } catch (Exception e) {
            log.error("Failed to initiate KEY_EXCHANGE", e, "peerId", peerId);
            throw e;
        }
    }
}

