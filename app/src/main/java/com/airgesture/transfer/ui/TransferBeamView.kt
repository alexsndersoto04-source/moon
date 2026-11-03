package com.airgesture.transfer.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * A single diffuse burst of electric-blue and neon-turquoise light.
 * There are no drawn outlines, rings, cards, or hard-edged shapes: only layered
 * radial gradients with a soft 20dp blur, expanding from the center and fading
 * away. The same burst is used for sending and receiving.
 */
class TransferBeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val softBlur = BlurMaskFilter(20f * density, BlurMaskFilter.Blur.NORMAL)

    private val corePaint = glowPaint().apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                Color.rgb(238, 255, 255),
                Color.rgb(62, 255, 235),
                Color.rgb(0, 206, 255),
                Color.rgb(28, 100, 255),
                Color.argb(150, 0, 45, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.14f, 0.37f, 0.66f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    // Offset color blooms break up the silhouette, keeping the light organic
    // rather than reading as a perfect circle.
    private val turquoisePaint = glowPaint().apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                Color.argb(220, 0, 255, 224),
                Color.argb(150, 0, 220, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val electricBluePaint = glowPaint().apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                Color.argb(215, 36, 99, 255),
                Color.argb(125, 29, 78, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val easeOut = PathInterpolator(0f, 0f, 0.2f, 1f)

    init {
        setWillNotDraw(false)
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // BlurMaskFilter is rendered by Android's software canvas. This view is
        // just a few gradients for 1.2s; its software layer does not affect the camera.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Starts the identical centered light burst for either direction. */
    fun play() {
        animator?.cancel()
        progress = 0f
        visibility = VISIBLE
        alpha = 1f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) visibility = GONE
                }
            })
            start()
        }
    }

    fun isPlaying(): Boolean = visibility == VISIBLE && animator?.isRunning == true

    fun stop() {
        animator?.cancel()
        animator = null
        progress = 0f
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f || width == 0 || height == 0) return

        val eased = easeOut.getInterpolation(progress.coerceIn(0f, 1f))
        val scale = START_SCALE + (END_SCALE - START_SCALE) * eased
        val opacity = (255f * (1f - eased)).toInt().coerceIn(0, 255)
        if (opacity == 0) return

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        // At scale 3.5 the main glow reaches the corners of the display.
        val maxReach = kotlin.math.hypot(centerX.toDouble(), centerY.toDouble()).toFloat()
        val radius = (maxReach / END_SCALE) * scale

        drawGlow(canvas, corePaint, centerX, centerY, radius * 0.94f, radius * 1.08f, opacity)
        drawGlow(
            canvas,
            turquoisePaint,
            centerX - radius * 0.11f,
            centerY + radius * 0.05f,
            radius * 0.72f,
            radius * 0.94f,
            (opacity * 0.72f).toInt()
        )
        drawGlow(
            canvas,
            electricBluePaint,
            centerX + radius * 0.12f,
            centerY - radius * 0.08f,
            radius * 0.83f,
            radius * 0.76f,
            (opacity * 0.66f).toInt()
        )
    }

    private fun drawGlow(
        canvas: Canvas,
        paint: Paint,
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        opacity: Int
    ) {
        if (opacity <= 0 || radiusX <= 0f || radiusY <= 0f) return
        val save = canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(radiusX, radiusY)
        paint.alpha = opacity.coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restoreToCount(save)
    }

    private fun glowPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = softBlur
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DURATION_MS = 1200L
        const val START_SCALE = 0.3f
        const val END_SCALE = 3.5f
    }
}
