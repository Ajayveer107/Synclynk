package com.synclynk.net

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

/**
 * Keeps the WebSocket alive and mirrors the clipboard in both directions
 * while the app is backgrounded. Runs as a foreground service so Android
 * doesn't kill the socket a few seconds after the user leaves the app.
 * Also dispatches incoming file-transfer messages to FileTransferManager.
 */
class SyncForegroundService : Service(), SocketManager.Listener {

    companion object {
        const val CHANNEL_ID = "synclynk_sync"
        const val NOTIF_ID = 1001
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"

        fun start(context: Context, ip: String, port: Int, token: String) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_TOKEN, token)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var clipboardManager: ClipboardManager
    private var lastClip: String? = null
    private var suppressNextClipBroadcast = false

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        createNotificationChannel()
        SocketManager.addListener(this)

        clipboardManager.addPrimaryClipChangedListener {
            if (!SyncLynkState.clipboardEnabled) return@addPrimaryClipChangedListener
            val text = currentClipText() ?: return@addPrimaryClipChangedListener
            if (text == lastClip) return@addPrimaryClipChangedListener
            lastClip = text
            if (suppressNextClipBroadcast) {
                suppressNextClipBroadcast = false
                return@addPrimaryClipChangedListener
            }
            SocketManager.send(JSONObject().apply {
                put("type", "clipboard")
                put("data", text)
            })
            SyncLynkState.addHistory("Clipboard sent to PC: \"${text.take(40)}\"")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Connecting..."))
        intent?.let {
            val ip = it.getStringExtra(EXTRA_IP)
            val port = it.getIntExtra(EXTRA_PORT, -1)
            val token = it.getStringExtra(EXTRA_TOKEN)
            if (ip != null && port > 0 && token != null) {
                SyncLynkState.setConnectionInfo(ip, port)
                SocketManager.connect(ip, port, token)
            }
        }
        return START_STICKY
    }

    private fun currentClipText(): String? {
        val clip: ClipData? = clipboardManager.primaryClip
        if (clip == null || clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    // ---- SocketManager.Listener --------------------------------------

    override fun onPaired() {
        updateNotification("Connected to PC — syncing")
        SyncLynkState.addHistory("Paired with PC at ${SyncLynkState.pairedIp}")
    }

    override fun onPairRejected() {
        updateNotification("Pairing rejected — rescan the QR code")
        SyncLynkState.addHistory("Pairing rejected by PC (stale or wrong QR code).")
    }

    override fun onDisconnected() {
        updateNotification("Disconnected — reopen SyncLynk to reconnect")
        SyncLynkState.addHistory("Disconnected from PC.")
    }

    override fun onMessage(type: String, json: JSONObject) {
        when {
            type == "clipboard" -> {
                if (!SyncLynkState.clipboardEnabled) return
                val text = json.optString("data")
                if (text.isNotEmpty() && text != lastClip) {
                    suppressNextClipBroadcast = true
                    lastClip = text
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("synclynk", text))
                    SyncLynkState.addHistory("Clipboard from PC: \"${text.take(40)}\"")
                }
            }
            type.startsWith("file_") -> FileTransferManager.handleMessage(applicationContext, type, json)
        }
    }

    // ---- notification plumbing ----------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SyncLynk sync status", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncLynk")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        SocketManager.removeListener(this)
        SocketManager.disconnect()
        SyncLynkState.setConnectionInfo(null, null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
