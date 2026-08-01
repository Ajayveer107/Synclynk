package com.synclynk.ui.fragments

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.synclynk.R
import com.synclynk.net.FileTransferManager
import com.synclynk.net.SocketManager

class FilesFragment : Fragment(R.layout.fragment_files) {

    private lateinit var fileStatusText: TextView

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        if (!SocketManager.isPaired) {
            toast("Pair with a PC first from the Home tab.")
            return@registerForActivityResult
        }
        val name = displayNameFor(uri)
        fileStatusText.text = "Sending $name... (check History tab for progress)"
        FileTransferManager.sendFile(requireContext(), uri, name)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fileStatusText = view.findViewById(R.id.fileStatusText)
        view.findViewById<Button>(R.id.pickFileButton).setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun displayNameFor(uri: Uri): String {
        var name = "file"
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
        }
        return name
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
