package com.htetznaing.adbotg.cli

import java.io.IOException

/**
 * Reframes a byte stream into whole ADB messages (24-byte little-endian header + payload).
 * The TCP side of the bridge delivers arbitrary byte chunks; the USB side wants each
 * message written as header then payload (the segmentation real USB adb stacks expect).
 */
class AdbFramer {
    interface Sink {
        @Throws(IOException::class)
        fun onMessage(header: ByteArray, payload: ByteArray?)
    }

    private var buf = ByteArray(65536)
    private var len = 0

    private fun hexHead(): String {
        val sb = StringBuilder()
        var i = 0
        while (i < 8 && i < len) {
            sb.append(String.format("%02x", buf[i]))
            i++
        }
        return sb.toString()
    }

    @Synchronized
    @Throws(IOException::class)
    fun feed(data: ByteArray, off: Int, n: Int, sink: Sink) {
        if (len + n > buf.size) {
            val nb = ByteArray(maxOf(buf.size * 2, len + n))
            System.arraycopy(buf, 0, nb, 0, len)
            buf = nb
        }
        System.arraycopy(data, off, buf, len, n)
        len += n
        var pos = 0
        while (len - pos >= 24) {
            val pl = payloadLength(buf, pos)
            if (pl < 0 || pl > MAX_MESSAGE_PAYLOAD) {
                throw IOException(
                    "garbage ADB framing (payload length $pl, first bytes ${hexHead()})"
                )
            }
            if (len - pos < 24 + pl) break
            val h = ByteArray(24)
            System.arraycopy(buf, pos, h, 0, 24)
            val p = if (pl == 0) null else ByteArray(pl)
            if (p != null) System.arraycopy(buf, pos + 24, p, 0, pl)
            pos += 24 + pl
            sink.onMessage(h, p)
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }
    }

    companion object {
        /** ADB MAX_PAYLOAD is 1 MiB on current devices; anything beyond this is garbage. */
        private const val MAX_MESSAGE_PAYLOAD = 4 * 1024 * 1024

        @JvmStatic
        fun payloadLength(header: ByteArray, off: Int): Int =
            (header[off + 12].toInt() and 0xff) or
                ((header[off + 13].toInt() and 0xff) shl 8) or
                ((header[off + 14].toInt() and 0xff) shl 16) or
                ((header[off + 15].toInt() and 0xff) shl 24)
    }
}
