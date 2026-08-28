package com.bs.sunmibridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.util.UUID

/**
 * The LEFT ear of the bridge.
 *
 * Opens a Bluetooth Classic RFCOMM server socket advertising the standard
 * Serial Port Profile (SPP) UUID. To the phone, THIS DEVICE (the SUNMI)
 * now looks like an ordinary Bluetooth serial / ESC-POS thermal printer.
 *
 * Any bytes the phone writes are handed to [onData] verbatim.
 */
class SppServer(
    private val adapter: BluetoothAdapter,
    private val onData: (ByteArray, Int) -> Unit
) {
    companion object {
        // Standard SPP UUID — what generic ESC/POS printer apps look for.
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SDP_NAME = "SunmiBtBridge"
    }

    @Volatile private var running = false
    private var serverSocket: BluetoothServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ acceptLoop() }, "spp-accept").also { it.start() }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                BridgeBus.setState(BridgeBus.ServerState.LISTENING)
                BridgeBus.log("SPP server listening (waiting for phone)…")
                val server = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, SPP_UUID)
                serverSocket = server
                val socket = server.accept()          // blocks until a phone connects
                // One client at a time (expected for a printer): stop advertising.
                try { server.close() } catch (_: Exception) {}
                serverSocket = null
                handleClient(socket)
            } catch (e: Exception) {
                if (running) {
                    BridgeBus.log("accept() error: ${e.message}")
                    BridgeBus.setState(BridgeBus.ServerState.ERROR)
                    Thread.sleep(1500)               // brief backoff, then re-listen
                }
            }
        }
        BridgeBus.setState(BridgeBus.ServerState.STOPPED)
    }

    private fun handleClient(socket: BluetoothSocket) {
        val remote = try { socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "?" }
        catch (_: Exception) { "?" }
        BridgeBus.setRemote(remote)
        BridgeBus.setState(BridgeBus.ServerState.CONNECTED)
        BridgeBus.log("Phone connected: $remote")

        val input: InputStream = socket.inputStream
        val buffer = ByteArray(4096)
        var total = 0L
        try {
            while (running) {
                val n = input.read(buffer)           // blocks; -1 on disconnect
                if (n < 0) break
                if (n > 0) {
                    total += n
                    onData(buffer, n)
                }
            }
        } catch (e: Exception) {
            BridgeBus.log("read() ended: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
            BridgeBus.log("Phone disconnected ($remote), bytes relayed=$total")
            BridgeBus.setRemote("-")
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        thread = null
    }
}
