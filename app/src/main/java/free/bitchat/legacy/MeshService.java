package free.bitchat.legacy;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.security.KeyPair;
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

    // -------------------------------------------------------------- cycle de vie

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

    // ------------------------------------------------------------------ émission

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

    // ----------------------------------------------------------------- réception

    @Override
    public void onPacket(MeshPacket p, String fromLinkId) {
        if (identity == null) return;
        if (Identity.sameId(p.senderId, identity.id)) return;   // notre propre paquet
        if (!dedup.checkAndAdd(p.msgId)) return;                 // déjà vu → jeté

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
            if (2 + nickLen > p.payload.length