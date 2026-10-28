package com.airgesture.transfer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The send effect: a stone dropped in water.
 *
 * What the eye must read, in this order:
 *
 * ```
 * 0.00  the contact: a bright flash where the file leaves
 * 0.00  wave 1 leaves, then wave 2, 3, 4, 5 — 110 ms apart
 * 0.05  the file card sinks into the water, growing and dissolving
 * 0.10  droplets are thrown outward along the wave front
 * 0.60  the last wave reaches the edge of the screen and fades
 * ```
 *
 * Every wave is its own animation with its own life: it is born at the centre,
 * expands with an ease-out (fast at the impact, slow at the edge — that is how
 * water behaves), thins as it grows and dissolves before it reaches the border.
 * Nothing on screen holds still, which is what separates an effect from a
 * picture that appears and disappears.
 *
 * Arrival plays the same language backwards: the waves close in and the card
 * rises into place.
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
    private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val impactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLUMN
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val cardRect = RectF()
    private val scratch = RectF()
    private val clip = Path()

    private var progress = 0f
    private var arrival = false
    private var durationMs = DURATION_MS
    private var bitmap: Bitmap? = null
    private var animator: ValueAnimator? = null

    /** Where the stone hits the water, as a fraction of this view. */
    private var originX = 0.5f
    private var originY = 0.58f

    /** Waves expand fast at the impact and slow at the edge, like real water. */
    private val waveEase = PathInterpolator(0.12f, 0.72f, 0.28f, 1f)
    private val sinkEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    init {
        setWillNotDraw(false)
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun play(
        thumbnail: Bitmap?,
        reduceMotion: Boolean = false,
        isArrival: Boolean = false,
        origin: PointF? = null
    ) {
        arrival = isArrival
        // Reduced motion shortens the drop instead of deleting it: the effect is
        // the only confirmation the file left, so removing it answers nothing.
        durationMs = if (reduceMotion) REDUCED_DURATION_MS else DURATION_MS
        originX = origin?.x ?: 0.5f
        originY = origin?.y ?: 0.58f
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

    fun stop() {
        animator?.cancel()
        animator = null
        progress = 0f
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return

        val cx = originX * width
        val cy = originY * height
        // The farthest corner: the ripple must be able to reach the whole screen.
        val maxRadius = hypot(
            maxOf(cx, width - cx).toDouble(),
            maxOf(cy, height - cy).toDouble()
        ).toFloat()

        val clock = if (arrival) 1f - progress else progress

        drawCard(canvas, cx, cy)
        drawWaves(canvas, cx, cy, maxRadius, clock)
        drawImpact(canvas, cx, cy, clock)
    }

    // ------------------------------------------------------------------ waves

    /**
     * Five independent waves. Each one is born at [WAVE_STEP_MS] intervals, so
     * the surface keeps moving from the first frame to the last: there is no
     * moment in which the screen shows a still image.
     */
    private fun drawWaves(canvas: Canvas, cx: Float, cy: Float, maxRadius: Float, clock: Float) {
        for (index in 0 until WAVE_COUNT) {
            val start = index * WAVE_STAGGER
            val local = ((clock - start) / WAVE_LIFE).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue

            val spread = waveEase.getInterpolation(local)
            val radius = (10f * density) + spread * maxRadius
            val fade = (1f - local)
            val alpha = (MAX_RING_ALPHA * fade * fade).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue

            // The crest: a thin dark line with a white inner light, the way a
            // ripple catches the light on two faces of the same wave.
            ringPaint.alpha = alpha
            ringPaint.strokeWidth = ((2.4f - 1.9f * local) * density).coerceAtLeast(0.5f * density)
            scratch.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawCircle(cx, cy, radius, ringPaint)

            if (index < 3) {
                ringGlowPaint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
                ringGlowPaint.strokeWidth = (1.1f - 0.7f * local) * density
                canvas.drawCircle(cx, cy, (radius - 1.6f * density).coerceAtLeast(1f), ringGlowPaint)
            }

            // A soft filled wash inside the newest waves makes the surface feel
            // like water rather than a wireframe drawing.
            if (index <= 1) {
                crestPaint.alpha = (alpha * 0.10f).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, radius, crestPaint)
            }

            // Droplets thrown off the crest of the first wave.
            if (index == 0 && local > 0.06f) {
                drawDroplets(canvas, cx, cy, radius, local, alpha)
            }
        }
    }

    private fun drawDroplets(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        local: Float,
        alpha: Int
    ) {
        val life = ((local - 0.06f) / 0.72f).coerceIn(0f, 1f)
        val fade = 1f - life
        if (fade <= 0f) return
        dropPaint.alpha = (alpha * 0.85f * fade).toInt().coerceIn(0, 255)

        for (i in 0 until DROPLET_COUNT) {
            val angle = (i * (360f / DROPLET_COUNT) + 14f) * (Math.PI / 180f)
            val distance = radius + life * 26f * density * (0.7f + (i % 3) * 0.2f)
            val x = cx + (cos(angle) * distance).toFloat()
            val y = cy + (sin(angle) * distance).toFloat()
            val r = (1.6f - life * 1.1f) * density
            if (r > 0.3f) canvas.drawCircle(x, y, r, dropPaint)
        }
    }

    /** The contact point: a brief, small, bright splash at the very beginning. */
    private fun drawImpact(canvas: Canvas, cx: Float, cy: Float, clock: Float) {
        if (clock > 0.30f) return
        val t = (clock / 0.30f).coerceIn(0f, 1f)
        val radius = (6f * density) + t * 54f * density
        val alpha = ((1f - t) * 190f).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        impactPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 255),
                ColorUtils.setAlphaComponent(Color.WHITE, 120),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        impactPaint.alpha = alpha
        canvas.drawCircle(cx, cy, radius, impactPaint)
        impactPaint.shader = null
    }

    // ------------------------------------------------------------------- card

    /**
     * The file itself, sinking into the water: it grows slightly, turns a few
     * degrees, loses definition and dissolves while the first wave passes over
     * it. On arrival the same motion runs backwards and it settles.
     */
    private fun drawCard(canvas: Canvas, cx: Float, cy: Float) {
        val cardWidth = minOf(width * 0.30f, 104f * density)
        val cardHeight = cardWidth * 1.24f

        val t = if (arrival) 1f - progress else progress
        val sink = sinkEase.getInterpolation(t.coerceIn(0f, 1f))
        val scale = if (arrival) {
            (0.62f + 0.38f * sink)
        } else {
            (1f + 0.22f * sink) * (1f - 0.28f * sink)
        }
        val alpha = if (arrival) {
            (255 * sinkEase.getInterpolation((progress / 0.5f).coerceIn(0f, 1f))).toInt()
                .coerceIn(0, 255)
        } else {
            (255 * (1f - sinkEase.getInterpolation((t / 0.55f).coerceIn(0f, 1f)))).toInt()
                .coerceIn(0, 255)
        }
        if (alpha <= 0) return

        val halfW = cardWidth * 0.5f * scale
        val halfH = cardHeight * 0.5f * scale
        val lift = if (arrival) (1f - sink) * -18f * density else -sink * 10f * density
        cardRect.set(cx - halfW, cy - halfH + lift, cx + halfW, cy + halfH + lift)

        val radius = 15f * density * scale
        val rotation = (if (arrival) (1f - sink) else sink) * -5f

        val save = canvas.save()
        canvas.rotate(rotation, cx, cy + lift)
        clip.reset()
        clip.addRoundRect(cardRect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)

        cardPaint.alpha = alpha
        canvas.drawRect(cardRect, cardPaint)

        val thumb = bitmap
        if (thumb != null) {
            thumbPaint.alpha = alpha
            drawCentreCrop(canvas, thumb, cardRect)
        } else {
            sheetPaint.alpha = alpha
            val inset = cardRect.width() * 0.15f
            scratch.set(
                cardRect.left + inset,
                cardRect.top + inset,
                cardRect.right - inset,
                cardRect.bottom - inset * 1.7f
            )
            canvas.drawRoundRect(scratch, 8f * density * scale, 8f * density * scale, sheetPaint)
        }

        // A light pass that follows the dissolution, so the card never looks
        // like a still photograph placed on top of the ripples.
        if (t in 0.02f..0.62f) {
            val sweep = (t - 0.02f) / 0.60f
            val sweepX = cardRect.left - cardRect.width() + sweep * cardRect.width() * 3f
            sweepPaint.shader = LinearGradient(
                sweepX,
                cardRect.top,
                sweepX + cardRect.width() * 0.7f,
                cardRect.bottom,
                intArrayOf(
                    Color.TRANSPARENT,
                    ColorUtils.setAlphaComponent(Color.WHITE, 210),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            sweepPaint.alpha = ((1f - sweep) * 255).toInt().coerceIn(0, 255)
            canvas.drawRect(cardRect, sweepPaint)
            sweepPaint.shader = null
        }
        canvas.restoreToCount(save)

        cardEdgePaint.alpha = (alpha * 0.8f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(cardRect, radius, radius, cardEdgePaint)
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

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DURATION_MS = 1200L
        const val REDUCED_DURATION_MS = 420L

        /** How many rings the stone sends across the surface. */
        const val WAVE_COUNT = 5
        /** Delay between one wave and the next, as a fraction of the timeline. */
        const val WAVE_STAGGER = 0.105f
        /** Life of a single wave. */
        const val WAVE_LIFE = 0.62f
        const val MAX_RING_ALPHA = 168f
        const val DROPLET_COUNT = 10

        val COLOR_INK: Int = Color.parseColor("#1A1A1A")
        val COLOR_HAIRLINE: Int = Color.parseColor("#E8E4DC")
        val COLUMN: Int = Color.parseColor("#F1EDE4")
    }
}
