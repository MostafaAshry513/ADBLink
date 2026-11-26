package com.htetznaing.adbotg.cli;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNotNull;

import com.cgutman.adblib.AdbMessage;
import com.cgutman.adblib.AdbProtocol;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the bridge end to end: a fake adb client over TCP through BridgeServer into
 * FakeAdbd (standing in for the USB target), plus framer unit tests. No hardware.
 */
public class BridgeTest {

    // ---------- helpers ----------

    /** BridgeLink over a TCP socket: FakeAdbd plays the USB target. */
    static class SocketLink implements BridgeLink {
        final Socket s;
        final InputStream in;
        final OutputStream out;
        volatile boolean closed;

        SocketLink(Socket s) throws IOException {
            this.s = s;
            s.setSoTimeout(300);
            in = s.getInputStream();
            out = s.getOutputStream();
        }

        @Override public int read(byte[] buf) throws IOException {
            try {
                return in.read(buf);
            } catch (SocketTimeoutException e) {
                return 0;
            } catch (IOException e) {
                if (closed) return -1;
                throw e;
            }
        }

        @Override public void writeMessage(byte[] header, byte[] payload) throws IOException {
            out.write(header); // header and payload go separately, like USB bulk transfers
            if (payload != null) out.write(payload);
            out.flush();
        }

        @Override public void close() {
            closed = true;
            try { s.close(); } catch (IOException ignored) {}
        }

        @Override public String name() { return "fake-usb"; }
    }

    /** In-memory link that records the messages it receives. */
    static class RecordingLink implements BridgeLink {
        final List<byte[]> headers = new ArrayList<byte[]>();
        final List<byte[]> payloads = new ArrayList<byte[]>();
        volatile boolean closed;

        @Override public synchronized int read(byte[] buf) {
            if (closed) return -1;
            try { wait(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return closed ? -1 : 0;
        }

        @Override public synchronized void writeMessage(byte[] header, byte[] payload) {
            headers.add(header);
            payloads.add(payload);
            notifyAll();
        }

        @Override public synchronized void close() {
            closed = true;
            notifyAll();
        }

        @Override public String name() { return "recording"; }
    }

    static class Provider implements BridgeServer.LinkProvider {
        BridgeLink next;
        int opened;
        @Override public BridgeLink open() {
            opened++;
            BridgeLink l = next;
            next = null;
            return l;
        }
        @Override public String describe() { return "test"; }
    }

    static class LogEvents implements BridgeServer.Events {
        final List<String> lines = new ArrayList<String>();
        volatile boolean active;
        @Override public synchronized void log(String msg) { lines.add(msg); }
        @Override public void sessionState(boolean a, String d) { active = a; }
    }

    static class Msg {
        int cmd, a0, a1;
        byte[] payload;
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n < 0) throw new IOException("eof");
            got += n;
        }
    }

    private static Msg readMsg(Socket s) throws IOException {
        byte[] h = new byte[24];
        readFully(s.getInputStream(), h);
        Msg m = new Msg();
        m.cmd = le(h, 0);
        m.a0 = le(h, 4);
        m.a1 = le(h, 8);
        int len = AdbFramer.payloadLength(h, 0);
        m.payload = new byte[len];
        readFully(s.getInputStream(), m.payload);
        return m;
    }

    private static int le(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    private static void sendMsg(Socket s, int cmd, int a0, int a1, byte[] payload) throws IOException {
        AdbMessage m = AdbProtocol.generateMessage(cmd, a0, a1, payload);
        OutputStream out = s.getOutputStream();
        out.write(m.getMessage());
        if (payload != null) out.write(m.getPayload());
        out.flush();
    }

    private static Socket client(BridgeServer b) throws IOException {
        Socket s = new Socket("127.0.0.1", b.getPort());
        s.setSoTimeout(8000);
        return s;
    }

    private static void handshake(Socket c) throws IOException {
        sendMsg(c, AdbProtocol.CMD_CNXN, 0x01000000, 65536, "host::\0".getBytes("UTF-8"));
        Msg r = readMsg(c);
        assertEquals(AdbProtocol.CMD_CNXN, r.cmd);
    }

    private static void expectClosed(Socket s) throws Exception {
        long end = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < end) {
            try {
                if (s.getInputStream().read() < 0) return;
            } catch (SocketTimeoutException e) {
                // keep waiting
            } catch (IOException e) {
                return; // reset counts as closed
            }
        }
        fail("socket should have been closed by the bridge");
    }

