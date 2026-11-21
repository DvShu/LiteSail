package com.joel.litesail.scan.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import com.joel.litesail.scan.decoder.QrDecoder
import kotlin.math.abs

/** The camera and session are managed on the main thread. Frames are decoded on a worker. */
internal class QrCamera(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onReady: () -> Unit,
    private val onFailure: () -> Unit
) : AutoCloseable {
    data class Configuration(
        val id: String,
        val size: Size,
        val sensorOrientation: Int,
        val focusMode: Int?,
        val hasTorch: Boolean
    )

    private class Stream(val preview: Surface, val reader: ImageReader) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var request: CaptureRequest.Builder? = null
        @Volatile var active = true

        // Only the analysis thread accesses the decoder and frame state.
        val decoder = QrDecoder()
        var nextFrameAt = 0L
        var nextFullFrameAt = 0L
        var resultFound = false
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = lazy { HandlerThread("QR analysis").apply { start() } }
    private val analysis by lazy { Handler(worker.value.looper) }
    private var stream: Stream? = null
    @Volatile var centerSize = 0
    val isRunning: Boolean get() = stream != null
    val configuration: Configuration? = try {
        chooseCamera()
    } catch (_: CameraAccessException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun chooseCamera(): Configuration? {
        val available = manager ?: return null
        for (id in available.cameraIdList) {
            val characteristics = available.getCameraCharacteristics(id)
            if (characteristics[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK) continue
            val outputs = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: continue
            val previewSizes = outputs.getOutputSizes(SurfaceHolder::class.java).orEmpty().toSet()
            val analysisSizes = outputs.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val size = analysisSizes.filter { it in previewSizes }
                .minByOrNull { abs(it.width - 640) + abs(it.height - 480) } ?: continue
            val focusModes = characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
            val focus = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                .firstOrNull { it in focusModes }
            return Configuration(
                id, size, characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
                focus, characteristics[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
            )
        }
        return null
    }

    @SuppressLint("MissingPermission") // The activity checks CAMERA permission before starting.
    fun start(preview: Surface) {
        val config = configuration ?: return
        val available = manager ?: return
        if (isRunning || !preview.isValid) return
        val reader = ImageReader.newInstance(config.size.width, config.size.height, ImageFormat.YUV_420_888, 2)
        val capture = Stream(preview, reader)
        stream = capture
        reader.setOnImageAvailableListener({ readFrame(capture) }, analysis)
        try {
            available.openCamera(config.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (stream !== capture) {
                        device.close()
                        return
                    }
                    capture.device = device
                    startPreview(capture, device, config.focusMode)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    fail(capture)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    fail(capture)
                }
            }, main)
        } catch (_: CameraAccessException) {
            fail(capture)
        } catch (_: SecurityException) {
            fail(capture)
        }
    }

    @Suppress("DEPRECATION") // The Surface-list overload supports API 26 and 27.
    private fun startPreview(capture: Stream, device: CameraDevice, focusMode: Int?) {
        try {
            val frameSurface = capture.reader.surface
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(capture.preview)
                addTarget(frameSurface)
                focusMode?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
            }
            device.createCaptureSession(listOf(capture.preview, frameSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (stream !== capture) {
                        session.close()
                        return
                    }
                    capture.session = session
                    capture.request = request
                    try {
                        session.setRepeatingRequest(request.build(), null, main)
                        onReady()
                    } catch (_: CameraAccessException) {
                        fail(capture)
                    } catch (_: IllegalStateException) {
                        fail(capture)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    fail(capture)
                }
            }, main)
        } catch (_: CameraAccessException) {
            fail(capture)
        } catch (_: IllegalArgumentException) {
            fail(capture)
        } catch (_: IllegalStateException) {
            fail(capture)
        }
    }

    fun setTorch(enabled: Boolean): Boolean {
        if (configuration?.hasTorch != true) return false
        val capture = stream ?: return false
        val session = capture.session ?: return false
        val request = capture.request ?: return false
        return try {
            request.set(
                CaptureRequest.FLASH_MODE,
                if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            session.setRepeatingRequest(request.build(), null, main)
            true
        } catch (_: CameraAccessException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun readFrame(capture: Stream) {
        if (!capture.active) return
        val content = capture.reader.acquireLatestImage()?.use { image ->
            val now = SystemClock.uptimeMillis()
            if (capture.resultFound || now < capture.nextFrameAt) return
            capture.nextFrameAt = now + FRAME_INTERVAL_MS
            if (capture.nextFullFrameAt == 0L) capture.nextFullFrameAt = now + FULL_FRAME_INTERVAL_MS

            val side = centerSize
            if (side in 1 until minOf(image.width, image.height) && now < capture.nextFullFrameAt) {
                capture.decoder.decode(image, side)
            } else {
                capture.nextFullFrameAt = now + FULL_FRAME_INTERVAL_MS
                capture.decoder.decode(image)
            }
        }?.takeUnless(String::isBlank) ?: return

        capture.resultFound = true
        main.post {
            if (stream === capture) {
                stop()
                onResult(content)
            }
        }
    }

    private fun fail(capture: Stream) {
        if (stream === capture) {
            stop()
            onFailure()
        }
    }

    fun stop() {
        val capture = stream ?: return
        stream = null
        capture.active = false
        capture.session?.close()
        capture.device?.close()
        capture.reader.setOnImageAvailableListener(null, null)
        analysis.post { capture.reader.close() }
    }

    override fun close() {
        stop()
        if (worker.isInitialized()) worker.value.quitSafely()
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 100L
        const val FULL_FRAME_INTERVAL_MS = 500L
    }
}
