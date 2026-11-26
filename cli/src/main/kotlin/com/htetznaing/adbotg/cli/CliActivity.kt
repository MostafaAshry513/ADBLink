package com.htetznaing.adbotg.cli

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.Layout
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class CliActivity : Activity() {
    private lateinit var dotView: TextView
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var logView: TextView
    private val handler = Handler()
    private val refresh = object : Runnable {
        override fun run() {
            val s = CliService.status
            statusView.text = s
            detailView.text = CliService.statusDetail
            dotView.setTextColor(statusColor(s))
            logView.text = CliService.LOG.tail(14)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dp = resources.displayMetrics.density.toInt()

        // modern full-dark look: no action bar, system bars match the app background
        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = BG
            window.navigationBarColor = BG
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp)
            setBackgroundColor(BG)
        }

        root.addView(text("ADB-OTG Bridge", 22, TEXT, true))
        val sub = text(
            "Real adb for Termux, over your OTG cable  \u00B7  v" + CliService.VERSION,
            13, DIM, false
        )
        sub.setPadding(0, 2 * dp, 0, 14 * dp)
        root.addView(sub)

        // status card
        val statusCard = card(dp)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dotView = text("\u25CF", 18, AMBER, false)
        dotView.setPadding(0, 0, 10 * dp, 0)
        statusView = text("Stopped", 16, TEXT, true)
        row.addView(dotView)
        row.addView(statusView)
        statusCard.addView(row)
        detailView = text("", 12, DIM, false)
        detailView.setPadding(0, 6 * dp, 0, 0)
        statusCard.addView(detailView)
        root.addView(statusCard)

        // setup card
        val setupCard = card(dp)
        setupCard.addView(text("Setup", 14, TEXT, true))
        val steps = text(
            "1. Plug the rooted phone into this phone and tap Start.\n" +
                "2. In Termux, run:\n" +
                "     pkg install android-tools\n" +
                "     adb connect 127.0.0.1:${CliService.PORT}\n" +
                "3. First time only: tap Allow on the USB prompt here, " +
                "and accept USB debugging on the rooted phone.",
            13, DIM, false
        )
        steps.setPadding(0, 8 * dp, 0, 0)
        setupCard.addView(steps)
        root.addView(setupCard)

        root.addView(button("Copy Termux commands", ACCENT, dp) { copy(setupText()) })
        root.addView(button("Start", ACCENT, dp) { sendAction(CliService.ACTION_START) })
        root.addView(button("Rescan USB", BTN_NEUTRAL, dp) { sendAction(CliService.ACTION_RESCAN) })
        root.addView(button("Stop", BTN_NEUTRAL, dp) { sendAction(CliService.ACTION_STOP) })

        // log card
        val logCard = card(dp)
        logCard.addView(text("Log", 14, TEXT, true))
        logView = text("", 11, DIM, false).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, 8 * dp, 0, 0)
            setHorizontallyScrolling(false) // wrap long lines instead of clipping them
            if (Build.VERSION.SDK_INT >= 23) {
                breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            }
            setTextIsSelectable(true)
        }
        logCard.addView(logView)
        root.addView(logCard)

        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        sv.addView(root)
        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    private fun text(s: String, sp: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            textSize = sp.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(dp: Int): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val g = GradientDrawable()
        g.setColor(CARD)
        g.cornerRadius = (16 * dp).toFloat()
        c.background = g
        c.setPadding(14 * dp, 12 * dp, 14 * dp, 12 * dp)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, 12 * dp)
        c.layoutParams = lp
        return c
    }

    private fun button(label: String, color: Int, dp: Int, onClick: (View) -> Unit): Button {
        val x = Button(this)
        x.text = label
        x.textSize = 13f
        x.setTextColor(TEXT)
        x.isAllCaps = false
        x.minHeight = 0
        x.minimumHeight = 0
        x.setPadding(0, 10 * dp, 0, 10 * dp)
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = (12 * dp).toFloat()
        x.background = g
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, 8 * dp)
        x.layoutParams = lp
        x.setOnClickListener(onClick)
        return x
    }

    private fun sendAction(action: String) {
        val i = Intent(this, CliService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26 && CliService.ACTION_STOP != action) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun setupText(): String =
        // kill-server clears stale device entries (e.g. emulator-5554 from older builds)
        "pkg install -y android-tools; adb kill-server 2>/dev/null; adb connect 127.0.0.1:" +
            CliService.PORT + " && adb devices"

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("adbotg", text))
        Toast.makeText(this, "Copied. Paste into Termux.", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    companion object {
        private val BG = Color.rgb(16, 21, 26)
        private val CARD = Color.rgb(27, 34, 43)
        private val ACCENT = Color.rgb(46, 125, 107)
        private val BTN_NEUTRAL = Color.rgb(42, 53, 64)
        private const val TEXT = Color.WHITE
        private val DIM = Color.rgb(138, 151, 163)
        private val GREEN = Color.rgb(76, 175, 80)
        private val AMBER = Color.rgb(255, 193, 7)
        private val RED = Color.rgb(239, 83, 80)

        private fun statusColor(s: String): Int = when {
            s.startsWith("Relaying") -> GREEN
            s.startsWith("Ready") -> GREEN
            s.startsWith("Stopped") || s.startsWith("Cannot") -> RED
            else -> AMBER
        }
    }
}
