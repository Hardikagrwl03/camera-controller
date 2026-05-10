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

//    /**
//     * Software fallback: YUV_420_888 → NV21 → JPEG.
//     * Only used when the camera is configured with YUV output.
//     */
//    @Synchronized
//    fun yuvToJpeg(image: Image, quality: Int = 70): ByteArray {
//        val nv21 = yuvToNv21(image)
//        jpegStream.reset()
//        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
//        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, jpegStream)
//        return jpegStream.toByteArray()
//    }

    fun yuv420ToNv21(image: Image): ByteArray {

        val width = image.width
        val height = image.height

        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        // Copy Y
        yBuffer.get(nv21, 0, ySize)

        val chromaRowStride =
            image.planes[1].rowStride

        val chromaPixelStride =
            image.planes[1].pixelStride

        var offset = ySize

        val uBytes = ByteArray(uBuffer.remaining())
        val vBytes = ByteArray(vBuffer.remaining())

        uBuffer.get(uBytes)
        vBuffer.get(vBytes)

        for (row in 0 until height / 2) {

            for (col in 0 until width / 2) {

                val uvOffset =
                    row * chromaRowStride +
                            col * chromaPixelStride

                nv21[offset++] =
                    vBytes[uvOffset]

                nv21[offset++] =
                    uBytes[uvOffset]
            }
        }

        return nv21
    }
}
