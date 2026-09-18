package com.betalgezia.omnivpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat

object VpnNotification {

    const val NOTIFICATION_ID = 1001
    private const val CHANNEL_ID = "omnivpn-vpn"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OmniVPN",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, text: String): Notification {
        val stopIntent = Intent(context, OmniVpnService::class.java).apply {
            action = OmniVpnService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("OmniVPN")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .build()
    }
}
