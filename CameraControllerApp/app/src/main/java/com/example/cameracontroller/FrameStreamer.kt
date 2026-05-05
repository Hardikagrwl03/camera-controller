package com.example.cameracontroller

import android.util.Log
import com.example.cameracontroller.ui.CameraViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Lock-free, single-frame streamer.  The producer ([queueFrame]) atomically
 * overwrites the latest frame; the consumer thread always sends whatever is
 * newest and never blocks behind a queue.
 */
class FrameStreamer(private val connectionManager: USBConnectionManager, private val viewModel: CameraViewModel) {

    companion object {
        private const val TAG = "FrameStreamer"
        private const val PARK_NANOS = 500_000L   // 0.5 ms idle spin
    }

    private val latestFrame = AtomicReference<ByteArray?>(null)
    private var streamingThread: Thread? = null

    @Volatile
    private var isRunning = false

    @Volatile
    var isConnected = false
        private set

    var onConnectionChanged: ((Boolean) -> Unit)? = null

    fun start() {
        isRunning = true
        streamingThread = Thread({
            while (isRunning) {
                try {
                    val socket = connectionManager.waitForFrameClient() ?: continue
                    isConnected = true
                    onConnectionChanged?.invoke(true)

                    val out = socket.getOutputStream()

                    try {
                        while (isRunning && !socket.isClosed) {
                            val frame = latestFrame.getAndSet(null)
                            val iso = viewModel.cameraState.value.iso
                            val exposureTime = viewModel.cameraState.value.exposureTime
                            val focalDistance = viewModel.cameraState.value.focusDistance
                            if (frame != null) {
                                NetworkProtocol.writeFrame(out, frame, iso, exposureTime, focalDistance)
                            } else {
                                LockSupport.parkNanos(PARK_NANOS)
                            }
                        }
                    } catch (e: IOException) {
                        Log.w(TAG, "Stream interrupted: ${e.message}")
                    } finally {
                        runCatching { socket.close() }
                        isConnected = false
                        onConnectionChanged?.invoke(false)
                        latestFrame.set(null)
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.i(TAG, "Streaming thread exited")
        }, "FrameStreamer").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
        streamingThread!!.start()
    }

    /** Atomically replaces the pending frame with [jpegData] and wakes the sender. */
    fun queueFrame(jpegData: ByteArray) {
        if (!isConnected) return
        latestFrame.set(jpegData)
        LockSupport.unpark(streamingThread)
    }

    fun stop() {
        isRunning = false
        streamingThread?.let {
            it.interrupt()
            LockSupport.unpark(it)
        }
        streamingThread = null
        latestFrame.set(null)
    }
}
