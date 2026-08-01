package com.synclynk.net

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * One WebSocket connection to the paired PC for the lifetime of the app.
 * No accounts, no server backend -- just ws://<pc-ip>:<port> on the LAN,
 * gated by the one-time pairing token scanned from the QR code.
 *
 * Every feature (clipboard, notifications, camera) sends/receives through
 * this single shared socket, multiplexed by the "type" field in each
 * JSON message.
 */
object SocketManager {

    interface Listener {
        fun onPaired()
        fun onPairRejected()
        fun onDisconnected()
        fun onMessage(type: String, json: JSONObject)
    }

    private var webSocket: WebSocket? = null
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming connection, no read timeout
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    var isPaired: Boolean = false
        private set

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    /** ip/port/token come straight from the decoded QR payload. */
    fun connect(ip: String, port: Int, token: String) {
        disconnect()
        val request = Request.Builder().url("ws://$ip:$port").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                val handshake = JSONObject().apply {
                    put("type", "pair")
                    put("token", token)
                }
                ws.send(handshake.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = json.optString("type")

                if (type == "pair_result") {
                    isPaired = json.optBoolean("ok", false)
                    listeners.forEach { if (isPaired) it.onPaired() else it.onPairRejected() }
                    return
                }
                listeners.forEach { it.onMessage(type, json) }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isPaired = false
                listeners.forEach { it.onDisconnected() }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e("SyncLynk", "Socket failure: ${t.message}")
                isPaired = false
                listeners.forEach { it.onDisconnected() }
            }
        })
    }

    fun send(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
        isPaired = false
    }
}
