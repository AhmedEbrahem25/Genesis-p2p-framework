package com.genesis.p2p.security.factory;

import com.genesis.p2p.security.api.*;
import com.genesis.p2p.security.config.SecurityConfig;
import com.genesis.p2p.security.crypto.AesCryptoProvider;
import com.genesis.p2p.security.keys.KeyManager;
import com.genesis.p2p.security.session.SecureSessionManager;
import com.genesis.p2p.security.signature.SignatureService;
import com.genesis.p2p.security.trust.TrustManager;

/**
 * Factory for creating security components.
 * Design Pattern: Factory Pattern
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class SecurityFactory {

    private final SecurityConfig config;

    public SecurityFactory(SecurityConfig config) {
        this.config = config;
    }

    public ICryptoProvider createCryptoProvider() {
        return switch (config.cryptoAlgorithm()) {
            case "AES-GCM" -> new AesCryptoProvider(config.keySize());
            // case "ChaCha20" -> new ChaCha20CryptoProvider();
            default -> new AesCryptoProvider(config.keySize());
        };
    }

    public IKeyManager createKeyManager() throws Exception {
        return new KeyManager();
    }

    public ISessionManager createSessionManager() {
        return new SecureSessionManager(config.sessionTimeout());
    }

    public ISignatureService createSignatureService() {
        return new SignatureService();
    }

    public ITrustManager createTrustManager() {
        return new TrustManager(config.defaultTrustLevel());
    }
}
