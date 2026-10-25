package free.bitchat.legacy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Interface style IRC : fond noir, monospace, commandes /msg /who /nick /slap.
 * Triple-tap sur le journal = effacement d'urgence, comme bitchat.
 */
public final class MainActivity extends Activity implements MeshService.Callback {

    private static final int  MAX_LOG_CHARS = 40000;
    private static final long TRIPLE_TAP_WINDOW_MS = 1200L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    private TextView logView;
    private EditText input;
    private ScrollView scroll;
    private TextView status;

    private long lastTap;
    private int  tapCount;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        logView = (TextView) findViewById(R.id.log);
        input   = (EditText) findViewById(R.id.input);
        scroll  = (ScrollView) findViewById(R.id.scroll);
        status  = (TextView) findViewById(R.id.status);
        Button send = (Button) findViewById(R.id.send);

        logView.setMovementMethod(new ScrollingMovementMethod());

        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { submit(); }
        });

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); return true; }
                return false;
            }
        });

        logView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onTripleTap(); }
        });

        startService(new Intent(this, MeshService.class));

        log("* bitchat legacy — mesh Bluetooth + UDP");
        log("* tapez /help pour la liste des commandes");
    }

    @Override
    protected void onResume() {
        super.onResume();
        MeshService s = MeshService.instance;
        if (s != null) s.setCallback(this);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        MeshService s = MeshService.instance;
        if (s != null) s.setCallback(null);
    }

    // ------------------------------------------------------------------ envoi

    private void submit() {
        String text = input.getText().toString().trim();
        if (text.length() == 0) return;
        input.setText("");

        MeshService s = MeshService.instance;
        if (s == null) {
            log("! service indisponible, réessayez dans un instant");
            return;
        }

        if (text.startsWith("/")) { command(text); return; }
        s.sendChannel(text);
    }

    private void command(String line) {
        MeshService s = MeshService.instance;
        if (s == null) return;

        String[] parts = line.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        if ("/msg".equals(cmd) || "/dm".equals(cmd)) {
            if (parts.length < 3) { log("! usage : /msg <pseudo> <texte>"); return; }
            if (!s.sendPrivate(parts[1], parts[2])) {
                log("! destinataire inconnu : " + parts[1]);
            }
        } else if ("/nick".equals(cmd)) {
            if (parts.length < 2) { log("! usage : /nick <pseudo>"); return; }
            s.setNick(parts[1]);
        } else if ("/who".equals(cmd)) {
            List<String> peers = s.peerNames();
            String others = peers.isEmpty() ? "personne d'autre" : join(peers);
            log("* " + (peers.size() + 1) + " sur le réseau — vous : " + s.myNick()
                    + " ; autres : " + others);
        } else if ("/slap".equals(cmd)) {
            String target = parts.length >= 2 ? parts[1] : "tout le monde";
            s.sendChannel("* " + s.myNick() + " gifle " + target + " avec une truite");
        } else if ("/id".equals(cmd)) {
            log("* votre identifiant réseau : " + s.myIdShort());
        } else if ("/clear".equals(cmd)) {
            logBuf.setLength(0);
            logView.setText("");
        } else if ("/help".equals(cmd)) {
            log("* commandes : /msg <pseudo> <texte>, /nick <pseudo>, /who, "
                    + "/slap [cible], /id, /clear, /help");
        } else {
            log("! commande inconnue : " + cmd + " (essayez /help)");
        }
    }

    // ----------------------------------------------------- effacement d'urgence

    private void onTripleTap() {
        long now = System.currentTimeMillis();
        if (now - lastTap > TRIPLE_TAP_WINDOW_MS) tapCount = 0;
        lastTap = now;
        tapCount++;
        if (tapCount >= 3) {
            tapCount = 0;
            wipeAll();
        }
    }

    private void wipeAll() {
        MeshService s = MeshService.instance;
        if (s != null) s.wipeIdentity();
        logBuf.setLength(0);
        logView.setText("");
        log("* effacement d'urgence — identité, pairs et historique purgés");
        log("* une nouvelle identité sera générée au prochain démarrage");
    }

    // -------------------------------------------------------------- callbacks

    @Override
    public void onLog(String line) {
        log(line);
    }

    @Override
    public void onPeersChanged(List<String> peers) {
        refreshStatus();
    }

    private void refreshStatus() {
        ui.post(new Runnable() {
            public void run() {
                MeshService s = MeshService.instance;
                if (s == null) {
                    status.setText("mesh : démarrage…");
                    ui.postDelayed(new Runnable() {
                        public void run() { refreshStatus(); }
                    }, 1000L);
                    return;
                }
                int n = s.peerNames().size() + 1;
                status.setText("mesh : " + n + (n > 1 ? " pairs" : " pair")
                        + "  ·  vous = " + s.myNick());
            }
        });
    }

    // ------------------------------------------------------------------- journal

    private void log(final String line) {
        ui.post(new Runnable() {
            public void run() {
                logBuf.append(line).append('\n');
                if (logBuf.length() > MAX_LOG_CHARS) {
                    logBuf.delete(0, logBuf.length() - MAX_LOG_CHARS);
                }
                logView.setText(logBuf.toString());
                scroll.post(new Runnable() {
                    public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
