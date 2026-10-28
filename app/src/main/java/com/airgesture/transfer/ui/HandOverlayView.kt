package com.airgesture.transfer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.airgesture.transfer.core.GestureState
import com.airgesture.transfer.vision.OverlayFrame
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the hand skeleton as a quiet constellation: hairline strokes, small
 * joints, and a soft halo that grows with the forward-thrust confidence.
 *
 * Everything is pre-allocated; onDraw touches no heap and posts no invalidations
 * of its own, so it costs well under a millisecond per frame on a Mali-G35.
 */
class HandOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Aspect ratio (width / height) of the analysed frame, portrait. */
    var frameAspect: Float = 3f / 4f

    /** Front cameras are mirrored in the preview but not in the analysis feed. */
    var mirrorHorizontally: Boolean = false

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.3f)
        color = Color.argb(70, 23, 23, 26)
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 23, 23, 26)
    }

    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(170, 180, 133, 92)
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = Color.argb(60, 180, 133, 92)
    }

    private var frame: OverlayFrame? = null
    private val points = FloatArray(LANDMARK_COUNT * 2)

    fun submit(next: OverlayFrame) {
        frame = next
        invalidate()
    }

    fun setSkeletonVisible(visible: Boolean) {
        alpha = if (visible) 1f else 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame ?: return
        val landmarks = current.landmarks
        if (landmarks == null || landmarks.size < LANDMARK_COUNT) return
        if (width == 0 || height == 0) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Centre-crop the analysed frame onto the preview (PreviewView uses
        // FILL_CENTER, so the mapping must crop identically).
        val drawHeight = max(viewHeight, viewWidth / frameAspect)
        val drawWidth = drawHeight * frameAspect
        val offsetX = (viewWidth - drawWidth) / 2f
        val offsetY = (viewHeight - drawHeight) / 2f

        for (i in 0 until LANDMARK_COUNT) {
            val landmark = landmarks[i]
            val nx = if (mirrorHorizontally) 1f - landmark.x() else landmark.x()
            points[i * 2] = offsetX + nx * drawWidth
            points[i * 2 + 1] = offsetY + landmark.y() * drawHeight
        }

        for (connection in CONNECTIONS) {
            val a = connection[0]
            val b = connection[1]
            canvas.drawLine(
                points[a * 2], points[a * 2 + 1],
                points[b * 2], points[b * 2 + 1],
                bonePaint
            )
        }

        for (i in 0 until LANDMARK_COUNT) {
            val paint = if (i in TIPS) tipPaint else jointPaint
            val radius = if (i in TIPS) dp(2.4f) else dp(1.8f)
            canvas.drawCircle(points[i * 2], points[i * 2 + 1], radius, paint)
        }

        if (current.state == GestureState.GRAB_FIST || current.state == GestureState.THROW_VECTOR) {
            val palmX = points[PALM_INDEX * 2]
            val palmY = points[PALM_INDEX * 2 + 1]
            val base = min(viewWidth, viewHeight) * 0.10f
            val radius = base * (1f + current.thrust * 1.1f)
            haloPaint.alpha = (36 + 130 * current.thrust).toInt().coerceIn(0, 255)
            canvas.drawCircle(palmX, palmY, radius, haloPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val LANDMARK_COUNT = 21
        private const val PALM_INDEX = 9
        private val TIPS = intArrayOf(4, 8, 12, 16, 20)

        private val CONNECTIONS = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
            intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
            intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
            intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
            intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
            intArrayOf(0, 17)
        )
    }
}
