package com.example.cameracontroller

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.cameracontroller.ui.theme.CameraControllerTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var cameraController: CameraController
    private lateinit var connectionManager: USBConnectionManager
    private lateinit var frameStreamer: FrameStreamer
    private lateinit var commandServer: CommandServer

    private val isConnected = mutableStateOf(false)
    private val statusText = mutableStateOf("Initialising …")
    private val fpsText = mutableStateOf("— FPS")

    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) {
            startSystem()
        } else {
            statusText.value = "Camera permission denied"
        }
    }

    // ── Activity lifecycle ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initComponents()

        setContent {
            CameraControllerTheme {
                CameraStreamScreen()
            }
        }

        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        commandServer.stop()
        frameStreamer.stop()
        connectionManager.stop()
        cameraController.release()
    }

    // ── Initialisation ─────────────────────────────────────────────────

    private fun initComponents() {
        cameraController = CameraController(this)
        connectionManager = USBConnectionManager()
        frameStreamer = FrameStreamer(connectionManager)
        commandServer = CommandServer(connectionManager)

        cameraController.onFrameAvailable = { jpeg ->
            frameStreamer.queueFrame(jpeg)
            tickFps()
        }

        frameStreamer.onConnectionChanged = { connected ->
            runOnUiThread {
                isConnected.value = connected
                statusText.value = if (connected) "Streaming" else "Waiting for connection …"
            }
        }

        commandServer.onCommand = { cmd, value -> handleCommand(cmd, value) }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startSystem()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun startSystem() {
        cameraController.initialize()
        connectionManager.startServers()
        frameStreamer.start()
        commandServer.start()
        statusText.value = "Waiting for connection …"
        Log.i(TAG, "System started")
    }

    private fun openCameraWithPreview(surface: Surface) {
        cameraController.openCamera(
            cameraController.currentWidth,
            cameraController.currentHeight,
            surface
        )
    }

    // ── FPS tracking ───────────────────────────────────────────────────

    private fun tickFps() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsTimestamp
        if (elapsed >= 1000L) {
            val fps = frameCount * 1000.0 / elapsed
            val text = String.format("%.1f FPS", fps)
            runOnUiThread { fpsText.value = text }
            frameCount = 0
            lastFpsTimestamp = now
        }
    }

    // ── Command dispatch ───────────────────────────────────────────────

    private fun handleCommand(cmd: String, value: Any?) {
        Log.i(TAG, "Command: $cmd  value=$value")
        when (cmd) {
            "set_exposure" -> {
                val ns = (value as? Number)?.toLong() ?: return
                cameraController.setExposure(ns)
            }
            "set_iso" -> {
                val iso = (value as? Number)?.toInt() ?: return
                cameraController.setISO(iso)
            }
            "set_focus" -> {
                val d = (value as? Number)?.toFloat() ?: return
                cameraController.setFocusDistance(d)
            }
            "set_resolution" -> {
                val parts = value?.toString()?.split("x") ?: return
                if (parts.size != 2) return
                val w = parts[0].toIntOrNull() ?: return
                val h = parts[1].toIntOrNull() ?: return
                cameraController.changeResolution(w, h)
            }
            "set_fps" -> {
                val fps = (value as? Number)?.toInt() ?: return
                cameraController.setFrameRate(fps)
            }
            "enable_auto_exposure"  -> cameraController.enableAutoExposure()
            "disable_auto_exposure" -> cameraController.disableAutoExposure()
            "enable_auto_focus"     -> cameraController.enableAutoFocus()
            "disable_auto_focus"    -> cameraController.disableAutoFocus()
            "set_white_balance" -> {
                val mode = (value as? Number)?.toInt() ?: return
                cameraController.setWhiteBalance(mode)
            }
            "set_torch" -> {
                val on = when (value) {
                    is Boolean -> value
                    is Number  -> value.toInt() != 0
                    else       -> return
                }
                cameraController.setTorch(on)
            }
            "capture_photo" -> cameraController.capturePhoto()
            else -> Log.w(TAG, "Unknown command: $cmd")
        }
    }

    // ── Compose UI ─────────────────────────────────────────────────────

    @Composable
    fun CameraStreamScreen() {
        val connected by isConnected
        val status by statusText
        val fps by fpsText

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: SurfaceTexture, w: Int, h: Int
                            ) {
                                openCameraWithPreview(Surface(st))
                            }

                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture, w: Int, h: Int
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Top status bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = status, color = Color.White, fontSize = 16.sp)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = if (connected) Color.Green else Color.Red,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (connected) "Connected" else "Disconnected",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "$fps  •  ${cameraController.currentWidth}×${cameraController.currentHeight}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            }

            // Bottom info strip
            Text(
                text = "USB Camera Controller  —  Ports ${NetworkProtocol.FRAME_PORT} / ${NetworkProtocol.COMMAND_PORT}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}
