package com.joel.litesail.browser

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.joel.litesail.R
import com.joel.litesail.databinding.ItemBrowserWindowAddBinding
import com.joel.litesail.databinding.ItemBrowserWindowBinding
import com.joel.litesail.databinding.ViewBrowserWindowsBinding

internal class BrowserWindowManager(
    private val binding: ViewBrowserWindowsBinding,
    onBack: () -> Unit,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit,
    private val onAdd: () -> Unit,
    onCloseAll: () -> Unit,
    onModeChange: (Boolean) -> Unit
) {
    private val context = binding.root.context
    private val inflater = LayoutInflater.from(context)
    private var windows: BrowserWindows? = null
    val isShowing get() = binding.root.visibility == View.VISIBLE

    private val adapter = object : BaseAdapter() {
        override fun getCount() = (windows?.items?.size ?: 0) + 1
        override fun getItem(position: Int) = windows?.items?.getOrNull(position - 1)
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (position == 0) {
                val row = convertView?.let(ItemBrowserWindowAddBinding::bind)
                    ?: ItemBrowserWindowAddBinding.inflate(inflater, parent, false)
                row.root.setOnClickListener { onAdd() }
                val canAdd = (windows?.items?.size ?: 0) < BrowserWindows.LIMIT
                row.root.isEnabled = canAdd
                row.root.alpha = if (canAdd) 1f else 0.5f
                return row.root
            }

            val window = checkNotNull(getItem(position))
            val row = convertView?.let(ItemBrowserWindowBinding::bind)
                ?: ItemBrowserWindowBinding.inflate(inflater, parent, false).also { it.root.clipToOutline = true }
            bindWindow(row, window)
            return row.root
        }
    }

    init {
        binding.windowManagerBack.setOnClickListener { onBack() }
        binding.closeAllWindows.setOnClickListener { onCloseAll() }
        binding.windowModeRegular.setOnClickListener { onModeChange(false) }
        binding.windowModeIncognito.setOnClickListener { onModeChange(true) }
    }

    fun show() {
        binding.root.visibility = View.VISIBLE
        binding.root.requestFocus()
    }

    fun hide() {
        binding.root.visibility = View.GONE
        binding.windowGrid.adapter = null
        windows = null
    }

    fun render(windows: BrowserWindows, regularCount: Int, incognito: Boolean, canUseIncognito: Boolean) {
        val modeChanged = this.windows !== windows
        this.windows = windows
        binding.windowManagerTitle.setText(if (incognito) R.string.browser_incognito_windows else R.string.browser_windows_page)
        binding.windowModeRegular.text = context.getString(R.string.browser_window_mode_regular, regularCount)
        binding.windowModeIncognito.isEnabled = canUseIncognito
        binding.windowModes.check(if (incognito) R.id.window_mode_incognito else R.id.window_mode_regular)
        binding.closeAllWindows.isEnabled = incognito || windows.items.size > 1 || windows.selected.url != null
        if (modeChanged) {
            binding.windowGrid.adapter = adapter
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    private fun bindWindow(row: ItemBrowserWindowBinding, window: BrowserWindows.Window) {
        val title = if (window.home) context.getString(R.string.home) else {
            window.title?.takeIf(String::isNotBlank) ?: window.url ?: context.getString(R.string.browser_window_untitled)
        }
        row.windowTitle.text = title
        row.root.isActivated = window === windows?.selected
        row.root.setOnClickListener {
            val index = windows?.items?.indexOf(window) ?: -1
            if (index >= 0) onSelect(index)
        }
        row.closeWindow.contentDescription = context.getString(R.string.browser_window_close_named, title)
        row.closeWindow.setOnClickListener {
            val index = windows?.items?.indexOf(window) ?: -1
            if (index >= 0) onClose(index)
        }

        val fallbackIcon = if (window.home) R.drawable.ic_toolbar_home else R.drawable.ic_toolbar_window
        val favicon = window.favicon.takeUnless { window.home }
        if (favicon != null) row.windowFavicon.setImageBitmap(favicon) else row.windowFavicon.setImageResource(fallbackIcon)

        val preview = window.preview.takeUnless { window.home }
        row.windowSnapshot.setImageBitmap(preview)
        row.windowSnapshot.visibility = if (preview != null) View.VISIBLE else View.GONE
        row.windowPreviewPlaceholder.visibility = if (preview == null) View.VISIBLE else View.GONE
        if (preview != null) return

        row.windowPreview.setBackgroundResource(R.drawable.start_page_surface)
        if (window.home) {
            row.windowPreviewIcon.setImageResource(R.mipmap.ic_launcher)
            row.windowPreviewLabel.setText(R.string.start_search_hint)
            row.windowPreviewLabel.setBackgroundResource(R.drawable.library_site_badge)
        } else {
            if (favicon != null) row.windowPreviewIcon.setImageBitmap(favicon) else row.windowPreviewIcon.setImageResource(fallbackIcon)
            row.windowPreviewLabel.text = window.url?.let { Uri.parse(it).host } ?: title
            row.windowPreviewLabel.background = null
        }
    }
}
