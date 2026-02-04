package com.genesis.p2p.security.api;

import java.security.KeyPair;

/**
 * Key manager interface for cryptographic key operations.
 *
 * Provides:
 * - Key generation (EC keypairs for ECDH)
 * - Key storage and retrieval
 * - ECDH shared secret derivation
 * - Identity key signing and verification (for secure channel negotiation)
 */
public interface IKeyManager {
    KeyPair generateKeyPair() throws Exception;
    byte[] getPublicKey(String peerId) throws Exception;
    void storePublicKey(String peerId, byte[] publicKey) throws Exception;
    byte[] deriveSharedSecret(String peerId, byte[] theirPublicKey) throws Exception;
    byte[] getSharedSecret(String peerId);

    /**
     * Gets this node's identity public key for sharing with peers.
     * The identity key is the long-term key used to sign ephemeral keys
     * during secure channel negotiation.
     *
     * @return X.509 encoded identity public key
     * @throws Exception if key retrieval fails
     */
    byte[] getIdentityPublicKey() throws Exception;

    /**
     * Signs data with this node's identity private key.
     * Used to prove ownership of ephemeral keys during channel negotiation.
     *
     * @param data the data to sign
     * @return ECDSA signature over the data
     * @throws Exception if signing fails
     */
    byte[] signWithIdentityKey(byte[] data) throws Exception;

    /**
     * Verifies a signature against a peer's identity public key.
     *
     * @param data the signed data
     * @param signature the signature to verify
     * @param identityPublicKey the peer's identity public key (X.509 encoded)
     * @return true if signature is valid
     * @throws Exception if verification fails (invalid key, etc.)
     */
    boolean verifyIdentitySignature(byte[] data, byte[] signature, byte[] identityPublicKey) throws Exception;

    /**
     * Stores a peer's identity public key for future verification.
     *
     * @param peerId the peer identifier
     * @param identityPublicKey X.509 encoded identity public key
     */
    void storeIdentityPublicKey(String peerId, byte[] identityPublicKey);

    /**
     * Gets a previously stored identity public key for a peer.
     *
     * @param peerId the peer identifier
     * @return X.509 encoded identity public key, or null if not stored
     */
    byte[] getIdentityPublicKey(String peerId);
}
