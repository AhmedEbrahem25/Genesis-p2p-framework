package com.genesis.p2p.security.api;

import java.security.KeyPair;

/**
 * Signature service interface.
 */
public interface ISignatureService {
    byte[] sign(byte[] data, KeyPair keyPair) throws Exception;
    boolean verify(byte[] data, byte[] signature, byte[] publicKey) throws Exception;
}
