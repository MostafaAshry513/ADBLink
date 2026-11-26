package com.htetznaing.adbotg.cli;

import com.cgutman.adblib.AdbMessage;
import com.cgutman.adblib.AdbProtocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A tiny in-JVM adbd: speaks the real adb packet protocol (no auth), runs commands in a local sh. */
class FakeAdbd {
    final ServerSocket ss;
    final Map<String, byte[]> files = new ConcurrentHashMap<String, byte[]>();
    final Map<String, String> modes = new ConcurrentHashMap<String, String>();
    final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<Socket>();
    volatile boolean rejectExec = false;
    volatile boolean blackholeOpen = false;
    final AtomicInteger accepted = new AtomicInteger();

    FakeAdbd() throws IOException {
        ss = new ServerSocket(0);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (!ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        accepted.incrementAndGet();
                        sockets.add(s);
                        Thread h = new Thread(new Runnable() {
                            @Override public void run() { new Session(s).run(); }
                        });
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    int port() { return ss.getLocalPort(); }

    /** Simulates the USB link dropping. */
    void dropAll() {
        for (Socket s : sockets) {
            try { s.close(); } catch (IOException ignored) {}
        }
        sockets.clear();
    }

    void close() {
        dropAll();
        try { ss.close(); } catch (IOException ignored) {}
    }

    private class Session {
        final Socket sock;
        final DataInputStream in;
        final OutputStream out;
        final Map<Integer, Stream> streams = new ConcurrentHashMap<Integer, Stream>();
        int nextId = 100;

        Session(Socket s) {
            sock = s;
            try {
                in = new DataInputStream(s.getInputStream());
                out = s.getOutputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        synchronized void send(int cmd, int a0, int a1, byte[] data) {
            try {
                AdbMessage m = AdbProtocol.generateMessage(cmd, a0, a1, data);
                out.write(m.getMessage());
                if (data != null) out.write(data);
                out.flush();
            } catch (IOException ignored) {
            }
        }

        void run() {
            try {
                while (true) {
                    byte[] h = new byte[24];
                    in.readFully(h);
                    ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
                    int cmd = b.getInt(0), a0 = b.getInt(4), a1 = b.getInt(8), len = b.getInt(12);
                    byte[] p = new byte[len];
                    if (len > 0) in.readFully(p);
                    if (cmd == AdbProtocol.CMD_CNXN) {
                        send(AdbProtocol.CMD_CNXN, 0x01000000, 65536, "device::\0".getBytes());
                    } else if (cmd == AdbProtocol.CMD_OPEN) {
                        open(a0, new String(p, 0, len - 1, "UTF-8"));
                    } else if (cmd == AdbProtocol.CMD_WRTE) {
                        Stream s = streams.get(a1);
                        if (s != null) s.onData(p);
                        send(AdbProtocol.CMD_OKAY, a1, a0, null);
                    } else if (cmd == AdbProtocol.CMD_CLSE) {
                        Stream s = streams.remove(a1);
                        if (s != null) s.kill();
                    }
                }
            } catch (IOException e) {
                // peer gone
            } finally {
                for (Stream s : streams.values()) s.kill();
            }
        }

        void open(int clientId, String dest) {
            if (blackholeOpen) return;
            int sid = nextId++;
            if (dest.startsWith("exec:") && rejectExec) {
                send(AdbProtocol.CMD_CLSE, 0, clientId, null);
                return;
            }
            Stream st;
            if (dest.startsWith("exec:") || dest.startsWith("shell:")) {
                String cmd = dest.substring(dest.indexOf(':') + 1);
                if (cmd.isEmpty()) cmd = "sh";
                st = new ProcStream(sid, clientId, cmd);
            } else if (dest.equals("sync:")) {
                st = new SyncStream(sid, clientId);
            } else if (dest.matches("(root|unroot|usb|remount|reboot|tcpip):.*")) {
                send(AdbProtocol.CMD_OKAY, sid, clientId, null);
                send(AdbProtocol.CMD_WRTE, sid, clientId, ("ok:" + dest + "\n").getBytes());
                send(AdbProtocol.CMD_CLSE, sid, clientId, null);
                return;
            } else {
                send(AdbProtocol.CMD_CLSE, 0, clientId, null);
                return;
            }
            streams.put(sid, st);
            send(AdbProtocol.CMD_OKAY, sid, clientId, null);
            st.start();
        }

        abstract class Stream {
            final int sid, cid;
            Stream(int sid, int cid) { this.sid = sid; this.cid = cid; }
            void start() {}
            abstract void onData(byte[] d);
            void kill() {}
            void closeFromServer() {
                streams.remove(sid);
                send(AdbProtocol.CMD_CLSE, sid, cid, null);
            }
        }

        class ProcStream extends Stream {
            Process proc;
            final String cmd;
            ProcStream(int sid, int cid, String cmd) { super(sid, cid); this.cmd = cmd; }
            @Override void start() {
                try {
                    // pretend pm exists
                    String full = "pm() { echo \"Success: pm $*\"; }; su() { [ \"$1\" = -c ] && shift; echo ROOT; sh -c \"$1\"; }; " + cmd;
                    ProcessBuilder pb = new ProcessBuilder("sh", "-c", full);
                    pb.redirectErrorStream(true);
                    proc = pb.start();
                } catch (IOException e) {
                    closeFromServer();
                    return;
                }
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            InputStream is = proc.getInputStream();
                            byte[] buf = new byte[1000];
                            int n;
                            while ((n = is.read(buf)) > 0) {
                                byte[] c = new byte[n];
                                System.arraycopy(buf, 0, c, 0, n);
                                send(AdbProtocol.CMD_WRTE, sid, cid, c);
                            }
                            proc.waitFor();
                        } catch (Exception ignored) {
                        }
                        closeFromServer();
                    }
                });
                t.setDaemon(true);
                t.start();
            }
            @Override void onData(byte[] d) {
                try {
                    proc.getOutputStream().write(d);
                    proc.getOutputStream().flush();
                } catch (IOException ignored) {}
            }
            @Override void kill() { if (proc != null) proc.destroy(); }
        }

        class SyncStream extends Stream {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            String sendPath;
            ByteArrayOutputStream sendData;
            SyncStream(int sid, int cid) { super(sid, cid); }

            @Override void onData(byte[] d) {
                buf.write(d, 0, d.length);
                while (true) {
                    byte[] all = buf.toByteArray();
                    if (all.length < 8) return;
                    String id = new String(all, 0, 4);
                    int len = ByteBuffer.wrap(all, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    int need = ("SEND".equals(id) || "DATA".equals(id) || "RECV".equals(id)) ? 8 + len : 8;
                    if (all.length < need) return;
                    byte[] payload = new byte[need - 8];
                    System.arraycopy(all, 8, payload, 0, payload.length);
                    buf.reset();
                    buf.write(all, need, all.length - need);
                    handle(id, len, payload);
                }
            }

            void reply(String id, int len, byte[] data) {
                ByteBuffer b = ByteBuffer.allocate(8 + (data == null ? 0 : data.length)).order(ByteOrder.LITTLE_ENDIAN);
                b.put(id.getBytes()).putInt(len);
                if (data != null) b.put(data);
                send(AdbProtocol.CMD_WRTE, sid, cid, b.array());
            }

            void handle(String id, int len, byte[] payload) {
                if ("SEND".equals(id)) {
                    String pm = new String(payload);
                    int c = pm.lastIndexOf(',');
                    sendPath = pm.substring(0, c);
                    modes.put(sendPath, pm.substring(c + 1));
                    sendData = new ByteArrayOutputStream();
                } else if ("DATA".equals(id)) {
                    sendData.write(payload, 0, payload.length);
                } else if ("DONE".equals(id)) {
                    files.put(sendPath, sendData.toByteArray());
                    reply("OKAY", 0, null);
                } else if ("RECV".equals(id)) {
                    byte[] f = files.get(new String(payload));
                    if (f == null) {
                        byte[] m = "No such file or directory".getBytes();
                        reply("FAIL", m.length, m);
                        return;
                    }
                    for (int off = 0; off < f.length; off += 30000) {
                        int n = Math.min(30000, f.length - off);
                        byte[] c = new byte[n];
                        System.arraycopy(f, off, c, 0, n);
                        reply("DATA", n, c);
                    }
                    reply("DONE", 0, null);
                } else if ("QUIT".equals(id)) {
                    closeFromServer();
                }
            }
            @Override void kill() {}
        }
    }
}
