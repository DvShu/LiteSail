package com.joel.litesail.browser

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.joel.litesail.R
import com.joel.litesail.databinding.ViewWebPageQrCodeBinding
import com.joel.litesail.ui.BottomSheetDialog
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors

internal class WebPageQrCodeShare(private val activity: Activity) {
    private val worker = Executors.newSingleThreadExecutor()
    private var dialog: BottomSheetDialog? = null
    private var awaitingPermission: String? = null
    private var saving = false

    fun show(url: String) {
        dialog?.dismiss()
        val sheet = BottomSheetDialog(activity, R.string.browser_page_qr_share, centerActions = true)
        val content = ViewWebPageQrCodeBinding.inflate(sheet.layoutInflater)
        try {
            showQrImage(content.qrImage, WebPageQrCode.encode(url))
            sheet.addAction(R.string.browser_page_qr_save, dismissOnClick = false) { requestSave(url) }
        } catch (_: WriterException) {
            content.qrHint.setText(R.string.browser_page_qr_code_too_long)
            content.qrImage.visibility = View.GONE
        }
        sheet.setBody(content.root)
        sheet.setOnDismissListener { if (dialog === sheet) dialog = null }
        dialog = sheet
        sheet.show()
    }

    private fun showQrImage(image: ImageView, qr: BitMatrix) {
        val padding = image.paddingLeft + image.paddingRight
        image.layoutParams = image.layoutParams.apply {
            width = maxOf(width, qr.width + padding)
            height = width
        }
        image.setImageDrawable(BitmapDrawable(activity.resources, toBitmap(qr)).apply { isFilterBitmap = false })
        image.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val available = right - left - padding
            if (available <= 0) return@addOnLayoutChangeListener
            val size = WebPageQrCode.displaySize(qr.width, available)
            val scale = size.toFloat() / qr.width
            val offset = ((available - size) / 2).toFloat()
            image.imageMatrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(offset, offset)
            }
        }
    }

    private fun requestSave(url: String) {
        if (saving || awaitingPermission != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            awaitingPermission = url
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_REQUEST)
            return
        }
        saveToGallery(url)
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != STORAGE_REQUEST) return
        val url = awaitingPermission ?: return
        awaitingPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            saveToGallery(url)
        } else {
            message(R.string.browser_page_qr_storage_denied)
        }
    }

    fun save(outState: Bundle) {
        awaitingPermission?.let { outState.putString(PENDING_QR_URL, it) }
    }

    fun restore(savedState: Bundle?) {
        awaitingPermission = savedState?.getString(PENDING_QR_URL)
    }

    fun close() {
        dialog?.dismiss()
        worker.shutdown()
    }

    private fun saveToGallery(url: String) {
        saving = true
        worker.execute {
            val result = try {
                val qr = WebPageQrCode.encode(url)
                val size = WebPageQrCode.displaySize(qr.width, 1_024)
                val bitmap = Bitmap.createScaledBitmap(toBitmap(qr), size, size, false)
                saveImage(bitmap)
                R.string.browser_page_qr_saved
            } catch (_: IOException) {
                R.string.browser_page_qr_save_failed
            } catch (_: SecurityException) {
                R.string.browser_page_qr_save_failed
            } catch (_: IllegalArgumentException) {
                R.string.browser_page_qr_save_failed
            } catch (_: IllegalStateException) {
                R.string.browser_page_qr_save_failed
            } catch (_: WriterException) {
                R.string.browser_page_qr_save_failed
            }
            activity.runOnUiThread {
                saving = false
                if (!activity.isDestroyed && !activity.isFinishing) message(result)
            }
        }
    }

    private fun saveImage(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            saveToPictures(bitmap)
            return
        }
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "LiteSail-QR-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LiteSail")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Image not created")
        var published = false
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Image not writable")
            writePng(bitmap, output)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) == 0) throw IOException("Image not published")
            published = true
        } finally {
            if (!published) runCatching { resolver.delete(uri, null, null) }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToPictures(bitmap: Bitmap) {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val directory = File(pictures, "LiteSail")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Pictures folder unavailable")
        }
        val image = File.createTempFile("LiteSail-QR-", ".png", directory)
        var written = false
        try {
            writePng(bitmap, image.outputStream())
            written = true
        } finally {
            if (!written) image.delete()
        }
        MediaScannerConnection.scanFile(activity.applicationContext, arrayOf(image.path), arrayOf("image/png"), null)
    }

    private fun writePng(bitmap: Bitmap, output: OutputStream) {
        output.use {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG encoding failed")
        }
    }

    private fun toBitmap(qr: BitMatrix): Bitmap {
        val pixels = IntArray(qr.width * qr.height)
        for (y in 0 until qr.height) {
            for (x in 0 until qr.width) {
                pixels[y * qr.width + x] = if (qr[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, qr.width, qr.height, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
        }
    }

    private fun message(text: Int) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val STORAGE_REQUEST = 104
        const val PENDING_QR_URL = "pending_qr_save_url"
    }
}
