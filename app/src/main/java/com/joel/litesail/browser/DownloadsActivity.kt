package com.joel.litesail.browser

import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.joel.litesail.R
import com.joel.litesail.databinding.ActivityDownloadsBinding
import com.joel.litesail.databinding.ItemBrowserDownloadBinding
import com.joel.litesail.databinding.ItemListDateBinding
import com.joel.litesail.databinding.ViewDownloadActionsBinding
import com.joel.litesail.databinding.ViewDownloadClearBinding
import com.joel.litesail.databinding.ViewDownloadSelectionMoreBinding
import com.joel.litesail.databinding.ViewDownloadUrlBinding
import com.joel.litesail.ui.AppearanceActivity
import com.joel.litesail.ui.BottomSheetDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class DownloadsActivity : AppearanceActivity() {
    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadAdapter
    private val downloads = BrowserDownloads(this)
    private val handler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var sheet: BottomSheetDialog? = null
    private var records = emptyList<BrowserDownloadRecord>()
    private var emptyMessage = R.string.download_history_loading
    private val selectedIds = mutableSetOf<Long>()
    private var isSelecting = false
    private val selectionBack = OnBackInvokedCallback { setSelectionMode(false) }
    private val poll = Runnable { refresh(checkFiles = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloads.restore(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val areas = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            val padding = insets.getInsets(areas)
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            updateStorageVisibility(insets)
            insets
        }
        binding.downloadsBack.setOnClickListener { finish() }
        binding.downloadsEdit.setOnClickListener { setSelectionMode(true) }
        binding.downloadsMore.setOnClickListener { showActions() }
        binding.downloadsSelectAll.setOnClickListener {
            if (binding.downloadsSelectAll.isChecked) {
                records.mapTo(selectedIds, BrowserDownloadRecord::id)
            } else {
                selectedIds.clear()
            }
            adapter.notifyDataSetChanged()
            renderSelection()
        }
        binding.downloadsSelectionDone.setOnClickListener { setSelectionMode(false) }
        binding.downloadsSelectionDelete.setOnClickListener {
            val selection = records.filter { it.id in selectedIds }
            confirmClear(R.string.download_history_delete_selected, R.string.download_history_delete) { selection }
        }
        binding.downloadsSelectionMore.setOnClickListener { showSelectionMore() }
        binding.downloadSearch.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            hideKeyboard()
            true
        }
        binding.downloadSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderList()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        adapter = DownloadAdapter()
        binding.downloadList.emptyView = binding.downloadListMessage
        binding.downloadList.adapter = adapter
        binding.downloadList.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.getItem(position) as? DownloadHistoryRow.Task ?: return@setOnItemClickListener
            if (isSelecting) {
                toggleSelection(row.record.id)
            } else {
                downloads.openCompletedDownload(row.record.id)
                refresh()
            }
        }
        savedInstanceState?.getLongArray(STATE_SELECTION)?.let { ids ->
            selectedIds.addAll(ids.asList())
            setSelectionMode(true)
        }
    }

    override fun onStart() {
        super.onStart()
        worker = Executors.newSingleThreadExecutor()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        worker?.shutdownNow()
        worker = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        downloads.save(outState)
        if (isSelecting) outState.putLongArray(STATE_SELECTION, selectedIds.toLongArray())
    }

    override fun onDestroy() {
        if (isSelecting) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(selectionBack)
        sheet?.dismiss()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        downloads.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        downloads.onRequestPermissionsResult(requestCode, grantResults)
        refresh()
    }

    private fun refresh(checkFiles: Boolean = true) {
        val activeWorker = worker ?: return
        handler.removeCallbacks(poll)
        val previous = if (checkFiles) emptyMap() else records.associateBy(BrowserDownloadRecord::id)
        activeWorker.execute {
            val result = downloads.queryHistory()?.map { record ->
                if (!record.isComplete) return@map record
                val known = previous[record.id]?.takeIf { it.isComplete && it.updatedAt == record.updatedAt }
                record.copy(fileMissing = known?.fileMissing ?: downloads.isFileMissing(record.id))
            }
            val storage = readStorage()
            handler.post {
                if (worker !== activeWorker) return@post
                emptyMessage = if (result == null) R.string.download_history_unavailable else R.string.download_history_empty
                records = downloads.visibleHistory(result.orEmpty())
                if (isSelecting && result != null) selectedIds.retainAll(records.mapTo(HashSet(), BrowserDownloadRecord::id))
                renderList()
                renderStorage(storage)
                handler.removeCallbacks(poll)
                if (records.any(BrowserDownloadRecord::isActive)) handler.postDelayed(poll, 1_500L)
            }
        }
    }

    private fun renderList(rebindRows: Boolean = false) {
        val query = if (isSelecting) "" else binding.downloadSearch.text.toString().trim()
        val rows = downloadHistoryRows(records, query, getString(R.string.download_history_untitled))
        val message = if (records.isNotEmpty() && rows.isEmpty()) R.string.download_history_no_matches else emptyMessage
        binding.downloadListMessage.setText(message)
        adapter.replaceRows(rows, rebindRows)
        if (isSelecting) renderSelection()
    }

    private fun setSelectionMode(enabled: Boolean) {
        if (isSelecting == enabled) return
        isSelecting = enabled
        if (enabled) {
            hideKeyboard()
            binding.downloadSearch.clearFocus()
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, selectionBack)
        } else {
            selectedIds.clear()
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(selectionBack)
        }
        val normalVisibility = if (enabled) View.GONE else View.VISIBLE
        binding.downloadsHeader.visibility = normalVisibility
        binding.downloadsSearchBar.visibility = normalVisibility
        val selectionVisibility = if (enabled) View.VISIBLE else View.GONE
        binding.downloadsSelectionHeader.visibility = selectionVisibility
        binding.downloadsSelectionActions.visibility = selectionVisibility
        if (enabled) {
            binding.downloadsSelectionActions.startAnimation(AnimationUtils.loadAnimation(this, R.anim.bottom_sheet_enter))
        } else {
            binding.downloadsSelectionActions.clearAnimation()
        }
        updateStorageVisibility(binding.root.rootWindowInsets)
        renderList(rebindRows = true)
    }

    private fun toggleSelection(id: Long) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        adapter.notifyDataSetChanged()
        renderSelection()
    }

    private fun renderSelection() {
        binding.downloadsSelectedCount.text = getString(R.string.download_history_selected_count, selectedIds.size)
        val hasRecords = records.isNotEmpty()
        val allSelected = hasRecords && selectedIds.size == records.size
        binding.downloadsSelectAll.isEnabled = hasRecords
        binding.downloadsSelectAll.isChecked = allSelected
        binding.downloadsSelectAll.contentDescription = getString(
            if (allSelected) R.string.download_history_deselect_all else R.string.download_history_select_all
        )
        val hasSelection = hasRecords && selectedIds.isNotEmpty()
        binding.downloadsSelectionDelete.isEnabled = hasSelection
        binding.downloadsSelectionMore.isEnabled = hasSelection
    }

    private fun updateStorageVisibility(insets: WindowInsets?) {
        val showStorage = !isSelecting && insets?.isVisible(WindowInsets.Type.ime()) != true
        binding.storageFooter.visibility = if (showStorage) View.VISIBLE else View.GONE
    }

    private fun readStorage(): StorageUsage? = try {
        // The primary app-specific directory is on the same volume as the public downloads directory.
        getExternalFilesDir(null)?.let { directory ->
            val stats = StatFs(directory.path)
            if (stats.totalBytes > 0) StorageUsage(stats.totalBytes, stats.availableBytes) else null
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun renderStorage(storage: StorageUsage?) {
        val visible = if (storage == null) View.GONE else View.VISIBLE
        binding.storageProgress.visibility = visible
        binding.storageAmounts.visibility = visible
        if (storage == null) return
        val available = storage.available.coerceIn(0, storage.total)
        binding.storageProgress.progress = (100.0 * (storage.total - available) / storage.total).roundToInt()
        binding.storageTotal.text = getString(R.string.download_storage_total, Formatter.formatFileSize(this, storage.total))
        binding.storageFree.text = getString(R.string.download_storage_free, Formatter.formatFileSize(this, available))
    }

    private fun showActions() {
        hideKeyboard()
        val dialog = BottomSheetDialog(this, R.string.download_history_actions)
        val content = ViewDownloadActionsBinding.inflate(dialog.layoutInflater)
        content.newDownload.setOnClickListener { showNewDownload() }
        content.clearCompleted.isEnabled = records.any(BrowserDownloadRecord::isComplete)
        content.clearCompleted.setOnClickListener {
            confirmClear(R.string.download_history_clear_completed) { records.filter(BrowserDownloadRecord::isComplete) }
        }
        content.clearAll.isEnabled = records.isNotEmpty()
        content.clearAll.setOnClickListener { confirmClear(R.string.download_history_clear_all) { records } }
        content.openSystemDownloads.setOnClickListener {
            dialog.dismiss()
            downloads.showSystemDownloads()
        }
        dialog.setBody(content.root)
        showSheet(dialog)
    }

    private fun showSelectionMore() {
        val selectedId = selectedIds.singleOrNull()
        val file = records.firstOrNull { it.id == selectedId && it.isComplete && !it.fileMissing }
        val dialog = BottomSheetDialog(this, R.string.download_history_more)
        val content = ViewDownloadSelectionMoreBinding.inflate(dialog.layoutInflater)
        content.openSelectedDownload.isEnabled = file != null
        if (file != null) {
            content.openSelectedDownload.setOnClickListener {
                dialog.dismiss()
                downloads.openCompletedDownload(file.id)
                refresh()
            }
        }
        content.openSystemDownloads.setOnClickListener {
            dialog.dismiss()
            downloads.showSystemDownloads()
        }
        dialog.setBody(content.root)
        showSheet(dialog)
    }

    private fun showNewDownload() {
        val dialog = BottomSheetDialog(this, R.string.download_history_new)
        val content = ViewDownloadUrlBinding.inflate(dialog.layoutInflater)
        val address = content.downloadUrl
        fun submit() {
            val url = BrowserAddress.scannedWebUrl(address.text.toString())
            if (url == null) {
                address.error = getString(R.string.download_history_invalid_url)
                return
            }
            dialog.dismiss()
            downloads.start(url, null, null, null)
            refresh()
        }
        address.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            submit()
            true
        }
        dialog.setBody(content.root)
        dialog.addAction(R.string.cancel)
        dialog.addAction(R.string.download_history_start, dismissOnClick = false, onClick = ::submit)
        address.requestFocus()
        showSheet(dialog)
    }

    private fun confirmClear(
        title: Int,
        action: Int = R.string.clear_search_history,
        recordsToClear: () -> List<BrowserDownloadRecord>
    ) {
        val dialog = BottomSheetDialog(this, title)
        dialog.setBody(ViewDownloadClearBinding.inflate(dialog.layoutInflater).root)
        dialog.addAction(R.string.cancel)
        dialog.addAction(action) {
            downloads.clearHistory(recordsToClear())
            records = downloads.visibleHistory(records)
            if (isSelecting) setSelectionMode(false) else renderList()
            refresh(checkFiles = false)
        }
        showSheet(dialog)
    }

    private fun showSheet(dialog: BottomSheetDialog) {
        sheet?.dismiss()
        sheet = dialog
        dialog.setOnDismissListener { if (sheet === dialog) sheet = null }
        dialog.show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun statusText(record: BrowserDownloadRecord): String {
        if (record.fileMissing) return getString(R.string.download_history_file_missing)
        val label = getString(when (record.status) {
            DownloadManager.STATUS_PENDING -> R.string.download_history_pending
            DownloadManager.STATUS_RUNNING -> R.string.download_history_running
            DownloadManager.STATUS_PAUSED -> R.string.download_history_paused
            DownloadManager.STATUS_SUCCESSFUL -> R.string.download_history_complete
            else -> R.string.download_history_failed
        })
        val detail = when {
            record.isComplete && record.totalBytes >= 0L -> Formatter.formatFileSize(this, record.totalBytes)
            record.isActive && record.totalBytes > 0L && record.downloadedBytes >= 0L -> {
                val percent = (100.0 * record.downloadedBytes / record.totalBytes).toInt().coerceIn(0, 100)
                getString(R.string.download_history_percent, percent)
            }
            else -> return label
        }
        return getString(R.string.download_history_detail, label, detail)
    }

    private inner class DownloadAdapter : BaseAdapter() {
        private var rows = emptyList<DownloadHistoryRow>()
        private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(resources.configuration.locales[0])

        fun replaceRows(newRows: List<DownloadHistoryRow>, rebind: Boolean) {
            if (!rebind && rows == newRows) return
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun hasStableIds() = true
        override fun getItemId(position: Int) = when (val row = getItem(position)) {
            is DownloadHistoryRow.Day -> -row.date.toEpochDay() - 2
            is DownloadHistoryRow.Task -> row.record.id
        }
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (getItem(position) is DownloadHistoryRow.Day) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int): Boolean {
            val record = (rows.getOrNull(position) as? DownloadHistoryRow.Task)?.record ?: return false
            return isSelecting || (record.isComplete && !record.fileMissing)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = getItem(position)) {
            is DownloadHistoryRow.Day -> {
                val item = convertView?.let(ItemListDateBinding::bind)
                    ?: ItemListDateBinding.inflate(layoutInflater, parent, false)
                item.root.text = dateFormat.format(row.date)
                item.root
            }
            is DownloadHistoryRow.Task -> {
                val item = convertView?.let(ItemBrowserDownloadBinding::bind)
                    ?: ItemBrowserDownloadBinding.inflate(layoutInflater, parent, false)
                item.downloadTitle.text = row.record.title?.takeIf(String::isNotBlank)
                    ?: getString(R.string.download_history_untitled)
                item.downloadDetails.text = statusText(row.record)
                item.downloadCheckBox.visibility = if (isSelecting) View.VISIBLE else View.GONE
                if (isSelecting) {
                    item.downloadCheckBox.isChecked = row.record.id in selectedIds
                    item.downloadCheckBox.contentDescription = item.downloadTitle.text
                    item.downloadCheckBox.setOnClickListener { toggleSelection(row.record.id) }
                }
                item.root
            }
        }
    }

    private data class StorageUsage(val total: Long, val available: Long)

    private companion object {
        const val STATE_SELECTION = "download_selection"
    }
}

internal sealed interface DownloadHistoryRow {
    data class Day(val date: LocalDate) : DownloadHistoryRow
    data class Task(val record: BrowserDownloadRecord) : DownloadHistoryRow
}

internal fun downloadHistoryRows(
    records: List<BrowserDownloadRecord>, query: String, untitled: String, zone: ZoneId = ZoneId.systemDefault()
): List<DownloadHistoryRow> = buildList {
    val matches = records.filter { (it.title?.takeIf(String::isNotBlank) ?: untitled).contains(query, ignoreCase = true) }
    var previousDay: LocalDate? = null
    for (record in matches.sortedByDescending(BrowserDownloadRecord::updatedAt)) {
        val day = record.updatedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        if (day != null && day != previousDay) add(DownloadHistoryRow.Day(day))
        add(DownloadHistoryRow.Task(record))
        previousDay = day
    }
}
