package com.bs.sunmibridge

import android.content.Context
import android.os.RemoteException
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService

/**
 * Wraps the SUNMI built-in printer.
 *
 * This is the RIGHT hand of the bridge: it takes raw ESC/POS bytes and hands
 * them to the physical thermal head via SUNMI's system printer service.
 *
 * We bind on start and keep the [SunmiPrinterService] handle. If the service
 * drops, [InnerPrinterCallback.onDisconnected] fires and we rebind.
 */
class PrinterClient(private val appContext: Context) {

    companion object {
        private const val STATE_POLL_INTERVAL_MS = 15_000L
    }

    @Volatile private var service: SunmiPrinterService? = null

    val isReady: Boolean get() = service != null

    @Volatile private var pollingActive = false
    private var pollingThread: Thread? = null

    private val connectCallback = object : InnerPrinterCallback() {
        override fun onConnected(s: SunmiPrinterService) {
            service = s
            BridgeBus.setPrinterReady(true)
            BridgeBus.log("Printer service connected")
            try { s.printerInit(null) } catch (e: RemoteException) {
                BridgeBus.log("printerInit failed: ${e.message}")
            }
            startStatusPolling()
        }

        override fun onDisconnected() {
            service = null
            BridgeBus.setPrinterReady(false)
            BridgeBus.setPrinterStatus("DISCONNECTED")
            stopStatusPolling()
            BridgeBus.log("Printer service DISCONNECTED — will rebind")
            // Best-effort rebind; the OS re-broadcasts on service availability.
            try { bind() } catch (_: Exception) {}
        }
    }

    /** Callback for print operations. We only log the outcome. */
    private val resultCallback = object : InnerResultCallback() {
        override fun onRunResult(isSuccess: Boolean) {}
        override fun onReturnString(result: String?) {}
        override fun onRaiseException(code: Int, msg: String?) {
            BridgeBus.log("Print exception [$code]: $msg")
        }
        override fun onPrintResult(code: Int, msg: String?) {}
    }

    fun bind() {
        try {
            val ok = InnerPrinterManager.getInstance().bindService(appContext, connectCallback)
            BridgeBus.log("bindService requested (result=$ok)")
        } catch (e: InnerPrinterException) {
            BridgeBus.log("bindService error: ${e.message}")
        }
    }

    fun unbind() {
        stopStatusPolling()
        try {
            InnerPrinterManager.getInstance().unBindService(appContext, connectCallback)
        } catch (_: Exception) {}
        service = null
        BridgeBus.setPrinterReady(false)
        BridgeBus.setPrinterStatus("DISCONNECTED")
    }

    /**
     * Polls [SunmiPrinterService.updatePrinterState] — the SDK's only status
     * check, no push/broadcast API — so consumables issues (out of paper,
     * cover open, cutter jam, overheat) show up in the log and the fleet
     * heartbeat instead of silently stalling a print job. This is monitoring
     * only: we don't replay bytes after a fault clears. The printer module
     * buffers/resumes a stalled job at the firmware level on its own: retrying
     * from the app side risks a duplicate print on top of whatever the
     * firmware already finishes once paper is reinserted.
     */
    private fun startStatusPolling() {
        if (pollingActive) return
        pollingActive = true
        pollingThread = Thread({
            while (pollingActive) {
                pollState()
                try {
                    Thread.sleep(STATE_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "printer-status-poll").also { it.start() }
    }

    private fun stopStatusPolling() {
        pollingActive = false
        pollingThread?.interrupt()
        pollingThread = null
    }

    private fun pollState() {
        val s = service ?: return
        try {
            BridgeBus.setPrinterStatus(describeState(s.updatePrinterState()))
        } catch (_: RemoteException) {
            // Transient — next poll retries.
        }
    }

    private fun describeState(code: Int): String = when (code) {
        1 -> "NORMAL"
        2 -> "PREPARING"
        3 -> "ABNORMAL_COMM"
        4 -> "OUT_OF_PAPER"
        5 -> "OVERHEATED"
        6 -> "COVER_OPEN"
        7 -> "CUTTER_ABNORMAL"
        8 -> "CUTTER_RECOVERED"
        9 -> "NO_BLACK_MARK"
        505 -> "NOT_FOUND"
        else -> "UNKNOWN($code)"
    }

    /**
     * Forward raw bytes straight to the printer. This is the transparent pipe:
     * whatever ESC/POS the phone app produced is printed verbatim.
     * Returns true if handed to the service, false if the printer wasn't ready.
     */
    fun printRaw(data: ByteArray): Boolean {
        val s = service ?: return false
        return try {
            s.sendRAWData(data, resultCallback)
            true
        } catch (e: RemoteException) {
            BridgeBus.log("sendRAWData failed: ${e.message}")
            false
        }
    }

    /**
     * Extra blank feed after a job, so the last thing printed (often a QR
     * code) fully clears the cutter/tear bar instead of being sliced or torn
     * through. The phone app's own ESC/POS stream ends right after its
     * content with no guaranteed trailing margin — this adds one on our side
     * regardless of what the app sent.
     */
    fun feedExtra(lines: Int = 4) {
        val s = service ?: return
        try {
            s.lineWrap(lines, resultCallback)
        } catch (e: RemoteException) {
            BridgeBus.log("feedExtra failed: ${e.message}")
        }
    }

    /**
     * Self-test that exercises the printer path WITHOUT any Bluetooth, so we
     * can prove the SUNMI side works on its own (Milestone 1).
     * @param widthMm 58 or 80 — only affects the divider width in the test slip.
     */
    fun testPrint(widthMm: Int = 58) {
        val s = service
        if (s == null) {
            BridgeBus.log("TEST PRINT skipped — printer not ready")
            return
        }
        try {
            s.printerInit(null)
            s.setAlignment(1, null) // center
            s.printText("SUNMI BT BRIDGE\n", null)
            s.setAlignment(0, null) // left
            val cols = if (widthMm >= 80) 48 else 32
            s.printText("-".repeat(cols) + "\n", null)
            s.printText("Self-test OK\n", null)
            s.printText("Printer path: SDK sendRAWData\n", null)
            s.printText("Paper: ${widthMm}mm\n", null)
            s.printText("-".repeat(cols) + "\n", null)
            s.lineWrap(3, resultCallback) // feed
            BridgeBus.log("TEST PRINT sent (${widthMm}mm)")
        } catch (e: RemoteException) {
            BridgeBus.log("TEST PRINT failed: ${e.message}")
        }
    }
}