    // ---------- framer unit tests ----------

    @Test
    public void framerSplitsAndCoalesces() throws Exception {
        AdbMessage m1 = AdbProtocol.generateMessage(AdbProtocol.CMD_WRTE, 1, 2, "hello".getBytes("UTF-8"));
        AdbMessage m2 = AdbProtocol.generateMessage(AdbProtocol.CMD_OKAY, 3, 4, null);
        byte[] all = concat(m1.getMessage(), m1.getPayload(), m2.getMessage());
        final List<byte[]> got = new ArrayList<byte[]>();
        AdbFramer.Sink sink = new AdbFramer.Sink() {
            @Override public void onMessage(byte[] header, byte[] payload) {
                got.add(header);
                got.add(payload == null ? new byte[0] : payload);
            }
        };
        AdbFramer f = new AdbFramer();
        for (int i = 0; i < all.length; i += 7) { // awkward chunk size, crosses boundaries
            int n = Math.min(7, all.length - i);
            f.feed(all, i, n, sink);
        }
        assertEquals(4, got.size());
        assertArrayEquals(m1.getMessage(), got.get(0));
        assertArrayEquals("hello".getBytes("UTF-8"), got.get(1));
        assertArrayEquals(m2.getMessage(), got.get(2));
        assertEquals(0, got.get(3).length);
    }

    @Test
    public void framerRejectsGarbage() throws Exception {
        byte[] h = new byte[24];
        h[12] = (byte) 0xff; h[13] = (byte) 0xff; h[14] = (byte) 0xff; h[15] = 0x7f; // ~2GB payload
        AdbFramer f = new AdbFramer();
        try {
            f.feed(h, 0, h.length, new AdbFramer.Sink() {
                @Override public void onMessage(byte[] header, byte[] payload) {}
            });
            fail("garbage framing must be rejected");
        } catch (IOException expected) {}
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p == null ? 0 : p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    // ---------- relay end-to-end ----------

    @Test
    public void relayHandshakeAndShell() throws Exception {
        FakeAdbd adbd = new FakeAdbd();
        try {
            Provider p = new Provider();
            p.next = new SocketLink(new Socket("127.0.0.1", adbd.port()));
            LogEvents ev = new LogEvents();
            BridgeServer b = new BridgeServer(0, p, ev);
            b.start();
            Socket c = client(b);
            handshake(c);
            sendMsg(c, AdbProtocol.CMD_OPEN, 7, 0, "shell:echo bridge-ok\0".getBytes("UTF-8"));
            Msg okay = readMsg(c);
            assertEquals(AdbProtocol.CMD_OKAY, okay.cmd);
            Msg wrte = readMsg(c);
            assertEquals(AdbProtocol.CMD_WRTE, wrte.cmd);
            assertTrue(new String(wrte.payload, "UTF-8").contains("bridge-ok"));
            Msg clse = readMsg(c);
            assertEquals(AdbProtocol.CMD_CLSE, clse.cmd);
            c.close();
            b.stop();
        } finally {
            adbd.close();
        }
    }

    @Test
    public void relayHandlesChoppedClientWrites() throws Exception {
        FakeAdbd adbd = new FakeAdbd();
        try {
            Provider p = new Provider();
            p.next = new SocketLink(new Socket("127.0.0.1", adbd.port()));
            BridgeServer b = new BridgeServer(0, p, new LogEvents());
            b.start();
            Socket c = client(b);
            AdbMessage m = AdbProtocol.generateMessage(AdbProtocol.CMD_CNXN, 0x01000000, 65536,
                    "host::\0".getBytes("UTF-8"));
            byte[] all = concat(m.getMessage(), m.getPayload());
            OutputStream out = c.getOutputStream();
            for (byte value : all) { // one byte at a time
                out.write(value);
                out.flush();
            }
            Msg r = readMsg(c);
            assertEquals(AdbProtocol.CMD_CNXN, r.cmd);
            c.close();
            b.stop();
        } finally {
            adbd.close();
        }
    }

    @Test
    public void relayPassesLargePayloadsBothWays() throws Exception {
        FakeAdbd adbd = new FakeAdbd();
        try {
            Provider p = new Provider();
            p.next = new SocketLink(new Socket("127.0.0.1", adbd.port()));
            BridgeServer b = new BridgeServer(0, p, new LogEvents());
            b.start();
            Socket c = client(b);
            handshake(c);
            sendMsg(c, AdbProtocol.CMD_OPEN, 9, 0, "shell:cat\0".getBytes("UTF-8"));
            Msg okay = readMsg(c);
            assertEquals(AdbProtocol.CMD_OKAY, okay.cmd);
            int remoteId = okay.a0; // the stream id the target assigned
            byte[] big = new byte[300000];
            for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31 + 7);
            sendMsg(c, AdbProtocol.CMD_WRTE, 9, remoteId, big);
            // the target's OKAY and the echoed WRTE chunks can arrive in any order
            java.io.ByteArrayOutputStream echoed = new java.io.ByteArrayOutputStream();
            while (echoed.size() < big.length) {
                Msg m = readMsg(c);
                if (m.cmd == AdbProtocol.CMD_WRTE) echoed.write(m.payload);
            }
            assertArrayEquals(big, echoed.toByteArray());
            c.close();
            b.stop();
        } finally {
            adbd.close();
        }
    }

