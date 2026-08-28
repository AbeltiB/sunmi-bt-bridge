package com.bs.sunmibridge

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Tiny in-process event bus shared between the [BridgeService] (producer)
 * and [MainActivity] (consumer). Avoids pulling in AndroidX LiveData /
 * LocalBroadcastManager for what is a single-screen infra app.
 *
 * All listener callbacks are dispatched on the main thread.
 */
object BridgeBus {

    /** High-level lifecycle state, surfaced to the UI. */
    enum class ServerState { STOPPED, STARTING, LISTENING, CONNECTED, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Ring buffer of recent log lines so a newly-opened Activity can back-fill.
    private const val MAX_LINES = 500
    private val logBuffer = ArrayDeque<String>()

    @Volatile var serverState: ServerState = ServerState.STOPPED
        private set
    @Volatile var printerReady: Boolean = false
        private set
    @Volatile var lastRemote: String = "-"
        private set

    var onStateChanged: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    fun setState(s: ServerState) {
        serverState = s
        main.post { onStateChanged?.invoke() }
    }

    fun setPrinterReady(ready: Boolean) {
        printerReady = ready
        main.post { onStateChanged?.invoke() }
    }

    fun setRemote(name: String) {
        lastRemote = name
        main.post { onStateChanged?.invoke() }
    }

    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LINES) logBuffer.removeFirst()
        }
        android.util.Log.d("SunmiBtBridge", msg)
        main.post { onLog?.invoke(line) }
    }

    fun snapshot(): List<String> = synchronized(logBuffer) { logBuffer.toList() }
}
