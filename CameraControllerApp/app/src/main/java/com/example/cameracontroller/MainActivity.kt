package com.example.cameracontroller

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    private val isoText = mutableStateOf("—")
    private val shutterText = mutableStateOf("—")
    private val focusDistText = mutableStateOf("—")
    private val gainText = mutableStateOf("—")
    private val dimensionsText = mutableStateOf("—")

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

        cameraController.onCaptureMetadata = { meta ->
            runOnUiThread {
                isoText.value = "ISO ${meta.iso}"
                shutterText.value = formatShutter(meta.exposureNs)
                focusDistText.value = if (meta.focusDistance > 0f)
                    String.format("%.2f m", 1.0 / meta.focusDistance) else "∞"
                gainText.value = String.format("×%.1f", meta.gain)
                dimensionsText.value =
                    "${cameraController.currentWidth}×${cameraController.currentHeight}"
            }
        }

        frameStreamer.onConnectionChanged = { connected ->
            runOnUiThread {
                isConnected.value = connected
                statusText.value = if (connected) "Streaming" else "Waiting for connection …"
            }
        }

        commandServer.onCommand = { cmd, value -> handleCommand(cmd, value) }
    }

    private fun formatShutter(ns: Long): String {
        if (ns <= 0) return "—"
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format("%.1f s", seconds)
        } else {
            val denom = (1.0 / seconds).toInt()
            "1/${denom}s"
        }
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
    fun rememberDeviceRotation(): Float {
        val context = LocalContext.current
        val rotation = remember { mutableFloatStateOf(0f) }

        DisposableEffect(context) {
            val listener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    val snapped = when {
                        orientation in 315..359 || orientation in 0..44 -> 0f
                        orientation in 45..134  -> -90f    // landscape-right
                        orientation in 135..224 -> -180f
                        orientation in 225..314 -> -270f   // landscape-left
                        else -> 0f
                    }
                    rotation.floatValue = snapped
                }
            }
            listener.enable()
            onDispose { listener.disable() }
        }
        return rotation.floatValue
    }

    @Composable
    fun CameraStreamScreen() {
        val deviceRotation = rememberDeviceRotation()
        val animatedRotation by animateFloatAsState(
            targetValue = deviceRotation,
            animationSpec = tween(durationMillis = 300),
            label = "rotation"
        )
        var showCapabilities by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            CameraPreview(modifier = Modifier.fillMaxSize())

            ConnectionBadge(
                animatedRotation = animatedRotation,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )

            // Info button — top-right, rotates with device
            InfoButton(
                animatedRotation = animatedRotation,
                onClick = { showCapabilities = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )

            CameraInfoOverlay(
                animatedRotation = animatedRotation,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            )

            if (showCapabilities) {
                CapabilitiesOverlay(onDismiss = { showCapabilities = false })
            }
        }
    }

    @Composable
    fun CameraPreview(modifier: Modifier) {
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
            modifier = modifier
        )
    }

    @Composable
    fun ConnectionBadge(animatedRotation: Float, modifier: Modifier = Modifier) {
        val connected by isConnected
        val status by statusText

        Box(
            modifier = modifier
                .wrapContentSize()
                .graphicsLayer { rotationZ = animatedRotation }
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (connected) Color(0xFF4CAF50) else Color(0xFFF44336),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (connected) "Connected" else "Disconnected",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    @Composable
    fun CameraInfoOverlay(animatedRotation: Float, modifier: Modifier = Modifier) {
        val fps by fpsText
        val iso by isoText
        val shutter by shutterText
        val focusDist by focusDistText
        val gain by gainText
        val dims by dimensionsText

        Box(
            modifier = modifier
                .wrapContentSize()
                .graphicsLayer { rotationZ = animatedRotation }
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow(label = "ISO", value = iso)
                InfoRow(label = "Shutter", value = shutter)
                InfoRow(label = "Focus", value = focusDist)
                InfoRow(label = "Gain", value = gain)
                InfoRow(label = "Dims", value = dims)
                InfoRow(label = "FPS", value = fps)
            }
        }
    }

    @Composable
    fun InfoRow(label: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(56.dp)
            )
            Text(
                text = value,
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    @Composable
    fun InfoButton(animatedRotation: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
        IconButton(
            onClick = onClick,
            modifier = modifier
                .graphicsLayer { rotationZ = animatedRotation }
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
        ) {
            Text(
                text = "i",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif
            )
        }
    }

    @Composable
    fun CapabilitiesOverlay(onDismiss: () -> Unit) {
        val caps = remember { cameraController.getCapabilities() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Device Capabilities",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

                if (caps == null) {
                    Text(
                        text = "Camera not initialised yet",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                } else {
                    CapabilitySection(
                        title = "ISO Range",
                        values = listOf(
                            "Min: ${caps.isoRange.lower}",
                            "Max: ${caps.isoRange.upper}"
                        )
                    )

                    CapabilitySection(
                        title = "Shutter Speed Range",
                        values = buildShutterList(caps.exposureRange)
                    )

                    CapabilitySection(
                        title = "Focus Distance",
                        values = buildFocusList(caps.minFocusDistance)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Tap anywhere to close",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    fun CapabilitySection(title: String, values: List<String>) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = Color(0xFF64B5F6),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            values.forEach { v ->
                Text(
                    text = "  $v",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    private fun buildShutterList(range: android.util.Range<Long>): List<String> {
        val minNs = range.lower
        val maxNs = range.upper

        val result = mutableListOf(
            "Fastest: ${formatShutter(minNs)}",
            "Slowest: ${formatShutter(maxNs)}",
            "",
            "Common values in range:"
        )

        val standardSpeeds = longArrayOf(
            1_000_000_000L / 8000,
            1_000_000_000L / 4000,
            1_000_000_000L / 2000,
            1_000_000_000L / 1000,
            1_000_000_000L / 500,
            1_000_000_000L / 250,
            1_000_000_000L / 125,
            1_000_000_000L / 60,
            1_000_000_000L / 30,
            1_000_000_000L / 15,
            1_000_000_000L / 8,
            1_000_000_000L / 4,
            1_000_000_000L / 2,
            1_000_000_000L,
            2_000_000_000L,
            4_000_000_000L,
            8_000_000_000L,
            16_000_000_000L,
            30_000_000_000L,
        )

        standardSpeeds
            .filter { it in minNs..maxNs }
            .forEach { result.add("  ${formatShutter(it)}") }

        return result
    }

    private fun buildFocusList(minFocusDist: Float): List<String> {
        if (minFocusDist <= 0f) {
            return listOf("Fixed-focus lens (no manual focus)")
        }
        val closestCm = (100.0 / minFocusDist)
        return listOf(
            "Range: ${String.format("%.1f cm", closestCm)}  →  ∞",
            "Min focus dist: ${String.format("%.2f", minFocusDist)} diopters",
            "",
            "Diopter = 1/distance(m)",
            "0.0 diopters = infinity"
        )
    }
}