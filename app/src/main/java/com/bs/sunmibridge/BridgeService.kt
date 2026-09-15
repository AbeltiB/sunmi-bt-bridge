package com.bs.sunmibridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.ByteArrayOutputStream

/**
 * Foreground service that keeps the bridge alive.
 *
 *   phone --SPP--> [SppServer] --bytes--> [BridgeService] --> [PrinterClient] --> printer
 *
 * Runs foreground so RFCOMM survives screen-off / backgrounding, which is
 * essential for an unattended printer station.
 */
class BridgeService : Service() {

    companion object {
        const val ACTION_START = "com.bs.sunmibridge.START"
        const val ACTION_STOP = "com.bs.sunmibridge.STOP"
        const val ACTION_TEST_PRINT = "com.bs.sunmibridge.TEST_PRINT"
        const val ACTION_SET_BT_NAME = "com.bs.sunmibridge.SET_BT_NAME"
        const val EXTRA_BT_NAME = "bt_name"
        const val EXTRA_WIDTH = "width_mm"

        private const val CHANNEL_ID = "bridge"
        private const val NOTI_ID = 1

        // How long to wait for the phone's Bluetooth socket to go quiet
        // before treating whatever arrived as one complete print job. Phones
        // split a single write into a different number/size of RFCOMM reads
        // depending on their Bluetooth stack; forwarding each raw read as its
        // own sendRAWData() call risked splitting a single ESC/POS command
        // (seen in practice: the totals section printing as zeros on some
        // phones but not others) across two calls. Coalescing removes that
        // whole class of chunking bugs, at the cost of this small delay
        // before printing starts.
        //
        // 50ms fixed the general case but NOT lower-spec devices (reported:
        // still zero on Tecno phones) — widened to 200ms as those devices'
        // app-side processing between writes plausibly exceeds 50ms. See the
        // "Job received" log line below for the diagnostic that will tell us
        // for certain whether that's the cause, or whether the incoming
        // bytes already contain zero (i.e. a bug in the sending app, not
        // something a coalescing window can fix).
        private const val COALESCE_QUIET_MS = 200L

        @Volatile var isRunning = false
            private set
    }

    private lateinit var printer: PrinterClient
    private lateinit var heartbeat: HeartbeatSender
    private var server: SppServer? = null

    // Buffer bytes that arrive before the printer service is ready, then flush.
    private val pending = ByteArrayOutputStream()
    private val pendingLock = Any()

    // Buffer raw Bluetooth reads until the socket goes quiet — see
    // COALESCE_QUIET_MS above — then hand the whole assembled job to
    // handleChunk() as a single call.
    private val coalesceBuffer = ByteArrayOutputStream()
    private val coalesceLock = Any()
    private val coalesceHandler = Handler(Looper.getMainLooper())
    private val flushCoalesced = Runnable { flushCoalescedBuffer() }

