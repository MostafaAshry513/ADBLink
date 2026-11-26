package com.htetznaing.adbotg.cli

import java.io.IOException

/** The USB side of the bridge, abstracted so the relay can be tested without hardware. */
interface BridgeLink {
    /**
     * Read target-to-host bytes.
     * Returns the byte count (>0), 0 when nothing arrived before a short timeout (retry),
     * or -1 when the link is dead.
     */
    @Throws(IOException::class)
    fun read(buf: ByteArray): Int

    /** Write one whole ADB message host-to-target: 24-byte header, then payload (may be null). */
    @Throws(IOException::class)
    fun writeMessage(header: ByteArray, payload: ByteArray?)

    /** Idempotent close; also unblocks a pending read. */
    fun close()

    /** Short name for logs and status, e.g. the USB device node. */
    fun name(): String
}
