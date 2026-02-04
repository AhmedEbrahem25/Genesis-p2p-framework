package com.genesis.p2p.core.peer;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.observability.metrics.MetricsRegistry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages state timeouts for peer connections.
 * FIX #5: TIMEOUT ENFORCEMENT
 */
public class StateTimeoutManager {

    private static final NodeLogger log = NodeLogger.getLogger(StateTimeoutManager.class);

    private final PeerStateMachine stateMachine;
    private final MetricsRegistry metrics;
    private final ScheduledExecutorService scheduler;

    private static final long CHANNEL_NEGOTIATING_TIMEOUT = 10_000L;
    private static final long CONNECTING_TIMEOUT = 5_000L;
    private static final long CONNECTED_TIMEOUT = 5_000L;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public StateTimeoutManager(PeerStateMachine stateMachine, MetricsRegistry metrics) {
        this.stateMachine = stateMachine;
        this.metrics = metrics;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "StateTimeout-" + Thread.currentThread().getId());
            t.setDaemon(true);
            return t;
        });

        log.info("StateTimeoutManager created");
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("StateTimeoutManager started");
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("StateTimeoutManager stopping");
            timeouts.forEach((peerId, timeout) -> timeout.cancel(false));
            timeouts.clear();
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("StateTimeoutManager scheduler did not terminate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("StateTimeoutManager stopped");
        }
    }

    public void scheduleChannelNegotiatingTimeout(String peerId) {
        if (!running.get()) return;
        scheduleStateTimeout(peerId, PeerStore.PeerState.CHANNEL_NEGOTIATING, CHANNEL_NEGOTIATING_TIMEOUT);
    }

    public void scheduleConnectingTimeout(String peerId) {
        if (!running.get()) return;
        scheduleStateTimeout(peerId, PeerStore.PeerState.CONNECTING, CONNECTING_TIMEOUT);
    }

    public void scheduleConnectedTimeout(String peerId) {
        if (!running.get()) return;
        scheduleStateTimeout(peerId, PeerStore.PeerState.CONNECTED, CONNECTED_TIMEOUT);
    }

    public void cancelTimeout(String peerId) {
        ScheduledFuture<?> timeout = timeouts.remove(peerId);
        if (timeout != null && !timeout.isDone()) {
            timeout.cancel(false);
            log.debug("Timeout cancelled for peer", "peerId", peerId);
            metrics.incrementCounter("state_timeout.cancelled");
        }
    }

    private void scheduleStateTimeout(String peerId, PeerStore.PeerState state, long timeoutMs) {
        ScheduledFuture<?> oldTimeout = timeouts.remove(peerId);
        if (oldTimeout != null && !oldTimeout.isDone()) {
            oldTimeout.cancel(false);
        }

        ScheduledFuture<?> newTimeout = scheduler.schedule(() -> {
            onStateTimeout(peerId, state);
        }, timeoutMs, TimeUnit.MILLISECONDS);

        timeouts.put(peerId, newTimeout);
        log.debug("State timeout scheduled", "peerId", peerId, "state", state.name(), "timeoutMs", timeoutMs);
        metrics.incrementCounter("state_timeout.scheduled");
    }

    private void onStateTimeout(String peerId, PeerStore.PeerState expectedState) {
        PeerStore.PeerState currentState = stateMachine.getState(peerId);

        if (currentState == expectedState) {
            log.warn("State timeout fired - auto-recovery triggered", "peerId", peerId, "state", expectedState.name());
            boolean transitioned = stateMachine.markDisconnected(peerId);
            if (transitioned) {
                log.info("State timeout recovery successful", "peerId", peerId, "fromState", expectedState.name());
                metrics.incrementCounter("state_timeout.auto_recovery");
            } else {
                log.warn("State timeout recovery failed", "peerId", peerId);
                metrics.incrementCounter("state_timeout.auto_recovery_failed");
            }
        } else {
            log.debug("State timeout fired but peer already progressed", "peerId", peerId, "expectedState", expectedState.name(), "currentState", currentState.name());
            metrics.incrementCounter("state_timeout.spurious");
        }

        timeouts.remove(peerId);
    }

    public int getActiveTimeoutCount() {
        return timeouts.size();
    }

    public ScheduledFuture<?> getTimeout(String peerId) {
        return timeouts.get(peerId);
    }
}

