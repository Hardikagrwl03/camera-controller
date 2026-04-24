package com.example.cameracontroller.interfaces

import java.io.InputStream
import java.io.OutputStream

interface Protocol {
    /**
     * Writes [4-byte big-endian length][frameData] as a single write() call
     * so that TCP_NODELAY pushes the entire frame in one segment.
     */
    fun writeFrame(outputStream: OutputStream, frameData: ByteArray)
    fun readCommand(inputStream: InputStream): String?
}