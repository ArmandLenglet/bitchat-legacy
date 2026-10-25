package free.bitchat.legacy;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second transport : UDP broadcast sur le réseau local.
 *
 * Utile quand tous les appareils sont sur le même hotspot Wi-Fi :
 * découverte instantanée, aucun appairage, aucune limite de 7 liens.
 */
public final class UdpTransport {

    public interface Listener {
        void onPacket(MeshPacket p, String fromLinkId);
        void onPeersChanged();
    }

    private static final int  PORT = 45999;
    private static final byte[] BEACON_MAGIC = "BCHT1".getBytes();
    private static final long BEACON_INTERVAL_MS = 5000L;
    private static final int  MAX_DATAGRAM = 2048;

    private final byte[] selfId;
    private final Listener listener;

    private final Set<String> peerSet =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread rxThread, beaconThread;

    public UdpTransport(byte[] selfId, Listener listener) {
        this.selfId = selfId;
        this.listener = listener;
    }

    public void start() {
        try {
            socket = new DatagramSocket(PORT);
            socket.setBroadcast(true);
        } catch (Exception e) {
            socket = null;
            return;
        }
        running = true;

        rxThread = new Thread(new Runnable() {
            public void run() { receiveLoop(); }
        }, "udp-rx");
        rxThread.start();

        beaconThread = new Thread(new Runnable() {
            public void run() { beaconLoop(); }
        }, "udp-beacon");
        beaconThread.start();
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
    }

    public void broadcast(byte[] data, String exceptLinkId) {
        if (socket == null || socket.isClosed()) return;
        for (String addr : peerSet) {
            if (exceptLinkId != null && exceptLinkId.equals("udp:" + addr)) continue;
            try {
                socket.send(new DatagramPacket(data, data.length,
                        InetAddress.getByName(addr), PORT));
            } catch (Exception ignored) {}
        }
    }

    public int peerCount() { return peerSet.size(); }

    // ---------------------------------------------------------------- interne

    private void receiveLoop() {
        byte[] buf = new byte[MAX_DATAGRAM];
        while (running) {
            try {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                socket.receive(dp);
                String from = dp.getAddress().getHostAddress();
                byte[] data = new byte[dp.getLength()];
                System.arraycopy(dp.getData(), dp.getOffset(), data, 0, dp.getLength());

                if (isBeacon(data)) {
                    if (peerSet.add(from)) listener.onPeersChanged();
                    continue;
                }

                MeshPacket p = MeshPacket.decode(data);
                if (p != null) listener.onPacket(p, "udp:" + from);
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private void beaconLoop() {
        while (running) {
            try {
                byte[] b = new byte[BEACON_MAGIC.length + selfId.length];
                System.arraycopy(BEACON_MAGIC, 0, b, 0, BEACON_MAGIC.length);
                System.arraycopy(selfId, 0, b, BEACON_MAGIC.length, selfId.length);

                socket.send(new DatagramPacket(b, b.length,
                        InetAddress.getByName("255.255.255.255"), PORT));
            } catch (Exception ignored) {}

            try {
                Thread.sleep(BEACON_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private boolean isBeacon(byte[] data) {
        if (data.length != BEACON_MAGIC.length + selfId.length) return false;
        for (int i = 0; i < BEACON_MAGIC.length; i++) {
            if (data[i] != BEACON_MAGIC[i]) return false;
        }
        return true;
    }
}
