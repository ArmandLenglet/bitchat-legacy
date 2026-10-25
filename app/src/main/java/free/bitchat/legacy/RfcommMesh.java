package free.bitchat.legacy;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Transport Bluetooth Classic RFCOMM.
 *
 * Le point clé est le mode *insecure* :
 *   listenUsingInsecureRfcommWithServiceRecord()
 *   createInsecureRfcommSocketToServiceRecord()
 *
 * Ces deux appels existent depuis l'API 10 et permettent de se connecter
 * SANS appairage ni dialogue utilisateur. C'est ce qui rend un mesh
 * automatique possible sur Android 4/5, là où le BLE en rôle périphérique
 * n'existe pas (advertising = API 21+, et instable jusqu'à l'API 26).
 */
public final class RfcommMesh {

    public interface Listener {
        void onPacket(MeshPacket p, String fromLinkId);
        void onPeersChanged();
    }

    private static final UUID SERVICE_UUID =
            UUID.fromString("8b8f2f2e-1c4a-4b7e-9d3a-6f0c2e5a7b91");
    private static final String SERVICE_NAME = "bitchat-legacy";

    private static final long RETRY_COOLDOWN_MS  = 60000L;
    private static final long DISCOVERY_PERIOD_MS = 30000L;
    private static final int  MAX_LINKS = 7;

    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    private final Map<String, Link> links = new ConcurrentHashMap<String, Link>();
    private final Map<String, Long> lastAttempt = new ConcurrentHashMap<String, Long>();

    private volatile boolean running;
    private AcceptThread acceptThread;

    public RfcommMesh(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.adapter = BluetoothAdapter.getDefaultAdapter();
    }

    // ------------------------------------------------------------------ cycle

    public void start() {
        if (adapter == null || !adapter.isEnabled()) return;
        running = true;

        acceptThread = new AcceptThread();
        acceptThread.start();

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ctx.registerReceiver(discoveryReceiver, f);

        // Connexions immédiates vers les appareils déjà appairés.
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) tryConnect(d);
        }

        scheduleDiscovery();
    }

    public void stop() {
        running = false;
        try { ctx.unregisterReceiver(discoveryReceiver); } catch (Exception ignored) {}
        if (adapter != null && adapter.isDiscovering()) {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
        }
        if (acceptThread != null) acceptThread.cancel();
        for (Link l : links.values()) l.close();
        links.clear();
        pool.shutdownNow();
    }

    public void broadcast(byte[] data, String exceptLinkId) {
        for (Link l : links.values()) {
            if (exceptLinkId != null && exceptLinkId.equals(l.addr)) continue;
            l.send(data);
        }
    }

    public int peerCount() { return links.size(); }

    // -------------------------------------------------------------- découverte

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) tryConnect(d);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                // L'inquiry Bluetooth dure ~12 s : on replanifie un cycle.
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() { scheduleDiscovery(); }
                }, DISCOVERY_PERIOD_MS);
            }
        }
    };

    private void scheduleDiscovery() {
        if (!running || adapter == null) return;
        try {
            if (!adapter.isDiscovering()) adapter.startDiscovery();
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------- connexion

    private void tryConnect(final BluetoothDevice device) {
        final String addr = device.getAddress();
        if (addr == null) return;
        if (links.containsKey(addr)) return;
        if (links.size() >= MAX_LINKS) return;

        Long last = lastAttempt.get(addr);
        long now = System.currentTimeMillis();
        if (last != null && now - last < RETRY_COOLDOWN_MS) return;
        lastAttempt.put(addr, now);

        pool.execute(new Runnable() {
            public void run() {
                BluetoothSocket socket = null;
                try {
                    if (adapter.isDiscovering()) adapter.cancelDiscovery();

                    try {
                        socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
                        socket.connect();
                    } catch (IOException first) {
                        // Repli SDP-less : contourne les vieux stacks qui refusent
                        // la résolution de service.
                        closeQuietly(socket);
                        Method m = device.getClass()
                                .getMethod("createInsecureRfcommSocket", int.class);
                        socket = (BluetoothSocket) m.invoke(device, 1);
                        socket.connect();
                    }

                    registerLink(addr, device.getName(), socket);
                } catch (Exception e) {
                    closeQuietly(socket);
                }
            }
        });
    }

    private void registerLink(String addr, String name, BluetoothSocket socket) {
        if (links.containsKey(addr)) { closeQuietly(socket); return; }
        Link l = new Link(addr, name == null ? addr : name, socket);
        links.put(addr, l);
        l.start();
        listener.onPeersChanged();
    }

    private final class AcceptThread extends Thread {
        private BluetoothServerSocket server;

        AcceptThread() { super("rfcomm-accept"); }

        @Override
        public void run() {
            try {
                server = adapter.listenUsingInsecureRfcommWithServiceRecord(
                        SERVICE_NAME, SERVICE_UUID);
            } catch (IOException e) {
                return;
            }
            while (running) {
                try {
                    BluetoothSocket s = server.accept();
                    if (s == null) break;
                    String addr = s.getRemoteDevice().getAddress();
                    if (links.size() >= MAX_LINKS) { closeQuietly(s); continue; }
                    registerLink(addr, s.getRemoteDevice().getName(), s);
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        }

        void cancel() {
            try { if (server != null) server.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------- lien

    private final class Link {
        final String addr;
        final String name;

        private final BluetoothSocket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private volatile boolean alive;
        private Thread reader;

        Link(String addr, String name, BluetoothSocket socket) {
            this.addr = addr;
            this.name = name;
            this.socket = socket;
        }

        void start() {
            try {
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());
                alive = true;
            } catch (IOException e) {
                close();
                return;
            }
            reader = new Thread(new Runnable() {
                public void run() { readLoop(); }
            }, "rfcomm-read-" + addr);
            reader.start();
        }

        private void readLoop() {
            try {
                while (alive) {
                    int len = in.readInt();                 // trame length-prefixed
                    if (len <= 0 || len > 64 * 1024) break;
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    MeshPacket p = MeshPacket.decode(buf);
                    if (p != null) listener.onPacket(p, addr);
                }
            } catch (IOException ignored) {
            } finally {
                close();
                links.remove(addr);
                listener.onPeersChanged();
            }
        }

        synchronized void send(byte[] data) {
            if (!alive || out == null) return;
            try {
                out.writeInt(data.length);
                out.write(data);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            alive = false;
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        try { if (s != null) s.close(); } catch (IOException ignored) {}
    }
}
