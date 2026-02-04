package com.genesis.p2p.core.handlers.processors.system;

import com.genesis.p2p.core.*;
import com.genesis.p2p.core.peer.PeerManager;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;
import com.google.gson.Gson;
import com.google.gson.JsonObject;




/**
 * Processes PONG messages - response to PING.
 *
 * Purpose:
 * - Receive connectivity test response
 * - Calculate round-trip time
 *
 * Responsibilities:
 * - Calculate and record latency
 * - Update peer health metrics
 */

public class PongProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(PongProcessor.class);

    private final PeerManager peerManager;
    private final Gson gson;

    public PongProcessor(PeerManager peerManager) {
        this.peerManager = peerManager;
        this.gson = new Gson();

        log.info("PongProcessor initialized");
    }

    @Override
    public void processMessage(Message message) {
        MessageHeader header = message.header();
        MessageBody body = message.body();

        String peerId = header.from();
        long receiveTime = Time.currentMillis();

        log.debug("Processing PONG", "peerId", peerId);

        try {
            JsonObject payload = gson.fromJson(body.content(), JsonObject.class);

            long pingTime = payload.has("pingTimestamp") ?
                    payload.get("pingTimestamp").getAsLong() : 0;
            long pongTime = payload.has("pongTimestamp") ?
                    payload.get("pongTimestamp").getAsLong() : receiveTime;

            // Calculate round-trip time
            long rtt = receiveTime - pingTime;

            if (rtt > 0 && rtt < 60_000) { // Sanity check: < 60 seconds
                peerManager.updateLatency(peerId, rtt);

                log.debug("RTT measured",
                        "peerId", peerId,
                        "rtt", rtt + "ms");
            }

            // Update peer state
            peerManager.refreshLastSeen(peerId);
            peerManager.recordSuccess(peerId);

        } catch (Exception e) {
            log.error("Error processing PONG", e, "peerId", peerId);
        }
    }
}