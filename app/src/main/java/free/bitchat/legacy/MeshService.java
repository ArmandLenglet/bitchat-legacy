package free.bitchat.legacy;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le routeur mesh. C'est ici que vit toute la logique de bitchat :
 * déduplication, décrément du TTL, inondation, annonces de présence.
 */
public final class MeshService extends Service
        implements RfcommMesh.Listener, UdpTransport.Listener {

    public interface Callback {
        void onLog(String line);
        void onPeersChanged(List<String> peers);
    }

    public static final int NOTIF_ID = 0xBC;
    public static volatile MeshService instance;

    private static final byte MAX_TTL = 7;              // comme bitchat
    private static final int  DEDUP_SIZE = 2048;
    private static final long ANNOUNCE_INTERVAL_MS = 20000L;
    private static final long PEER_TIMEOUT_MS = 120000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DedupCache dedup = new DedupCache(DEDUP_SIZE);
    private final Map<String, Peer> peers = new ConcurrentHashMap<String, Peer>();

    private Identity identity;
    private RfcommMesh rfcomm;
    private UdpTransport udp;
    private volatile Callback callback;

    private static final class Peer {
        String nick;
        byte[] publicKey;
        long lastSeen;
    }

    // ------------------------------------------------------------ cycle de vie

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            identity = Identity.load(this);
        } catch (Exception e) {
            stopSelf();
            return;
        }

        rfcomm = new RfcommMesh(this, this);
        udp = new UdpTransport(identity.id, this);

        startForeground(NOTIF_ID, buildNotification());

        rfcomm.start();
        udp.start();

        announce();
        schedulePeerSweep();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (rfcomm != null) rfcomm.stop();
        if (udp != null) udp.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public void setCallback(Callback c) {
        this.callback = c;
        if (c != null) c.onPeersChanged(peerNames());
    }

    // ---------------------------------------------------------------- émission

    /** Émet un paquet dans le mesh : livraison locale + inondation. */
    public void send(byte type, byte[] payload) {
        if (identity == null) return;
        MeshPacket p = new MeshPacket();
        p.type = type;
        p.ttl = MAX_TTL;
        p.senderId = identity.id;
        p.msgId = Crypto.random(MeshPacket.MSG_LEN);
        p.timestamp = (int) (System.currentTimeMillis() / 1000);
        p.payload = payload == null ? new byte[0] : payload;

        dedup.checkAndAdd(p.msgId);
        handleLocal(p);
        flood(p, null);
    }

    private void announce() {
        try {
            byte[] nick = identity.nick().getBytes("UTF-8");
            byte[] pub = identity.publicKey();
            byte[] payload = new byte[2 + nick.length + pub.length];
            payload[0] = (byte) (nick.length >> 8);
            payload[1] = (byte) (nick.length & 0xFF);
            System.arraycopy(nick, 0, payload, 2, nick.length);
            System.arraycopy(pub, 0, payload, 2 + nick.length, pub.length);
            send(MeshPacket.T_ANNOUNCE, payload);
        } catch (Exception ignored) {}

        ui.postDelayed(new Runnable() {
            public void run() {
                if (instance != null) announce();
            }
        }, ANNOUNCE_INTERVAL_MS);
    }

    private void flood(MeshPacket p, String exceptLink) {
        if (p.ttl <= 1) return;
        p.ttl--;
        byte[] raw = p.encode();
        if (rfcomm != null) rfcomm.broadcast(raw, exceptLink);
        if (udp != null) udp.broadcast(raw, exceptLink);
    }

    // --------------------------------------------------------------- réception

    @Override
    public void onPacket(MeshPacket p, String fromLinkId) {
        if (identity == null) return;
        if (Identity.sameId(p.senderId, identity.id)) return;   // notre propre paquet
        if (!dedup.checkAndAdd(p.msgId)) return;                // déjà vu → jeté

        touchPeer(p.senderId);
        handleLocal(p);
        flood(p, fromLinkId);
    }

    @Override
    public void onPeersChanged() {
        notifyPeers();
    }

    private void handleLocal(MeshPacket p) {
        switch (p.type) {
            case MeshPacket.T_ANNOUNCE: onAnnounce(p); break;
            case MeshPacket.T_MESSAGE:  onMessage(p);  break;
            case MeshPacket.T_PRIVATE:  onPrivate(p);  break;
            case MeshPacket.T_LEAVE:    onLeave(p);    break;
            default: break;
        }
    }

    private void onAnnounce(MeshPacket p) {
        try {
            if (p.payload.length < 3) return;
            int nickLen = ((p.payload[0] & 0xFF) << 8) | (p.payload[1] & 0xFF);
            if (2 + nickLen > p.payload.length) return;

            String nick = new String(p.payload, 2, nickLen, "UTF-8");
            byte[] pub = new byte[p.payload.length - 2 - nickLen];
            System.arraycopy(p.payload, 2 + nickLen, pub, 0, pub.length);

            String key = Identity.hex(p.senderId);
            Peer peer = peers.get(key);
            if (peer == null) {
                peer = new Peer();
                peers.put(key, peer);
                log("* " + nick + " a rejoint le réseau");
            }
            peer.nick = nick;
            peer.publicKey = pub;
            peer.lastSeen = System.currentTimeMillis();
            notifyPeers();
        } catch (Exception ignored) {}
    }

    private void onMessage(MeshPacket p) {
        try {
            log("<" + nickOf(p.senderId) + "> " + new String(p.payload, "UTF-8"));
        } catch (Exception ignored) {}
    }

    private void onLeave(MeshPacket p) {
        Peer peer = peers.remove(Identity.hex(p.senderId));
        if (peer != null) {
            log("* " + peer.nick + " a quitté le réseau");
            notifyPeers();
        }
    }

    private void onPrivate(MeshPacket p) {
        try {
            // payload : recipientId(16) || ephPubLen(2) || ephPub || sealed
            if (p.payload.length < 18) return;

            byte[] recipient = new byte[MeshPacket.ID_LEN];
            System.arraycopy(p.payload, 0, recipient, 0, MeshPacket.ID_LEN);
            if (!Identity.sameId(recipient, identity.id)) return;   // pas pour nous

            int pubLen = ((p.payload[16] & 0xFF) << 8) | (p.payload[17] & 0xFF);
            if (18 + pubLen > p.payload.length) return;

            byte[] ephPub = new byte[pubLen];
            System.arraycopy(p.payload, 18, ephPub, 0, pubLen);

            byte[] sealed = new byte[p.payload.length - 18 - pubLen];
            System.arraycopy(p.payload, 18 + pubLen, sealed, 0, sealed.length);

            PublicKey eph = Crypto.decodePublic(ephPub);
            byte[] shared = Crypto.ecdh(identity.privateKey(), eph);
            byte[] plain = Crypto.open(shared, sealed, null);
            if (plain == null) return;

            log("[" + nickOf(p.senderId) + " → vous] " + new String(plain, "UTF-8"));
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------- commandes

    public void sendChannel(String text) {
        try {
            send(MeshPacket.T_MESSAGE, text.getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    public boolean sendPrivate(String targetNick, String text) {
        Peer target = null;
        for (Peer peer : peers.values()) {
            if (peer.nick != null && peer.nick.equalsIgnoreCase(targetNick)) {
                target = peer;
                break;
            }
        }
        if (target == null || target.publicKey == null) return false;

        try {
            java.security.KeyPair eph = Crypto.generateKeyPair();
            byte[] shared = Crypto.ecdh(eph.getPrivate(),
                    Crypto.decodePublic(target.publicKey));
            byte[] sealed = Crypto.seal(shared, text.getBytes("UTF-8"), null);
            byte[] ephPub = eph.getPublic().getEncoded();

            // L'identité réseau est dérivée de la clé publique du destinataire.
            byte[] targetId = Crypto.deriveId(Crypto.decodePublic(target.publicKey));

            byte[] payload = new byte[MeshPacket.ID_LEN + 2 + ephPub.length + sealed.length];
            System.arraycopy(targetId, 0, payload, 0, MeshPacket.ID_LEN);
            payload[16] = (byte) (ephPub.length >> 8);
            payload[17] = (byte) (ephPub.length & 0xFF);
            System.arraycopy(ephPub, 0, payload, 18, ephPub.length);
            System.arraycopy(sealed, 0, payload, 18 + ephPub.length, sealed.length);

            send(MeshPacket.T_PRIVATE, payload);
            log("[vous → " + target.nick + "] " + text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void setNick(String nick) {
        if (nick == null || nick.length() == 0) return;
        identity.setNick(nick);
        announce();
        log("* pseudo changé en " + nick);
        notifyPeers();
    }

    public String myNick() {
        return identity == null ? "?" : identity.nick();
    }

    public String myIdShort() {
        return identity == null ? "?" : Identity.hex(identity.id).substring(0, 8);
    }

    public List<String> peerNames() {
        List<String> out = new ArrayList<String>();
        for (Peer p : peers.values()) {
            if (p.nick != null) out.add(p.nick);
        }
        Collections.sort(out);
        return out;
    }

    /** Effacement d'urgence : purge identité, pairs et cache de déduplication. */
    public void wipeIdentity() {
        dedup.clear();
        peers.clear();
        if (identity != null) identity.wipe();
        notifyPeers();
    }

    // ------------------------------------------------------------------ interne

    private void touchPeer(byte[] senderId) {
        String key = Identity.hex(senderId);
        Peer peer = peers.get(key);
        if (peer == null) {
            peer = new Peer();
            peer.nick = "anon-" + key.substring(0, 4);
            peers.put(key, peer);
        }
        peer.lastSeen = System.currentTimeMillis();
    }

    private String nickOf(byte[] senderId) {
        Peer peer = peers.get(Identity.hex(senderId));
        if (peer != null && peer.nick != null) return peer.nick;
        return "anon-" + Identity.hex(senderId).substring(0, 4);
    }

    private void schedulePeerSweep() {
        ui.postDelayed(new Runnable() {
            public void run() {
                if (instance == null) return;
                long now = System.currentTimeMillis();
                List<String> gone = new ArrayList<String>();
                for (Map.Entry<String, Peer> e : peers.entrySet()) {
                    if (now - e.getValue().lastSeen > PEER_TIMEOUT_MS) {
                        gone.add(e.getKey());
                    }
                }
                for (String k : gone) {
                    Peer p = peers.remove(k);
                    if (p != null && p.nick != null) log("* " + p.nick + " hors de portée");
                }
                if (!gone.isEmpty()) notifyPeers();
                schedulePeerSweep();
            }
        }, 30000L);
    }

    private void notifyPeers() {
        final Callback c = callback;
        if (c == null) return;
        final List<String> names = peerNames();
        ui.post(new Runnable() {
            public void run() { c.onPeersChanged(names); }
        });
    }

    private void log(final String line) {
        final Callback c = callback;
        if (c == null) return;
        ui.post(new Runnable() {
            public void run() { c.onLog(line); }
        });
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);
        return new Notification.Builder(this)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .getNotification();
    }
}
