package com.joel.litesail.search

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.joel.litesail.R
import com.joel.litesail.databinding.ItemSearchHistoryBinding

internal class SearchHistoryAdapter(
    context: Context,
    private val onOpen: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : ArrayAdapter<String>(context, R.layout.item_search_history) {
    private val inflater = LayoutInflater.from(context)

    fun replaceEntries(entries: List<String>) {
        setNotifyOnChange(false)
        clear()
        addAll(entries)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = convertView?.let(ItemSearchHistoryBinding::bind)
            ?: ItemSearchHistoryBinding.inflate(inflater, parent, false)
        val entry = requireNotNull(getItem(position))
        binding.historyQuery.text = entry
        binding.historyQuery.setOnClickListener { onOpen(entry) }
        binding.deleteEntry.contentDescription = context.getString(R.string.delete_search_history_entry, entry)
        binding.deleteEntry.setOnClickListener { onDelete(entry) }
        return binding.root
    }
}
