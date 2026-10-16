package com.betalgezia.omnivpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.betalgezia.omnivpn.MainActivity
import com.betalgezia.omnivpn.R
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol

/**
 * The VPN service's foreground notification (handoff §7): the «Орбита» status
 * icon in the accent colour, «Подключено · <name>» over «VLESS · host:port ·
 * 38 мс», and the actions «Отключить» and - for servers, not WARP - «Сменить
 * сервер», which opens the Servers tab. Speed is deliberately not shown.
 */
object VpnNotification {

    const val NOTIFICATION_ID = 1001
    private const val CHANNEL_ID = "omnivpn-vpn"
    private const val ACCENT = 0xFFC67139.toInt()

    fun createChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OmniVPN",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, title: String, text: String?, canSwitchServer: Boolean): Notification {
        val stopIntent = Intent(context, OmniVpnService::class.java).apply {
            action = OmniVpnService.ACTION_STOP
        }
        val stop = PendingIntent.getService(
            context,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_omnivpn)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentIntent(openApp(context, requestCode = 1003, tab = null))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Отключить", stop)
        if (!text.isNullOrBlank()) builder.setContentText(text)
        if (canSwitchServer) {
            builder.addAction(0, "Сменить сервер", openApp(context, requestCode = 1004, tab = MainActivity.TAB_SERVERS))
        }
        return builder.build()
    }

    /** The second line for a server: «VLESS · host:port» (IPv6 hosts in brackets). */
    fun serverDetail(node: Node): String {
        val protocol = if (node.protocol == Protocol.SHADOWSOCKS) "SS" else node.protocol.name
        val host = if (node.server.contains(':')) "[${node.server}]" else node.server
        return "$protocol · $host:${node.port}"
    }

    private fun openApp(context: Context, requestCode: Int, tab: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Reuse the running window (onNewIntent) instead of stacking a second one.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (tab != null) putExtra(MainActivity.EXTRA_OPEN_TAB, tab)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
