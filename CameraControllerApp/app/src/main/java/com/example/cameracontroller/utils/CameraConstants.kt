package com.example.cameracontroller.utils

import android.util.Range

object CameraConstants {
    const val WIDTH = 1280
    const val HEIGHT = 720
    val ISO_RANGE = Range(100, 800)
    val EXPOSURE_RANGE = Range(1_000_000L, 1_000_000_000L)
}