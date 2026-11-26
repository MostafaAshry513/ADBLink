package com.htetznaing.adbotg.cli;

import java.io.IOException;

/**
 * Absorbs USB re-enumeration gaps: when the target's adbd restarts its USB function
 * (auth timeout, protocol error), the device vanishes from the bus for a second or two.
 * Failing the adb client immediately makes adb retry-storm during the gap, which is the
 * bounce seen on hardware. Instead, hold the client and wait for the target to return.
 */
public class RetryingLinkProvider implements BridgeServer.LinkProvider {
    private final BridgeServer.LinkProvider delegate;
    private final long waitMs;
    private final long pollMs;
    private final BridgeServer.Events events;

    public RetryingLinkProvider(BridgeServer.LinkProvider delegate, long waitMs, long pollMs,
                                BridgeServer.Events events) {
        this.delegate = delegate;
        this.waitMs = waitMs;
        this.pollMs = pollMs;
        this.events = events;
    }

    @Override
    public BridgeLink open() throws IOException {
        BridgeLink link = delegate.open();
        if (link != null) return link;
        events.log("no USB target right now; waiting for it to settle...");
        long end = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            link = delegate.open();
            if (link != null) {
                events.log("USB target is back");
                return link;
            }
        }
        return null;
    }

    @Override
    public String describe() {
        return delegate.describe();
    }
}
