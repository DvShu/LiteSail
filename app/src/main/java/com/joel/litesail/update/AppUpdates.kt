package com.joel.litesail.update

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Toast
import com.joel.litesail.R
import com.joel.litesail.browser.BrowserDownloads
import com.joel.litesail.databinding.ViewAppUpdateBinding
import com.joel.litesail.ui.BottomSheetDialog
import java.io.IOException
import java.util.concurrent.Executors

internal class AppUpdates(private val activity: Activity, private val downloads: BrowserDownloads) {
    private data class TrackedDownload(val id: Long, val title: String?, val status: Int)

    private val worker = Executors.newSingleThreadExecutor()
    private val preferences by lazy { activity.getSharedPreferences("app_updates", Context.MODE_PRIVATE) }
    private val manager by lazy { activity.getSystemService(DownloadManager::class.java) }
    private val downloadId get() = preferences.getLong(DOWNLOAD_ID, -1L)
    private var resumed = false
    private var availableUpdate: AppUpdate? = null
    private var dialog: BottomSheetDialog? = null

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE && id >= 0 && id == downloadId) {
                checkDownloadCompletion()
            }
        }
    }

    @Suppress("DEPRECATION")
    private val currentVersion by lazy { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }

    fun check() {
        val version = currentVersion ?: return
        worker.execute {
            val update = GitHubUpdates.check(version) ?: return@execute
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                availableUpdate = update
                showAvailableUpdate()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Older Android versions use the sender permission alone.
    fun onResume() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(completionReceiver, filter, COMPLETION_PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(completionReceiver, filter, COMPLETION_PERMISSION, null)
        }
        resumed = true
        if (!checkDownloadCompletion()) showAvailableUpdate()
    }

    fun onPause() {
        resumed = false
        activity.unregisterReceiver(completionReceiver)
    }

    fun close() {
        worker.shutdownNow()
        dialog?.dismiss()
    }

    fun onDownloadQueued(id: Long) {
        preferences.edit().putLong(DOWNLOAD_ID, id).putBoolean(COMPLETION_HANDLED, false).apply()
    }

    private fun showAvailableUpdate() {
        if (!resumed || activity.isFinishing) return
        val update = availableUpdate ?: return
        val version = currentVersion ?: return
        availableUpdate = null
        val sheet = BottomSheetDialog(activity, R.string.app_update_title)
        val content = ViewAppUpdateBinding.inflate(sheet.layoutInflater)
        content.currentVersion.text = activity.getString(R.string.app_update_current_version, version.removePrefix("v"))
        content.latestVersion.text = activity.getString(R.string.app_update_latest_version, update.versionName)
        update.releaseNotes?.let { notes ->
            content.releaseNotes.text = notes
                .replace(RELEASE_NOTES_HEADING, "")
                .replace(RELEASE_NOTES_BULLET, "$1• ")
                .replace(RELEASE_NOTES_BOLD, "$1")
            content.releaseNotes.visibility = View.VISIBLE
        }
        sheet.setBody(content.root)
        sheet.addAction(R.string.app_update_later)
        sheet.addAction(R.string.app_update_download) { startDownload(update) }
        sheet.setOnDismissListener { if (dialog === sheet) dialog = null }
        dialog = sheet
        sheet.show()
    }

    private fun startDownload(update: AppUpdate) {
        val existing = trackedDownload()
        when (existing?.status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                message(R.string.app_update_downloading)
                return
            }
            DownloadManager.STATUS_SUCCESSFUL -> if (existing.title == update.fileName) {
                if (hasApkHeader(existing.id)) {
                    preferences.edit().putBoolean(COMPLETION_HANDLED, true).apply()
                    downloads.showSystemDownloads()
                    return
                }
                removeDownload(existing.id)
            }
        }
        downloads.startAppUpdate(update.downloadUrl, update.fileName)
    }

    private fun checkDownloadCompletion(): Boolean {
        if (!resumed || activity.isFinishing || preferences.getBoolean(COMPLETION_HANDLED, false)) return false
        val download = trackedDownload() ?: return false
        when (download.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                preferences.edit().putBoolean(COMPLETION_HANDLED, true).apply()
                if (download.title == "LiteSail-v${currentVersion?.removePrefix("v")}.apk") return false
                if (hasApkHeader(download.id)) {
                    dialog?.dismiss()
                    downloads.showSystemDownloads()
                    return true
                }
                removeDownload(download.id)
                message(R.string.app_update_download_failed)
            }
            DownloadManager.STATUS_FAILED -> {
                preferences.edit().putBoolean(COMPLETION_HANDLED, true).apply()
                message(R.string.app_update_download_failed)
            }
        }
        return false
    }

    private fun trackedDownload(): TrackedDownload? {
        val id = downloadId.takeIf { it >= 0L } ?: return null
        return try {
            manager?.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                TrackedDownload(
                    id,
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                )
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun hasApkHeader(id: Long): Boolean = try {
        val file = manager?.openDownloadedFile(id)
        // A proxy can return an HTML error page with a successful HTTP status. APKs are ZIP files.
        file != null && ParcelFileDescriptor.AutoCloseInputStream(file).use { stream ->
            stream.read() == 0x50 && stream.read() == 0x4b && stream.read() == 0x03 && stream.read() == 0x04
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun removeDownload(id: Long) {
        try {
            manager?.remove(id)
        } catch (_: SecurityException) {
            // A device may restrict access to the downloads provider.
        } catch (_: IllegalStateException) {
            // The provider can be unavailable even if the app owns the download.
        }
    }

    private fun message(text: Int) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val COMPLETION_PERMISSION = "android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS"
        const val DOWNLOAD_ID = "download_id"
        const val COMPLETION_HANDLED = "completion_handled"
        val RELEASE_NOTES_HEADING = Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]+""")
        val RELEASE_NOTES_BULLET = Regex("""(?m)^([ \t]*)[-+*][ \t]+""")
        val RELEASE_NOTES_BOLD = Regex("""\*\*([^*\r\n]+)\*\*""")
    }
}
