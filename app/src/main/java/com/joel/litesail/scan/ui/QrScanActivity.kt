package com.joel.litesail.scan.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import com.joel.litesail.R
import com.joel.litesail.databinding.ActivityQrScanBinding
import com.joel.litesail.scan.camera.QrCamera
import kotlin.math.roundToInt

class QrScanActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var binding: ActivityQrScanBinding
    private lateinit var camera: QrCamera
    private var resumed = false
    private var requestedPermission = false
    private var surfaceReady = false
    private var torchEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedPermission = savedInstanceState?.getBoolean(PERMISSION_REQUESTED) ?: false
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        camera = QrCamera(this, ::returnContent, ::showReady) { showCameraError() }
        camera.configuration?.size?.let { binding.qrPreview.holder.setFixedSize(it.width, it.height) }
        binding.qrPreview.holder.addCallback(this)
        binding.qrScanGuide.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
        binding.closeQrScan.setOnClickListener { finish() }
        binding.qrScanRetry.setOnClickListener { startOrRequestPermission() }
        binding.qrScanTorch.setOnClickListener {
            val enable = !torchEnabled
            if (camera.setTorch(enable)) {
                updateTorchButton(enable)
            } else {
                Toast.makeText(this, R.string.qr_torch_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= 35) {
            binding.root.setOnApplyWindowInsetsListener { _, insets ->
                val areas = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                binding.qrScanHeader.setPadding(areas.left, areas.top, areas.right, 0)
                val vertical = resources.getDimensionPixelSize(R.dimen.qr_scan_footer_vertical_padding)
                val horizontal = resources.getDimensionPixelSize(R.dimen.qr_scan_footer_horizontal_padding)
                binding.qrScanFooter.setPadding(
                    areas.left + horizontal, vertical, areas.right + horizontal, areas.bottom + vertical
                )
                insets
            }
        }
        if (camera.configuration == null) {
            showCameraError(allowRetry = false)
        } else if (!hasCameraPermission() && !requestedPermission) {
            startOrRequestPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (hasCameraPermission()) {
            startCameraIfReady()
        } else if (camera.configuration != null) {
            showPermissionMessage()
        }
    }

    override fun onPause() {
        resumed = false
        camera.stop()
        super.onPause()
    }

    override fun onDestroy() {
        binding.qrPreview.holder.removeCallback(this)
        camera.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(PERMISSION_REQUESTED, requestedPermission)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val size = camera.configuration?.size ?: return
        surfaceReady = width == size.width && height == size.height
        fitPreview()
        startCameraIfReady()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        camera.stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        if (hasCameraPermission()) startCameraIfReady() else showPermissionMessage()
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startOrRequestPermission() {
        when {
            hasCameraPermission() -> startCameraIfReady()
            shouldOpenSettings() -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
            else -> {
                requestedPermission = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }
        }
    }

    private fun shouldOpenSettings(): Boolean =
        requestedPermission && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

    private fun startCameraIfReady() {
        if (!resumed || !surfaceReady || !hasCameraPermission() || camera.isRunning || isFinishing) return
        showFeedback(R.string.qr_camera_opening)
        updateTorchButton(false)
        camera.start(binding.qrPreview.holder.surface)
    }

    private fun showReady() {
        binding.qrScanGuide.visibility = View.VISIBLE
        binding.qrScanStatus.setText(R.string.qr_scan_hint)
        if (camera.configuration?.hasTorch == true) binding.qrScanTorch.visibility = View.VISIBLE
    }

    private fun updateTorchButton(enabled: Boolean) {
        torchEnabled = enabled
        binding.qrScanTorch.isSelected = enabled
        binding.qrScanTorch.setText(if (enabled) R.string.qr_torch_off else R.string.qr_torch_on)
    }

    private fun fitPreview() {
        val config = camera.configuration ?: return
        val preview = binding.qrPreview
        if (preview.width == 0 || preview.height == 0) return
        val rotation = preview.display?.rotation ?: Surface.ROTATION_0
        val sideRotated = (config.sensorOrientation + rotation * 90) % 180 != 0
        val frameWidth = if (sideRotated) config.size.height else config.size.width
        val frameHeight = if (sideRotated) config.size.width else config.size.height
        val scaleX = preview.width.toFloat() / frameWidth
        val scaleY = preview.height.toFloat() / frameHeight
        val scale = maxOf(scaleX, scaleY)
        preview.scaleX = scale / scaleX
        preview.scaleY = scale / scaleY
        // Leave space around codes that sit close to the edges of the visible guide.
        val side = (binding.qrScanGuide.windowSize / scale * 1.2f).roundToInt()
        camera.centerSize = side.takeIf { it >= 160 && it < minOf(frameWidth, frameHeight) } ?: 0
    }

    private fun showCameraError(allowRetry: Boolean = true) {
        showFeedback(R.string.qr_camera_unavailable, if (allowRetry) R.string.retry else null)
    }

    private fun showPermissionMessage() {
        val action = if (shouldOpenSettings()) R.string.qr_camera_settings else R.string.retry
        showFeedback(R.string.qr_camera_permission, action)
    }

    private fun showFeedback(message: Int, action: Int? = null) {
        binding.qrScanGuide.visibility = View.INVISIBLE
        binding.qrScanStatus.setText(message)
        binding.qrScanTorch.visibility = View.GONE
        if (action != null) binding.qrScanRetry.setText(action)
        binding.qrScanRetry.visibility = if (action != null) View.VISIBLE else View.GONE
    }

    private fun returnContent(content: String) {
        setResult(RESULT_OK, Intent().putExtra(RESULT_CONTENT, content))
        finish()
    }

    internal companion object {
        const val RESULT_CONTENT = "qr_content"
        private const val PERMISSION_REQUESTED = "permission_requested"
        private const val CAMERA_PERMISSION_REQUEST = 102
    }
}
