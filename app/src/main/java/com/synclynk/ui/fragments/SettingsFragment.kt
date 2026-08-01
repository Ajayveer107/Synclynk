package com.synclynk.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import androidx.fragment.app.Fragment
import com.synclynk.R
import com.synclynk.net.SyncLynkState

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val clipboardSwitch = view.findViewById<Switch>(R.id.clipboardSwitch)
        val notificationsSwitch = view.findViewById<Switch>(R.id.notificationsSwitch)

        clipboardSwitch.isChecked = SyncLynkState.clipboardEnabled
        notificationsSwitch.isChecked = SyncLynkState.notificationsEnabled

        clipboardSwitch.setOnCheckedChangeListener { _, checked ->
            SyncLynkState.clipboardEnabled = checked
            SyncLynkState.addHistory("Clipboard sync ${if (checked) "enabled" else "disabled"}")
        }
        notificationsSwitch.setOnCheckedChangeListener { _, checked ->
            SyncLynkState.notificationsEnabled = checked
            SyncLynkState.addHistory("Notification mirroring ${if (checked) "enabled" else "disabled"}")
        }

        view.findViewById<Button>(R.id.notificationAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
}
