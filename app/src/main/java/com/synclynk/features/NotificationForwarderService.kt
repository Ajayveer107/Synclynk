package com.synclynk.features

import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.synclynk.net.SocketManager
import com.synclynk.net.SyncLynkState
import org.json.JSONObject

/**
 * Mirrors every incoming notification to the PC. Android requires the
 * user to grant "Notification access" manually once, in system settings
 * -- there's no programmatic way around that single permission screen,
 * by design, to stop apps from silently reading notifications.
 *
 * Once granted, no further login/setup is needed: notifications just
 * flow over the existing SocketManager connection.
 */
class NotificationForwarderService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (!SocketManager.isPaired) return
        if (!SyncLynkState.notificationsEnabled) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val appName = appLabelFor(sbn.packageName)

        SocketManager.send(JSONObject().apply {
            put("type", "notification")
            put("app", appName)
            put("title", title)
            put("text", text)
        })
        SyncLynkState.addHistory("Notification mirrored: $appName — $title")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Intentionally not mirrored -- keeps the protocol simple.
    }

    private fun appLabelFor(packageName: String): String {
        return try {
            val pm: PackageManager = applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
