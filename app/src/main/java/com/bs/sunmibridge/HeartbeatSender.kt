package com.bs.sunmibridge

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL

/**
 * Periodically reports this device's live status to the fleet dashboard
 * (sunmi-fleet-dashboard's /api/heartbeat).
 *
 * Each tick sends the CURRENT state, not a history — there is nothing to
 * queue while offline. A failed or skipped send is simply retried on the
 * next tick, and the dashboard's "last seen" naturally reflects reality:
 * it shows the device as offline while Wi-Fi is down and flips back the
 * moment a heartbeat gets through. No local persistence needed for that.
 *
 * No-ops entirely if HEARTBEAT_URL/HEARTBEAT_SECRET weren't configured at
 * build time (see app/build.gradle.kts), so this is safe to ship even
 * before a dashboard exists.
 */
class HeartbeatSender(private val context: Context) {

    companion object {
        private const val INTERVAL_MS = 60_000L
        private const val TIMEOUT_MS = 8_000
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        if (BuildConfig.HEARTBEAT_URL.isBlank()) {
            BridgeBus.log("Heartbeat disabled (no HEARTBEAT_URL configured)")
            return
        }
        running = true
        thread = Thread({ loop() }, "heartbeat").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            sendOnce()
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun sendOnce() {
        if (!isOnline()) return
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BuildConfig.HEARTBEAT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${BuildConfig.HEARTBEAT_SECRET}")
            }
            conn.outputStream.use { it.write(buildPayload().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) BridgeBus.log("Heartbeat rejected: HTTP $code")
        } catch (_: Exception) {
            // Offline / unreachable — silently retried next tick.
        } finally {
            conn?.disconnect()
        }
    }

    private fun isOnline(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    } catch (_: Exception) {
        true // can't tell — attempt the send and let it fail/timeout on its own
    }

    @Suppress("HardwareIds")
    private fun buildPayload(): String {
        val id = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null } ?: "unknown"
        val name = try {
            BluetoothAdapter.getDefaultAdapter()?.name
        } catch (_: Exception) { null } ?: "unknown"

        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        return "{" +
            "\"deviceId\":\"${esc(id)}\"," +
            "\"name\":\"${esc(name)}\"," +
            "\"serverState\":\"${BridgeBus.serverState.name}\"," +
            "\"printerReady\":${BridgeBus.printerReady}," +
            "\"appVersion\":\"${esc(BuildConfig.VERSION_NAME)}\"" +
            "}"
    }
}
