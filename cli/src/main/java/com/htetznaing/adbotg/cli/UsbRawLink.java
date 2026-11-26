package com.htetznaing.adbotg.cli;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/**
 * Raw USB side of the bridge: claims the adb interface and moves bytes without
 * interpreting them. Writes keep the header-then-payload segmentation that the
 * original app's UsbChannel used on real hardware.
 */
public class UsbRawLink implements BridgeLink {
    private static final int READ_TIMEOUT_MS = 1000;
    private static final int WRITE_TIMEOUT_MS = 2000;

    private final UsbManager usb;
    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint inEp;
    private final UsbEndpoint outEp;
    private final String name;
    private volatile boolean closed;

    private UsbRawLink(UsbManager usb, UsbDeviceConnection conn, UsbInterface intf,
                       UsbEndpoint inEp, UsbEndpoint outEp, String name) {
        this.usb = usb;
        this.conn = conn;
        this.intf = intf;
        this.inEp = inEp;
        this.outEp = outEp;
        this.name = name;
    }

    /** The adb interface: class 255, subclass 66, protocol 1. Same rule as the original app. */
    public static UsbInterface findAdbInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 255 && f.getInterfaceSubclass() == 66
                    && f.getInterfaceProtocol() == 1) {
                return f;
            }
        }
        return null;
    }

    /** Open and claim the interface. Caller has already checked USB permission. */
    public static UsbRawLink open(UsbManager usb, UsbDevice d, UsbInterface intf) throws IOException {
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e;
            else if (e.getDirection() == UsbConstants.USB_DIR_OUT) out = e;
        }
        if (in == null || out == null) throw new IOException("adb interface has no bulk endpoints");
        UsbDeviceConnection c = usb.openDevice(d);
        if (c == null) throw new IOException("cannot open USB device");
        if (!c.claimInterface(intf, true)) {
            c.close();
            throw new IOException("cannot claim USB interface");
        }
        return new UsbRawLink(usb, c, intf, in, out, d.getDeviceName());
    }

    /** True while the device node we opened still exists on the bus. */
    private boolean devicePresent() {
        try {
            return usb.getDeviceList().containsKey(name);
        } catch (RuntimeException e) {
            return true; // if the check itself fails, do not kill a healthy link
        }
    }

    /**
     * bulkTransfer returns -1 BOTH on a real error and on a read timeout, so a negative
     * return alone cannot kill the relay: the AUTH dialog on the target phone leaves the
     * link silent for as long as the user takes to tap Allow, and an idle shell is silent
     * too. The original app's proven path (UsbChannel.readx) blocks with no timeout at
     * all. Mirror that: treat a failed read as a timeout while the device node is still
     * on the bus, and only fail when it is actually gone.
     */
    @Override
    public int read(byte[] buf) throws IOException {
        int n = conn.bulkTransfer(inEp, buf, buf.length, READ_TIMEOUT_MS);
        if (n >= 0) {
            return n;
        }
        if (closed) return -1;
        if (!devicePresent()) {
            throw new IOException("USB target detached mid-read");
        }
        return 0; // quiet link: read timeout, caller polls again
    }


    @Override
    public void writeMessage(byte[] header, byte[] payload) throws IOException {
        writeAll(header);
        if (payload != null) writeAll(payload);
    }

    /** Same offset-copy loop as the original UsbChannel: one bulkTransfer per call. */
    private void writeAll(byte[] data) throws IOException {
        if (data.length == 0) return;
        int off = 0;
        byte[] tmp = new byte[data.length];
        System.arraycopy(data, 0, tmp, 0, data.length);
        while (off < data.length) {
            int w = conn.bulkTransfer(outEp, tmp, data.length - off, WRITE_TIMEOUT_MS);
            if (w < 0) {
                if (closed) throw new IOException("link closed");
                throw new IOException("USB bulk write failed");
            }
            off += w;
            if (off < data.length) {
                System.arraycopy(data, off, tmp, 0, data.length - off);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            conn.releaseInterface(intf);
        } catch (RuntimeException ignored) {}
        conn.close();
    }

    @Override
    public String name() {
        return name;
    }
}
