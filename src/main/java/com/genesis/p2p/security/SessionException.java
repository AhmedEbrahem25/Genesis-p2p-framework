package com.genesis.p2p.security;

/**
 * Exception thrown when session operations fail.
 */
public class SessionException extends SecurityException {
    public SessionException(String message) {
        super(message);
    }

    public SessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
