package com.example.cameracontroller.ui

import android.hardware.camera2.*
import android.graphics.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.cameracontroller.CameraState

class CameraViewModel : ViewModel() {

    // 🔥 Single source of truth
    private val _cameraState = MutableStateFlow(CameraState())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    // =========================
    // 📷 CAMERA RESULT UPDATE
    // =========================

    fun onCaptureResult(result: TotalCaptureResult) {
        val prev = _cameraState.value

        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: prev.iso
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: prev.exposureTime
        val frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: prev.frameDuration
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: prev.timestamp

        val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: prev.afState
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: prev.aeState
        val awbState = result.get(CaptureResult.CONTROL_AWB_STATE) ?: prev.awbState

        val flashState = result.get(CaptureResult.FLASH_STATE) ?: prev.flashState
        val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: prev.focusDistance

        val faces = result.get(CaptureResult.STATISTICS_FACES)?.size ?: 0

        val cropRegion = result.get(CaptureResult.SCALER_CROP_REGION) ?: prev.cropRegion

        val fps = if (frameDuration > 0) {
            1_000_000_000f / frameDuration
        } else prev.fps

        _cameraState.value = prev.copy(
            // Frame sync
            frameNumber = result.frameNumber,
            timestamp = timestamp,

            // Exposure
            iso = iso,
            exposureTime = exposure,
            frameDuration = frameDuration,

            // 3A states
            afState = afState,
            aeState = aeState,
            awbState = awbState,

            // Lens
            focusDistance = focusDistance,

            // Flash
            flashState = flashState,

            // Scene
            faceCount = faces,

            // Zoom
            cropRegion = cropRegion,

            // Performance
            fps = fps
        )
    }

    // =========================
    // 🎛️ CONTROL (REQUEST SIDE)
    // =========================

    fun updateRequestSettings(request: CaptureRequest) {
        val prev = _cameraState.value

        _cameraState.value = prev.copy(
            requestedIso = request.get(CaptureRequest.SENSOR_SENSITIVITY),
            requestedExposureTime = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME),

            aeMode = request.get(CaptureRequest.CONTROL_AE_MODE) ?: prev.aeMode,
            aeLock = request.get(CaptureRequest.CONTROL_AE_LOCK) ?: prev.aeLock,
            aeCompensation = request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ?: prev.aeCompensation,

            afMode = request.get(CaptureRequest.CONTROL_AF_MODE) ?: prev.afMode,
            awbMode = request.get(CaptureRequest.CONTROL_AWB_MODE) ?: prev.awbMode,

            flashMode = request.get(CaptureRequest.FLASH_MODE) ?: prev.flashMode,

            noiseReductionMode = request.get(CaptureRequest.NOISE_REDUCTION_MODE) ?: prev.noiseReductionMode,
            edgeMode = request.get(CaptureRequest.EDGE_MODE) ?: prev.edgeMode,

            isAutoExposure = (request.get(CaptureRequest.CONTROL_AE_MODE)
                ?: CaptureRequest.CONTROL_AE_MODE_ON) != CaptureRequest.CONTROL_AE_MODE_OFF,

            isAutoFocus = (request.get(CaptureRequest.CONTROL_AF_MODE)
                ?: CaptureRequest.CONTROL_AF_MODE_AUTO) != CaptureRequest.CONTROL_AF_MODE_OFF,

            isAutoWhiteBalance = (request.get(CaptureRequest.CONTROL_AWB_MODE)
                ?: CaptureRequest.CONTROL_AWB_MODE_AUTO) != CaptureRequest.CONTROL_AWB_MODE_OFF
        )
    }

    // =========================
    // 🚀 STREAMING / CONNECTION
    // =========================

    fun setStreaming(enabled: Boolean) {
        _cameraState.update {
            it.copy(isStreaming = enabled)
        }
    }

    fun setConnectionStatus(connected: Boolean) {
        _cameraState.update {
            it.copy(isConnectedToHost = connected)
        }
    }

    // =========================
    // 🎯 USER INTENTS (ABSTRACTIONS)
    // =========================

    fun setManualExposure(iso: Int, exposureTime: Long) {
        _cameraState.update {
            it.copy(
                requestedIso = iso,
                requestedExposureTime = exposureTime,
                isAutoExposure = false
            )
        }
    }

    fun enableAutoExposure() {
        _cameraState.update {
            it.copy(
                requestedIso = null,
                requestedExposureTime = null,
                isAutoExposure = true
            )
        }
    }

    fun setFocusDistance(distance: Float) {
        _cameraState.update {
            it.copy(
                focusDistance = distance,
                isAutoFocus = false
            )
        }
    }

    fun enableAutoFocus() {
        _cameraState.update {
            it.copy(isAutoFocus = true)
        }
    }

    fun setWhiteBalance(mode: Int) {
        _cameraState.update {
            it.copy(
                awbMode = mode,
                isAutoWhiteBalance = (mode != CaptureRequest.CONTROL_AWB_MODE_OFF)
            )
        }
    }
}