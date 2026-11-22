package com.joel.litesail.browser

import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import com.joel.litesail.R
import com.joel.litesail.databinding.ItemBrowserDownloadBinding
import com.joel.litesail.databinding.ViewBrowserDownloadHistoryBinding
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class BrowserDownloadHistoryDialog(
    context: Context,
    private val queryHistory: () -> List<BrowserDownloadRecord>?,
    private val onOpen: (Long) -> Unit,
    private val onSystemDownloads: () -> Unit
) : Dialog(context, R.style.Theme_LiteSail_BrowserMenu) {
    private lateinit var binding: ViewBrowserDownloadHistoryBinding
    private var records: List<BrowserDownloadRecord> = emptyList()
    private var worker: ExecutorService? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { refresh() }
    private val dateFormat = DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT, context.resources.configuration.locales[0]
    )

    private val adapter = object : BaseAdapter() {
        override fun getCount() = records.size
        override fun getItem(position: Int) = records[position]
        override fun getItemId(position: Int) = getItem(position).id
        override fun hasStableIds() = true
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = records.getOrNull(position)?.isComplete == true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let(ItemBrowserDownloadBinding::bind)
                ?: ItemBrowserDownloadBinding.inflate(layoutInflater, parent, false)
            val record = getItem(position)
            row.downloadTitle.text = record.title?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.download_history_untitled)
            val status = statusText(record)
            row.downloadDetails.text = if (record.updatedAt > 0L) {
                context.getString(R.string.download_history_detail, status, dateFormat.format(Date(record.updatedAt)))
            } else {
                status
            }
            return row.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewBrowserDownloadHistoryBinding.inflate(layoutInflater)
        setTitle(R.string.browser_menu_downloads)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        binding.downloadList.emptyView = binding.downloadListMessage
        binding.downloadList.adapter = adapter
        binding.downloadList.setOnItemClickListener { _, _, position, _ ->
            records.getOrNull(position)?.takeIf { it.isComplete }?.let { onOpen(it.id) }
        }
        binding.closeDownloads.setOnClickListener { dismiss() }
        binding.systemDownloads.setOnClickListener {
            dismiss()
            onSystemDownloads()
        }
        window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isShowing) {
            if (worker == null) {
                worker = Executors.newSingleThreadExecutor()
                refresh()
            }
        } else {
            stopRefreshing()
        }
    }

    override fun onStop() {
        stopRefreshing()
        super.onStop()
    }

    private fun refresh() {
        val activeWorker = worker ?: return
        activeWorker.execute {
            val result = queryHistory()
            handler.post {
                if (worker !== activeWorker) return@post
                binding.downloadListMessage.setText(
                    if (result == null) R.string.download_history_unavailable else R.string.download_history_empty
                )
                val updated = result.orEmpty()
                if (updated != records) {
                    records = updated
                    adapter.notifyDataSetChanged()
                }
                if (records.any(BrowserDownloadRecord::isActive)) handler.postDelayed(poll, 1_500L)
            }
        }
    }

    private fun stopRefreshing() {
        handler.removeCallbacks(poll)
        worker?.shutdownNow()
        worker = null
    }

    private fun statusText(record: BrowserDownloadRecord): String {
        val label = context.getString(when (record.status) {
            DownloadManager.STATUS_PENDING -> R.string.download_history_pending
            DownloadManager.STATUS_RUNNING -> R.string.download_history_running
            DownloadManager.STATUS_PAUSED -> R.string.download_history_paused
            DownloadManager.STATUS_SUCCESSFUL -> R.string.download_history_complete
            else -> R.string.download_history_failed
        })
        val detail = when {
            record.isComplete && record.totalBytes >= 0L -> Formatter.formatFileSize(context, record.totalBytes)
            record.isActive && record.totalBytes > 0L && record.downloadedBytes >= 0L -> {
                val percent = (100.0 * record.downloadedBytes / record.totalBytes).toInt().coerceIn(0, 100)
                context.getString(R.string.download_history_percent, percent)
            }
            else -> return label
        }
        return context.getString(R.string.download_history_detail, label, detail)
    }
}
