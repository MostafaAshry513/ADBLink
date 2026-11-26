package com.htetznaing.adbotg.cli

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridge mode: the app only owns the USB handle and relays bytes between the target's
 * adbd and a loopback TCP socket. The real adb client in Termux (android-tools) connects
 * to 127.0.0.1:6000 and does everything else, including RSA auth with the target.
 */
class CliService : Service() {
    private lateinit var usb: UsbManager
    private var bridge: BridgeServer? = null
    private var receiverRegistered = false
    private var watchdog: Thread? = null

    /** Set while a permission prompt is on screen; completed by the receiver. */
    @Volatile
    private var permissionLatch: CountDownLatch? = null

    @Volatile
    private var lastPermissionGranted = false

    @Volatile
    private var permissionAskedFor: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usb = getSystemService(USB_SERVICE) as UsbManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = if (intent == null) ACTION_START else intent.action
        if (ACTION_STOP == action) {
            shutdown()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            goForeground()
        } catch (e: RuntimeException) {
            // e.g. Android 12+ refuses a foreground start from the background
            Log.w(TAG, "cannot start foreground", e)
            status = "Stopped"
            statusDetail = "Android refused to start the service. Open the app and tap Start."
            stopSelf()
            return START_NOT_STICKY
        }
        if (bridge == null) startBridge()
        if (ACTION_RESCAN == action) {
            permissionAskedFor = null
            LOG.add("manual rescan")
            updateStatus()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("bridge", "ADBLink", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "bridge")
        else Notification.Builder(this)
        val open = Intent(this, CliActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, open,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val n = b.setContentTitle("ADBLink running")
            .setContentText("In Termux: adb connect 127.0.0.1:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
    }

    private fun startBridge() {
        try {
            val events = object : BridgeServer.Events {
                override fun log(msg: String) {
                    LOG.add(msg)
                    updateStatus()
                }

                override fun sessionState(active: Boolean, detail: String?) {
                    updateStatus()
                }
            }
            val provider = object : BridgeServer.LinkProvider {
                @Throws(IOException::class)
                override fun open(): BridgeLink? = openUsb()
                override fun describe(): String = usbDescription()
            }
            val server = BridgeServer(PORT, RetryingLinkProvider(provider, 20000, 400, events), events)
            bridge = server
            server.start()
            serverRunning = true
            LOG.add("bridge v$VERSION up on 127.0.0.1:$PORT")
            startWatchdog()
        } catch (e: IOException) {
            bridge = null
            serverRunning = false
            status = "Cannot listen on port $PORT"
            statusDetail = e.message ?: ""
        }
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, f)
            }
            receiverRegistered = true
        }
        updateStatus()
    }

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    LOG.add("USB device detached: ${d?.deviceName ?: "?"}")
                    bridge?.closeActive("USB detached")
                    permissionAskedFor = null
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    LOG.add("USB device attached: ${d?.deviceName ?: "?"}")
                    permissionAskedFor = null
                }
                ACTION_USB_PERMISSION -> {
                    lastPermissionGranted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    LOG.add("USB permission " + if (lastPermissionGranted) "granted" else "denied")
                    permissionLatch?.countDown()
                }
            }
            updateStatus()
        }
    }

    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = Thread({
            while (serverRunning) {
                try {
                    Thread.sleep(3000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                updateStatus()
            }
        }, "adbotg-bridge-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun findTarget(): UsbDevice? =
        usb.deviceList.values.firstOrNull { UsbRawLink.findAdbInterface(it) != null }

    private fun usbDescription(): String {
        val d = findTarget() ?: return "no USB target with USB debugging enabled"
        if (!usb.hasPermission(d)) return "USB target present, permission not granted yet"
        return "USB target present: ${d.deviceName}"
    }

    /**
     * Called by the bridge when an adb client connects. Blocks briefly for the USB
     * permission prompt so "adb connect" just works after the user taps Allow.
     */
    @Throws(IOException::class)
    private fun openUsb(): BridgeLink? {
        val d = findTarget()
        if (d == null) {
            LOG.add("no USB target; plug the rooted phone in and enable USB debugging on it")
            return null
        }
        if (!usb.hasPermission(d)) {
            val latch = CountDownLatch(1)
            permissionLatch = latch
            if (d.deviceName != permissionAskedFor) {
                permissionAskedFor = d.deviceName
                LOG.add("asking USB permission for ${d.deviceName}")
            }
            val fl = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val i = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, fl))
            try {
                if (!latch.await(60, TimeUnit.SECONDS)) {
                    LOG.add("USB permission prompt timed out")
                    return null
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } finally {
                permissionLatch = null
            }
            if (!lastPermissionGranted) {
                LOG.add("USB permission denied")
                return null
            }
        }
        val intf = UsbRawLink.findAdbInterface(d)!!
        val link = UsbRawLink.open(usb, d, intf)
        LOG.add("USB link open: ${d.deviceName}")
        return link
    }

    private fun updateStatus() {
        if (!serverRunning) return // shutdown() sets its own text
        val b = bridge
        if (b != null && b.sessionActive()) {
            status = "Relaying to adb"
            statusDetail = usbDescription()
            return
        }
        val d = findTarget()
        if (d == null) {
            status = "Waiting for USB target"
            statusDetail = "Plug the rooted phone in with the OTG cable; USB debugging must be on."
        } else if (!usb.hasPermission(d)) {
            status = "USB permission needed"
            statusDetail = "Run adb connect 127.0.0.1:$PORT in Termux, then tap Allow here."
        } else {
            status = "Ready"
            statusDetail = "In Termux: adb connect 127.0.0.1:$PORT"
        }
    }

    private fun shutdown() {
        serverRunning = false
        bridge?.stop()
        bridge = null
        watchdog?.interrupt()
        watchdog = null
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (ignored: RuntimeException) {
            }
            receiverRegistered = false
        }
        status = "Stopped"
        statusDetail = ""
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        const val TAG = "AdbOtgBridge"
        const val ACTION_START = "com.htetznaing.adbotg.cli.START"
        const val ACTION_RESCAN = "com.htetznaing.adbotg.cli.RESCAN"
        const val ACTION_STOP = "com.htetznaing.adbotg.cli.STOP"
        const val ACTION_USB_PERMISSION = "com.htetznaing.adbotg.cli.USB_PERMISSION"

        // NOT 5555: adb's emulator detector probes localhost ports 5555-5585 and would register
        // this bridge as a second device (emulator-5554), so the two adb transports would keep
        // replacing each other on our single-client relay. 6000 is outside that range.
        const val PORT = 6000
        const val VERSION = "3.4"

        /** Read by the activity. */
        @Volatile
        var status = "Stopped"

        @Volatile
        var statusDetail = ""

        @Volatile
        var serverRunning = false

        @JvmField
        val LOG = CliLog(300)
    }
}
