
package com.genesis.p2p.security.signature;

import com.genesis.p2p.security.api.ISignatureService;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;

/**
 * Signature service implementation.
 *
 * SECURITY FIX (v2.6): Added comprehensive input parameter validation
 * to prevent null pointer exceptions and ensure cryptographic operations
 * fail fast with clear error messages.
 *
 * @author Genesis P2P Framework
 * @version 2.6
 */
public class SignatureService implements ISignatureService {
    private static final String ALGORITHM = "SHA256withECDSA";
    private static final String EC_ALGORITHM = "EC";

    /**
     * Signs data using the private key from the provided keypair.
     *
     * SECURITY FIX (v2.6): Added null checks for all parameters.
     *
     * @param data the data to sign (must not be null or empty)
     * @param keyPair the keypair containing the private key (must not be null)
     * @return the signature bytes
     * @throws IllegalArgumentException if data or keyPair is null/invalid
     * @throws Exception if signing fails
     */
    @Override
    public byte[] sign(byte[] data, KeyPair keyPair) throws Exception {
        // SECURITY FIX (v2.6): Validate input parameters
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data to sign cannot be null or empty");
        }
        if (keyPair == null) {
            throw new IllegalArgumentException("KeyPair cannot be null");
        }
        if (keyPair.getPrivate() == null) {
            throw new IllegalArgumentException("KeyPair must contain a private key for signing");
        }

        Signature signature = Signature.getInstance(ALGORITHM);
        signature.initSign(keyPair.getPrivate());
        signature.update(data);
        return signature.sign();
    }

    /**
     * Verifies a signature against the provided data using the public key.
     *
     * SECURITY FIX (v2.6): Added null checks for all parameters.
     *
     * @param data the original data that was signed (must not be null or empty)
     * @param signature the signature to verify (must not be null or empty)
     * @param publicKeyBytes the X.509 encoded public key (must not be null or empty)
     * @return true if signature is valid, false otherwise
     * @throws IllegalArgumentException if any parameter is null/invalid
     * @throws Exception if verification fails due to cryptographic error
     */
    @Override
    public boolean verify(byte[] data, byte[] signature, byte[] publicKeyBytes) throws Exception {
        // SECURITY FIX (v2.6): Validate input parameters
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }
        if (signature == null || signature.length == 0) {
            throw new IllegalArgumentException("Signature cannot be null or empty");
        }
        if (publicKeyBytes == null || publicKeyBytes.length == 0) {
            throw new IllegalArgumentException("Public key bytes cannot be null or empty");
        }

        KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        PublicKey publicKey = keyFactory.generatePublic(keySpec);

        Signature sig = Signature.getInstance(ALGORITHM);
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signature);
    }
}