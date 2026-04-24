package com.example.cameracontroller

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

class USBConnectionManager {

    companion object {
        private const val TAG = "USBConnectionManager"
    }

    private var frameServerSocket: ServerSocket? = null
    private var commandServerSocket: ServerSocket? = null

    @Volatile
    var frameClient: Socket? = null
        private set

    @Volatile
    var commandClient: Socket? = null
        private set

    @Volatile
    var isRunning = false
        private set

    fun startServers() {
        isRunning = true
        try {
            frameServerSocket = ServerSocket(NetworkProtocol.FRAME_PORT).apply {
                reuseAddress = true
            }
            commandServerSocket = ServerSocket(NetworkProtocol.COMMAND_PORT).apply {
                reuseAddress = true
            }
            Log.i(TAG, "Servers started — frame:${NetworkProtocol.FRAME_PORT}  cmd:${NetworkProtocol.COMMAND_PORT}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start servers", e)
            stop()
        }
    }

    fun waitForFrameClient(): Socket? {
        return try {
            Log.i(TAG, "Waiting for frame client on port ${NetworkProtocol.FRAME_PORT} ...")
            frameServerSocket?.accept()?.also { sock ->
                frameClient = sock
                sock.tcpNoDelay = true
                sock.sendBufferSize = 1024 * 1024
                Log.i(TAG, "Frame client connected")
            }
        } catch (e: IOException) {
            if (isRunning) Log.e(TAG, "Error accepting frame client", e)
            null
        }
    }

    fun waitForCommandClient(): Socket? {
        return try {
            Log.i(TAG, "Waiting for command client on port ${NetworkProtocol.COMMAND_PORT} ...")
            commandServerSocket?.accept()?.also { sock ->
                commandClient = sock
                sock.tcpNoDelay = true
                Log.i(TAG, "Command client connected")
            }
        } catch (e: IOException) {
            if (isRunning) Log.e(TAG, "Error accepting command client", e)
            null
        }
    }

    fun disconnectClients() {
        runCatching { frameClient?.close() }
        runCatching { commandClient?.close() }
        frameClient = null
        commandClient = null
    }

    fun stop() {
        isRunning = false
        disconnectClients()
        runCatching { frameServerSocket?.close() }
        runCatching { commandServerSocket?.close() }
        frameServerSocket = null
        commandServerSocket = null
        Log.i(TAG, "Servers stopped")
    }
}
