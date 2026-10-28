package com.airgesture.transfer.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Converts CameraX analysis frames into an upright RGB bitmap, reusing every
 * allocation across frames.
 *
 * Three paths, tried in this order, because OEM camera stacks are inconsistent:
 *  1. **RGBA_8888** (what we ask CameraX for): a straight memcpy per pixel row.
 *  2. **YUV_420_888**: manual BT.601 conversion, needed if the device ignores
 *     the requested output format.
 *  3. **JPEG**: decoded through BitmapFactory, for the rare legacy case.
 *
 * Failures are counted and exposed so the on-screen diagnostics can show them
 * instead of silently producing no detections.
 */
class FrameConverter {

    private var buffer: Bitmap? = null
    private var rotated: Bitmap? = null
    private var canvas: Canvas? = null

    private val paint = Paint().apply { isFilterBitmap = false }
    private val matrix = Matrix()
    private var scratch = IntArray(0)

    private val failureCounter = AtomicLong(0)
    private val successCounter = AtomicLong(0)

    val failures: Long get() = failureCounter.get()
    val successes: Long get() = successCounter.get()

    /** "RGBA", "YUV" or "JPEG" — shown in diagnostics. */
    @Volatile
    var lastMode: String = "—"
        private set

    /**
     * Set by [com.airgesture.transfer.camera.CameraAnalyzer] only while it is
     * probing which channel order this device really delivers. Correct-by-spec
     * is `false` (CameraX documents R,G,B,A).
     */
    @Volatile
    var swapChannels: Boolean = false

