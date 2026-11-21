package com.joel.litesail.browser

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.URLUtil
import android.widget.Toast
import com.joel.litesail.R

internal class BrowserDownloads(private val activity: Activity) {
    private data class FileRequest(
        val url: Uri,
        val name: String,
        val userAgent: String?,
        val mimeType: String?
    )

    private var awaitingPermission: FileRequest? = null

    fun start(url: String?, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val uri = url?.let(Uri::parse)?.normalizeScheme()
        if (uri == null || !BrowserAddress.isWebScheme(uri.scheme) || uri.host.isNullOrBlank()) {
            message(R.string.download_unsupported_url)
            return
        }

        val name = downloadFileName(URLUtil.guessFileName(uri.toString(), contentDisposition, mimeType))
        val request = FileRequest(uri, name, userAgent, mimeType)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            if (awaitingPermission != null) {
                message(R.string.download_storage_permission_pending)
                return
            }
            awaitingPermission = request
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_REQUEST)
            return
        }
        enqueue(request)
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != STORAGE_REQUEST) return
        val request = awaitingPermission ?: return
        awaitingPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            enqueue(request)
        } else {
            message(R.string.download_storage_permission_denied)
        }
    }

    fun save(outState: Bundle) {
        val request = awaitingPermission ?: return
        // Keep a permission prompt across recreation without retaining a WebView.
        outState.putBundle(PENDING_DOWNLOAD, Bundle().apply {
            putString("url", request.url.toString())
            putString("name", request.name)
            putString("userAgent", request.userAgent)
            putString("mimeType", request.mimeType)
        })
    }

    fun restore(state: Bundle?) {
        val saved = state?.getBundle(PENDING_DOWNLOAD) ?: return
        val url = saved.getString("url") ?: return
        val name = saved.getString("name") ?: return
        awaitingPermission = FileRequest(Uri.parse(url), name, saved.getString("userAgent"), saved.getString("mimeType"))
    }

    private fun enqueue(file: FileRequest) {
        val manager = activity.getSystemService(DownloadManager::class.java)
        val id = try {
            val request = DownloadManager.Request(file.url)
                .setTitle(file.name)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // Android can replay supplied headers across redirects. Keep session credentials in WebView.
            file.userAgent?.takeIf(String::isNotBlank)?.let { request.addRequestHeader("User-Agent", it) }
            file.mimeType?.takeIf(String::isNotBlank)?.let(request::setMimeType)
            manager?.enqueue(request) ?: -1L
        } catch (_: IllegalArgumentException) {
            -1L
        } catch (_: IllegalStateException) {
            -1L
        } catch (_: SecurityException) {
            -1L
        }
        message(if (id >= 0L) R.string.download_queued else R.string.download_enqueue_failed)
    }

    fun show() {
        if (launch(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))) return

        val downloadsRoot = DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads")
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsRoot)
        if (!launch(picker, OPEN_FILE_REQUEST)) message(R.string.download_ui_unavailable)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != OPEN_FILE_REQUEST || resultCode != Activity.RESULT_OK) return
        val file = data?.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } ?: return
        val opened = try {
            val mimeType = activity.contentResolver.getType(file) ?: "*/*"
            launch(Intent(Intent.ACTION_VIEW).setDataAndType(file, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: SecurityException) {
            false
        }
        if (!opened) message(R.string.download_file_open_failed)
    }

    @Suppress("DEPRECATION")
    private fun launch(intent: Intent, requestCode: Int? = null): Boolean = try {
        if (requestCode == null) activity.startActivity(intent) else activity.startActivityForResult(intent, requestCode)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun message(text: Int) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val STORAGE_REQUEST = 102
        const val OPEN_FILE_REQUEST = 103
        const val PENDING_DOWNLOAD = "pending_download"
    }
}

private val unsafeFileNameChars = Regex("[\\p{Cc}<>:\"|?*]")

internal fun downloadFileName(guessedName: String): String {
    val name = guessedName.substringAfterLast('/').substringAfterLast('\\')
        .replace(unsafeFileNameChars, "_")
        .trim { it == '.' || it.isWhitespace() }
        .ifEmpty { "download" }
    if (name.toByteArray().size <= 240) return name

    val stem = name.substringBeforeLast('.').take(64).trimEnd { it.isHighSurrogate() }
    val extension = name.substringAfterLast('.', "").take(12).trimEnd { it.isHighSurrogate() }
    return if (extension.isEmpty()) stem else "$stem.$extension"
}
