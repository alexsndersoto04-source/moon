package com.airgesture.transfer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Water-surface send effect.
 *
 * The eye must read *volume*, not outlines. Three ideas carry that:
 *
 *  1. **Every ridge is drawn in layers.** A real ripple has a wide dark trough, a
 *     narrow bright crest and a soft bloom around it. Each wave is painted four
 *     times — wide and faint, medium, tight and bright, then a specular pass —
 *     which is what turns a flat circle into something that looks lit.
 *  2. **Light comes from somewhere.** The ridge is bright at the upper-left and
 *     fades toward the lower-right, driven by a sweep gradient anchored on the
 *     impact point. A ripple lit evenly in every direction is a drawing; a ripple
 *     lit from one side is water.
 *  3. **Nothing is a perfect circle.** Each wave carries a slow radial wobble, so
 *     the ridge breathes instead of being a geometric ring.
 *
 * The file itself becomes a **glass bead** at the impact point: it is carried on
 * the surface, squashes the ripples around it and dissolves as they pass.
 */
class TransferBeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // --- paints ---------------------------------------------------------------
    private val troughPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = TROUGH
    }
    private val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dropletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beadShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beadRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLUMN
    }

    private val rect = RectF()
    private val clip = Path()
    private val ridge = Path()

    // --- state ----------------------------------------------------------------
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

    /** Direction of the light, as a sweep gradient around the impact point. */
    private var lightShader: SweepGradient? = null
    private var lightShaderRadius = -1f

    private val surfaceEase = PathInterpolator(0.16f, 0.62f, 0.24f, 1f)
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
        lightShader = null
        lightShaderRadius = -1f

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

        impactX = originX * width
        impactY = originY * height
        maxRadius = hypot(
            maxOf(impactX, width - impactX).toDouble(),
            maxOf(impactY, height - impactY).toDouble()
        ).toFloat()

        val clock = if (arrival) 1f - progress else progress

        ensureLightShader()

        drawCaustics(canvas, clock)
        drawBead(canvas)
        drawWaves(canvas, clock)
        drawDroplets(canvas, clock)
        drawSurfaceSheen(canvas, clock)
    }

    // --------------------------------------------------------------- lighting

    /**
     * A sweep gradient around the impact point: bright white at the upper-left,
     * almost nothing at the lower-right. Applied to every ridge, it is what makes
     * the rings read as cut out of a lit surface instead of stroked circles.
     */
    private fun ensureLightShader() {
        val radius = maxRadius
        if (lightShader != null && lightShaderRadius == radius) return
        lightShaderRadius = radius
        lightShader = SweepGradient(
            impactX,
            impactY,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 255),
                ColorUtils.setAlphaComponent(Color.WHITE, 150),
                ColorUtils.setAlphaComponent(Color.WHITE, 255)
            ),
            floatArrayOf(0.62f, 0.06f, 0.30f)
        )
    }

    // ----------------------------------------------------------------- waves

    /**
     * Six ridges born [WAVE_STAGGER] apart. Each one is painted in four layers:
     *
     * ```
     * bloom   wide, very faint   → the mist above the water
     * trough  narrow, dark       → the depression behind the crest
     * ridge   tight, white       → the crest itself, lit by [lightShader]
     * glint   hair-thin, bright  → the specular line on top of the crest
     * ```
     */
    private fun drawWaves(canvas: Canvas, clock: Float) {
        for (index in 0 until WAVE_COUNT) {
            val start = index * WAVE_STAGGER
            val local = ((clock - start) / WAVE_LIFE).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue

            val spread = surfaceEase.getInterpolation(local)
            val radius = (7f * density) + spread * maxRadius
            val fade = (1f - local) * (1f - local)
            if (fade <= 0.01f) continue

            // The ripple is never a perfect circle: it breathes.
            val wobble = 1f + 0.012f * sin(local * 9f + index)
            val rx = radius * wobble
            val ry = radius / wobble

            // 1. bloom — wide and faint, the light scattered off the crest
            bloomPaint.alpha = (fade * 46f).toInt().coerceIn(0, 255)
            bloomPaint.strokeWidth = (11f - 5f * local) * density
            canvas.drawOval(oval(rx, ry), bloomPaint)

            // 2. trough — the dark ring that gives the crest its depth
            troughPaint.alpha = (fade * 62f).toInt().coerceIn(0, 255)
            troughPaint.strokeWidth = (3.2f - 1.4f * local) * density
            canvas.drawOval(oval(rx * 0.995f, ry * 0.995f), troughPaint)

            // 3. ridge — the crest, carrying the light direction
            ridgePaint.shader = lightShader
            ridgePaint.alpha = (fade * 235f).toInt().coerceIn(0, 255)
            ridgePaint.strokeWidth = (2.1f - 1.0f * local) * density
            canvas.drawOval(oval(rx, ry), ridgePaint)

            // 4. glint — hair-thin specular on the crest, only on the young waves
            if (index <= 2) {
                ridgePaint.alpha = (fade * 150f).toInt().coerceIn(0, 255)
                ridgePaint.strokeWidth = 0.9f * density
                canvas.drawOval(oval(rx * 1.004f, ry * 1.004f), ridgePaint)
            }
            ridgePaint.shader = null
        }
    }

    private fun oval(rx: Float, ry: Float): RectF {
        rect.set(impactX - rx, impactY - ry, impactX + rx, impactY + ry)
        return rect
    }

    // --------------------------------------------------------------- droplets

    /** A fine spray thrown off the crest of the first wave, each with a soft edge. */
    private fun drawDroplets(canvas: Canvas, clock: Float) {
        val local = (clock / (WAVE_LIFE * 0.55f)).coerceIn(0f, 1f)
        if (local <= 0.04f) return

        val life = (local - 0.04f) / 0.96f
        val fade = (1f - life) * (1f - life)
        if (fade <= 0.01f) return

        val travel = (24f * density) + surfaceEase.getInterpolation(local) * maxRadius * 0.22f

        for (i in 0 until DROPLET_COUNT) {
            val angle = Math.toRadians((i * (360.0 / DROPLET_COUNT)) + 12.0 + (i % 2) * 6.0)
            val reach = travel * (0.72f + (i % 4) * 0.11f)
            val x = impactX + (cos(angle) * reach).toFloat()
            val y = impactY + (sin(angle) * reach * 0.92f).toFloat()
            val r = ((2.4f - life * 1.6f) * density).coerceAtLeast(0.4f * density)

            // halo then core: a dot is a dot, a drop has a highlight.
            dropletPaint.alpha = (fade * 54f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r * 2.6f, dropletPaint)
            dropletPaint.alpha = (fade * 210f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r, dropletPaint)
        }
    }

    // --------------------------------------------------------------- caustics

    /** The pool of light the stone leaves on the surface, sitting under the waves. */
    private fun drawCaustics(canvas: Canvas, clock: Float) {
        val grow = surfaceEase.getInterpolation((clock / 0.5f).coerceIn(0f, 1f))
        val fade = 1f - fadeEase.getInterpolation(((clock - 0.22f) / 0.6f).coerceIn(0f, 1f))
        val alpha = (128f * grow * fade).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        val radius = (18f * density) + grow * maxRadius * 0.42f
        causticPaint.shader = RadialGradient(
            impactX,
            impactY,
            radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 240),
                ColorUtils.setAlphaComponent(Color.WHITE, 90),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        causticPaint.alpha = alpha
        canvas.drawCircle(impactX, impactY, radius, causticPaint)
        causticPaint.shader = null
    }

    /** A single soft highlight that sweeps the whole surface as the waves travel. */
    private fun drawSurfaceSheen(canvas: Canvas, clock: Float) {
        val t = (clock / 0.85f).coerceIn(0f, 1f)
        val fade = (1f - t) * 0.5f
        if (fade <= 0.01f) return
        val radius = (30f * density) + t * maxRadius * 0.9f

        causticPaint.shader = RadialGradient(
            impactX,
            impactY,
            radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 0),
                ColorUtils.setAlphaComponent(Color.WHITE, 26),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0.55f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        causticPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(impactX, impactY, radius, causticPaint)
        causticPaint.shader = null
    }

    // -------------------------------------------------------------------- bead

    /**
     * The file as a glass bead sitting on the water: it is lifted by the first
     * wave, squashed by the surface, rim-lit from the light direction and finally
     * dissolved into the ripple that passes over it.
     */
    private fun drawBead(canvas: Canvas) {
        val t = if (arrival) 1f - progress else progress
        val sink = sinkEase.getInterpolation(t.coerceIn(0f, 1f))

        val radius = (minOf(width, height) * 0.115f).coerceIn(38f * density, 62f * density) *
            (if (arrival) (0.55f + 0.45f * sink) else (1f - 0.34f * sink))

        val alpha = if (arrival) {
            (255f * fadeEase.getInterpolation((progress / 0.45f).coerceIn(0f, 1f))).toInt()
        } else {
            (255f * (1f - fadeEase.getInterpolation((t / 0.5f).coerceIn(0f, 1f)))).toInt()
        }.coerceIn(0, 255)
        if (alpha <= 0) return

        // Squash: the bead is pressed into the surface, so it is wider than tall.
        val squash = 1f - 0.12f * sink
        val cx = impactX
        val cy = impactY

        // soft contact shadow under the bead
        beadShadePaint.alpha = (alpha * 0.16f).toInt().coerceIn(0, 255)
        beadShadePaint.shader = RadialGradient(
            cx,
            cy + radius * 0.34f,
            radius * 1.5f,
            intArrayOf(
                ColorUtils.setAlphaComponent(TROUGH, 200),
                ColorUtils.setAlphaComponent(TROUGH, 0)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + radius * 0.34f, radius * 1.5f, beadShadePaint)
        beadShadePaint.shader = null

        val save = canvas.save()
        canvas.scale(1f, squash, cx, cy)

        // body
        beadPaint.alpha = alpha
        canvas.drawCircle(cx, cy, radius, beadPaint)

        // the file, clipped inside the glass
        clip.reset()
        clip.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(clip)

        val thumb = bitmap
        if (thumb != null) {
            thumbPaint.alpha = alpha
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            drawCentreCrop(canvas, thumb, rect)
        } else {
            sheetPaint.alpha = (alpha * 0.9f).toInt().coerceIn(0, 255)
            rect.set(
                cx - radius * 0.52f,
                cy - radius * 0.62f,
                cx + radius * 0.52f,
                cy + radius * 0.5f
            )
            canvas.drawRoundRect(rect, radius * 0.16f, radius * 0.16f, sheetPaint)
        }

        // glass: a cool veil that makes the bead read as a dome, not a photo
        beadShadePaint.alpha = (alpha * 0.20f).toInt().coerceIn(0, 255)
        beadShadePaint.shader = RadialGradient(
            cx - radius * 0.3f,
            cy - radius * 0.45f,
            radius * 1.45f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 210),
                ColorUtils.setAlphaComponent(Color.WHITE, 30),
                ColorUtils.setAlphaComponent(TROUGH, 120)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, beadShadePaint)
        beadShadePaint.shader = null

        canvas.restoreToCount(save)

        // rim light on the lit side, thin on the shadow side
        beadRimPaint.alpha = (alpha * 0.85f).toInt().coerceIn(0, 255)
        beadRimPaint.strokeWidth = 1.5f * density
        rect.set(cx - radius, cy - radius * squash, cx + radius, cy + radius * squash)
        canvas.drawArc(rect, 145f, 190f, false, beadRimPaint)

        beadRimPaint.alpha = (alpha * 0.28f).toInt().coerceIn(0, 255)
        canvas.drawArc(rect, -35f, 190f, false, beadRimPaint)

        // specular hot spot
        specularPaint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
        specularPaint.shader = RadialGradient(
            cx - radius * 0.34f,
            cy - radius * 0.42f,
            radius * 0.5f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 255),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx - radius * 0.34f, cy - radius * 0.42f, radius * 0.5f, specularPaint)
        specularPaint.shader = null
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
        const val DURATION_MS = 1250L
        const val REDUCED_DURATION_MS = 420L

        const val WAVE_COUNT = 6
        const val WAVE_STAGGER = 0.085f
        const val WAVE_LIFE = 0.60f
        const val DROPLET_COUNT = 12

        val TROUGH: Int = Color.parseColor("#3A3A3E")
        val COLUMN: Int = Color.parseColor("#F1EDE4")
    }
}
