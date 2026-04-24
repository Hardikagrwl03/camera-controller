package com.example.cameracontroller

data class CameraState(

    // Frame Info (CRITICAL for syncing with stream)
    val frameNumber: Long = 0L,
    val timestamp: Long = 0L,

    // Exposure (Actual values from CaptureResult)
    val iso: Int = 0,
    val exposureTime: Long = 0L,          // nanoseconds
    val frameDuration: Long = 0L,         // nanoseconds

    // Exposure (Requested values from CaptureRequest)
    val requestedIso: Int? = null,
    val requestedExposureTime: Long? = null,

    // AE (Auto Exposure)
    val aeMode: Int = 0,
    val aeState: Int = 0,
    val aeCompensation: Int = 0,
    val aeLock: Boolean = false,

    // Focus / Lens
    val focusDistance: Float = 0f,
    val afMode: Int = 0,
    val afState: Int = 0,

    // White Balance
    val awbMode: Int = 0,
    val awbState: Int = 0,

    // Flash
    val flashMode: Int = 0,
    val flashState: Int = 0,

    // Lens / Optical
    val aperture: Float? = null,
    val focalLength: Float? = null,

    // Zoom / Crop
    val cropRegion: android.graphics.Rect? = null,

    // Scene / Detection
    val faceCount: Int = 0,

    // Image Quality / Processing
    val noiseReductionMode: Int = 0,
    val edgeMode: Int = 0,

    // FPS / Performance
    val fps: Float = 0f,

    // 3A Flags
    val isAutoExposure: Boolean = true,
    val isAutoFocus: Boolean = true,
    val isAutoWhiteBalance: Boolean = true,

    // Status flags
    val isStreaming: Boolean = false,
    val isConnectedToHost: Boolean = false
)