package free.bitchat.legacy;

import android.util.Base64;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ECDH P-256 + AES-CTR + HMAC-SHA256, avec HKDF-SHA256 pour la dérivation.
 *
 * Tout est disponible dès l'API 11 : aucun besoin de SpongyCastle, ce qui
 * garde l'APK minuscule et évite d'alourdir un SoC de 2013.
 *
 * AES/GCM n'existe qu'à partir de l'API 19, d'où le construction
 * encrypt-then-MAC manuelle.
 */
public final class Crypto {

    private static final String CURVE   = "secp256r1";
    private static final String CIPHER  = "AES/CTR/NoPadding";
    private static final String MAC_ALG = "HmacSHA256";
    private static final byte[] INFO    = "bitchat-legacy-v1".getBytes();

    private Crypto() {}

    // ------------------------------------------------------------- identité

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec(CURVE), new SecureRandom());
        return kpg.generateKeyPair();
    }

    public static PublicKey decodePublic(byte[] der) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }

    public static PrivateKey decodePrivate(byte[] der) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static byte[] ecdh(PrivateKey mine, PublicKey theirs) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(theirs, true);
        return ka.generateSecret();
    }

    public static byte[] sha256(byte[] in) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** Les 16 premiers octets du SHA-256 de la clé publique = identité réseau. */
    public static byte[] deriveId(PublicKey pub) throws GeneralSecurityException {
        byte[] h = sha256(pub.getEncoded());
        byte[] id = new byte[MeshPacket.ID_LEN];
        System.arraycopy(h, 0, id, 0, MeshPacket.ID_LEN);
        return id;
    }

    // ------------------------------------------------------------------ HKDF

    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance(MAC_ALG);
        byte[] s = (salt == null || salt.length == 0) ? new byte[32] : salt;
        mac.init(new SecretKeySpec(s, MAC_ALG));
        byte[] prk = mac.doFinal(ikm);

        byte[] okm = new byte[len];
        byte[] t = new byte[0];
        int off = 0;
        byte counter = 1;
        while (off < len) {
            mac.init(new SecretKeySpec(prk, MAC_ALG));
            mac.update(t);
            if (info != null) mac.update(info);
            mac.update(counter);
            t = mac.doFinal();
            int n = Math.min(t.length, len - off);
            System.arraycopy(t, 0, okm, off, n);
            off += n;
            counter++;
        }
        return okm;
    }

    // ------------------------------------------------- encrypt-then-MAC
    // Sortie : iv(16) || ciphertext || tag(32)

    public static byte[] seal(byte[] sharedSecret, byte[] plaintext, byte[] aad)
            throws GeneralSecurityException {
        byte[] keys = hkdf(sharedSecret, new byte[32], INFO, 64);
        byte[] encKey = new byte[32];
        byte[] macKey = new byte[32];
        System.arraycopy(keys, 0, encKey, 0, 32);
        System.arraycopy(keys, 32, macKey, 0, 32);

        byte[] iv = random(16);
        Cipher c = Cipher.getInstance(CIPHER);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(plaintext);

        Mac mac = Mac.getInstance(MAC_ALG);
        mac.init(new SecretKeySpec(macKey, MAC_ALG));
        mac.update(aad == null ? new byte[0] : aad);
        mac.update(iv);
        mac.update(ct);
        byte[] tag = mac.doFinal();

        byte[] out = new byte[16 + ct.length + 32];
        System.arraycopy(iv, 0, out, 0, 16);
        System.arraycopy(ct, 0, out, 16, ct.length);
        System.arraycopy(tag, 0, out, 16 + ct.length, 32);
        return out;
    }

    public static byte[] open(byte[] sharedSecret, byte[] sealed, byte[] aad)
            throws GeneralSecurityException {
        if (sealed == null || sealed.length < 16 + 32) return null;
        int ctLen = sealed.length - 16 - 32;

        byte[] iv  = new byte[16];
        byte[] ct  = new byte[ctLen];
        byte[] tag = new byte[32];
        System.arraycopy(sealed, 0, iv, 0, 16);
        System.arraycopy(sealed, 16, ct, 0, ctLen);
        System.arraycopy(sealed, 16 + ctLen, tag, 0, 32);

        byte[] keys = hkdf(sharedSecret, new byte[32], INFO, 64);
        byte[] encKey = new byte[32];
        byte[] macKey = new byte[32];
        System.arraycopy(keys, 0, encKey, 0, 32);
        System.arraycopy(keys, 32, macKey, 0, 32);

        Mac mac = Mac.getInstance(MAC_ALG);
        mac.init(new SecretKeySpec(macKey, MAC_ALG));
        mac.update(aad == null ? new byte[0] : aad);
        mac.update(iv);
        mac.update(ct);
        byte[] expected = mac.doFinal();

        // Comparaison à temps constant : évite de fuiter le tag octet par octet.
        int diff = 0;
        for (int i = 0; i < 32; i++) diff |= expected[i] ^ tag[i];
        if (diff != 0) return null;

        Cipher c = Cipher.getInstance(CIPHER);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        return c.doFinal(ct);
    }

    // ---------------------------------------------------------------- base64

    public static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    public static byte[] unb64(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }
}
