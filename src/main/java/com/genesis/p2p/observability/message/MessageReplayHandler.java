package com.genesis.p2p.observability.message;

import com.genesis.p2p.storage.message.PersistedMessage;

/**
 * Handler for replaying messages on restart.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public interface MessageReplayHandler {

    /**
     * Replays an outbound message that was pending.
     */
    void replayOutbound(PersistedMessage message);

    /**
     * Checks a sent message (e.g., for acknowledgment).
     */
    void checkSent(PersistedMessage message);
}