    @Test
    public void newClientReplacesOld() throws Exception {
        FakeAdbd adbd = new FakeAdbd();
        try {
            Provider p = new Provider();
            p.next = new SocketLink(new Socket("127.0.0.1", adbd.port()));
            BridgeServer b = new BridgeServer(0, p, new LogEvents());
            b.start();
            Socket c1 = client(b);
            handshake(c1);
            p.next = new SocketLink(new Socket("127.0.0.1", adbd.port()));
            Socket c2 = client(b);
            expectClosed(c1);
            handshake(c2);
            c2.close();
            b.stop();
        } finally {
            adbd.close();
        }
    }

    @Test
    public void usbDropClosesClient() throws Exception {
        FakeAdbd adbd = new FakeAdbd();
        try {
            Provider p = new Provider();
            p.next = new SocketLink(new Socket("127.0.0.1", adbd.port()));
            BridgeServer b = new BridgeServer(0, p, new LogEvents());
            b.start();
            Socket c = client(b);
            handshake(c);
            adbd.dropAll(); // USB unplugged
            expectClosed(c);
            b.stop();
        } finally {
            adbd.close();
        }
    }

    @Test
    public void noTargetClosesClientAndServerSurvives() throws Exception {
        FakeAdbd adbd = new FakeAdbd();
        try {
            Provider p = new Provider(); // no link staged: no USB target
            BridgeServer b = new BridgeServer(0, p, new LogEvents());
            b.start();
            Socket c1 = client(b);
            expectClosed(c1);
            p.next = new SocketLink(new Socket("127.0.0.1", adbd.port()));
            Socket c2 = client(b);
            handshake(c2);
            c2.close();
            b.stop();
        } finally {
            adbd.close();
        }
    }

    @Test
    public void retryingProviderAbsorbsReEnumerationGap() throws Exception {
        final BridgeLink good = new RecordingLink();
        final int[] calls = {0};
        BridgeServer.LinkProvider flaky = new BridgeServer.LinkProvider() {
            @Override public BridgeLink open() {
                calls[0]++;
                return calls[0] < 3 ? null : good; // target gone for two polls, then back
            }
            @Override public String describe() { return "flaky"; }
        };
        LogEvents ev = new LogEvents();
        RetryingLinkProvider r = new RetryingLinkProvider(flaky, 5000, 50, ev);
        long start = System.currentTimeMillis();
        BridgeLink got = r.open();
        long took = System.currentTimeMillis() - start;
        assertTrue(got == good);
        assertEquals(3, calls[0]);
        assertTrue("should return as soon as the target is back, took " + took, took < 2000);

        BridgeServer.LinkProvider never = new BridgeServer.LinkProvider() {
            @Override public BridgeLink open() { return null; }
            @Override public String describe() { return "never"; }
        };
        RetryingLinkProvider r2 = new RetryingLinkProvider(never, 400, 50, ev);
        assertTrue(r2.open() == null);
    }

