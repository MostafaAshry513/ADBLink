package com.htetznaing.adbotg.cli

import java.io.IOException

/**
 * Absorbs USB re-enumeration gaps: when the target's adbd restarts its USB function
 * (auth timeout, protocol error), the device vanishes from the bus for a second or two.
 * Failing the adb client immediately makes adb retry-storm during the gap, which is the
 * bounce seen on hardware. Instead, hold the client and wait for the target to return.
 */
class RetryingLinkProvider(
    private val delegate: BridgeServer.LinkProvider,
    private val waitMs: Long,
    private val pollMs: Long,
    private val events: BridgeServer.Events
) : BridgeServer.LinkProvider {

    @Throws(IOException::class)
    override fun open(): BridgeLink? {
        delegate.open()?.let { return it }
        events.log("no USB target right now; waiting for it to settle...")
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(pollMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            val link = delegate.open()
            if (link != null) {
                events.log("USB target is back")
                return link
            }
        }
        return null
    }

    override fun describe(): String = delegate.describe()
}
