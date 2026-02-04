package com.genesis.p2p.security;

/**
 * Base security exception for the security module.
 */
public class SecurityException extends RuntimeException {
    public SecurityException(String message) {
        super(message);
    }

    public SecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
