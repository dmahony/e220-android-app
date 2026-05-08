package com.dmahony.e220chat

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service to keep BLE connection alive when the app is backgrounded (item 27).
 *
 * Started when BLE connects, stopped when BLE disconnects.
 * Shows a persistent low-priority notification while active.
 */
class E220ForegroundService : Service() {

    private var notificationManager: E220NotificationManager? = null

    companion object {
        const val ACTION_START = "com.dmahony.e220chat.action.START_FOREGROUND"
        const val ACTION_STOP = "com.dmahony.e220chat.action.STOP_FOREGROUND"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val FOREGROUND_NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = E220NotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "BLE device"
                val notification = notificationManager?.buildForegroundNotification(deviceName)
                    ?: buildFallbackNotification(deviceName)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        FOREGROUND_NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startForeground(
                        FOREGROUND_NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(FOREGROUND_NOTIFICATION_ID, notification)
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        notificationManager = null
    }

    private fun buildFallbackNotification(deviceName: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, E220NotificationManager.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("E220 Chat — Connected")
            .setContentText("BLE link active: $deviceName")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
