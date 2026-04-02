package com.example.cameracontroller

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

object ImageUtils {

    private var nv21Buffer: ByteArray? = null
    private val jpegStream = ByteArrayOutputStream(512 * 1024)

    /**
     * Fast path for JPEG-format ImageReader: the hardware encoder already produced
     * JPEG bytes — just copy them out of the Image buffer.
     */
    fun extractJpeg(image: Image): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    /**
     * Software fallback: YUV_420_888 → NV21 → JPEG.
     * Only used when the camera is configured with YUV output.
     */
    @Synchronized
    fun yuvToJpeg(image: Image, quality: Int = 70): ByteArray {
        val nv21 = yuvToNv21(image)
        jpegStream.reset()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, jpegStream)
        return jpegStream.toByteArray()
    }

    private fun yuvToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = width * height
        val totalSize = ySize + width * height / 2

        val nv21 = if (nv21Buffer?.size == totalSize) {
            nv21Buffer!!
        } else {
            ByteArray(totalSize).also { nv21Buffer = it }
        }

        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2

        var offset = ySize
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val idx = row * uvRowStride + col * uvPixelStride
                nv21[offset++] = vBuffer.get(idx)
                nv21[offset++] = uBuffer.get(idx)
            }
        }

        return nv21
    }
}
