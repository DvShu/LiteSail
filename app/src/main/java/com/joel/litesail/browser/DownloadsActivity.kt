package com.joel.litesail.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.os.Build
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import com.joel.litesail.R
import com.joel.litesail.databinding.ActivityDownloadsBinding
import com.joel.litesail.databinding.ItemBrowserDownloadBinding
import com.joel.litesail.databinding.ItemListDateBinding
import com.joel.litesail.databinding.ViewDownloadActionsBinding
import com.joel.litesail.databinding.ViewDownloadClearBinding
import com.joel.litesail.databinding.ViewDownloadUrlBinding
import com.joel.litesail.ui.BottomSheetDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class DownloadsActivity : Activity() {
    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadAdapter
    private val downloads = BrowserDownloads(this)
    private val handler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var sheet: BottomSheetDialog? = null
    private var records = emptyList<BrowserDownloadRecord>()
    private var emptyMessage = R.string.download_history_loading
    private val poll = Runnable { refresh(checkFiles = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloads.restore(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= 30) {
            binding.root.setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 35) {
                    val areas = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                    val padding = insets.getInsets(areas)
                    view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                }
                binding.storageFooter.visibility = if (insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
                insets
            }
        }
        binding.downloadsBack.setOnClickListener { finish() }
        binding.downloadsMore.setOnClickListener { showActions() }
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
            downloads.openCompletedDownload(row.record.id)
            refresh()
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
    }

    override fun onDestroy() {
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
                renderList()
                renderStorage(storage)
                handler.removeCallbacks(poll)
                if (records.any(BrowserDownloadRecord::isActive)) handler.postDelayed(poll, 1_500L)
            }
        }
    }

    private fun renderList() {
        val query = binding.downloadSearch.text.toString().trim()
        val rows = downloadHistoryRows(records, query, getString(R.string.download_history_untitled))
        val message = if (records.isNotEmpty() && rows.isEmpty()) R.string.download_history_no_matches else emptyMessage
        binding.downloadListMessage.setText(message)
        adapter.replaceRows(rows)
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
        content.clearCompleted.setOnClickListener { confirmClear(completedOnly = true) }
        content.clearAll.isEnabled = records.isNotEmpty()
        content.clearAll.setOnClickListener { confirmClear(completedOnly = false) }
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

    private fun confirmClear(completedOnly: Boolean) {
        val title = if (completedOnly) R.string.download_history_clear_completed else R.string.download_history_clear_all
        val dialog = BottomSheetDialog(this, title)
        dialog.setBody(ViewDownloadClearBinding.inflate(dialog.layoutInflater).root)
        dialog.addAction(R.string.cancel)
        dialog.addAction(R.string.clear_search_history) {
            val removed = if (completedOnly) records.filter(BrowserDownloadRecord::isComplete) else records
            downloads.clearHistory(removed)
            records = downloads.visibleHistory(records)
            renderList()
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

        fun replaceRows(newRows: List<DownloadHistoryRow>) {
            if (rows == newRows) return
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
            return record.isComplete && !record.fileMissing
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
                item.root
            }
        }
    }

    private data class StorageUsage(val total: Long, val available: Long)
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
