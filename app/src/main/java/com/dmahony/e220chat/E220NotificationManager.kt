package com.dmahony.e220chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Manages Android notifications for incoming radio messages (item 26)
 * and the persistent foreground service notification (item 27).
 */
class E220NotificationManager(private val context: Context) {

    init {
        createChannels(context)
    }

    /**
     * Check and request POST_NOTIFICATIONS permission on Android 13+.
     * Returns true if permission is already granted.
     */
    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Show notifications for newly received messages.
     * Called when messages arrive while the app is in background.
     */
    @SuppressLint("MissingPermission")
    fun showMessageNotifications(messages: List<ChatMessage>, senderName: String) {
        if (!hasNotificationPermission()) return
        if (messages.isEmpty()) return

        val nm = NotificationManagerCompat.from(context)

        // Open app to Chat tab when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", AppTab.CHAT.name)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(if (messages.size == 1) "New radio message" else "${messages.size} new radio messages")
            .setContentText("Tap to open chat")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        nm.notify(MESSAGE_NOTIFICATION_BASE_ID + messages.hashCode(), notification)
    }

    /**
     * Build the persistent notification for the foreground service (item 27).
     */
    fun buildForegroundNotification(deviceName: String): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", AppTab.CHAT.name)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("E220 Chat — Connected")
            .setContentText("BLE link active: $deviceName")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Cancel all message notifications (called when app returns to foreground).
     */
    fun cancelAll() {
        val nm = NotificationManagerCompat.from(context)
        nm.cancelAll()
    }

    companion object {
        const val CHANNEL_MESSAGES = "e220_messages"
        const val CHANNEL_SERVICE = "e220_service"
        const val FOREGROUND_NOTIFICATION_ID = 1
        private const val MESSAGE_NOTIFICATION_BASE_ID = 100

        /**
         * Create notification channels. Safe to call from Application.onCreate()
         * to ensure channels exist before any UI or ViewModel is loaded.
         */
        fun createChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel for incoming message notifications
            val messageChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Radio Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming radio messages"
                setShowBadge(true)
            }
            nm.createNotificationChannel(messageChannel)

            // Channel for foreground service persistent notification
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "BLE Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Bluetooth connection is active to keep the app running"
                setShowBadge(false)
            }
            nm.createNotificationChannel(serviceChannel)
        }
    }
}
