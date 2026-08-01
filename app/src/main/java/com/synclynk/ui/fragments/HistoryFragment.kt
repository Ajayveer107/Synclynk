package com.synclynk.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.fragment.app.Fragment
import com.synclynk.R
import com.synclynk.net.SyncLynkState

class HistoryFragment : Fragment(R.layout.fragment_history), SyncLynkState.HistoryListener {

    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView = view.findViewById(R.id.historyListView)

        adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            SyncLynkState.allHistory().toMutableList()
        )
        listView.adapter = adapter

        SyncLynkState.addHistoryListener(this)
    }

    override fun onHistoryAppended(line: String) {
        activity?.runOnUiThread {
            adapter.add(line)
            listView.setSelection(adapter.count - 1)
        }
    }

    override fun onDestroyView() {
        SyncLynkState.removeHistoryListener(this)
        super.onDestroyView()
    }
}
