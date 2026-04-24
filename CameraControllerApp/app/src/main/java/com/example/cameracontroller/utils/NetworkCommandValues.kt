package com.example.cameracontroller.utils

enum class NetworkCommandValues(name: String) {
    SET_EXPOSURE("set_exposure"),
    SET_ISO("set_iso"),
    SET_FOCUS("set_focus"),
    SET_RESOLUTION("set_resolution"),
    SET_FPS("set_fps"),
    ENABLE_AE("enable_auto_exposure"),
    DISABLE_AE("disable_auto_exposure"),
    ENABLE_AF("enable_auto_focus"),
    DISABLE_AF("disable_auto_focus"),
    SET_WB("set_white_balance"),
    SET_TORCH("set_torch"),
    CAPTURE_PHOTO("capture_photo");
}