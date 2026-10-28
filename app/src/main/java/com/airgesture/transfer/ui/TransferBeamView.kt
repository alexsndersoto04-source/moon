package com.airgesture.transfer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils

/**
 * The "hand-off" animation shown when a file leaves the phone.
 *
 * It is the moment the gesture deserves: a soft ring expands from the palm, the
 * file card lifts, flies upward and dissolves while a light sweep crosses it.
 * Everything is drawn in one [Canvas] pass and driven by a single animator, so
 * it costs one extra draw per frame for 700 ms and nothing when idle.
 *
 * The curves are the point: `PathInterpolator(0.16, 1, 0.3, 1)` is the
 * decelerating ease used by iOS for interactive transitions. Nothing snaps,
 * nothing bounces, nothing fades linearly.
 */
class TransferBeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = COLOR_INK
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_INK
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val cardEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = COLOR_HAIRLINE
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLUMN
    }

    private val cardRect = RectF()
    private val ringRect = RectF()
    private val clipPath = Path()

    /** 0..1 animation clock. */
    private var progress = 0f
    private var bitmap: Bitmap? = null
    private var animator: ValueAnimator? = null

    private val flyEase = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val ringEase = PathInterpolator(0.22f, 0.9f, 0.24f, 1f)

    init {
        setWillNotDraw(false)
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Plays the effect. [thumbnail] is optional: without it the card still flies
     * with the same silhouette, so the animation never blocks on decoding.
     */
    fun play(thumbnail: Bitmap?, reduceMotion: Boolean = false) {
        if (reduceMotion) {
            visibility = GONE
            return
        }
        bitmap = thumbnail
        animator?.cancel()
        progress = 0f
        visibility = VISIBLE
        alpha = 1f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            // Linear clock: every easing curve is applied per channel below,
            // which is what makes the motion feel hand-timed instead of mechanical.
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    visibility = GONE
                }
            })
            start()
        }
    }

    fun isPlaying(): Boolean = visibility == VISIBLE && animator?.isRunning == true

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return

        val cx = width / 2f
        val cy = height * 0.62f
        val cardWidth = minOf(width * 0.34f, 108f * density)
        val cardHeight = cardWidth * 1.28f

        // ---- flying card -----------------------------------------------------
        val fly = flyEase.getInterpolation(progress.coerceIn(0f, 1f))
        val lift = fly * height * 0.62f
        val scale = 1f + 0.12f * easeOut(progress / 0.22f) - 0.52f * fly
        val cardCenterX = cx + (fly * width * 0.08f)
        val cardCenterY = cy - lift
        val halfW = cardWidth * 0.5f * scale
        val halfH = cardHeight * 0.5f * scale

        cardRect.set(cardCenterX - halfW, cardCenterY - halfH, cardCenterX + halfW, cardCenterY + halfH)

        val cardAlpha = when {
            progress < 0.62f -> 255
            else -> (255 * (1f - (progress - 0.62f) / 0.38f)).toInt().coerceIn(0, 255)
        }
        if (cardAlpha > 0) {
            val radius = 14f * density * scale
            clipPath.reset()
            clipPath.addRoundRect(cardRect, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)

            cardPaint.alpha = cardAlpha
            canvas.drawRect(cardRect, cardPaint)

            val thumb = bitmap
            if (thumb != null) {
                thumbPaint.alpha = cardAlpha
                drawCentreCrop(canvas, thumb, cardRect)
            } else {
                // A quiet sheet stands in for the file, matching the light UI.
                sheetPaint.alpha = cardAlpha
                val inset = cardRect.width() * 0.16f
                val sheet = RectF(
                    cardRect.left + inset,
                    cardRect.top + inset,
                    cardRect.right - inset,
                    cardRect.bottom - inset * 1.6f
                )
                canvas.drawRoundRect(sheet, 8f * density * scale, 8f * density * scale, sheetPaint)
            }

            // Light sweep: gives the card a sense of travelling, not sliding.
            if (progress in 0.12f..0.72f) {
                val t = (progress - 0.12f) / 0.60f
                val sweepX = cardRect.left - cardRect.width() + t * cardRect.width() * 3f
                val gradient = android.graphics.LinearGradient(
                    sweepX,
                    cardRect.top,
                    sweepX + cardRect.width() * 0.7f,
                    cardRect.bottom,
                    intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.WHITE, 190), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                sweepPaint.shader = gradient
                sweepPaint.alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
                canvas.drawRect(cardRect, sweepPaint)
                sweepPaint.shader = null
            }
            canvas.restoreToCount(save)

            cardEdgePaint.alpha = cardAlpha
            canvas.drawRoundRect(cardRect, radius, radius, cardEdgePaint)
        }

        // ---- rings -----------------------------------------------------------
        drawRing(canvas, cx, cy, ringEase.getInterpolation(progress.coerceIn(0f, 1f)), 1f)
        drawRing(canvas, cx, cy, ringEase.getInterpolation((progress - 0.12f).coerceIn(0f, 1f)), 0.6f)
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, t: Float, weight: Float) {
        if (t <= 0f) return
        val radius = (18f * density) + t * (width * 0.52f)
        val alpha = ((1f - t) * 120f * weight).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        haloPaint.alpha = (alpha * 0.10f).toInt()
        canvas.drawCircle(cx, cy, radius * 0.96f, haloPaint)

        ringPaint.alpha = alpha
        ringPaint.strokeWidth = (1.6f - 0.9f * t) * density
        ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(ringRect, -90f, 360f * (0.35f + 0.65f * t), false, ringPaint)
    }

    private fun drawCentreCrop(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        val scale = maxOf(dst.width() / bw, dst.height() / bh)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val left = (bw - sw) * 0.5f
        val top = (bh - sh) * 0.5f
        canvas.drawBitmap(
            bitmap,
            android.graphics.Rect(left.toInt(), top.toInt(), (left + sw).toInt(), (top + sh).toInt()),
            dst,
            thumbPaint
        )
    }

    private fun easeOut(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return 1f - (1f - v) * (1f - v)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DURATION_MS = 700L
        val COLOR_INK: Int = Color.parseColor("#1A1A1A")
        val COLOR_HAIRLINE: Int = Color.parseColor("#E8E4DC")
        val COLUMN: Int = Color.parseColor("#F1EDE4")
    }
}
