package free.bitchat.legacy;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Clé persistante de l'appareil + pseudo.
 *
 * Pas de compte, pas de numéro : l'identité réseau est dérivée de la clé
 * publique, comme le fait bitchat.
 */
public final class Identity {

    private static final String PREFS  = "bitchat_identity";
    private static final String K_PRIV = "priv";
    private static final String K_PUB  = "pub";
    private static final String K_NICK = "nick";

    public final byte[] id;
    public final byte[] publicKey;

    private final PrivateKey privateKey;
    private final SharedPreferences prefs;

    private Identity(SharedPreferences prefs, PrivateKey priv, PublicKey pub, byte[] id) {
        this.prefs = prefs;
        this.privateKey = priv;
        this.publicKey = pub.getEncoded();
        this.id = id;
    }

    public static Identity load(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String privB64 = sp.getString(K_PRIV, null);
        String pubB64  = sp.getString(K_PUB, null);

        if (privB64 == null || pubB64 == null) {
            KeyPair kp = Crypto.generateKeyPair();
            privB64 = Crypto.b64(kp.getPrivate().getEncoded());
            pubB64  = Crypto.b64(kp.getPublic().getEncoded());
            sp.edit().putString(K_PRIV, privB64).putString(K_PUB, pubB64).commit();
        }

        PrivateKey priv = Crypto.decodePrivate(Crypto.unb64(privB64));
        PublicKey  pub  = Crypto.decodePublic(Crypto.unb64(pubB64));
        byte[] id = Crypto.deriveId(pub);
        return new Identity(sp, priv, pub, id);
    }

    public PrivateKey privateKey() { return privateKey; }

    public PublicKey publicKey() throws Exception {
        return Crypto.decodePublic(publicKey);
    }

    public String nick() {
        String n = prefs.getString(K_NICK, null);
        if (n == null) n = "anon-" + hex(id).substring(0, 4);
        return n;
    }

    public void setNick(String n) {
        prefs.edit().putString(K_NICK, n).commit();
    }

    public void wipe() {
        prefs.edit().clear().commit();
    }

    public static String hex(byte[] in) {
        StringBuilder sb = new StringBuilder(in.length * 2);
        for (byte b : in) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static boolean sameId(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}
