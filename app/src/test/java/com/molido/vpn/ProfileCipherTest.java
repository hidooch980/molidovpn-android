package com.molido.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import java.util.Base64;

public class ProfileCipherTest {
    private SecretKey key() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }
    @Test public void roundTripPreservesUnicodeAndPrivateKey() throws Exception {
        SecretKey key = key();
        String secret = "نام پروفایل\n<key>\nPRIVATE-KEY\n</key>";
        String stored = ProfileCipher.encrypt(key, secret);
        assertTrue(stored.startsWith("vp1:"));
        assertFalse(stored.contains("PRIVATE-KEY"));
        assertEquals(secret, ProfileCipher.decrypt(key, stored));
    }
    @Test public void repeatedSavesUseDifferentNonces() throws Exception {
        SecretKey key = key();
        assertNotEquals(ProfileCipher.encrypt(key, "secret"), ProfileCipher.encrypt(key, "secret"));
    }
    @Test public void rejectsWrongKey() throws Exception {
        String stored = ProfileCipher.encrypt(key(), "secret");
        try { ProfileCipher.decrypt(key(), stored); fail(); }
        catch (GeneralSecurityException expected) { }
    }
    @Test public void rejectsTampering() throws Exception {
        SecretKey key = key();
        String stored = ProfileCipher.encrypt(key, "secret");
        byte[] payload = Base64.getDecoder().decode(stored.substring(4));
        payload[payload.length - 1] ^= 1;
        try { ProfileCipher.decrypt(key, "vp1:" + Base64.getEncoder().encodeToString(payload)); fail(); }
        catch (GeneralSecurityException expected) { }
    }
    @Test public void rejectsPlaintextMalformedTruncatedAndUnknownVersion() throws Exception {
        SecretKey key = key();
        for (String invalid : new String[] { "secret", "", "vp1:!", "vp1:AAAA", "vp2:AAAA" }) {
            try { ProfileCipher.decrypt(key, invalid); fail(); }
            catch (GeneralSecurityException expected) { }
        }
    }
    @Test public void missingKeyCannotFallBackToPlaintext() throws Exception {
        try { ProfileCipher.encrypt(null, "PRIVATE-KEY"); fail(); }
        catch (GeneralSecurityException expected) { }
    }
}
