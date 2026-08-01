package com.synclynk.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.synclynk.R
import com.synclynk.net.SocketManager
import com.synclynk.net.SyncLynkState

class DevicesFragment : Fragment(R.layout.fragment_devices), SyncLynkState.ConnectionListener {

    private lateinit var deviceInfoText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        deviceInfoText = view.findViewById(R.id.deviceInfoText)

        view.findViewById<Button>(R.id.disconnectButton).setOnClickListener {
            SocketManager.disconnect()
            SyncLynkState.setConnectionInfo(null, null)
            refresh()
        }

        SyncLynkState.addConnectionListener(this)
        refresh()
    }

    private fun refresh() {
        val ip = SyncLynkState.pairedIp
        val port = SyncLynkState.pairedPort
        deviceInfoText.text = if (ip != null && port != null) {
            "Connected to PC\n$ip:$port\n\nClipboard, notifications, and file\ntransfer are active."
        } else {
            "No device paired yet.\nScan a QR code from the Home tab to connect."
        }
    }

    override fun onConnectionInfoChanged() {
        activity?.runOnUiThread { refresh() }
    }

    override fun onDestroyView() {
        SyncLynkState.removeConnectionListener(this)
        super.onDestroyView()
    }
}