    /**
     * Returns an upright bitmap ready for inference. The instance is reused on
     * the next call: consume it synchronously and never retain it.
     */
    fun convert(image: ImageProxy): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            failureCounter.incrementAndGet()
            return null
        }

        val source = ensureBuffer(width, height)
        if (source == null) {
            failureCounter.incrementAndGet()
            return null
        }

        val ok = when {
            image.format == ImageFormat.JPEG -> decodeJpeg(image, source)
            isRgba(image, width, height) -> copyRgba(image, source, width, height)
            image.planes.size >= 3 -> copyYuv(image, source, width, height)
            else -> false
        }

        if (!ok) {
            failureCounter.incrementAndGet()
            return null
        }
        successCounter.incrementAndGet()

        return rotate(source, image.imageInfo.rotationDegrees)
    }

    private fun isRgba(image: ImageProxy, width: Int, height: Int): Boolean {
        if (image.format == ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) return true
        val plane = image.planes.firstOrNull() ?: return false
        return plane.buffer.remaining() >= width * height * 4 && image.planes.size == 1
    }

    private fun ensureBuffer(width: Int, height: Int): Bitmap? {
        val existing = buffer
        if (existing != null && !existing.isRecycled &&
            existing.width == width && existing.height == height
        ) {
            return existing
        }
        existing?.recycle()
        buffer = null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { buffer = it }
        }.getOrNull()
    }

    /**
     * RGBA_8888 → Bitmap, with an **explicit** colour-int conversion.
     *
     * CameraX hands back the bytes packed as `R,G,B,A`. Android's `ARGB_8888`
     * pixel memory is `B,G,R,A` (Skia's kN32 is BGRA on every ARM device), so
     * dumping the plane straight into the bitmap with [Bitmap.copyPixelsFromBuffer]
     * reinterprets the bytes: red and blue come out **swapped**. The preview you
     * see on screen is a different pipeline (PreviewView decodes YUV itself), so
     * the camera looks perfect while MediaPipe receives a blue-skinned image and
     * detects nothing at all.
     *
     * Building the colour ints by hand removes the ambiguity: [Bitmap.setPixels]
     * takes documented `A,R,G,B` ints, exactly like [copyYuv] already does, so
     * both paths now agree with each other.
     */
    private fun copyRgba(image: ImageProxy, target: Bitmap, width: Int, height: Int): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        val source = plane.buffer
        val rowStride = plane.rowStride
        if (rowStride <= 0 || rowStride % 4 != 0) return false

        val rowStrideInts = rowStride / 4
        val ints = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val capacity = ints.capacity()
        val count = width * height
        val out = rowScratch(count)

        var written = 0
        for (row in 0 until height) {
            val start = row * rowStrideInts
            if (start + width > capacity) return false
            ints.position(start)
            ints.get(out, written, width)
            written += width
        }

        // Unpack the RGBA bytes and re-pack them as ARGB colour ints. When the
        // device is actually delivering BGRA (seen on a few OEM stacks) the probe
        // flips [swapChannels] and the same code decodes it correctly.
        if (swapChannels) {
            for (i in 0 until count) {
                val value = out[i]
                val blue = value and 0xFF
                val green = (value ushr 8) and 0xFF
                val red = (value ushr 16) and 0xFF
                out[i] = OPAQUE or (red shl 16) or (green shl 8) or blue
            }
        } else {
            for (i in 0 until count) {
                val value = out[i]
                val red = value and 0xFF
                val green = (value ushr 8) and 0xFF
                val blue = (value ushr 16) and 0xFF
                out[i] = OPAQUE or (red shl 16) or (green shl 8) or blue
            }
        }

        target.setPixels(out, 0, width, 0, 0, width, height)
        lastMode = if (swapChannels) "RGBA (BGR)" else "RGBA"
        return true
    }

    private fun copyYuv(image: ImageProxy, target: Bitmap, width: Int, height: Int): Boolean {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        if (yRowStride <= 0 || uRowStride <= 0 || vRowStride <= 0) return false

        val out = rowScratch(width * height)
        var index = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = row shr 1
            val uRow = uvRow * uRowStride
            val vRow = uvRow * vRowStride
            for (col in 0 until width) {
                val yIndex = yRow + col * yPixelStride
                val uIndex = uRow + (col shr 1) * uPixelStride
                val vIndex = vRow + (col shr 1) * vPixelStride
                if (yIndex >= yBuffer.limit() || uIndex >= uBuffer.limit() || vIndex >= vBuffer.limit()) {
                    return false
                }
                val y = (yBuffer.get(yIndex).toInt() and 0xFF)
                val u = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vIndex).toInt() and 0xFF) - 128
                val yTerm = (y - 16).coerceAtLeast(0)
                val r = (1.164f * yTerm + 1.596f * v).toInt().coerceIn(0, 255)
                val g = (1.164f * yTerm - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b = (1.164f * yTerm + 2.017f * u).toInt().coerceIn(0, 255)
                out[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        target.setPixels(out, 0, width, 0, 0, width, height)
        // YUV carries chroma explicitly, so the RGBA probe never applies here.
        lastMode = "YUV"
        return true
    }

    private fun decodeJpeg(image: ImageProxy, target: Bitmap): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        val duplicate = plane.buffer.duplicate()
        duplicate.rewind()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return false
        if (decoded.width == target.width && decoded.height == target.height) {
            canvas?.let { c ->
                c.drawBitmap(decoded, 0f, 0f, paint)
                decoded.recycle()
                lastMode = "JPEG"
                return true
            }
        }
        // Dimensions differ: fall back to a scaled draw into our buffer.
        val c = Canvas(target)
        c.drawBitmap(decoded, null, android.graphics.Rect(0, 0, target.width, target.height), paint)
        decoded.recycle()
        lastMode = "JPEG"
        return true
    }

    private fun rotate(source: Bitmap, rotationDegrees: Int): Bitmap {
        val degrees = ((rotationDegrees % 360) + 360) % 360
        if (degrees == 0) return source

        val swap = degrees == 90 || degrees == 270
        val outWidth = if (swap) source.height else source.width
        val outHeight = if (swap) source.width else source.height

        var target = rotated
        if (target == null || target.isRecycled ||
            target.width != outWidth || target.height != outHeight
        ) {
            target?.recycle()
            val fresh = runCatching {
                Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return source
            target = fresh
            rotated = fresh
            canvas = Canvas(fresh)
        }

        val targetCanvas = canvas ?: Canvas(target).also { canvas = it }
        matrix.reset()
        matrix.postRotate(degrees.toFloat())
        val translateX = when (degrees) {
            90 -> source.height.toFloat()
            180 -> source.width.toFloat()
            else -> 0f
        }
        val translateY = when (degrees) {
            180 -> source.height.toFloat()
            270 -> source.width.toFloat()
            else -> 0f
        }
        matrix.postTranslate(translateX, translateY)
        targetCanvas.drawBitmap(source, matrix, paint)
        return target
    }

    private fun rowScratch(size: Int): IntArray {
        if (scratch.size < size) scratch = IntArray(size)
        return scratch
    }

    private companion object {
        /** Alpha is irrelevant for hand inference; force opaque so RGBX works. */
        const val OPAQUE = 0xFF shl 24
    }

    fun release() {
        runCatching { buffer?.recycle() }
        runCatching { rotated?.recycle() }
        buffer = null
        rotated = null
        canvas = null
    }
}
