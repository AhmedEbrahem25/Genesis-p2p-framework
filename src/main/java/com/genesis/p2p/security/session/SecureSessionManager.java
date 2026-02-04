package com.genesis.p2p.security.session;

import com.genesis.p2p.security.api.ISecureSession;
import com.genesis.p2p.security.api.ISessionManager;
import com.genesis.p2p.observability.logging.NodeLogger;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Session manager implementation with key rotation support.
 *
 * Manages lifecycle of secure sessions:
 * - Creation via ECDH shared secret
 * - Expiration based on timeout
 * - Key rotation for long-lived sessions
 * - Cleanup of expired sessions
 *
 * Thread-safe using ConcurrentHashMap.
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class SecureSessionManager implements ISessionManager {

    private static final NodeLogger log = NodeLogger.getLogger(SecureSessionManager.class);

    private final ConcurrentHashMap<String, SecureSession> sessions;
    private final Duration timeout;

    // SECURITY FIX (v2.6): Session replacement callback support
    // Allows applications to be notified when a session is replaced,
    // enabling them to take security actions (e.g., force re-authentication)
    private final List<SessionReplacementListener> replacementListeners;

    /**
     * Listener interface for session replacement events.
     *
     * SECURITY FIX (v2.6): Applications can register listeners to be notified
     * when an existing session is replaced. This is important for:
     * - Detecting potential session hijacking attempts
     * - Forcing re-authentication when sessions are unexpectedly replaced
     * - Auditing session lifecycle events
     */
    @FunctionalInterface
    public interface SessionReplacementListener {
        /**
         * Called when a session is replaced.
         *
         * @param peerId the peer whose session was replaced
         * @param oldSessionId the ID of the replaced session
         * @param newSessionId the ID of the new session
         * @param oldMessageCount the message count from the replaced session
         */
        void onSessionReplaced(String peerId, String oldSessionId, String newSessionId, long oldMessageCount);
    }

    public SecureSessionManager(Duration timeout) {
        this.sessions = new ConcurrentHashMap<>();
        this.timeout = timeout;
        this.replacementListeners = new CopyOnWriteArrayList<>();

        log.info("SecureSessionManager initialized", "timeout", timeout);
    }

    /**
     * Registers a session replacement listener.
     *
     * SECURITY FIX (v2.6): Allows applications to monitor session replacements.
     *
     * @param listener the listener to register
     */
    public void addSessionReplacementListener(SessionReplacementListener listener) {
        if (listener != null) {
            replacementListeners.add(listener);
            log.debug("Session replacement listener added");
        }
    }

    /**
     * Removes a session replacement listener.
     *
     * @param listener the listener to remove
     */
    public void removeSessionReplacementListener(SessionReplacementListener listener) {
        if (listener != null) {
            replacementListeners.remove(listener);
            log.debug("Session replacement listener removed");
        }
    }

    /**
     * Creates a new session from ECDH shared secret.
     *
     * Uses HKDF to derive:
     * - Encryption key (for AES-GCM)
     * - HMAC key (for message authentication)
     * - Session ID (for identification)
     *
     * @param localPeerId local peer identifier
     * @param remotePeerId remote peer identifier
     * @param sharedSecret raw ECDH shared secret
     * @return new secure session
     */
    @Override
    public ISecureSession createSession(String localPeerId, String remotePeerId, byte[] sharedSecret)
            throws Exception {

        if (sharedSecret == null || sharedSecret.length == 0) {
            throw new IllegalArgumentException("Shared secret cannot be null or empty");
        }

        // Create session with HKDF key derivation
        SecureSession session = new SecureSession(localPeerId, remotePeerId, sharedSecret);

        // Store session
        SecureSession oldSession = sessions.put(remotePeerId, session);

        if (oldSession != null) {
            log.warn("Replaced existing session",
                    "peerId", remotePeerId,
                    "oldSessionId", oldSession.getSessionId(),
                    "newSessionId", session.getSessionId(),
                    "oldMessageCount", oldSession.getMessageCount());

            // SECURITY FIX (v2.6): Notify listeners of session replacement
            notifySessionReplaced(remotePeerId, oldSession.getSessionId(),
                    session.getSessionId(), oldSession.getMessageCount());

            oldSession.invalidate();
        }

        log.info("Session created",
                "peerId", remotePeerId,
                "sessionId", session.getSessionId(),
                "messageCount", 0);

        return session;
    }

    /**
     * Gets an existing session for a peer.
     */
    @Override
    public ISecureSession getSession(String peerId) {
        SecureSession session = sessions.get(peerId);

        if (session != null && session.isExpired(timeout)) {
            log.debug("Session expired", "peerId", peerId);
            invalidateSession(peerId);
            return null;
        }

        return session;
    }

    /**
     * Invalidates and removes a session.
     */
    @Override
    public void invalidateSession(String peerId) {
        SecureSession session = sessions.remove(peerId);
        if (session != null) {
            session.invalidate();
            log.info("Session invalidated",
                    "peerId", peerId,
                    "sessionId", session.getSessionId(),
                    "messageCount", session.getMessageCount());
        }
    }

    /**
     * Removes all expired sessions.
     */
    @Override
    public void cleanupExpiredSessions() {
        int removedCount = 0;

        for (Map.Entry<String, SecureSession> entry : sessions.entrySet()) {
            SecureSession session = entry.getValue();

            if (session.isExpired(timeout)) {
                String peerId = entry.getKey();
                sessions.remove(peerId);
                session.invalidate();
                removedCount++;

                log.debug("Expired session removed",
                        "peerId", peerId,
                        "age", session.getAge(),
                        "idleTime", session.getIdleTime());
            }
        }

        if (removedCount > 0) {
            log.info("Session cleanup completed", "removed", removedCount);
        }
    }

    /**
     * Gets all active session peer IDs.
     */
    public Collection<String> getActivePeerIds() {
        return sessions.entrySet().stream()
                .filter(e -> e.getValue().isActive() && !e.getValue().isExpired(timeout))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Gets count of active sessions.
     */
    public int getActiveSessionCount() {
        return (int) sessions.values().stream()
                .filter(s -> s.isActive() && !s.isExpired(timeout))
                .count();
    }

    /**
     * Gets total session count (including expired).
     */
    public int getTotalSessionCount() {
        return sessions.size();
    }

    /**
     * Gets sessions that need key rotation.
     */
    public Collection<String> getSessionsNeedingRotation() {
        return sessions.entrySet().stream()
                .filter(e -> e.getValue().needsKeyRotation())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Rotates keys for a specific session.
     * Implements ISessionManager.rotateSession().
     *
     * @param peerId peer identifier
     * @return true if rotation succeeded, false if no session exists
     */
    @Override
    public boolean rotateSession(String peerId) {
        return rotateSessionKeys(peerId);
    }

    /**
     * Rotates keys for a specific session (legacy method name).
     *
     * @param peerId peer identifier
     * @return true if rotation succeeded, false if no session exists
     */
    public boolean rotateSessionKeys(String peerId) {
        SecureSession session = sessions.get(peerId);
        if (session == null) {
            return false;
        }

        try {
            // Generate new session with rotated keys
            SecureSession newSession = session.rotateKeys();

            // Replace old session
            sessions.put(peerId, newSession);
            session.invalidate();

            log.info("Session keys rotated",
                    "peerId", peerId,
                    "oldSessionId", session.getSessionId(),
                    "newSessionId", newSession.getSessionId());

            return true;

        } catch (Exception e) {
            log.error("Key rotation failed",
                    "peerId", peerId,
                    "error", e.getMessage());
            return false;
        }
    }

    /**
     * Rotates keys for all sessions that need it.
     */
    public int rotateAllNeededKeys() {
        Collection<String> needsRotation = getSessionsNeedingRotation();
        int rotatedCount = 0;

        for (String peerId : needsRotation) {
            if (rotateSessionKeys(peerId)) {
                rotatedCount++;
            }
        }

        if (rotatedCount > 0) {
            log.info("Batch key rotation completed", "rotated", rotatedCount);
        }

        return rotatedCount;
    }

    /**
     * Gets comprehensive statistics for all sessions.
     */
    public SessionManagerStats getStats() {
        int active = getActiveSessionCount();
        int total = getTotalSessionCount();
        int needsRotation = getSessionsNeedingRotation().size();

        long totalMessages = sessions.values().stream()
                .mapToLong(SecureSession::getMessageCount)
                .sum();

        return new SessionManagerStats(
                active,
                total - active, // expired/inactive
                total,
                needsRotation,
                totalMessages,
                timeout
        );
    }

    /**
     * Gets detailed stats for a specific session.
     */
    public SecureSession.SessionStats getSessionStats(String peerId) {
        SecureSession session = sessions.get(peerId);
        return session != null ? session.getStats() : null;
    }

    /**
     * Invalidates all sessions (for shutdown or emergency).
     */
    public void invalidateAllSessions() {
        log.warn("Invalidating all sessions", "count", sessions.size());

        sessions.values().forEach(SecureSession::invalidate);
        sessions.clear();
    }

    /**
     * Notifies all registered listeners of a session replacement.
     *
     * SECURITY FIX (v2.6): Internal helper for notifying listeners.
     */
    private void notifySessionReplaced(String peerId, String oldSessionId, String newSessionId, long oldMessageCount) {
        for (SessionReplacementListener listener : replacementListeners) {
            try {
                listener.onSessionReplaced(peerId, oldSessionId, newSessionId, oldMessageCount);
            } catch (Exception e) {
                log.error("Session replacement listener threw exception",
                        "listenerClass", listener.getClass().getSimpleName(),
                        "error", e.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return String.format("SecureSessionManager[active=%d, total=%d, timeout=%s]",
                getActiveSessionCount(),
                getTotalSessionCount(),
                timeout);
    }

    /**
     * Session manager statistics record.
     */
    public record SessionManagerStats(
            int activeSessions,
            int expiredSessions,
            int totalSessions,
            int sessionsNeedingRotation,
            long totalMessages,
            Duration sessionTimeout
    ) {}
}