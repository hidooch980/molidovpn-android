package com.molido.vpn;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned authenticated encryption. Never falls back to storing plaintext. */
public final class ProfileCipher {
    private static final String PREFIX = "vp1:";
    private static final byte[] AAD = "molido-vpn-profile-v1".getBytes(StandardCharsets.UTF_8);
    private ProfileCipher() {}

    public static String encrypt(SecretKey key, String value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(AAD);
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new GeneralSecurityException("Profile encryption unavailable");
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] payload = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        return PREFIX + Base64.getEncoder().encodeToString(payload);
    }

    public static String decrypt(SecretKey key, String value) throws GeneralSecurityException {
        if (!value.startsWith(PREFIX)) throw new GeneralSecurityException("Invalid profile envelope");
        final byte[] payload;
        try { payload = Base64.getDecoder().decode(value.substring(PREFIX.length())); }
        catch (IllegalArgumentException e) { throw new GeneralSecurityException("Invalid profile envelope"); }
        if (payload.length < 28) throw new GeneralSecurityException("Invalid profile envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, payload, 0, 12));
        cipher.updateAAD(AAD);
        return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
    }
}
