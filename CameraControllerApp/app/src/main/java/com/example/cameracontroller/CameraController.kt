package com.example.cameracontroller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.example.cameracontroller.ui.CameraViewModel

data class CaptureMetadata(
    val iso: Int,
    val exposureNs: Long,
    val focusDistance: Float,
    val gain: Float,
)

data class CameraCapabilities(
    val isoRange: Range<Int>,
    val exposureRange: Range<Long>,
    val minFocusDistance: Float,
)

class CameraController(private val context: Context, private val viewModel: CameraViewModel) {

    companion object {
        private const val TAG = "CameraController"
        private const val MAX_IMAGES = 2
        private const val DEFAULT_JPEG_QUALITY: Byte = 60
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var requestBuilder: CaptureRequest.Builder? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var previewSurface: Surface? = null
    private var cameraId: String = ""
    private var characteristics: CameraCharacteristics? = null

    var currentWidth = 1280
        private set
    var currentHeight = 720
        private set

    var onFrameAvailable: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onCaptureMetadata: ((CaptureMetadata) -> Unit)? = null

    // ── Lifecycle ──────────────────────────────────────────────────────

    fun initialize() {
        cameraThread = HandlerThread("CameraThread", Process.THREAD_PRIORITY_URGENT_DISPLAY)
            .also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        cameraId = selectBackCamera()
        characteristics = cameraManager.getCameraCharacteristics(cameraId)
        Log.i(TAG, "Initialized with camera $cameraId")
    }

    private fun selectBackCamera(): String {
        for (id in cameraManager.cameraIdList) {
            val facing = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cameraManager.cameraIdList[0]
    }

    fun getSupportedResolutions(): List<Size> {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
    }

    fun getCapabilities(): CameraCapabilities? {
        val chars = characteristics ?: return null
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: Range(100, 800)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(1_000_000L, 1_000_000_000L)
        val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?: 0f
        return CameraCapabilities(isoRange, exposureRange, minFocusDist)
    }

    @Suppress("MissingPermission")
    fun openCamera(width: Int, height: Int, preview: Surface?) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError?.invoke("Camera permission not granted")
            return
        }

        previewSurface = preview
        currentWidth = width
        currentHeight = height

        createImageReader(width, height)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.i(TAG, "Camera opened")
                cameraDevice = camera
                createCaptureSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera disconnected")
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
                cameraDevice = null
                onError?.invoke("Camera device error $error")
            }
        }, cameraHandler)
    }

    fun release() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        cameraThread?.quitSafely()
        captureSession = null
        cameraDevice = null
        imageReader = null
        cameraThread = null
        cameraHandler = null
        Log.i(TAG, "Released")
    }

    // ── Internal pipeline ──────────────────────────────────────────────

    private fun createImageReader(width: Int, height: Int) {
        imageReader?.close()
        imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, MAX_IMAGES).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val jpeg = ImageUtils.extractJpeg(image)
                    onFrameAvailable?.invoke(jpeg)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame extraction error", e)
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return

        val surfaces = mutableListOf(reader.surface)
        previewSurface?.let { surfaces.add(it) }

        device.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.i(TAG, "Capture session configured")
                    captureSession = session
                    requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                        previewSurface?.let { addTarget(it) }
                        applyLowLatencyDefaults(this)
                    }
                    applyRepeatingRequest()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Session configuration failed")
                    onError?.invoke("Capture session configuration failed")
                }
            },
            cameraHandler
        )
    }

    /** Minimise ISP post-processing and lock frame rate for lowest pipeline latency. */
    private fun applyLowLatencyDefaults(builder: CaptureRequest.Builder) {
        builder.apply {
            set(CaptureRequest.JPEG_QUALITY, DEFAULT_JPEG_QUALITY)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
            )
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            viewModel.onCaptureResult(result)
            viewModel.updateRequestSettings(request)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            val postGain = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
            val gain = (postGain ?: 100) / 100f
            onCaptureMetadata?.invoke(CaptureMetadata(iso, exposureNs, focusDist, gain))
        }
    }

    private fun applyRepeatingRequest() {
        val builder = requestBuilder ?: return
        val session = captureSession ?: return
        try {
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to set repeating request", e)
        }
    }

    private fun runOnCameraThread(action: () -> Unit) {
        cameraHandler?.post(action) ?: action()
    }

    // ── Remote parameter controls ──────────────────────────────────────

    fun setExposure(nanoseconds: Long) = runOnCameraThread {
        requestBuilder?.apply {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, nanoseconds)
        }
        applyRepeatingRequest()
        Log.d(TAG, "Exposure → $nanoseconds ns")
    }

    fun setISO(iso: Int) = runOnCameraThread {
        requestBuilder?.apply {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        }
        applyRepeatingRequest()
        Log.d(TAG, "ISO → $iso")
    }

    fun setFocusDistance(distance: Float) = runOnCameraThread {
        requestBuilder?.apply {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        }
        applyRepeatingRequest()
        Log.d(TAG, "Focus → $distance diopters")
    }

    fun enableAutoExposure() = runOnCameraThread {
        requestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        applyRepeatingRequest()
        Log.d(TAG, "AE ON")
    }

    fun disableAutoExposure() = runOnCameraThread {
        requestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        applyRepeatingRequest()
        Log.d(TAG, "AE OFF")
    }

    fun enableAutoFocus() = runOnCameraThread {
        requestBuilder?.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )
        applyRepeatingRequest()
        Log.d(TAG, "AF ON (continuous-video)")
    }

    fun disableAutoFocus() = runOnCameraThread {
        requestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        applyRepeatingRequest()
        Log.d(TAG, "AF OFF")
    }

    fun setWhiteBalance(mode: Int) = runOnCameraThread {
        requestBuilder?.set(CaptureRequest.CONTROL_AWB_MODE, mode)
        applyRepeatingRequest()
        Log.d(TAG, "AWB mode → $mode")
    }

    fun setFrameRate(fps: Int) = runOnCameraThread {
        requestBuilder?.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        applyRepeatingRequest()
        Log.d(TAG, "FPS → $fps")
    }

    fun setTorch(enabled: Boolean) = runOnCameraThread {
        requestBuilder?.set(
            CaptureRequest.FLASH_MODE,
            if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        applyRepeatingRequest()
        Log.d(TAG, "Torch → $enabled")
    }

    fun changeResolution(width: Int, height: Int) = runOnCameraThread {
        Log.i(TAG, "Resolution change → ${width}x$height")
        currentWidth = width
        currentHeight = height
        captureSession?.close()
        captureSession = null
        createImageReader(width, height)
        createCaptureSession()
    }

    fun capturePhoto() = runOnCameraThread {
        val device = cameraDevice ?: return@runOnCameraThread
        val session = captureSession ?: return@runOnCameraThread
        val reader = imageReader ?: return@runOnCameraThread
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.i(TAG, "Still capture completed")
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Still capture failed", e)
        }
    }
}