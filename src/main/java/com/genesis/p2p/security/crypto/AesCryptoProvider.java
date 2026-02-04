package com.genesis.p2p.security.crypto;

import com.genesis.p2p.security.api.ICryptoProvider;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.SecureRandom;

/**
 * AES-GCM encryption implementation.
 *
 * SECURITY FIX (v2.6): IV is now included as Additional Authenticated Data (AAD).
 * This ensures the IV is authenticated as part of the GCM tag, preventing
 * IV manipulation attacks where an attacker swaps IVs between messages.
 *
 * @author Genesis P2P Framework
 * @version 2.6
 */
public class AesCryptoProvider implements ICryptoProvider {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private final int keySize;

    public AesCryptoProvider(int keySize) {
        this.keySize = keySize / 8; // Convert bits to bytes
    }

    /**
     * Encrypts data using AES-GCM.
     *
     * SECURITY FIX (v2.6): IV is included as AAD (Additional Authenticated Data)
     * to prevent IV manipulation attacks.
     *
     * Wire format: [12 bytes IV] + [ciphertext + auth tag]
     */
    @Override
    public byte[] encrypt(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);

        // SECURITY FIX (v2.6): Include IV as AAD to authenticate it
        // This prevents IV swapping attacks
        cipher.updateAAD(iv);

        byte[] ciphertext = cipher.doFinal(data);

        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
        return result;
    }

    /**
     * Decrypts data using AES-GCM.
     *
     * SECURITY FIX (v2.6): Verifies IV as AAD before decryption.
     */
    @Override
    public byte[] decrypt(byte[] data, byte[] key) throws Exception {
        if (data == null || data.length < GCM_IV_LENGTH + 16) { // 16 = minimum GCM tag
            throw new IllegalArgumentException("Invalid ciphertext: too short");
        }

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH);

        byte[] ciphertext = new byte[data.length - GCM_IV_LENGTH];
        System.arraycopy(data, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");

        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);

        // SECURITY FIX (v2.6): Include IV as AAD to verify it was not manipulated
        cipher.updateAAD(iv);

        return cipher.doFinal(ciphertext);
    }

    @Override
    public int getKeySize() { return keySize * 8; }

    @Override
    public String getAlgorithm() { return "AES-GCM-" + (keySize * 8); }
}
