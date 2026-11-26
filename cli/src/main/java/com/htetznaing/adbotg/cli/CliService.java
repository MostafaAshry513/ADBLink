package com.htetznaing.adbotg.cli;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bridge mode: the app only owns the USB handle and relays bytes between the target's
 * adbd and a loopback TCP socket. The real adb client in Termux (android-tools) connects
 * to 127.0.0.1:6000 and does everything else, including RSA auth with the target.
 */
public class CliService extends Service {
    static final String TAG = "AdbOtgBridge";
    static final String ACTION_START = "com.htetznaing.adbotg.cli.START";
    static final String ACTION_RESCAN = "com.htetznaing.adbotg.cli.RESCAN";
    static final String ACTION_STOP = "com.htetznaing.adbotg.cli.STOP";
    static final String ACTION_USB_PERMISSION = "com.htetznaing.adbotg.cli.USB_PERMISSION";
    // NOT 5555: adb's emulator detector probes localhost ports 5555-5585 and would register
    // this bridge as a second device (emulator-5554), so the two adb transports would keep
    // replacing each other on our single-client relay. 6000 is outside that range.
    static final int PORT = 6000;
    public static final String VERSION = "3.4";

    /** Read by the activity. */
    static volatile String status = "Stopped";
    static volatile String statusDetail = "";
    static volatile boolean serverRunning = false;

    static final CliLog LOG = new CliLog(300);

    private UsbManager usb;
    private BridgeServer bridge;
    private boolean receiverRegistered;
    private Thread watchdog;

    /** Set while a permission prompt is on screen; completed by the receiver. */
    private volatile CountDownLatch permissionLatch;
    private volatile boolean lastPermissionGranted;
    private volatile String permissionAskedFor;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        usb = (UsbManager) getSystemService(USB_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            goForeground();
        } catch (RuntimeException e) {
            // e.g. Android 12+ refuses a foreground start from the background
            Log.w(TAG, "cannot start foreground", e);
            status = "Stopped";
            statusDetail = "Android refused to start the service. Open the app and tap Start.";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (bridge == null) startBridge();
        if (ACTION_RESCAN.equals(action)) {
            permissionAskedFor = null;
            LOG.add("manual rescan");
            updateStatus();
        }
        return START_NOT_STICKY;
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("bridge", "ADB-OTG Bridge", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "bridge") : new Notification.Builder(this);
        Intent open = new Intent(this, CliActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification n = b.setContentTitle("ADB-OTG Bridge running")
                .setContentText("In Termux: adb connect 127.0.0.1:" + PORT)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
    }

    private void startBridge() {
        try {
            BridgeServer.Events events = new BridgeServer.Events() {
                @Override public void log(String msg) {
                    LOG.add(msg);
                    updateStatus();
                }
                @Override public void sessionState(boolean active, String detail) {
                    updateStatus();
                }
            };
            bridge = new BridgeServer(PORT, new RetryingLinkProvider(
                    new BridgeServer.LinkProvider() {
                        @Override public BridgeLink open() throws IOException {
                            return openUsb();
                        }
                        @Override public String describe() {
                            return usbDescription();
                        }
                    }, 20000, 400, events), events);
            bridge.start();
            serverRunning = true;
            LOG.add("bridge v" + VERSION + " up on 127.0.0.1:" + PORT);
            startWatchdog();
        } catch (IOException e) {
            bridge = null;
            serverRunning = false;
            status = "Cannot listen on port " + PORT;
            statusDetail = e.getMessage();
        }
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, f);
            }
            receiverRegistered = true;
        }
        updateStatus();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                LOG.add("USB device detached: " + (d == null ? "?" : d.getDeviceName()));
                if (bridge != null) bridge.closeActive("USB detached");
                permissionAskedFor = null;
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                LOG.add("USB device attached: " + (d == null ? "?" : d.getDeviceName()));
                permissionAskedFor = null;
            } else if (ACTION_USB_PERMISSION.equals(a)) {
                lastPermissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                LOG.add("USB permission " + (lastPermissionGranted ? "granted" : "denied"));
                CountDownLatch l = permissionLatch;
                if (l != null) l.countDown();
            }
            updateStatus();
        }
    };

    private void startWatchdog() {
        if (watchdog != null) return;
        watchdog = new Thread(new Runnable() {
            @Override public void run() {
                while (serverRunning) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    updateStatus();
                }
            }
        }, "adbotg-bridge-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private UsbDevice findTarget() {
        HashMap<String, UsbDevice> devices = usb.getDeviceList();
        for (UsbDevice d : devices.values()) {
            if (UsbRawLink.findAdbInterface(d) != null) return d;
        }
        return null;
    }

    private String usbDescription() {
        UsbDevice d = findTarget();
        if (d == null) return "no USB target with USB debugging enabled";
        if (!usb.hasPermission(d)) return "USB target present, permission not granted yet";
        return "USB target present: " + d.getDeviceName();
    }

    /**
     * Called by the bridge when an adb client connects. Blocks briefly for the USB
     * permission prompt so "adb connect" just works after the user taps Allow.
     */
    private BridgeLink openUsb() throws IOException {
        UsbDevice d = findTarget();
        if (d == null) {
            LOG.add("no USB target; plug the rooted phone in and enable USB debugging on it");
            return null;
        }
        if (!usb.hasPermission(d)) {
            CountDownLatch latch = new CountDownLatch(1);
            permissionLatch = latch;
            if (!d.getDeviceName().equals(permissionAskedFor)) {
                permissionAskedFor = d.getDeviceName();
                LOG.add("asking USB permission for " + d.getDeviceName());
            }
            int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, fl));
            try {
                if (!latch.await(60, TimeUnit.SECONDS)) {
                    LOG.add("USB permission prompt timed out");
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                permissionLatch = null;
            }
            if (!lastPermissionGranted) {
                LOG.add("USB permission denied");
                return null;
            }
        }
        UsbInterface intf = UsbRawLink.findAdbInterface(d);
        UsbRawLink link = UsbRawLink.open(usb, d, intf);
        LOG.add("USB link open: " + d.getDeviceName());
        return link;
    }

    private void updateStatus() {
        if (!serverRunning) return; // shutdown() sets its own text
        BridgeServer b = bridge;
        if (b != null && b.sessionActive()) {
            status = "Relaying to adb";
            statusDetail = usbDescription();
            return;
        }
        UsbDevice d = findTarget();
        if (d == null) {
            status = "Waiting for USB target";
            statusDetail = "Plug the rooted phone in with the OTG cable; USB debugging must be on.";
        } else if (!usb.hasPermission(d)) {
            status = "USB permission needed";
            statusDetail = "Run adb connect 127.0.0.1:" + PORT + " in Termux, then tap Allow here.";
        } else {
            status = "Ready";
            statusDetail = "In Termux: adb connect 127.0.0.1:" + PORT;
        }
    }

    private void shutdown() {
        serverRunning = false;
        if (bridge != null) bridge.stop();
        bridge = null;
        if (watchdog != null) watchdog.interrupt();
        watchdog = null;
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
            receiverRegistered = false;
        }
        status = "Stopped";
        statusDetail = "";
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
