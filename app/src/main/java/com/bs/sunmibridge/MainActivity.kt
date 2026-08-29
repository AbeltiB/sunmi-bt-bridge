package com.bs.sunmibridge

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

/**
 * Single-screen control panel. This is infrastructure, not a product UI:
 * it shows what the bridge is doing and lets an operator start/stop it,
 * run a printer self-test, and make the SUNMI pairable.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var nameInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        nameInput = findViewById(R.id.btName)
        nameInput.setText(defaultDeviceName())

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ensureBluetooth()
            send(BridgeService.ACTION_START)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            send(BridgeService.ACTION_STOP)
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val i = Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTION_TEST_PRINT)
                .putExtra(BridgeService.EXTRA_WIDTH, 58)
            startService(i)
        }
        findViewById<Button>(R.id.btnDiscoverable).setOnClickListener { requestDiscoverable() }
        findViewById<Button>(R.id.btnSetName).setOnClickListener {
            val i = Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTION_SET_BT_NAME)
                .putExtra(BridgeService.EXTRA_BT_NAME, nameInput.text.toString().trim())
            startService(i)
        }

        // back-fill existing log, then subscribe
        logView.text = BridgeBus.snapshot().joinToString("\n")
        BridgeBus.onLog = { line ->
            logView.append("\n$line")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        BridgeBus.onStateChanged = { renderStatus() }
        renderStatus()
    }

    override fun onDestroy() {
        BridgeBus.onLog = null
        BridgeBus.onStateChanged = null
        super.onDestroy()
    }

    private fun renderStatus() {
        val s = BridgeBus.serverState
        val printer = if (BridgeBus.printerReady) "READY" else "not ready"
        statusView.text = buildString {
            append("Server:  ").append(s.name).append('\n')
            append("Printer: ").append(printer).append(" (").append(BridgeBus.printerStatus).append(")\n")
            append("Client:  ").append(BridgeBus.lastRemote)
        }
    }

    private fun send(action: String) {
        val i = Intent(this, BridgeService::class.java).setAction(action)
        if (action == BridgeService.ACTION_START &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) startForegroundService(i) else startService(i)
    }

    private fun ensureBluetooth() {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!a.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    /** App-driven discoverability (automatic path). Manual pairing in
     *  Settings is the fail-safe if a picky OS clamps this. */
    private fun requestDiscoverable() {
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) // max on Android 7
        startActivity(i)
    }

    /** "InnerPrinter-XXXX" — unique per device out of the box (ANDROID_ID is
     *  stable per device+app-signing-key and needs no permission), so a fleet
     *  of SUNMIs doesn't all advertise the same Bluetooth name. Still fully
     *  editable in the UI in case the phone app expects an exact name match. */
    @Suppress("HardwareIds")
    private fun defaultDeviceName(): String {
        val id = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }
        val suffix = id?.takeLast(4)?.uppercase() ?: "0000"
        return "InnerPrinter-$suffix"
    }
}
