package com.htetznaing.adbotg.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Loopback TCP endpoint that the real adb client (Termux android-tools) connects to,
 * relaying to the USB target. One client at a time; a new client replaces the old one.
 *
 * ADB over TCP and ADB over USB use the same wire format (24-byte header + payload), so
 * target-to-client is a raw byte pump. Client-to-target is reframed into whole messages
 * and written header-then-payload, the segmentation USB adb stacks are proven to accept.
 *
 * The relay itself is dumb: RSA auth happens between the Termux adb and the target's adbd,
 * so this app no longer holds or generates adb keys.
 */
public class BridgeServer {
    public interface LinkProvider {
        /** Open the USB side now, or return null when no target is available. */
        BridgeLink open() throws IOException;
        /** Short status of the USB side for the UI, e.g. "target present" or "no USB target". */
        String describe();
    }

    public interface Events {
        void log(String msg);
        void sessionState(boolean active, String detail);
    }

    private final int port;
    private final LinkProvider provider;
    private final Events events;
    private ServerSocket server;
    private volatile boolean running;
    private volatile Session active;

    public BridgeServer(int port, LinkProvider provider, Events events) {
        this.port = port;
        this.provider = provider;
        this.events = events;
    }

    public int getPort() { return server == null ? port : server.getLocalPort(); }

    public boolean sessionActive() {
        Session s = active;
        return s != null && s.alive;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "adbotg-bridge-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        closeActive("bridge stopped");
    }

    /** USB disappeared or the service is shutting down: end the relay, the client will see a reset. */
    public void closeActive(String reason) {
        Session s = active;
        if (s != null) s.close(reason);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                onClient(s);
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void onClient(Socket s) {
        closeActive("replaced by a new adb connection");
        BridgeLink link;
        try {
            link = provider.open();
        } catch (IOException e) {
            events.log("adb connected but the USB side failed: " + e.getMessage());
            try { s.close(); } catch (IOException ignored) {}
            return;
        }
        if (link == null) {
            events.log("adb connected but there is no usable USB target (" + provider.describe() + ")");
            try { s.close(); } catch (IOException ignored) {}
            return;
        }
        Session session = new Session(s, link);
        active = session;
        session.start();
        events.log("adb client connected, relaying to " + link.name());
        events.sessionState(true, link.name());
    }

    private class Session {
        final Socket client;
        final BridgeLink link;
        volatile boolean alive = true;
        private boolean reported;
        private boolean loggedCnxn;
        private boolean loggedAuth;

        /** Watch the client-to-target stream for the messages that explain a bounce. */
        private void inspect(byte[] header) {
            String cmd = new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (!loggedCnxn && "CNXN".equals(cmd)) {
                loggedCnxn = true;
                events.log("adb handshake started (CNXN from Termux)");
            } else if (!loggedAuth && "AUTH".equals(cmd)) {
                int arg0 = header[4] & 0xff;
                if (arg0 == 3) { // ADB_AUTH_RSAPUBLICKEY: target does not know this key yet
                    loggedAuth = true;
                    events.log("the rooted phone is asking to authorize this computer: "
                            + "tap Allow (and tick always-allow) on its USB debugging dialog");
                }
            }
        }

        Session(Socket client, BridgeLink link) {
            this.client = client;
            this.link = link;
        }

        void start() {
            Thread toUsb = new Thread(new Runnable() {
                @Override public void run() { clientToUsb(); }
            }, "adbotg-bridge-tx");
            Thread fromUsb = new Thread(new Runnable() {
                @Override public void run() { usbToClient(); }
            }, "adbotg-bridge-rx");
            toUsb.setDaemon(true);
            fromUsb.setDaemon(true);
            toUsb.start();
            fromUsb.start();
        }

        private void clientToUsb() {
            try {
                InputStream in = client.getInputStream();
                // USB writes happen only on this thread, via link.writeMessage
                AdbFramer framer = new AdbFramer();
                byte[] buf = new byte[65536];
                int n;
                while (alive && (n = in.read(buf)) > 0) {
                    framer.feed(buf, 0, n, new AdbFramer.Sink() {
                        @Override public void onMessage(byte[] header, byte[] payload) throws IOException {
                            inspect(header);
                            link.writeMessage(header, payload);
                        }
                    });
                }
                close(alive ? "adb client ended the connection" : null);
            } catch (IOException e) {
                close(alive ? "client read failed: " + e.getMessage() : null);
            }
        }

        private void usbToClient() {
            try {
                OutputStream out = client.getOutputStream();
                byte[] buf = new byte[65536];
                while (alive) {
                    int n = link.read(buf);
                    if (n < 0) {
                        close("USB link died");
                        return;
                    }
                    if (n == 0) continue; // read timeout, just poll again
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                close(alive ? "USB read failed: " + e.getMessage() : null);
            }
        }

        synchronized void close(String reason) {
            if (!alive) return;
            alive = false;
            link.close();
            // Log BEFORE closing the client socket: anything that watches for the socket to close
            // (including the unit tests) must already be able to see why the relay ended.
            if (reason != null && !reported) {
                reported = true;
                events.log("relay ended: " + reason);
            }
            try { client.close(); } catch (IOException ignored) {}
            events.sessionState(false, null);
            if (active == this) active = null;
        }
    }
}
