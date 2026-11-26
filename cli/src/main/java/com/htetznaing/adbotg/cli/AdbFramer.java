package com.htetznaing.adbotg.cli;

import java.io.IOException;

/**
 * Reframes a byte stream into whole ADB messages (24-byte little-endian header + payload).
 * The TCP side of the bridge delivers arbitrary byte chunks; the USB side wants each
 * message written as header then payload (the segmentation real USB adb stacks expect).
 */
public class AdbFramer {
    public interface Sink {
        void onMessage(byte[] header, byte[] payload) throws IOException;
    }

    /** ADB MAX_PAYLOAD is 1 MiB on current devices; anything beyond this is garbage. */
    private static final int MAX_MESSAGE_PAYLOAD = 4 * 1024 * 1024;

    private byte[] buf = new byte[65536];
    private int len = 0;

    public static int payloadLength(byte[] header, int off) {
        return (header[off + 12] & 0xff) | ((header[off + 13] & 0xff) << 8)
                | ((header[off + 14] & 0xff) << 16) | ((header[off + 15] & 0xff) << 24);
    }

    private String hexHead() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8 && i < len; i++) sb.append(String.format("%02x", buf[i]));
        return sb.toString();
    }

    public synchronized void feed(byte[] data, int off, int n, Sink sink) throws IOException {
        if (len + n > buf.length) {
            byte[] nb = new byte[Math.max(buf.length * 2, len + n)];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
        System.arraycopy(data, off, buf, len, n);
        len += n;
        int pos = 0;
        while (len - pos >= 24) {
            int pl = payloadLength(buf, pos);
            if (pl < 0 || pl > MAX_MESSAGE_PAYLOAD) {
                throw new IOException("garbage ADB framing (payload length " + pl
                        + ", first bytes " + hexHead() + ")");
            }
            if (len - pos < 24 + pl) break;
            byte[] h = new byte[24];
            System.arraycopy(buf, pos, h, 0, 24);
            byte[] p = pl == 0 ? null : new byte[pl];
            if (p != null) System.arraycopy(buf, pos + 24, p, 0, pl);
            pos += 24 + pl;
            sink.onMessage(h, p);
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos);
            len -= pos;
        }
    }
}
