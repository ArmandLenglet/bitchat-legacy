package free.bitchat.legacy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * En-tête de 34 octets, dans l'esprit du format binaire compact de bitchat.
 *
 *   version(1) type(1) ttl(1) flags(1) senderId(16) msgId(8) timestamp(4) len(2)
 */
public final class MeshPacket {

    public static final byte VERSION = 0x01;

    public static final byte T_ANNOUNCE = 0x01;
    public static final byte T_MESSAGE  = 0x02;
    public static final byte T_PRIVATE  = 0x03;
    public static final byte T_LEAVE    = 0x04;

    public static final int ID_LEN  = 16;
    public static final int MSG_LEN = 8;
    public static final int HEADER  = 1 + 1 + 1 + 1 + ID_LEN + MSG_LEN + 4 + 2; // 34

    public byte version = VERSION;
    public byte type;
    public byte ttl;
    public byte flags;
    public byte[] senderId = new byte[ID_LEN];
    public byte[] msgId = new byte[MSG_LEN];
    public int timestamp;
    public byte[] payload = new byte[0];

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length).order(ByteOrder.BIG_ENDIAN);
        b.put(VERSION);
        b.put(type);
        b.put(ttl);
        b.put(flags);
        b.put(senderId);
        b.put(msgId);
        b.putInt(timestamp);
        b.putShort((short) payload.length);
        b.put(payload);
        return b.array();
    }

    public static MeshPacket decode(byte[] raw) {
        if (raw == null || raw.length < HEADER) return null;
        try {
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
            MeshPacket p = new MeshPacket();
            p.version = b.get();
            if (p.version != VERSION) return null;
            p.type = b.get();
            p.ttl = b.get();
            p.flags = b.get();
            p.senderId = new byte[ID_LEN];
            b.get(p.senderId);
            p.msgId = new byte[MSG_LEN];
            b.get(p.msgId);
            p.timestamp = b.getInt();
            int len = b.getShort() & 0xFFFF;
            if (len != b.remaining()) return null;
            p.payload = new byte[len];
            b.get(p.payload);
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
