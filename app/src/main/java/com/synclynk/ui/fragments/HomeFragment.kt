package com.synclynk.ui.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.synclynk.R
import com.synclynk.net.PairingUri
import com.synclynk.net.SocketManager
import com.synclynk.net.SyncForegroundService
import com.synclynk.ui.CameraStreamActivity
import org.json.JSONObject

class HomeFragment : Fragment(R.layout.fragment_home), SocketManager.Listener {

    private lateinit var statusText: TextView
    private var scannerLaunching = false

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult
        val info = PairingUri.parse(raw)
        if (info == null) {
            toast("That QR code isn't a SyncLynk pairing code.")
            return@registerForActivityResult
        }
        statusText.text = getString(R.string.status_connecting, info.ip)
        SyncForegroundService.start(requireContext(), info.ip, info.port, info.token)
    }

    private val scanPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchScanner()
        } else {
            toast(
                "Camera permission is required to scan. If you previously denied it, " +
                    "enable it manually: Settings > Apps > SyncLynk > Permissions > Camera."
            )
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraMirror() else toast("Camera permission is required to mirror the camera.")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusText = view.findViewById(R.id.statusText)

        view.findViewById<Button>(R.id.scanButton).setOnClickListener { requestScanPermissionAndLaunch() }
        view.findViewById<Button>(R.id.cameraMirrorButton).setOnClickListener { requestCameraAndStart() }

        SocketManager.addListener(this)
        if (SocketManager.isPaired) statusText.text = getString(R.string.status_paired)
    }

    private fun requestScanPermissionAndLaunch() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchScanner()
        } else {
            scanPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        if (scannerLaunching) return
        scannerLaunching = true
        val options = ScanOptions().apply {
            setPrompt("Scan the QR code shown in the SyncLynk Windows app")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        qrLauncher.launch(options)
        view?.postDelayed({ scannerLaunching = false }, 800)
    }

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraMirror()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraMirror() {
        if (!SocketManager.isPaired) {
            toast("Pair with a PC first by scanning its QR code.")
            return
        }
        startActivity(Intent(requireContext(), CameraStreamActivity::class.java))
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onPaired() {
        activity?.runOnUiThread { statusText.text = getString(R.string.status_paired) }
    }

    override fun onPairRejected() {
        activity?.runOnUiThread { statusText.text = getString(R.string.status_rejected) }
    }

    override fun onDisconnected() {
        activity?.runOnUiThread { statusText.text = getString(R.string.status_disconnected) }
    }

    override fun onMessage(type: String, json: JSONObject) {
        // Clipboard/file/notification handling lives in SyncForegroundService
        // so it keeps working while this fragment isn't visible.
    }

    override fun onDestroyView() {
        SocketManager.removeListener(this)
        super.onDestroyView()
    }
}