    override fun onCreate() {
        super.onCreate()
        printer = PrinterClient(applicationContext)
        // Bind the printer early so the self-test works even before Start.
        printer.bind()
        // Runs for the service's whole lifetime — independent of Start/Stop —
        // so the fleet dashboard can tell "device alive but bridge stopped"
        // apart from "device unreachable".
        heartbeat = HeartbeatSender(applicationContext).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { WatchdogReceiver.cancel(this); stopSelf(); return START_NOT_STICKY }
            ACTION_TEST_PRINT -> {
                val w = intent.getIntExtra(EXTRA_WIDTH, 58)
                printer.testPrint(w)
                return START_STICKY
            }
            ACTION_SET_BT_NAME -> {
                val name = intent.getStringExtra(EXTRA_BT_NAME)
                if (!name.isNullOrBlank()) setBluetoothName(name)
                return START_STICKY
            }
            else -> startBridge()
        }
        return START_STICKY   // restart if the OS kills us
    }

    private fun startBridge() {
        if (isRunning) return
        isRunning = true
        startForeground(NOTI_ID, buildNotification("Starting…"))
        BridgeBus.setState(BridgeBus.ServerState.STARTING)

        if (!printer.isReady) printer.bind()

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            BridgeBus.log("No Bluetooth adapter on this device")
            BridgeBus.setState(BridgeBus.ServerState.ERROR)
            return
        }
        if (!adapter.isEnabled) {
            BridgeBus.log("Enabling Bluetooth…")
            @Suppress("DEPRECATION")
            adapter.enable()   // works on Android 7; ignored on newer OS
        }

        ensureDiscoverable(adapter)
        ensureBatteryUnrestricted()
        WatchdogReceiver.schedule(this)

        server = SppServer(adapter) { buf, len -> onBytes(buf, len) }.also { it.start() }
        BridgeBus.log("Bridge started")
    }

    /**
     * Ask the OS to stop applying battery-optimization throttling to this app.
     * Unattended kiosk devices need this — without it, Doze/App Standby can
     * suspend network access or delay the process on some OEM skins, and
     * aggressive "auto-start manager" style battery savers may kill the
     * foreground service outright. Like [ensureDiscoverable], this still
     * shows one system consent dialog the first time (non-system apps can't
     * self-whitelist silently), but only once per install.
     */
    private fun ensureBatteryUnrestricted() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            BridgeBus.log("Requested battery-optimization exemption")
        } catch (e: Exception) {
            BridgeBus.log("Battery-optimization request failed: ${e.message}")
        }
    }

    /**
     * Best-effort auto-discoverable so the operator doesn't have to tap
     * "Make Discoverable" separately. duration=0 means "no timeout", so this
     * only needs to fire once per boot (scanMode stays DISCOVERABLE until BT
     * is toggled or the device reboots). Android still shows its one-time
     * "Allow this app to make your device discoverable?" system dialog for a
     * non-system app — that consent can't be skipped without the app being
     * signed/installed as a system app.
     */
    private fun ensureDiscoverable(adapter: BluetoothAdapter) {
        try {
            @Suppress("DEPRECATION")
            if (adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) return
            val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 0) // 0 = no timeout
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            BridgeBus.log("Requested discoverable mode")
        } catch (e: Exception) {
            BridgeBus.log("Auto-discoverable request failed: ${e.message}")
        }
    }

    /**
     * Raw bytes straight off the Bluetooth socket, one call per SppServer
     * read() — NOT one call per print job. Accumulate and (re)schedule the
     * quiet-period flush; see COALESCE_QUIET_MS.
     */
    private fun onBytes(buf: ByteArray, len: Int) {
        synchronized(coalesceLock) { coalesceBuffer.write(buf, 0, len) }
        coalesceHandler.removeCallbacks(flushCoalesced)
        coalesceHandler.postDelayed(flushCoalesced, COALESCE_QUIET_MS)
    }

    private fun flushCoalescedBuffer() {
        val chunk: ByteArray
        synchronized(coalesceLock) {
            if (coalesceBuffer.size() == 0) return
            chunk = coalesceBuffer.toByteArray()
            coalesceBuffer.reset()
        }
        handleChunk(chunk)
    }

    /** A fully-assembled print job: relay now, or buffer until printer ready. */
    private fun handleChunk(chunk: ByteArray) {
        BridgeBus.log("Job received: ${chunk.size}B — \"${printablePreview(chunk)}\"")
        if (printer.isReady) {
            flushPending()
            if (printer.printRaw(chunk)) {
                printer.feedExtra() // breathing room so a trailing QR code isn't cut off
            } else {
                bufferChunk(chunk)
            }
        } else {
            bufferChunk(chunk)
            BridgeBus.log("Printer not ready — buffered ${chunk.size}B")
        }
    }

    /**
     * Renders a job's bytes as printable ASCII (control/binary bytes shown as
     * '.') so the on-screen Log / logcat can be read directly to check
     * whether e.g. "0.00" was already present in what the phone sent, versus
     * a correct value that went wrong later. Diagnostic only — this is what
     * lets us tell a bridge-side chunking bug apart from a sending-app bug.
     */
    private fun printablePreview(data: ByteArray, maxLen: Int = 400): String {
        val sb = StringBuilder()
        val n = minOf(data.size, maxLen)
        for (i in 0 until n) {
            val b = data[i].toInt() and 0xFF
            sb.append(if (b in 32..126) b.toChar() else '.')
        }
        if (data.size > maxLen) sb.append('…')
        return sb.toString()
    }

    private fun bufferChunk(chunk: ByteArray) {
        synchronized(pendingLock) { pending.write(chunk) }
    }

    private fun flushPending() {
        val data: ByteArray
        synchronized(pendingLock) {
            if (pending.size() == 0) return
            data = pending.toByteArray()
            pending.reset()
        }
        if (printer.printRaw(data)) BridgeBus.log("Flushed ${data.size}B buffered")
        else synchronized(pendingLock) { pending.write(data) }
    }

    private fun setBluetoothName(name: String) {
        try {
            val ok = BluetoothAdapter.getDefaultAdapter()?.setName(name)
            BridgeBus.log("Bluetooth name set to \"$name\" (ok=$ok)")
        } catch (e: Exception) {
            BridgeBus.log("setName failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        BridgeBus.log("Bridge stopping")
        coalesceHandler.removeCallbacks(flushCoalesced)
        server?.stop()
        printer.unbind()
        heartbeat.stop()
        isRunning = false
        BridgeBus.setState(BridgeBus.ServerState.STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notification -------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Printer Bridge",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setContentTitle("SUNMI Printer Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
