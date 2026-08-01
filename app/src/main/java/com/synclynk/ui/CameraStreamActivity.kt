package com.synclynk.ui

import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.synclynk.net.SocketManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Live camera mirroring: captures frames from the phone's camera and
 * streams them as JPEG-over-WebSocket to the PC, throttled to a modest
 * frame rate so it stays smooth on plain Wi-Fi without any special
 * codecs or WebRTC setup.
 */
class CameraStreamActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Simple frame-rate throttle -- send at most ~12 fps to keep the
    // socket + PC-side decode comfortably real time.
    private var lastSentAt = 0L
    private val minFrameIntervalMs = 1000L / 12

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this)
        setContentView(previewView)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy -> onFrame(imageProxy) }
            }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (e: Exception) {
            Log.e("SyncLynk", "Camera bind failed: ${e.message}")
        }
    }

    /** Switch between front/back camera -- wire this to a UI button as desired. */
    fun toggleLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCameraUseCases()
    }

    private fun onFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastSentAt < minFrameIntervalMs || !SocketManager.isPaired) {
            imageProxy.close()
            return
        }
        lastSentAt = now

        try {
            val jpegBytes = imageProxy.toJpegBytes(quality = 55)
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            SocketManager.send(JSONObject().apply {
                put("type", "camera_frame")
                put("data", b64)
            })
        } catch (e: Exception) {
            Log.e("SyncLynk", "Frame encode failed: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        SocketManager.send(JSONObject().apply { put("type", "camera_stop") })
        super.onDestroy()
    }
}

/**
 * Converts a CameraX YUV_420_888 ImageProxy to JPEG bytes.
 * Kept as an extension so it's easy to unit test / swap out later.
 */
private fun ImageProxy.toJpegBytes(quality: Int): ByteArray {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), quality, out)
    return out.toByteArray()
}
