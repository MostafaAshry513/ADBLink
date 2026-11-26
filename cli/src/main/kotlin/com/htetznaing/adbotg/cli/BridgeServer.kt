package com.htetznaing.adbotg.cli

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

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
class BridgeServer(
    private val port: Int,
    private val provider: LinkProvider,
    private val events: Events
) {
    interface LinkProvider {
        /** Open the USB side now, or return null when no target is available. */
        @Throws(IOException::class)
        fun open(): BridgeLink?

        /** Short status of the USB side for the UI, e.g. "target present" or "no USB target". */
        fun describe(): String
    }

    interface Events {
        fun log(msg: String)
        fun sessionState(active: Boolean, detail: String?)
    }

    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var active: Session? = null

    fun getPort(): Int = server?.localPort ?: port

    fun sessionActive(): Boolean = active?.alive == true

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (running) return
        server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
        running = true
        thread(isDaemon = true, name = "adbotg-bridge-accept") { acceptLoop() }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            server?.close()
        } catch (ignored: IOException) {
        }
        closeActive("bridge stopped")
    }

    /** USB disappeared or the service is shutting down: end the relay, the client will see a reset. */
    fun closeActive(reason: String?) {
        active?.close(reason)
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val s = server!!.accept()
                onClient(s)
            } catch (e: IOException) {
                if (!running) return
            }
        }
    }

    private fun onClient(s: Socket) {
        closeActive("replaced by a new adb connection")
        val link: BridgeLink?
        try {
            link = provider.open()
        } catch (e: IOException) {
            events.log("adb connected but the USB side failed: ${e.message}")
            try {
                s.close()
            } catch (ignored: IOException) {
            }
            return
        }
        if (link == null) {
            events.log("adb connected but there is no usable USB target (${provider.describe()})")
            try {
                s.close()
            } catch (ignored: IOException) {
            }
            return
        }
        val session = Session(s, link)
        active = session
        session.start()
        events.log("adb client connected, relaying to ${link.name()}")
        events.sessionState(true, link.name())
    }

    private inner class Session(val client: Socket, val link: BridgeLink) {
        @Volatile
        var alive = true
        private var reported = false
        private var loggedCnxn = false
        private var loggedAuth = false

        /** Watch the client-to-target stream for the messages that explain a bounce. */
        private fun inspect(header: ByteArray) {
            val cmd = String(header, 0, 4, Charsets.US_ASCII)
            if (!loggedCnxn && cmd == "CNXN") {
                loggedCnxn = true
                events.log("adb handshake started (CNXN from Termux)")
            } else if (!loggedAuth && cmd == "AUTH") {
                val arg0 = header[4].toInt() and 0xff
                if (arg0 == 3) { // ADB_AUTH_RSAPUBLICKEY: target does not know this key yet
                    loggedAuth = true
                    events.log(
                        "the rooted phone is asking to authorize this computer: " +
                            "tap Allow (and tick always-allow) on its USB debugging dialog"
                    )
                }
            }
        }

        fun start() {
            thread(isDaemon = true, name = "adbotg-bridge-tx") { clientToUsb() }
            thread(isDaemon = true, name = "adbotg-bridge-rx") { usbToClient() }
        }

        private fun clientToUsb() {
            try {
                val input = client.getInputStream()
                // USB writes happen only on this thread, via link.writeMessage
                val framer = AdbFramer()
                val buf = ByteArray(65536)
                val sink = object : AdbFramer.Sink {
                    override fun onMessage(header: ByteArray, payload: ByteArray?) {
                        inspect(header)
                        link.writeMessage(header, payload)
                    }
                }
                while (alive) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    framer.feed(buf, 0, n, sink)
                }
                close(if (alive) "adb client ended the connection" else null)
            } catch (e: IOException) {
                close(if (alive) "client read failed: ${e.message}" else null)
            }
        }

        private fun usbToClient() {
            try {
                val out = client.getOutputStream()
                val buf = ByteArray(65536)
                while (alive) {
                    val n = link.read(buf)
                    if (n < 0) {
                        close("USB link died")
                        return
                    }
                    if (n == 0) continue // read timeout, just poll again
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (e: IOException) {
                close(if (alive) "USB read failed: ${e.message}" else null)
            }
        }

        @Synchronized
        fun close(reason: String?) {
            if (!alive) return
            alive = false
            link.close()
            // Log BEFORE closing the client socket: anything that watches for the socket to close
            // (including the unit tests) must already be able to see why the relay ended.
            if (reason != null && !reported) {
                reported = true
                events.log("relay ended: $reason")
            }
            try {
                client.close()
            } catch (ignored: IOException) {
            }
            events.sessionState(false, null)
            if (active === this) active = null
        }
    }
}
