package com.example.cameracontroller

import android.util.Log
import com.example.cameracontroller.interfaces.NetworkProtocolCommand
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException

class CommandServer(private val connectionManager: USBConnectionManager) {

    companion object {
        private const val TAG = "CommandServer"
    }

    var clientNetworkProtocolCommand: NetworkProtocolCommand? = null

//    var onCommand: ((command: String, value: Any?) -> Unit)? = null
    private var listenerThread: Thread? = null

    @Volatile
    private var isRunning = false

    fun start() {
        isRunning = true
        listenerThread = Thread({
            while (isRunning) {
                try {
                    val socket = connectionManager.waitForCommandClient() ?: continue
                    Log.i(TAG, "Command client connected")

                    val inputStream = BufferedInputStream(socket.getInputStream())

                    try {
                        while (isRunning && !socket.isClosed) {
                            val jsonStr = NetworkProtocol.readCommand(inputStream) ?: break
                            try {
                                val json = JSONObject(jsonStr)
                                val cmd = json.getString("cmd")
                                val value: Any? = json.opt("value")
                                Log.d(TAG, "Command received: $cmd = $value")
                                onReceive(cmd, value)
//                                onCommand?.invoke(cmd, value)
                            } catch (e: Exception) {
                                Log.e(TAG, "Bad command payload: $jsonStr", e)
                            }
                        }
                    } catch (e: IOException) {
                        Log.w(TAG, "Command connection lost: ${e.message}")
                    } finally {
                        runCatching { socket.close() }
                        Log.i(TAG, "Command client disconnected — waiting for reconnect")
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.i(TAG, "Command listener thread exited")
        }, "CommandServer").apply { isDaemon = true }
        listenerThread!!.start()
    }

    fun stop() {
        isRunning = false
        listenerThread?.interrupt()
        listenerThread = null
    }

    private fun onReceive(command: String, value: Any?) {
        clientNetworkProtocolCommand?.onReceive(command, value)
    }
}
