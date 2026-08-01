package com.synclynk.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Chunked file transfer over the existing SocketManager connection.
 * Sending reads a picked-file Uri in chunks and streams them out as
 * base64 JSON messages. Receiving buffers chunks to a temp file in the
 * app's cache dir, then moves the completed file into the public
 * Downloads/SyncLynk folder via MediaStore -- no extra storage
 * permission needed on modern Android.
 */
object FileTransferManager {

    private const val CHUNK_SIZE = 48 * 1024 // raw bytes per chunk

    private data class Incoming(val name: String, val tempFile: File, var stream: FileOutputStream)

    private val incoming = HashMap<String, Incoming>()

    fun sendFile(context: Context, uri: Uri, displayName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val fileId = UUID.randomUUID().toString().take(8)
            val resolver = context.contentResolver
            val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L

            SocketManager.send(JSONObject().apply {
                put("type", "file_start")
                put("id", fileId)
                put("name", displayName)
                put("size", size)
            })
            SyncLynkState.addHistory("Sending $displayName...")

            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                var seq = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val chunkBytes = if (read == buffer.size) buffer else buffer.copyOf(read)
                    val b64 = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)
                    SocketManager.send(JSONObject().apply {
                        put("type", "file_chunk")
                        put("id", fileId)
                        put("seq", seq)
                        put("data", b64)
                    })
                    seq++
                }
            }

            SocketManager.send(JSONObject().apply {
                put("type", "file_end")
                put("id", fileId)
            })
            SyncLynkState.addHistory("Sent $displayName.")
        }
    }

    /** Call from SocketManager.Listener.onMessage for file_start/file_chunk/file_end. */
    fun handleMessage(context: Context, type: String, json: JSONObject) {
        when (type) {
            "file_start" -> {
                val id = json.optString("id")
                val name = json.optString("name", "file_$id")
                val safeName = name.replace("/", "_").replace("..", "_")
                val tempFile = File(context.cacheDir, "$id.part")
                incoming[id] = Incoming(safeName, tempFile, FileOutputStream(tempFile))
                SyncLynkState.addHistory("Receiving $safeName...")
            }
            "file_chunk" -> {
                val id = json.optString("id")
                val entry = incoming[id] ?: return
                val bytes = Base64.decode(json.optString("data"), Base64.NO_WRAP)
                entry.stream.write(bytes)
            }
            "file_end" -> {
                val id = json.optString("id")
                val entry = incoming.remove(id) ?: return
                entry.stream.flush()
                entry.stream.close()
                saveToDownloads(context, entry.tempFile, entry.name)
                entry.tempFile.delete()
                SyncLynkState.addHistory("Received ${entry.name} -> Downloads/SyncLynk")
            }
        }
    }

    private fun saveToDownloads(context: Context, source: File, displayName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SyncLynk")
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(itemUri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
        } else {
            // Pre-Android 10: no scoped-storage MediaStore.Downloads collection.
            // Requires WRITE_EXTERNAL_STORAGE, requested at runtime by the caller.
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SyncLynk")
            dir.mkdirs()
            val dest = File(dir, displayName)
            source.inputStream().use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
        }
    }
}
