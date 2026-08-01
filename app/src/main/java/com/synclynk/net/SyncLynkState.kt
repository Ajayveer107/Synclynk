package com.synclynk.net

import java.text.SimpleDateFormat
import java.util.*

/**
 * Shared, in-memory-only state for the whole app: current connection
 * info, a rolling activity history, and feature on/off toggles. Same
 * "no database" philosophy as the rest of SyncLynk -- this all resets
 * when the app process dies.
 */
object SyncLynkState {

    interface HistoryListener {
        fun onHistoryAppended(line: String)
    }

    interface ConnectionListener {
        fun onConnectionInfoChanged()
    }

    var pairedIp: String? = null
        private set
    var pairedPort: Int? = null
        private set

    var clipboardEnabled: Boolean = true
    var notificationsEnabled: Boolean = true

    private val history = ArrayList<String>()
    private const val MAX_HISTORY = 200
    private val historyListeners = mutableListOf<HistoryListener>()
    private val connectionListeners = mutableListOf<ConnectionListener>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setConnectionInfo(ip: String?, port: Int?) {
        pairedIp = ip
        pairedPort = port
        connectionListeners.forEach { it.onConnectionInfoChanged() }
    }

    fun addHistory(text: String) {
        val line = "[${timeFormat.format(Date())}] $text"
        synchronized(history) {
            history.add(line)
            if (history.size > MAX_HISTORY) history.removeAt(0)
        }
        historyListeners.forEach { it.onHistoryAppended(line) }
    }

    fun allHistory(): List<String> = synchronized(history) { history.toList() }

    fun addHistoryListener(l: HistoryListener) = historyListeners.add(l)
    fun removeHistoryListener(l: HistoryListener) = historyListeners.remove(l)

    fun addConnectionListener(l: ConnectionListener) = connectionListeners.add(l)
    fun removeConnectionListener(l: ConnectionListener) = connectionListeners.remove(l)
}
