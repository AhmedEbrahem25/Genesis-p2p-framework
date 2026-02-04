package com.genesis.p2p.security.api;

/**
 * Crypto provider interface.
 * Design Pattern: Strategy Pattern
 */
public interface ICryptoProvider {
    byte[] encrypt(byte[] data, byte[] key) throws Exception;
    byte[] decrypt(byte[] data, byte[] key) throws Exception;
    int getKeySize();
    String getAlgorithm();
}
