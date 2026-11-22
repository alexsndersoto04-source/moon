package com.airgesture.transfer.jarvis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** Orbe animado de JARVIS: estados idle, listening, thinking y speaking. */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state: State = State.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private var time = 0.0
    private var running = false

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(90, 34, 211, 238)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 34, 211, 238)
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val animator = object : Runnable {
        override fun run() {
            if (!running) return
            time += 0.05
            invalidate()
            postDelayed(this, 33)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!running) {
            running = true
            post(animator)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(animator)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(width, height) / 2f - 18f
        if (baseRadius <= 0) return

        val pulse = when (state) {
            State.IDLE -> 1.0 + 0.04 * sin(time * 1.2)
            State.LISTENING -> 1.0 + 0.10 * sin(time * 4.0)
            State.THINKING -> 1.0 + 0.06 * sin(time * 6.0)
            State.SPEAKING -> 1.0 + 0.12 * sin(time * 5.0) + 0.04 * sin(time * 11.0)
        }
        val radius = (baseRadius * pulse).toFloat()

        // Halo exterior
        canvas.drawCircle(cx, cy, radius + 14f, outerPaint)

        // Anillo giratorio (arcos)
        val sweep = when (state) {
            State.IDLE -> 40f
            State.LISTENING -> 120f
            State.THINKING -> 200f
            State.SPEAKING -> 160f
        }
        val startAngle = (time * 60).toFloat() % 360f
        canvas.drawArc(
            cx - radius, cy - radius, cx + radius, cy + radius,
            startAngle, sweep, false, ringPaint
        )
        canvas.drawArc(
            cx - radius, cy - radius, cx + radius, cy + radius,
            startAngle + 180f, sweep * 0.6f, false, ringPaint
        )

        // Núcleo con degradado
        val coreRadius = radius * 0.62f
        val gradient = RadialGradient(
            cx, cy, coreRadius,
            intArrayOf(Color.rgb(165, 243, 252), Color.rgb(34, 211, 238), Color.rgb(37, 99, 235)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.shader = gradient
        canvas.drawCircle(cx, cy, coreRadius, corePaint)

        // Punto central brillante
        corePaint.shader = null
        corePaint.color = Color.argb(220, 255, 255, 255)
        canvas.drawCircle(cx, cy, coreRadius * 0.28f, corePaint)
    }
}
