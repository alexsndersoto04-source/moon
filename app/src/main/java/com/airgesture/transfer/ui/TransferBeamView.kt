package com.airgesture.transfer.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.sin

/**
 * Full-screen, AirDrop-inspired hand-off animation.
 *
 * The selected file travels from its shelf position to a luminous nearby-device
 * target. The transfer itself is still owned by NearbyTransferManager; this view
 * is a visual hand-off, not a fake progress indicator or success notification.
 * All geometry and paints are reused between frames so the animation stays light
 * enough for older Android phones.
 */
class TransferBeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val trailGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = CYAN
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val targetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val tileImagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 175
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val curve = Path()
    private val visibleTrail = Path()
    private val pathMeasure = PathMeasure()
    private val tilePath = Path()
    private val documentPath = Path()
    private val tileRect = RectF()
    private val imageRect = RectF()
    private val imageSource = Rect()
    private val arcRect = RectF()
    private val point = PointF()

    private val auraShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(ColorUtils.setAlphaComponent(CYAN, 190), ColorUtils.setAlphaComponent(BLUE, 95), Color.TRANSPARENT),
        floatArrayOf(0f, 0.48f, 1f),
        Shader.TileMode.CLAMP
    )

    private var progress = 0f
    private var isArrival = false
    private var bitmap: Bitmap? = null
    private var animator: ValueAnimator? = null
    private var originX = 0.5f
    private var originY = 0.82f
    private var targetX = 0f
    private var targetY = 0f
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var maxRadius = 1f

    private val flightEase = PathInterpolator(0.18f, 0.78f, 0.22f, 1f)
    private val fadeEase = PathInterpolator(0.35f, 0f, 0.2f, 1f)

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
        this.isArrival = isArrival
        originX = origin?.x ?: 0.5f
        originY = origin?.y ?: 0.82f
        bitmap = thumbnail

        animator?.cancel()
        progress = 0f
        visibility = VISIBLE
        alpha = 1f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (reduceMotion) REDUCED_DURATION_MS else DURATION_MS
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

        configureFlightPath()
        val eased = flightEase.getInterpolation(progress.coerceIn(0f, 1f))
        val entrance = (progress / 0.12f).coerceIn(0f, 1f)
        val outro = 1f - fadeEase.getInterpolation(((progress - 0.88f) / 0.12f).coerceIn(0f, 1f))
        val visibilityAlpha = (entrance * outro * 255f).toInt().coerceIn(0, 255)

        // A cinematic, translucent tint leaves the real screen visible beneath it.
        canvas.drawColor(Color.argb((88f * entrance * outro).toInt(), 4, 9, 28))
        drawAmbientLight(canvas, targetX, targetY, maxRadius * 0.48f, (160f * entrance * outro).toInt())
        drawFlightTrail(canvas, eased, visibilityAlpha)
        drawParticles(canvas, eased, visibilityAlpha)
        drawDeviceTarget(canvas, progress, visibilityAlpha)
        drawFileTile(canvas, eased, visibilityAlpha)
        drawCaptions(canvas, visibilityAlpha)
    }

    private fun configureFlightPath() {
        targetX = width * 0.5f
        targetY = height * 0.39f
        val shelfX = (originX * width).coerceIn(width * 0.12f, width * 0.88f)
        val shelfY = (originY * height).coerceIn(height * 0.15f, height * 0.94f)

        if (isArrival) {
            startX = targetX
            startY = targetY
            endX = shelfX
            endY = shelfY
        } else {
            startX = shelfX
            startY = shelfY
            endX = targetX
            endY = targetY
        }

        val dx = endX - startX
        val dy = endY - startY
        curve.reset()
        curve.moveTo(startX, startY)
        curve.cubicTo(
            startX + dx * 0.14f,
            startY + dy * 0.34f,
            endX - dx * 0.16f,
            endY - dy * 0.34f,
            endX,
            endY
        )
        pathMeasure.setPath(curve, false)
        visibleTrail.reset()

        maxRadius = kotlin.math.hypot(width.toDouble(), height.toDouble()).toFloat()
    }

    private fun drawAmbientLight(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        if (alpha <= 0) return
        val save = canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        targetFillPaint.shader = auraShader
        targetFillPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, 1f, targetFillPaint)
        targetFillPaint.shader = null
        canvas.restoreToCount(save)
    }

    private fun drawFlightTrail(canvas: Canvas, t: Float, alpha: Int) {
        val length = pathMeasure.length
        if (length <= 0f || t <= 0f) return
        visibleTrail.reset()
        pathMeasure.getSegment(0f, length * t.coerceIn(0f, 1f), visibleTrail, true)

        val fade = (alpha * (0.8f - 0.35f * t)).toInt().coerceIn(0, 255)
        trailGlowPaint.strokeWidth = 19f * density
        trailGlowPaint.alpha = (fade * 0.16f).toInt()
        canvas.drawPath(visibleTrail, trailGlowPaint)
        trailGlowPaint.strokeWidth = 7f * density
        trailGlowPaint.alpha = (fade * 0.42f).toInt()
        canvas.drawPath(visibleTrail, trailGlowPaint)
        trailPaint.strokeWidth = 1.6f * density
        trailPaint.alpha = (fade * 0.88f).toInt()
        canvas.drawPath(visibleTrail, trailPaint)
    }

    private fun drawParticles(canvas: Canvas, t: Float, alpha: Int) {
        if (t <= 0.02f) return
        for (index in 0 until PARTICLE_COUNT) {
            val particleT = t - index * 0.028f
            if (particleT <= 0f) continue
            sampleCurve(particleT, point)
            val wave = sin((particleT * 8.5f + index * 1.7f).toDouble()).toFloat()
            val side = wave * (3.5f + index % 4) * density
            val fade = (alpha * (1f - index / (PARTICLE_COUNT * 1.2f))).toInt().coerceIn(0, 255)
            particlePaint.color = if (index % 3 == 0) CYAN else Color.WHITE
            particlePaint.alpha = fade
            val size = (1.2f + (index % 4) * 0.42f) * density
            canvas.drawCircle(point.x + side, point.y - side * 0.52f, size, particlePaint)
        }
    }

    private fun drawDeviceTarget(canvas: Canvas, t: Float, alpha: Int) {
        val pulse = 0.5f + 0.5f * sin((t * Math.PI * 4.0).toFloat())
        val aura = (180f + pulse * 55f).toInt().coerceIn(0, 255)
        drawAmbientLight(canvas, targetX, targetY, (74f + pulse * 12f) * density, (aura * alpha / 255))

        for (ring in 0 until 3) {
            val radius = (30f + ring * 14f + pulse * 3f) * density
            targetPaint.color = if (ring == 0) Color.WHITE else CYAN
            targetPaint.alpha = ((0.82f - ring * 0.18f) * alpha).toInt().coerceIn(0, 255)
            targetPaint.strokeWidth = (if (ring == 0) 1.6f else 1f) * density
            canvas.drawCircle(targetX, targetY, radius, targetPaint)
        }

        val coreRadius = 23f * density
        targetFillPaint.shader = null
        targetFillPaint.color = Color.argb((alpha * 0.26f).toInt(), 105, 197, 255)
        canvas.drawCircle(targetX, targetY, coreRadius, targetFillPaint)
        targetPaint.color = Color.WHITE
        targetPaint.alpha = (alpha * 0.9f).toInt()
        targetPaint.strokeWidth = 2f * density
        canvas.drawCircle(targetX, targetY, coreRadius, targetPaint)

        // Minimal radiating-device glyph, drawn as crisp vector strokes.
        val glyphRadius = 10f * density
        arcRect.set(targetX - glyphRadius, targetY - glyphRadius, targetX + glyphRadius, targetY + glyphRadius)
        targetPaint.strokeWidth = 1.8f * density
        targetPaint.alpha = alpha
        canvas.drawArc(arcRect, 205f, 130f, false, targetPaint)
        arcRect.inset(4f * density, 4f * density)
        canvas.drawArc(arcRect, 205f, 130f, false, targetPaint)
        targetFillPaint.color = Color.WHITE
        targetFillPaint.alpha = alpha
        canvas.drawCircle(targetX, targetY + 5f * density, 1.5f * density, targetFillPaint)
        targetPaint.style = Paint.Style.STROKE
    }

    private fun drawFileTile(canvas: Canvas, t: Float, alpha: Int) {
        sampleCurve(t, point)
        val scale = if (isArrival) 0.52f + 0.48f * t else 1f - 0.48f * t
        val tileWidth = 76f * density * scale
        val tileHeight = 84f * density * scale
        val left = point.x - tileWidth * 0.5f
        val top = point.y - tileHeight * 0.5f
        val radius = 16f * density * scale
        tileRect.set(left, top, left + tileWidth, top + tileHeight)
        val tileAlpha = (alpha * (if (t > 0.90f) (1f - (t - 0.90f) / 0.10f).coerceIn(0f, 1f) else 1f)).toInt()

        drawAmbientLight(canvas, point.x, point.y, tileWidth * 1.05f, (tileAlpha * 0.48f).toInt())
        tilePaint.color = Color.argb((tileAlpha * 0.94f).toInt(), 239, 248, 255)
        canvas.drawRoundRect(tileRect, radius, radius, tilePaint)

        tilePath.reset()
        tilePath.addRoundRect(tileRect, radius, radius, Path.Direction.CW)
        val thumb = bitmap
        if (thumb != null && !thumb.isRecycled) {
            val inset = 4f * density * scale
            imageRect.set(tileRect.left + inset, tileRect.top + inset, tileRect.right - inset, tileRect.bottom - inset)
            val sourceWidth = thumb.width.toFloat()
            val sourceHeight = thumb.height.toFloat()
            val cropScale = maxOf(imageRect.width() / sourceWidth, imageRect.height() / sourceHeight)
            val cropWidth = imageRect.width() / cropScale
            val cropHeight = imageRect.height() / cropScale
            val cropLeft = (sourceWidth - cropWidth) * 0.5f
            val cropTop = (sourceHeight - cropHeight) * 0.5f
            imageSource.set(cropLeft.toInt(), cropTop.toInt(), (cropLeft + cropWidth).toInt(), (cropTop + cropHeight).toInt())
            val save = canvas.save()
            canvas.clipPath(tilePath)
            tileImagePaint.alpha = tileAlpha
            canvas.drawBitmap(thumb, imageSource, imageRect, tileImagePaint)
            canvas.restoreToCount(save)
        } else {
            drawDocumentGlyph(canvas, tileRect, tileAlpha)
        }

        targetPaint.color = Color.WHITE
        targetPaint.alpha = (tileAlpha * 0.9f).toInt()
        targetPaint.strokeWidth = 1.1f * density
        targetPaint.style = Paint.Style.STROKE
        canvas.drawRoundRect(tileRect, radius, radius, targetPaint)
    }

    private fun drawDocumentGlyph(canvas: Canvas, rect: RectF, alpha: Int) {
        targetFillPaint.shader = null
        targetFillPaint.color = Color.argb((alpha * 0.9f).toInt(), 95, 167, 223)
        val left = rect.left + rect.width() * 0.27f
        val right = rect.right - rect.width() * 0.27f
        val top = rect.top + rect.height() * 0.2f
        val bottom = rect.bottom - rect.height() * 0.2f
        val corner = rect.width() * 0.11f
        documentPath.reset()
        documentPath.moveTo(left, top)
        documentPath.lineTo(right - corner, top)
        documentPath.lineTo(right, top + corner)
        documentPath.lineTo(right, bottom)
        documentPath.lineTo(left, bottom)
        documentPath.close()
        canvas.drawPath(documentPath, targetFillPaint)
        targetPaint.color = Color.WHITE
        targetPaint.alpha = (alpha * 0.88f).toInt()
        targetPaint.strokeWidth = 1.4f * density
        canvas.drawLine(left + 5f * density, top + 17f * density, right - 5f * density, top + 17f * density, targetPaint)
        canvas.drawLine(left + 5f * density, top + 23f * density, right - 8f * density, top + 23f * density, targetPaint)
    }

    private fun drawCaptions(canvas: Canvas, alpha: Int) {
        val title = if (isArrival) "RECIBIDO" else "COMPARTIENDO"
        labelPaint.textSize = 12f * density
        labelPaint.alpha = alpha
        canvas.drawText(title, width * 0.5f, targetY + 70f * density, labelPaint)
        detailPaint.textSize = 10f * density
        detailPaint.alpha = (alpha * 0.72f).toInt()
        canvas.drawText("DISPOSITIVO CERCANO", width * 0.5f, targetY + 88f * density, detailPaint)
    }

    private fun sampleCurve(t: Float, out: PointF) {
        val u = 1f - t.coerceIn(0f, 1f)
        val x = u * u * u * startX + 3f * u * u * t * (startX + (endX - startX) * 0.14f) +
            3f * u * t * t * (endX - (endX - startX) * 0.16f) + t * t * t * endX
        val y = u * u * u * startY + 3f * u * u * t * (startY + (endY - startY) * 0.34f) +
            3f * u * t * t * (endY - (endY - startY) * 0.34f) + t * t * t * endY
        out.set(x, y)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DURATION_MS = 1450L
        const val REDUCED_DURATION_MS = 520L
        const val PARTICLE_COUNT = 15
        val CYAN: Int = Color.rgb(95, 218, 255)
        val BLUE: Int = Color.rgb(77, 105, 255)
    }
}
