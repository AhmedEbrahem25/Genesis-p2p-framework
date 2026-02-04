package com.genesis.p2p.security;

/**
 * Exception thrown when key exchange operations fail.
 */
public class KeyExchangeException extends SecurityException {
    public KeyExchangeException(String message) {
        super(message);
    }

    public KeyExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
