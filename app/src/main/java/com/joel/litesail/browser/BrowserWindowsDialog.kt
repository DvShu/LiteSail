package com.joel.litesail.browser

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.joel.litesail.R
import com.joel.litesail.databinding.ItemBrowserWindowBinding
import com.joel.litesail.databinding.ViewBrowserWindowsBinding
import com.joel.litesail.ui.BottomSheetDialog

internal class BrowserWindowsDialog(
    context: Context,
    private val windows: BrowserWindows,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit,
    private val onAdd: () -> Unit,
    incognito: Boolean = false
) : BottomSheetDialog(context, if (incognito) R.string.browser_incognito_windows else R.string.browser_window_manager) {
    private lateinit var binding: ViewBrowserWindowsBinding

    private val adapter = object : BaseAdapter() {
        override fun getCount() = windows.items.size
        override fun getItem(position: Int) = windows.items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let(ItemBrowserWindowBinding::bind)
                ?: ItemBrowserWindowBinding.inflate(layoutInflater, parent, false)
                    .also { it.root.clipToOutline = true }
            val window = getItem(position)
            val selected = position == windows.selectedIndex
            val title = window.title?.takeIf { !window.home && it.isNotBlank() } ?: if (window.home) {
                context.getString(R.string.home)
            } else {
                window.url ?: context.getString(R.string.browser_window_untitled)
            }
            row.windowTitle.text = title
            row.windowAddress.text = window.url.orEmpty()
            row.windowAddress.visibility = if (!window.home && window.url != null && title != window.url) {
                View.VISIBLE
            } else {
                View.GONE
            }
            row.root.isActivated = selected
            row.windowDetails.isSelected = selected
            row.windowDetails.setOnClickListener {
                val index = windows.items.indexOf(window)
                if (index < 0) return@setOnClickListener
                dismiss()
                onSelect(index)
            }
            row.closeWindow.contentDescription = context.getString(R.string.browser_window_close_named, title)
            row.closeWindow.setOnClickListener {
                val index = windows.items.indexOf(window)
                if (index < 0) return@setOnClickListener
                onClose(index)
                refresh()
            }
            return row.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewBrowserWindowsBinding.inflate(layoutInflater)
        setBody(binding.root)
        binding.windowList.adapter = adapter
        binding.addWindow.setOnClickListener {
            dismiss()
            onAdd()
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
        binding.windowList.setSelection(windows.selectedIndex)
    }

    private fun refresh() {
        val count = windows.items.size
        setHeaderDetail(context.getString(R.string.browser_window_count_limit, count, BrowserWindows.LIMIT))
        binding.addWindow.isEnabled = count < BrowserWindows.LIMIT
        binding.addWindow.alpha = if (binding.addWindow.isEnabled) 1f else 0.5f
        adapter.notifyDataSetChanged()
    }
}
