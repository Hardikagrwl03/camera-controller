package com.example.cameracontroller

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NetworkProtocol {

    const val FRAME_PORT = 5555
    const val COMMAND_PORT = 5556

    private const val MAX_COMMAND_SIZE = 1024 * 1024

    // Reusable send buffer — only the FrameStreamer thread calls writeFrame,
    // so no synchronisation is needed.
    private var sendBuffer = ByteArray(256 * 1024)

    /**
     * Writes [4-byte big-endian length][frameData] as a single write() call
     * so that TCP_NODELAY pushes the entire frame in one segment.
     */
    fun writeFrame(outputStream: OutputStream, frameData: ByteArray) {
        val total = 4 + frameData.size
        if (sendBuffer.size < total) {
            sendBuffer = ByteArray(total)
        }
        val sz = frameData.size
        sendBuffer[0] = (sz shr 24).toByte()
        sendBuffer[1] = (sz shr 16).toByte()
        sendBuffer[2] = (sz shr  8).toByte()
        sendBuffer[3] = sz.toByte()
        System.arraycopy(frameData, 0, sendBuffer, 4, sz)
        outputStream.write(sendBuffer, 0, total)
    }

    fun readCommand(inputStream: InputStream): String? {
        val sizeBytes = readExact(inputStream, 4) ?: return null
        val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.BIG_ENDIAN).int
        if (size <= 0 || size > MAX_COMMAND_SIZE) return null

        val data = readExact(inputStream, size) ?: return null
        return String(data, Charsets.UTF_8)
    }

    private fun readExact(inputStream: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val bytesRead = inputStream.read(buffer, offset, length - offset)
            if (bytesRead == -1) return null
            offset += bytesRead
        }
        return buffer
    }
}
