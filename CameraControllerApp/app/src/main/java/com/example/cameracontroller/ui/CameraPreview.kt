package com.example.cameracontroller.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cameracontroller.CameraController
import com.example.cameracontroller.CameraState

@Composable
fun CameraPreview(cameraController: CameraController, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        st: SurfaceTexture, w: Int, h: Int
                    ) {
                        openCameraWithPreview(cameraController, Surface(st))
                    }

                    override fun onSurfaceTextureSizeChanged(
                        st: SurfaceTexture, w: Int, h: Int
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier.aspectRatio(3f/4f)
    )
}

fun openCameraWithPreview(cameraController: CameraController, surface: Surface) {
    cameraController.openCamera(
        cameraController.currentWidth,
        cameraController.currentHeight,
        surface
    )
}
