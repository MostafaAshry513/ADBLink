package com.htetznaing.adbotg.cli;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory event log so a dropped link can be explained afterwards. */
public class CliLog {
    private final ArrayDeque<String> lines = new ArrayDeque<String>();
    private final int max;

    public CliLog(int max) { this.max = max; }

    public synchronized void add(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        lines.addLast(ts + " " + msg);
        while (lines.size() > max) lines.removeFirst();
    }

    public synchronized String tail(int n) {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, lines.size() - n);
        int i = 0;
        for (String l : lines) {
            if (i++ >= skip) sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
