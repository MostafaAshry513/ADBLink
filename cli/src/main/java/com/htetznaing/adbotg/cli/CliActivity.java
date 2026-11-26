package com.htetznaing.adbotg.cli;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class CliActivity extends Activity {
    private static final int BG = Color.rgb(16, 21, 26);
    private static final int CARD = Color.rgb(27, 34, 43);
    private static final int ACCENT = Color.rgb(46, 125, 107);
    private static final int BTN_NEUTRAL = Color.rgb(42, 53, 64);
    private static final int TEXT = Color.WHITE;
    private static final int DIM = Color.rgb(138, 151, 163);
    private static final int GREEN = Color.rgb(76, 175, 80);
    private static final int AMBER = Color.rgb(255, 193, 7);
    private static final int RED = Color.rgb(239, 83, 80);

    private TextView dotView;
    private TextView statusView;
    private TextView detailView;
    private TextView logView;
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String s = CliService.status;
            statusView.setText(s);
            detailView.setText(CliService.statusDetail);
            dotView.setTextColor(statusColor(s));
            logView.setText(CliService.LOG.tail(14));
            handler.postDelayed(this, 1000);
        }
    };

    private static int statusColor(String s) {
        if (s.startsWith("Relaying")) return GREEN;
        if (s.startsWith("Ready")) return GREEN;
        if (s.startsWith("Stopped") || s.startsWith("Cannot")) return RED;
        return AMBER;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int dp = (int) getResources().getDisplayMetrics().density;

        // modern full-dark look: no action bar, system bars match the app background
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
        root.setBackgroundColor(BG);

        TextView title = text("ADB-OTG Bridge", 22, TEXT, true);
        root.addView(title);
        TextView sub = text("Real adb for Termux, over your OTG cable  \u00B7  v" + CliService.VERSION, 13, DIM, false);
        sub.setPadding(0, 2 * dp, 0, 14 * dp);
        root.addView(sub);

        // status card
        LinearLayout statusCard = card(dp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        dotView = text("\u25CF", 18, AMBER, false);
        dotView.setPadding(0, 0, 10 * dp, 0);
        statusView = text("Stopped", 16, TEXT, true);
        row.addView(dotView);
        row.addView(statusView);
        statusCard.addView(row);
        detailView = text("", 12, DIM, false);
        detailView.setPadding(0, 6 * dp, 0, 0);
        statusCard.addView(detailView);
        root.addView(statusCard);

        // setup card
        LinearLayout setupCard = card(dp);
        setupCard.addView(text("Setup", 14, TEXT, true));
        TextView steps = text(
                "1. Plug the rooted phone into this phone and tap Start.\n"
                        + "2. In Termux, run:\n"
                        + "     pkg install android-tools\n"
                        + "     adb connect 127.0.0.1:" + CliService.PORT + "\n"
                        + "3. First time only: tap Allow on the USB prompt here, "
                        + "and accept USB debugging on the rooted phone.",
                13, DIM, false);
        steps.setPadding(0, 8 * dp, 0, 0);
        setupCard.addView(steps);
        root.addView(setupCard);

        root.addView(button("Copy Termux commands", ACCENT, dp, new View.OnClickListener() {
            @Override public void onClick(View v) { copy(setupText()); }
        }));
        root.addView(button("Start", ACCENT, dp, new View.OnClickListener() {
            @Override public void onClick(View v) { sendAction(CliService.ACTION_START); }
        }));
        root.addView(button("Rescan USB", BTN_NEUTRAL, dp, new View.OnClickListener() {
            @Override public void onClick(View v) { sendAction(CliService.ACTION_RESCAN); }
        }));
        root.addView(button("Stop", BTN_NEUTRAL, dp, new View.OnClickListener() {
            @Override public void onClick(View v) { sendAction(CliService.ACTION_STOP); }
        }));

        // log card
        LinearLayout logCard = card(dp);
        logCard.addView(text("Log", 14, TEXT, true));
        logView = text("", 11, DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(0, 8 * dp, 0, 0);
        logView.setHorizontallyScrolling(false); // wrap long lines instead of clipping them
        if (Build.VERSION.SDK_INT >= 23) {
            logView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        }
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        root.addView(logCard);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card(int dp) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(16 * dp);
        c.setBackground(g);
        c.setPadding(14 * dp, 12 * dp, 14 * dp, 12 * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 12 * dp);
        c.setLayoutParams(lp);
        return c;
    }

    private Button button(String label, int color, int dp, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(label);
        x.setTextSize(13);
        x.setTextColor(TEXT);
        x.setAllCaps(false);
        x.setMinHeight(0);
        x.setMinimumHeight(0);
        x.setPadding(0, 10 * dp, 0, 10 * dp);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(12 * dp);
        x.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 8 * dp);
        x.setLayoutParams(lp);
        x.setOnClickListener(l);
        return x;
    }

    private void sendAction(String action) {
        Intent i = new Intent(this, CliService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26 && !CliService.ACTION_STOP.equals(action)) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private String setupText() {
        // kill-server clears stale device entries (e.g. emulator-5554 from older builds)
        return "pkg install -y android-tools; adb kill-server 2>/dev/null; adb connect 127.0.0.1:"
                + CliService.PORT + " && adb devices";
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("adbotg", text));
        Toast.makeText(this, "Copied. Paste into Termux.", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }
}
