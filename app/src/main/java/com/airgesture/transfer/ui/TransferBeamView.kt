package com.airgesture.transfer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.sin

/**
 * The hand-off effect: what a file looks like when it leaves the phone.
 *
 * Choreography (a single 900 ms clock, one Canvas pass):
 *
 * ```
 * 0.00 ─ halo bloom + first ring burst
 * 0.05 ─ the file card lifts off, rises, turns slightly and shrinks
 * 0.10 ─ a light streak trails under it while it travels
 * 0.18 ─ second ring, 0.34 ─ third ring
 * 0.20 ─ ten particles drift upward along the card's path
 * 0.70 ─ the card starts dissolving into the light
 * 0.86 ─ a soft flash closes the movement
 * ```
 *
 * The curves are the point. `PathInterpolator(0.16, 1, 0.3, 1)` is the
 * decelerating ease iOS uses for interactive transitions: fast departure,
 * long settle. Nothing is linear, nothing bounces, nothing snaps.
 */
class TransferBeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_INK
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
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
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLUMN
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_INK
    }

    private val cardRect = RectF()
    private val scratchRect = RectF()
    private val path = Path()

    private var progress = 0f
    private var arrival = false
    private var durationMs = DURATION_MS
    private var bitmap: Bitmap? = null
    private var animator: ValueAnimator? = null
    private var haloShader: Shader? = null
    private var haloRadius = 0f

    private val flyEase = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val ringEase = PathInterpolator(0.22f, 0.9f, 0.24f, 1f)
    private val fadeEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** Deterministic particle fan, so every play looks identical. */
    private val particles = Array(PARTICLE_COUNT) { index ->
        val angle = -Math.PI / 2 + (index - (PARTICLE_COUNT - 1) / 2f) * 0.24
        floatArrayOf(
            cos(angle).toFloat(),
            sin(angle).toFloat(),
            0.55f + (index % 4) * 0.16f,
            0.30f + (index % 3) * 0.18f
        )
    }

    init {
        setWillNotDraw(false)
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Plays the effect. [thumbnail] is optional: without it the card flies with
     * the same silhouette, so the animation never waits on a decode.
     */
    fun play(thumbnail: Bitmap?, reduceMotion: Boolean = false, isArrival: Boolean = false) {
        // Reduced motion shortens the flight instead of deleting it: the effect
        // is the confirmation that something left the phone, so removing it
        // entirely leaves the user with no answer at all.
        arrival = isArrival
        durationMs = if (reduceMotion) REDUCED_DURATION_MS else DURATION_MS
        bitmap = thumbnail
        animator?.cancel()
        progress = 0f
        visibility = VISIBLE
        alpha = 1f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
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

    /** Used by the settings preview so the button never stacks animations. */
    fun stop() {
        animator?.cancel()
        animator = null
        progress = 0f
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return

        val cx = width / 2f
        val originY = height * 0.66f

        // Departure: 0 → 1, the card climbs away. Arrival: 1 → 0, it comes down
        // from above and comes to rest at the same anchor point.
        val eased = flyEase.getInterpolation(progress.coerceIn(0f, 1f))
        val fly = if (arrival) 1f - eased else eased
        val cardWidth = minOf(width * 0.34f, 112f * density)
        val cardHeight = cardWidth * 1.26f

        // ---- halo -------------------------------------------------------------
        drawHalo(canvas, cx, originY, progress)

        // ---- rings ------------------------------------------------------------
        drawRing(canvas, cx, originY, ringEase.getInterpolation(progress.coerceIn(0f, 1f)), 1f, 132f)
        drawRing(canvas, cx, originY, ringEase.getInterpolation(ringProgress(progress, 0.18f, 0.82f)), 0.72f, 110f)
        drawRing(canvas, cx, originY, ringEase.getInterpolation(ringProgress(progress, 0.34f, 0.66f)), 0.48f, 88f)

        // ---- flying card ------------------------------------------------------
        val lift = fly * height * 0.74f
        val scale = (1f + 0.10f * easeOut(progress / 0.18f)) * (1f - 0.44f * fly)
        val centreX = cx + fly * width * 0.06f
        val centreY = originY - lift
        val halfW = cardWidth * 0.5f * scale
        val halfH = cardHeight * 0.5f * scale
        cardRect.set(centreX - halfW, centreY - halfH, centreX + halfW, centreY + halfH)

        val alphaIn = fadeEase.getInterpolation((progress / 0.10f).coerceIn(0f, 1f))
        val fadeStart = if (arrival) 0.82f else 0.66f
        val fadeSpan = if (arrival) 0.18f else 0.34f
        val alphaOut = 1f - fadeEase.getInterpolation(((progress - fadeStart) / fadeSpan).coerceIn(0f, 1f))
        val cardAlpha = (255 * alphaIn * alphaOut).toInt().coerceIn(0, 255)

        if (cardAlpha > 0) {
            drawStreak(canvas, centreX, centreY, halfH, cardAlpha, progress)

            val radius = 15f * density * scale
            val rotation = (1f - fly) * 3.2f
            val save = canvas.save()
            canvas.rotate(rotation, centreX, centreY)
            path.reset()
            path.addRoundRect(cardRect, radius, radius, Path.Direction.CW)
            canvas.clipPath(path)

            cardPaint.alpha = cardAlpha
            canvas.drawRect(cardRect, cardPaint)

            val thumb = bitmap
            if (thumb != null) {
                thumbPaint.alpha = cardAlpha
                drawCentreCrop(canvas, thumb, cardRect)
            } else {
                sheetPaint.alpha = cardAlpha
                val inset = cardRect.width() * 0.15f
                scratchRect.set(
                    cardRect.left + inset,
                    cardRect.top + inset,
                    cardRect.right - inset,
                    cardRect.bottom - inset * 1.7f
                )
                canvas.drawRoundRect(
                    scratchRect,
                    8f * density * scale,
                    8f * density * scale,
                    sheetPaint
                )
            }

            drawSweep(canvas)
            canvas.restoreToCount(save)

            cardEdgePaint.alpha = cardAlpha
            canvas.drawRoundRect(cardRect, radius, radius, cardEdgePaint)
        }

        // ---- particles --------------------------------------------------------
        drawParticles(canvas, cx, originY, progress)

        // ---- closing flash ----------------------------------------------------
        if (progress > 0.86f) {
            val t = (progress - 0.86f) / 0.14f
            haloPaint.alpha = ((1f - t) * 26f).toInt().coerceIn(0, 255)
            haloPaint.shader = null
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), haloPaint)
        }
    }

    /** Progress of a ring whose burst starts at [start] over [span]. */
    private fun ringProgress(t: Float, start: Float, span: Float): Float =
        ((t - start) / span).coerceIn(0f, 1f)

    private fun drawHalo(canvas: Canvas, cx: Float, cy: Float, t: Float) {
        val grow = easeOut((t / 0.45f).coerceIn(0f, 1f))
        val fade = 1f - fadeEase.getInterpolation(((t - 0.30f) / 0.55f).coerceIn(0f, 1f))
        val alpha = (150f * grow * fade).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        val radius = (26f * density) + grow * width * 0.46f
        if (haloShader == null || haloRadius != radius) {
            haloShader = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    ColorUtils.setAlphaComponent(Color.WHITE, 235),
                    ColorUtils.setAlphaComponent(Color.WHITE, 120),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            haloRadius = radius
        }
        haloPaint.shader = haloShader
        haloPaint.alpha = alpha
        canvas.drawCircle(cx, cy, radius, haloPaint)
        haloPaint.shader = null
    }

    private fun drawRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        t: Float,
        weight: Float,
        maxRadiusDp: Float
    ) {
        if (t <= 0f || t >= 1f) return
        // Arrival rings close in, departure rings open out: same drawing, and the
        // direction alone tells the user whether the file left or landed.
        val spread = if (arrival) 1f - t else t
        val radius = (16f * density) + spread * maxRadiusDp * density
        val alpha = ((1f - t) * 105f * weight).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        ringPaint.alpha = alpha
        ringPaint.strokeWidth = (1.5f - 0.8f * t) * density * weight.coerceAtLeast(0.5f)
        scratchRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        // A 300° arc reads as motion; a closed circle reads as a static graphic.
        canvas.drawArc(scratchRect, -90f + t * 40f, 300f, false, ringPaint)
    }

    private fun drawStreak(
        canvas: Canvas,
        cx: Float,
        centreY: Float,
        halfH: Float,
        cardAlpha: Int,
        t: Float
    ) {
        if (t < 0.08f) return
        val strength = (1f - fadeEase.getInterpolation(((t - 0.45f) / 0.45f).coerceIn(0f, 1f)))
        val alpha = (cardAlpha * 0.34f * strength).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        val top = centreY + halfH * 0.6f
        val bottom = top + halfH * 2.6f
        val halfWidth = halfH * 0.42f
        streakPaint.shader = LinearGradient(
            cx,
            top,
            cx,
            bottom,
            ColorUtils.setAlphaComponent(COLOR_INK, alpha),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        scratchRect.set(cx - halfWidth, top, cx + halfWidth, bottom)
        canvas.drawRect(scratchRect, streakPaint)
        streakPaint.shader = null
    }

    private fun drawSweep(canvas: Canvas) {
        if (progress !in 0.10f..0.70f) return
        val t = (progress - 0.10f) / 0.60f
        val sweepX = cardRect.left - cardRect.width() + t * cardRect.width() * 3f
        sweepPaint.shader = LinearGradient(
            sweepX,
            cardRect.top,
            sweepX + cardRect.width() * 0.72f,
            cardRect.bottom,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Color.WHITE, 205),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        sweepPaint.alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
        canvas.drawRect(cardRect, sweepPaint)
        sweepPaint.shader = null
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, t: Float) {
        if (t < 0.16f) return
        val life = ((t - 0.16f) / 0.72f).coerceIn(0f, 1f)
        val fadeOut = 1f - fadeEase.getInterpolation(((life - 0.55f) / 0.45f).coerceIn(0f, 1f))
        val alpha = (110f * fadeOut * fadeEase.getInterpolation((life / 0.25f).coerceIn(0f, 1f)))
            .toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        particlePaint.alpha = alpha
        val travel = life * height * 0.52f
        for (particle in particles) {
            val dx = particle[0] * travel * particle[3] * 0.55f
            val dy = particle[1] * travel * particle[2]
            val radius = (1.1f + particle[3] * 1.5f) * density * (1f - life * 0.4f)
            canvas.drawCircle(cx + dx, cy + dy, radius.coerceAtLeast(0.4f * density), particlePaint)
        }
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
        const val DURATION_MS = 900L
        /** Shortened version used when the system asks for less motion. */
        const val REDUCED_DURATION_MS = 320L
        const val PARTICLE_COUNT = 10
        val COLOR_INK: Int = Color.parseColor("#1A1A1A")
        val COLOR_HAIRLINE: Int = Color.parseColor("#E8E4DC")
        val COLUMN: Int = Color.parseColor("#F1EDE4")
    }
}
