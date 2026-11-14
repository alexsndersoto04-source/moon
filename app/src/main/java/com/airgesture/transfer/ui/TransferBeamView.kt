package com.airgesture.transfer.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The 2.0 hand-off: a singularity, not a splash.
 *
 * The water ripples (1.6–1.7) were geometrically right and emotionally flat:
 * concentric circles read as "decoration". This version tells the story of the
 * file actually leaving the phone, in four beats over about a second:
 *
 * ```
 * 0.00  the ring closes          a band of light falls inward from the edges
 * 0.00  sparks converge          18 motes spiral into the point of contact
 * 0.10  the card is pulled in    the real thumbnail shrinks, tilts, dissolves
 * 0.36  the flash                a hot core and a lens flare: it is gone
 * 0.34  the beam                 an elongated streak shoots out of the phone
 * 0.78  the wake                 the streak retracts, the shockwave dies
 * ```
 *
 * Every shape is a **radial gradient stretched by `canvas.scale`**, so nothing
 * here is a stroked outline and nothing allocates per frame: one shader serves
 * the ring, the beam, the sparks and the flare. That is what keeps it smooth on
 * a Helio G35, where a `Paint` per frame is enough to lose the animation.
 *
 * Arrival runs the same timeline backwards (the beam comes in, the ring opens,
 * the card unfolds), so send and receive speak the same language.
 */
class TransferBeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        colorFilter = PorterDuffColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN)
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val rectF = RectF()
    private val srcRect = Rect()

    private var progress = 0f
    private var arrival = false
    private var durationMs = DURATION_MS
    private var bitmap: Bitmap? = null
    private var animator: ValueAnimator? = null

    private var originX = 0.5f
    private var originY = 0.58f
    private var cx = 0f
    private var cy = 0f
    private var reach = 1f

    private var coreShader: Shader? = null
    private var bloomShader: Shader? = null
    private var ringShader: Shader? = null
    private var streakShader: Shader? = null
    private var featherShader: Shader? = null

    private val gatherEase = PathInterpolator(0.36f, 0f, 0.2f, 1f)
    private val launchEase = PathInterpolator(0.1f, 0.72f, 0.16f, 1f)
    private val fadeEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    // Deterministic motes: a fixed layout looks intentional, a random one per
    // frame looks like noise (and allocates).
    private val sparkAngle = FloatArray(SPARK_COUNT)
    private val sparkStart = FloatArray(SPARK_COUNT)
    private val sparkSize = FloatArray(SPARK_COUNT)

    init {
        setWillNotDraw(false)
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val random = Random(20261114)
        for (i in 0 until SPARK_COUNT) {
            sparkAngle[i] = random.nextFloat() * TWO_PI
            sparkStart[i] = 0.45f + random.nextFloat() * 0.55f
            sparkSize[i] = 0.6f + random.nextFloat() * 1.2f
        }
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
                // On arrival the card ends up fully visible at the contact point;
                // without this short fade the overlay would vanish mid-frame.
                if (isArrival) {
                    alpha = ((1f - progress) / 0.12f).coerceIn(0f, 1f)
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (progress >= 1f) visibility = GONE
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

    // ---------------------------------------------------------------- shaders

    private fun buildShaders() {
        if (coreShader != null) return

        coreShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 255),
                ColorUtils.setAlphaComponent(Color.WHITE, 150),
                ColorUtils.setAlphaComponent(COLOR_ACCENT, 60),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 0.2f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        bloomShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 130),
                ColorUtils.setAlphaComponent(Color.WHITE, 34),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )

        // One narrow band: the ring has a face and a shadow, no outline.
        ringShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 0),
                ColorUtils.setAlphaComponent(Color.WHITE, 0),
                ColorUtils.setAlphaComponent(COLOR_SHADOW, 36),
                ColorUtils.setAlphaComponent(Color.WHITE, 190),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 0.62f, 0.775f, 0.845f, 1f),
            Shader.TileMode.CLAMP
        )

        // The comet: hot head, long tail. Stretched into an ellipse on draw.
        streakShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 235),
                ColorUtils.setAlphaComponent(Color.WHITE, 110),
                ColorUtils.setAlphaComponent(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 0.34f, 1f),
            Shader.TileMode.CLAMP
        )

        featherShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        buildShaders()

        cx = originX * width
        cy = originY * height
        reach = hypot(
            maxOf(cx, width - cx).toDouble(),
            maxOf(cy, height - cy).toDouble()
        ).toFloat().coerceAtLeast(1f)

        val t = if (arrival) 1f - progress else progress

        drawBloom(canvas, t)
        drawRing(canvas, t)
        drawSparks(canvas, t)
        drawCard(canvas, t)
        drawBeam(canvas, t)
        drawCore(canvas, t)
    }

    private fun drawBloom(canvas: Canvas, t: Float) {
        val grow = gatherEase.getInterpolation((t / 0.45f).coerceIn(0f, 1f))
        val fade = 1f - fadeEase.getInterpolation(((t - 0.5f) / 0.5f).coerceIn(0f, 1f))
        val alpha = (135f * (0.4f + grow * 0.6f) * fade).toInt().coerceIn(0, 255)
        drawGlow(canvas, bloomShader, glowPaint, reach * (0.16f + grow * 0.5f), alpha, 0.9f)
    }

    /** A band of light closing in, then a shockwave running away from the point. */
    private fun drawRing(canvas: Canvas, t: Float) {
        val close = (t / 0.34f).coerceIn(0f, 1f)
        if (close < 1f) {
            val eased = gatherEase.getInterpolation(close)
            val radius = reach * (0.9f - 0.86f * eased) + 12f * density
            val alpha = (210f * sin(PI * close)).toInt().coerceIn(0, 255)
            drawGlow(canvas, ringShader, glowPaint, radius, alpha, 0.94f)
        }

        val wave = ((t - 0.28f) / 0.44f).coerceIn(0f, 1f)
        if (wave > 0f && wave < 1f) {
            val radius = 16f * density + launchEase.getInterpolation(wave) * reach * 1.12f
            val fade = (1f - wave) * (1f - wave)
            val alpha = (170f * fade).toInt().coerceIn(0, 255)
            drawGlow(canvas, ringShader, glowPaint, radius, alpha, 0.92f)
        }
    }

    /** Eighteen motes falling into the point, each a soft dot rather than a circle. */
    private fun drawSparks(canvas: Canvas, t: Float) {
        val pull = gatherEase.getInterpolation((t / 0.40f).coerceIn(0f, 1f))
        if (pull >= 1f) return
        val alpha = (175f * (1f - pull) * (1f - pull * 0.4f)).toInt().coerceIn(0, 255)
        if (alpha <= 2) return

        for (i in 0 until SPARK_COUNT) {
            val angle = sparkAngle[i] + pull * 1.5f
            val radius = reach * sparkStart[i] * (1f - pull) + 7f * density * pull
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius * 0.92f
            val size = sparkSize[i] * (5.6f - 3.4f * pull) * density
            drawGlow(canvas, streakShader, sparkPaint, size, alpha, 1f, x, y)
        }
    }

    /** The file itself: pulled in and dissolved, or unfolding on arrival. */
    private fun drawCard(canvas: Canvas, t: Float) {
        val shrink = gatherEase.getInterpolation(((t - 0.04f) / 0.34f).coerceIn(0f, 1f))
        val scale = 1f - 0.93f * shrink
        val fade = 1f - fadeEase.getInterpolation(((t - 0.12f) / 0.28f).coerceIn(0f, 1f))
        val alpha = (255f * fade).toInt().coerceIn(0, 255)
        if (alpha <= 2 || scale <= 0.02f) return

        val base = (minOf(width, height) * 0.135f).coerceIn(46f * density, 78f * density)
        val radius = base * scale

        val rotation = 11f * shrink * (if (arrival) -1f else 1f)
        canvas.save()
        canvas.rotate(rotation, cx, cy)

        val layer = canvas.saveLayer(
            cx - radius * 1.35f, cy - radius * 1.35f,
            cx + radius * 1.35f, cy + radius * 1.35f, null
        )

        val thumb = bitmap
        if (thumb != null && !thumb.isRecycled) {
            rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
            val side = radius * 2f
            val ratio = maxOf(side / thumb.width.toFloat(), side / thumb.height.toFloat())
            val sw = (side / ratio).toInt().coerceAtLeast(1)
            val sh = (side / ratio).toInt().coerceAtLeast(1)
            val left = ((thumb.width - sw) / 2).coerceAtLeast(0)
            val top = ((thumb.height - sh) / 2).coerceAtLeast(0)
            srcRect.set(left, top, left + sw, top + sh)
            cardPaint.alpha = alpha
            canvas.drawBitmap(thumb, srcRect, rectF, cardPaint)
        } else {
            // No thumbnail: a soft sheet of paper, the shape of the file itself.
            sheetPaint.alpha = (alpha * 0.9f).toInt().coerceIn(0, 255)
            rectF.set(
                cx - radius * 0.62f,
                cy - radius * 0.78f,
                cx + radius * 0.62f,
                cy + radius * 0.78f
            )
            canvas.drawRoundRect(rectF, radius * 0.16f, radius * 0.16f, sheetPaint)
        }

        // Feather the edge into nothing: the card dissolves into the light
        // instead of ending in a visible cut.
        maskPaint.shader = featherShader
        maskPaint.alpha = alpha
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, maskPaint)
        canvas.restore()
        maskPaint.shader = null

        // Light from the upper left, so the thumbnail has volume.
        drawGlow(
            canvas, bloomShader, glowPaint,
            radius * 0.85f, (alpha * 0.32f).toInt().coerceIn(0, 255), 1f,
            cx - radius * 0.32f, cy - radius * 0.38f
        )

        canvas.restoreToCount(layer)
        canvas.restore()
    }

    /** The streak that leaves the phone: elongated gradient, no drawn line. */
    private fun drawBeam(canvas: Canvas, t: Float) {
        val local = ((t - 0.34f) / 0.44f).coerceIn(0f, 1f)
        if (local <= 0f) return
        val grow = launchEase.getInterpolation(local)
        val fade = 1f - ((t - 0.76f) / 0.24f).coerceIn(0f, 1f)
        if (fade <= 0.02f) return

        val length = (70f * density) + grow * height * 0.92f
        val halfWidth = ((7f + (1f - grow) * 11f) * density)
        val headY = cy - length * 0.52f
        val alpha = (205f * grow * fade).toInt().coerceIn(0, 255)

        // Chromatic split: the halo leads, the white core follows.
        drawStreak(canvas, cx, headY + 4f * density, halfWidth * 1.9f, length * 0.8f,
            (alpha * 0.34f).toInt().coerceIn(0, 255), haloPaint)
        drawStreak(canvas, cx, headY, halfWidth, length * 0.66f, alpha, streakPaint)
    }

    private fun drawStreak(
        canvas: Canvas,
        x: Float,
        y: Float,
        halfWidth: Float,
        halfHeight: Float,
        alpha: Int,
        paint: Paint
    ) {
        if (alpha <= 0 || halfWidth <= 0f || halfHeight <= 0f) return
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.scale(halfWidth, halfHeight)
        paint.shader = streakShader
        paint.alpha = alpha
        canvas.drawCircle(0f, 0f, 1f, paint)
        paint.shader = null
        canvas.restoreToCount(save)
    }

    /** The instant of departure: hot core plus a horizontal lens flare. */
    private fun drawCore(canvas: Canvas, t: Float) {
        val z = (t - 0.36f) / 0.11f
        val flash = exp(-(z * z)).toFloat()
        if (flash <= 0.01f) return

        val radius = (11f + 44f * flash) * density
        drawGlow(canvas, coreShader, corePaint, radius, (255f * flash).toInt().coerceIn(0, 255), 1f)
        drawGlow(
            canvas, bloomShader, glowPaint,
            radius * 4.6f, (255f * flash * 0.45f).toInt().coerceIn(0, 255), 0.09f
        )
    }

    /**
     * Draws a unit shape filled with [shader] at the requested size. Scaling the
     * canvas instead of rebuilding the gradient is what keeps this allocation-free
     * at 60 fps.
     */
    private fun drawGlow(
        canvas: Canvas,
        shader: Shader?,
        paint: Paint,
        radius: Float,
        alpha: Int,
        squashY: Float = 1f,
        x: Float = cx,
        y: Float = cy
    ) {
        if (alpha <= 0 || radius <= 0f) return
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.scale(radius, radius * squashY)
        paint.shader = shader
        paint.alpha = alpha
        canvas.drawCircle(0f, 0f, 1f, paint)
        paint.shader = null
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DURATION_MS = 1050L
        const val REDUCED_DURATION_MS = 380L
        const val SPARK_COUNT = 18

        val TWO_PI = (PI * 2.0).toFloat()

        val COLOR_SHADOW: Int = Color.parseColor("#2B2B30")
        val COLOR_ACCENT: Int = Color.parseColor("#B4855C")
    }
}