    @Test
    public void handshakeAndAuthAreLogged() throws Exception {
        RecordingLink link = new RecordingLink();
        Provider p = new Provider();
        p.next = link;
        LogEvents ev = new LogEvents();
        BridgeServer b = new BridgeServer(0, p, ev);
        b.start();
        Socket c = client(b);
        sendMsg(c, AdbProtocol.CMD_CNXN, 0x01000000, 65536, "host::\0".getBytes("UTF-8"));
        byte[] auth = new byte[24]; // A_AUTH with ADB_AUTH_RSAPUBLICKEY (arg0 = 3)
        System.arraycopy("AUTH".getBytes("US-ASCII"), 0, auth, 0, 4);
        auth[4] = 3;
        c.getOutputStream().write(auth);
        c.getOutputStream().flush();
        long end = System.currentTimeMillis() + 5000;
        String joined = "";
        while (System.currentTimeMillis() < end) {
            synchronized (ev) {
                joined = ev.lines.toString();
            }
            if (joined.contains("handshake") && joined.contains("authorize")) break;
            Thread.sleep(50);
        }
        assertTrue(joined, joined.contains("adb handshake started"));
        assertTrue(joined, joined.contains("asking to authorize"));
        c.close();
        b.stop();
    }

    @Test
    public void authPublicKeyPayloadSurvivesReframing() throws Exception {
        RecordingLink link = new RecordingLink();
        Provider p = new Provider();
        p.next = link;
        LogEvents ev = new LogEvents();
        BridgeServer b = new BridgeServer(0, p, ev);
        b.start();
        Socket c = client(b);
        // Realistic AUTH RSAPUBLICKEY: ~720-byte base64 key, bigger than one USB packet (512).
        byte[] key = new byte[720];
        for (int i = 0; i < key.length; i++) key[i] = (byte) ('A' + (i % 26));
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        AdbMessage m = AdbProtocol.generateMessage(AdbProtocol.CMD_AUTH, 3, 0, key);
        blob.write(m.getMessage());
        blob.write(m.getPayload());
        byte[] all = blob.toByteArray();
        // Deliberately ugly chunking: split mid-header, mid-payload, 1-byte pieces.
        OutputStream out = c.getOutputStream();
        int[] cuts = {1, 7, 24, 25, 300, 511, 512, 700, all.length};
        int prev = 0;
        for (int cut : cuts) {
            out.write(all, prev, cut - prev);
            out.flush();
            prev = cut;
        }
        long end = System.currentTimeMillis() + 5000;
        byte[] got = null;
        while (System.currentTimeMillis() < end) {
            synchronized (link) {
                if (!link.payloads.isEmpty()) got = link.payloads.get(0);
            }
            if (got != null) break;
            Thread.sleep(50);
        }
        assertNotNull("RSAPUBLICKEY payload never reached the USB side", got);
        assertArrayEquals(key, got);
        c.close();
        b.stop();
    }

    @Test
    public void garbageBytesCloseSessionWithHexEvidence() throws Exception {
        RecordingLink link = new RecordingLink();
        Provider p = new Provider();
        p.next = link;
        LogEvents ev = new LogEvents();
        BridgeServer b = new BridgeServer(0, p, ev);
        b.start();
        Socket c = client(b);
        byte[] tls = new byte[]{0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01,
                (byte) 0xfc, 0x03, 0x03, 0x1a, 0x7f, 0x7f, 0x7f, 0x7f, // nonsense length at 12-15
                0, 0, 0, 0, 0, 0, 0, 0};
        c.getOutputStream().write(tls);
        c.getOutputStream().flush();
        expectClosed(c);
        String joined;
        synchronized (ev) {
            joined = ev.lines.toString();
        }
        assertTrue(joined, joined.contains("garbage ADB framing"));
        assertTrue(joined, joined.contains("16030102"));
        b.stop();
    }

    @Test
    public void clientMessagesArriveAsWholeUsbWrites() throws Exception {
        RecordingLink link = new RecordingLink();
        Provider p = new Provider();
        p.next = link;
        BridgeServer b = new BridgeServer(0, p, new LogEvents());
        b.start();
        Socket c = client(b);
        AdbMessage m = AdbProtocol.generateMessage(AdbProtocol.CMD_CNXN, 0x01000000, 65536,
                "host::\0".getBytes("UTF-8"));
        c.getOutputStream().write(concat(m.getMessage(), m.getPayload())); // coalesced in one TCP write
        c.getOutputStream().flush();
        long end = System.currentTimeMillis() + 5000;
        synchronized (link) {
            while (link.headers.isEmpty() && System.currentTimeMillis() < end) {
                link.wait(200);
            }
        }
        assertEquals(1, link.headers.size());
        assertEquals(24, link.headers.get(0).length);
        assertArrayEquals("host::\0".getBytes("UTF-8"), link.payloads.get(0));
        c.close();
        b.stop();
    }
}
