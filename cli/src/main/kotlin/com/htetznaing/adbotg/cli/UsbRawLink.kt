package com.htetznaing.adbotg.cli

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Raw USB side of the bridge: claims the adb interface and moves bytes without
 * interpreting them. Writes keep the header-then-payload segmentation that the
 * original app's UsbChannel used on real hardware.
 */
class UsbRawLink private constructor(
    private val usb: UsbManager,
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val deviceName: String
) : BridgeLink {

    @Volatile
    private var closed = false

    /** True while the device node we opened still exists on the bus. */
    private fun devicePresent(): Boolean =
        try {
            usb.deviceList.containsKey(deviceName)
        } catch (e: RuntimeException) {
            true // if the check itself fails, do not kill a healthy link
        }

    /**
     * bulkTransfer returns -1 BOTH on a real error and on a read timeout, so a negative
     * return alone cannot kill the relay: the AUTH dialog on the target phone leaves the
     * link silent for as long as the user takes to tap Allow, and an idle shell is silent
     * too. The original app's proven path (UsbChannel.readx) blocks with no timeout at
     * all. Mirror that: treat a failed read as a timeout while the device node is still
     * on the bus, and only fail when it is actually gone.
     */
    @Throws(IOException::class)
    override fun read(buf: ByteArray): Int {
        val n = conn.bulkTransfer(inEp, buf, buf.size, READ_TIMEOUT_MS)
        if (n >= 0) return n
        if (closed) return -1
        if (!devicePresent()) throw IOException("USB target detached mid-read")
        return 0 // quiet link: read timeout, caller polls again
    }

    @Throws(IOException::class)
    override fun writeMessage(header: ByteArray, payload: ByteArray?) {
        writeAll(header)
        if (payload != null) writeAll(payload)
    }

    /** Same offset-copy loop as the original UsbChannel: one bulkTransfer per call. */
    @Throws(IOException::class)
    private fun writeAll(data: ByteArray) {
        if (data.isEmpty()) return
        var off = 0
        val tmp = data.copyOf()
        while (off < data.size) {
            val w = conn.bulkTransfer(outEp, tmp, data.size - off, WRITE_TIMEOUT_MS)
            if (w < 0) {
                if (closed) throw IOException("link closed")
                throw IOException("USB bulk write failed")
            }
            off += w
            if (off < data.size) {
                System.arraycopy(data, off, tmp, 0, data.size - off)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            conn.releaseInterface(intf)
        } catch (ignored: RuntimeException) {
        }
        conn.close()
    }

    override fun name(): String = deviceName

    companion object {
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 2000

        /** The adb interface: class 255, subclass 66, protocol 1. Same rule as the original app. */
        @JvmStatic
        fun findAdbInterface(d: UsbDevice): UsbInterface? {
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.interfaceClass == 255 && f.interfaceSubclass == 66 && f.interfaceProtocol == 1) {
                    return f
                }
            }
            return null
        }

        /** Open and claim the interface. Caller has already checked USB permission. */
        @JvmStatic
        @Throws(IOException::class)
        fun open(usb: UsbManager, d: UsbDevice, intf: UsbInterface): UsbRawLink {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val e = intf.getEndpoint(i)
                if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (e.direction == UsbConstants.USB_DIR_IN) inEp = e
                else if (e.direction == UsbConstants.USB_DIR_OUT) outEp = e
            }
            if (inEp == null || outEp == null) throw IOException("adb interface has no bulk endpoints")
            val c = usb.openDevice(d) ?: throw IOException("cannot open USB device")
            if (!c.claimInterface(intf, true)) {
                c.close()
                throw IOException("cannot claim USB interface")
            }
            return UsbRawLink(usb, c, intf, inEp, outEp, d.deviceName)
        }
    }
}
