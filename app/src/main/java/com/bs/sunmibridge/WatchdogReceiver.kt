package com.bs.sunmibridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Periodic self-check: if [BridgeService] isn't running, restart it.
 *
 * Re-schedules itself on every fire (one-shot exact alarm, not a fixed
 * repeating one) so the interval stays predictable across Doze. Requires
 * the battery-optimization exemption requested in
 * [BridgeService.ensureBatteryUnrestricted] to fire reliably while idle.
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val INTERVAL_MS = 5 * 60 * 1000L   // 5 minutes
        private const val REQUEST_CODE = 1001

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
                }
            } catch (e: Exception) {
                BridgeBus.log("Watchdog schedule failed: ${e.message}")
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val i = Intent(context, WatchdogReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getBroadcast(context, REQUEST_CODE, i, flags)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!BridgeService.isRunning) {
            BridgeBus.log("Watchdog: bridge not running — restarting")
            val svc = Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
        schedule(context)   // keep watching
    }
}
