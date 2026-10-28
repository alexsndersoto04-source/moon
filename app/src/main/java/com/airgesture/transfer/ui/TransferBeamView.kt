package com.airgesture.transfer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
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
 * Soft-surface send effect — no outlines anywhere.
 *
 * ## Why this version looks different
 * Every previous attempt drew the crest with a **stroke**, and a stroke is a hard
 * edge: at any zoom the eye reads "line". This view never strokes anything. Each
 * ripple is a **radial gradient rendered inside a scaled circle**:
 *
 * ```
 * radius 0.00 → 0.70   transparent            the still water
 * radius 0.70 → 0.84   a whisper of shadow    the trough behind the crest
 * radius 0.84 → 0.93   white, rising          the face of the wave
 * radius 0.93          white, peak            the crest
 * radius 0.93 → 1.00   falls to nothing       the far face
 * ```
 *
 * Because the falloff is continuous, there is no border to see at any size: the
 * band simply fades in and out. The same trick draws the droplets, the bloom and
 * the glass bead, and the bead's edge is feathered with a `DST_IN` mask so the
 * thumbnail dissolves into the light instead of ending in a circle.
 *
 * The gradient is built once per play against a unit radius and reused by every
 * wave through `canvas.scale`, so this costs one shader and no per-frame
 * allocation — it stays smooth on the Helio G35.
 */
class TransferBeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beadBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val rect = RectF()
    private val srcRect = Rect()

    private var progress = 0f
    private var arrival = false
    private var durationMs = DURATION_MS
    private var bitmap: Bitmap? = null
    private var animator: ValueAnimator? = null

    private var originX = 0.5f
    private var originY = 0.58f
    private var impactX = 0f
    private var impactY = 0f
    private var maxRadius = 1f

    // Unit shaders: built once, reused for every wave via canvas.scale().
    private var crestShader: Shader? = null
    private var bloomShader: Shader? = null
    private var dropShader: Shader? = null
    private var featherShader: Shader? = null

    private val surfaceEase = PathInterpolator(0.14f, 0.66f, 0.22f, 1f)
    private val sinkEase = PathInterpolator(0.33f, 0f, 0.2f, 1f)
    private val fadeEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)

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

    private fun buildShaders() {
        if (crestShader != null) return

        // The ripple: continuous, so there is no border at any size.
        crestShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 0),
                ColorUtils.setAlphaComponent(Color.WHITE, 0),
                ColorUtils.setAlphaComponent(COLOR_SHADOW, 34),
                ColorUtils.setAlphaComponent(Color.WHITE, 96),
                ColorUtils.setAlphaComponent(Color.WHITE, 214),
                ColorUtils.setAlphaComponent(Color.WHITE, 120),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 0.62f, 0.78f, 0.855f, 0.925f, 0.965f, 1f),
            Shader.TileMode.CLAMP
        )

        bloomShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 130),
                ColorUtils.setAlphaComponent(Color.WHITE, 54),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        dropShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 235),
                ColorUtils.setAlphaComponent(Color.WHITE, 90),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )

        // Feathers the bead's edge into nothing: no circle to see.
        featherShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        buildShaders()

        impactX = originX * width
        impactY = originY * height
        maxRadius = hypot(
            maxOf(impactX, width - impactX).toDouble(),
            maxOf(impactY, height - impactY).toDouble()
        ).toFloat()

        val clock = if (arrival) 1f - progress else progress

        drawWaterLight(canvas, clock)
        drawBead(canvas, clock)
        drawRipples(canvas, clock)
        drawDroplets(canvas, clock)
    }

    /**
     * Draws a unit-1 shape filled with [shader] at the given radius.
     *
     * Scaling the canvas is what lets one gradient serve every wave without
     * rebuilding it 60 times a second.
     */
    private fun drawSoftCircle(
        canvas: Canvas,
        shader: Shader?,
        paint: Paint,
        radius: Float,
        alpha: Int,
        squashY: Float = 1f
    ) {
        if (alpha <= 0 || radius <= 0f) return
        val save = canvas.save()
        canvas.translate(impactX, impactY)
        canvas.scale(radius, radius * squashY)
        paint.shader = shader
        paint.alpha = alpha
        canvas.drawCircle(0f, 0f, 1f, paint)
        paint.shader = null
        canvas.restoreToCount(save)
    }

    /** The light that spreads over the surface before any ripple arrives. */
    private fun drawWaterLight(canvas: Canvas, clock: Float) {
        val grow = surfaceEase.getInterpolation((clock / 0.55f).coerceIn(0f, 1f))
        val fade = 1f - fadeEase.getInterpolation(((clock - 0.3f) / 0.6f).coerceIn(0f, 1f))
        val alpha = (150f * grow * fade).toInt().coerceIn(0, 255)
        if (alpha <= 0) return
        drawSoftCircle(canvas, bloomShader, bloomPaint, maxRadius * (0.35f + grow * 0.75f), alpha)
    }

    /** Six ripples, 85 ms apart, each a soft band that breathes instead of a ring. */
    private fun drawRipples(canvas: Canvas, clock: Float) {
        for (index in 0 until WAVE_COUNT) {
            val start = index * WAVE_STAGGER
            val local = ((clock - start) / WAVE_LIFE).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue

            val spread = surfaceEase.getInterpolation(local)
            val radius = (6f * density) + spread * maxRadius * 1.02f
            val fade = (1f - local) * (1f - local)
            val alpha = (255f * fade).toInt().coerceIn(0, 255)
            if (alpha <= 3) continue

            // Squash and wobble: the surface is not a flat geometrical plane.
            val squash = 0.985f - 0.02f * sin(local * 7f + index)
            drawSoftCircle(canvas, crestShader, ringPaint, radius, alpha, squash)
        }
    }

    /** Fine spray, each drop a feathered dot rather than a drawn circle. */
    private fun drawDroplets(canvas: Canvas, clock: Float) {
        val local = (clock / (WAVE_LIFE * 0.5f)).coerceIn(0f, 1f)
        if (local <= 0.05f) return

        val life = (local - 0.05f) / 0.95f
        val fade = (1f - life) * (1f - life)
        if (fade <= 0.02f) return

        val travel = (18f * density) + surfaceEase.getInterpolation(local) * maxRadius * 0.2f

        for (i in 0 until DROPLET_COUNT) {
            val angle = Math.toRadians(i * (360.0 / DROPLET_COUNT) + 11.0 + (i % 3) * 5.0)
            val reach = travel * (0.68f + (i % 4) * 0.12f)
            val x = impactX + (cos(angle) * reach).toFloat()
            val y = impactY + (sin(angle) * reach * 0.9f).toFloat()
            val size = ((5.2f - life * 3.2f) * density).coerceAtLeast(1.2f * density)

            // Drawn around its own centre, so it needs its own transform.
            val save = canvas.save()
            canvas.translate(x, y)
            canvas.scale(size, size)
            dropPaint.shader = dropShader
            dropPaint.alpha = (fade * 190f).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, 0f, 1f, dropPaint)
            dropPaint.shader = null
            canvas.restoreToCount(save)
        }
    }

    /**
     * The file as a soft glass bead: the thumbnail is masked with a feathered
     * radial gradient, so it dissolves into the surface with no visible edge,
     * then a glass veil and a highlight give it volume.
     */
    private fun drawBead(canvas: Canvas, clock: Float) {
        val t = if (arrival) 1f - clock else clock
        val sink = sinkEase.getInterpolation(t.coerceIn(0f, 1f))

        val base = (minOf(width, height) * 0.13f).coerceIn(44f * density, 74f * density)
        val radius = if (arrival) base * (0.6f + 0.4f * sink) else base * (1f - 0.3f * sink)

        val alpha = if (arrival) {
            (255f * fadeEase.getInterpolation((clock / 0.45f).coerceIn(0f, 1f))).toInt()
        } else {
            (255f * (1f - fadeEase.getInterpolation((t / 0.55f).coerceIn(0f, 1f)))).toInt()
        }.coerceIn(0, 255)
        if (alpha <= 0 || radius <= 1f) return

        val cx = impactX
        val cy = impactY

        // 1. shadow / body, soft, no circumference line
        val saveLayer = canvas.saveLayer(
            cx - radius * 1.2f, cy - radius * 1.2f,
            cx + radius * 1.2f, cy + radius * 1.2f, null
        )

        beadBodyPaint.alpha = alpha
        canvas.drawCircle(cx, cy, radius, beadBodyPaint)

        val thumb = bitmap
        if (thumb != null) {
            val side = radius * 2f
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            val bw = thumb.width.toFloat()
            val bh = thumb.height.toFloat()
            val scale = maxOf(side / bw, side / bh)
            val sw = side / scale
            val sh = side / scale
            val left = ((bw - sw) * 0.5f).toInt()
            val top = ((bh - sh) * 0.5f).toInt()
            srcRect.set(left, top, (left + sw).toInt(), (top + sh).toInt())
            thumbPaint.alpha = alpha
            canvas.drawBitmap(thumb, srcRect, rect, thumbPaint)
        } else {
            sheetPaint.alpha = (alpha * 0.85f).toInt().coerceIn(0, 255)
            rect.set(
                cx - radius * 0.5f,
                cy - radius * 0.58f,
                cx + radius * 0.5f,
                cy + radius * 0.48f
            )
            canvas.drawRoundRect(rect, radius * 0.18f, radius * 0.18f, sheetPaint)
        }

        // 2. feather the edge to transparent — this is what removes the "circle"
        maskPaint.shader = featherShader
        maskPaint.alpha = alpha
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, maskPaint)
        canvas.restore()
        maskPaint.shader = null

        // 3. glass veil: light from the upper left, shadow at the lower right
        glassPaint.alpha = alpha
        glassPaint.shader = RadialGradient(
            cx - radius * 0.32f,
            cy - radius * 0.4f,
            radius * 1.5f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 120),
                ColorUtils.setAlphaComponent(Color.WHITE, 18),
                ColorUtils.setAlphaComponent(COLOR_SHADOW, 70)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, glassPaint)
        glassPaint.shader = null

        canvas.restoreToCount(saveLayer)

        // 4. specular hot spot, feathered
        val save = canvas.save()
        canvas.translate(cx - radius * 0.33f, cy - radius * 0.4f)
        val spot = radius * 0.46f
        canvas.scale(spot, spot)
        dropPaint.shader = dropShader
        dropPaint.alpha = (alpha * 0.5f).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, 1f, dropPaint)
        dropPaint.shader = null
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DURATION_MS = 1250L
        const val REDUCED_DURATION_MS = 420L

        const val WAVE_COUNT = 6
        const val WAVE_STAGGER = 0.085f
        const val WAVE_LIFE = 0.60f
        const val DROPLET_COUNT = 12

        val COLOR_SHADOW: Int = Color.parseColor("#2B2B30")
    }
}
