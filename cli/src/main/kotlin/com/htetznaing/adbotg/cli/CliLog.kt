package com.htetznaing.adbotg.cli

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Small in-memory event log so a dropped link can be explained afterwards. */
class CliLog(private val max: Int) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$ts $msg")
        while (lines.size > max) lines.removeFirst()
    }

    @Synchronized
    fun tail(n: Int): String {
        val sb = StringBuilder()
        val skip = maxOf(0, lines.size - n)
        for ((i, l) in lines.withIndex()) {
            if (i >= skip) sb.append(l).append('\n')
        }
        return sb.toString()
    }
}
