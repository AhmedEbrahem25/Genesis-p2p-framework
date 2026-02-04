package com.genesis.p2p.security;

/**
 * Exception thrown when encryption/decryption operations fail.
 */
public class EncryptionException extends SecurityException {
    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
