package com.atakmap.android.plowtak.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that holds a persistent notification while a shift is
 * active so Android doze / OEM battery killers keep recording alive on
 * mounted tablets. Started at shift start, stopped at shift end; the
 * notification text mirrors callsign + vehicle status.
 *
 * SDK-setup note: plugin code runs inside the ATAK host process, so this
 * service (declared in the plugin APK's own manifest/package) is started
 * cross-package via explicit ComponentName — it must remain exported for
 * that to work. FGS type is [dataSync] (not location): the plugin process
 * does not hold ACCESS_*_LOCATION; ATAK does. Type=location crashes on
 * Android 14+ and sticky-restarts loop-kill the package.
 */
class PlowTakShiftService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Shift active"
        return try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            START_STICKY
        } catch (t: Throwable) {
            // Never crash the plugin process (Fold 8 / API 34 SecurityException
            // on wrong FGS type previously took the package down in a loop).
            Log.e(TAG, "startForeground failed; stopping service", t)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
                // ignore
            }
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
            // ignore
        }
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "PlowTAK shift", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps coverage recording alive during a shift"
                    setShowBadge(false)
                }
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PlowTAK — shift active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PlowTakShiftService"
        private const val CHANNEL_ID = "plowtak_shift"
        private const val NOTIFICATION_ID = 0x1DEA
        private const val EXTRA_TEXT = "text"

        /** Plugin package (applicationId), not the host's. */
        private const val PLUGIN_PACKAGE = "com.atakmap.android.plowtak.plugin"

        fun start(context: Context, statusText: String) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(PLUGIN_PACKAGE, PlowTakShiftService::class.java.name)
                    putExtra(EXTRA_TEXT, statusText)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Never let notification plumbing break shift start.
                Log.w(TAG, "failed starting shift service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(PLUGIN_PACKAGE, PlowTakShiftService::class.java.name)
                }
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "failed stopping shift service", e)
            }
        }
    }
}
