package com.genesis.p2p.security.api;

/**
 * Trust manager interface.
 */
public interface ITrustManager {
    void trustPeer(String peerId, int level);
    void untrustPeer(String peerId);
    boolean isTrusted(String peerId);
    int getTrustLevel(String peerId);
}
