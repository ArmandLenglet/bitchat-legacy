package free.bitchat.legacy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU en mode access-order : la clé la plus ancienne sort en premier.
 * C'est ce qui empêche un paquet de tourner indéfiniment dans le mesh.
 */
public final class DedupCache {

    private final int max;
    private final LinkedHashMap<String, Long> seen;

    public DedupCache(int max) {
        this.max = max;
        this.seen = new LinkedHashMap<String, Long>(max, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > DedupCache.this.max;
            }
        };
    }

    /** @return true si le paquet est nouveau (à traiter), false s'il a déjà été vu. */
    public synchronized boolean checkAndAdd(byte[] msgId) {
        String k = hex(msgId);
        if (seen.containsKey(k)) return false;
        seen.put(k, System.currentTimeMillis());
        return true;
    }

    public synchronized void clear() {
        seen.clear();
    }

    private static String hex(byte[] in) {
        StringBuilder sb = new StringBuilder(in.length * 2);
        for (byte b : in) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
