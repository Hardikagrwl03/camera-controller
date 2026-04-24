package com.example.cameracontroller.interfaces

interface CameraCommandInterface {
    fun setExposure(nanoseconds: Long)
    fun setISO(iso: Int)
    fun setFocusDistance(distance: Float)
    fun enableAutoExposure()
    fun disableAutoExposure()
    fun enableAutoFocus()
    fun disableAutoFocus()
    fun setWhiteBalance(mode: Int)
    fun setFrameRate(fps: Int)
    fun setTorch(enabled: Boolean)
    fun changeResolution(width: Int, height: Int)
    fun capturePhoto()
}