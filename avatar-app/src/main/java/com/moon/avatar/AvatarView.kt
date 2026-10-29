package com.moon.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/** Emociones que el cerebro puede pedir. */
enum class Emotion { NEUTRAL, HAPPY, SAD, SURPRISED, THINKING, EXCITED, SHY }

/** Gestos corporales que el cerebro puede pedir. */
enum class Gesture { NONE, WAVE, NOD, SHAKE, THINK, CELEBRATE, BOW }

/**
 * El avatar: una persona dibujada y animada en tiempo real (sin assets externos,
 * funciona en teléfonos modestos). Respira, parpadea, balancea el cuerpo,
 * mueve la boca al hablar (lip-sync), expresa emociones y hace gestos.
 *
 * Personalizable: piel, pelo (estilo/color), ropa — se redibuja al instante.
 */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- Apariencia (personalización) ----
    var skinColor: Int = Color.rgb(198, 134, 88)
    var hairColor: Int = Color.rgb(38, 28, 22)
    var hairStyle: Int = 0            // 0 corto, 1 largo, 2 moño, 3 rizado
    var shirtColor: Int = Color.rgb(99, 82, 220)

    // ---- Estado vivo ----
    var emotion: Emotion = Emotion.HAPPY
    var speaking: Boolean = false

    private var gesture: Gesture = Gesture.NONE
    private var gestureStart: Long = 0L
    private val gestureDuration = 1800L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startTime = System.currentTimeMillis()

    init {
        // Bucle de animación ligero (~30 fps), suficiente y amable con la batería.
        object : Runnable {
            override fun run() {
                invalidate()
                postDelayed(this, 33L)
            }
        }.also { post(it) }
    }

    fun playGesture(g: Gesture) {
        gesture = g
        gestureStart = System.currentTimeMillis()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val t = (now - startTime) / 1000f

        val w = width.toFloat()
        val h = height.toFloat()

        // Fondo con degradado suave
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            Color.rgb(24, 20, 52), Color.rgb(12, 10, 34), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Halo detrás del avatar
        paint.color = Color.argb(46, 139, 124, 255)
        canvas.drawCircle(w / 2f, h * 0.42f, w * 0.36f, paint)

        // ----- Ritmos naturales -----
        val breathe = sin(t * 1.6f) * 4f                 // respiración
        val sway = sin(t * 0.7f) * 6f                    // balanceo idle
        val blinkPhase = (now % 3400L)
        val blinking = blinkPhase < 120L                 // parpadeo cada ~3.4 s
        val mouthOpen = if (speaking) (sin(t * 18f) * 0.5f + 0.5f) * 14f + 3f else 0f

        // Progreso del gesto actual
        val gElapsed = now - gestureStart
        val gActive = gesture != Gesture.NONE && gElapsed < gestureDuration
        if (!gActive && gesture != Gesture.NONE) gesture = Gesture.NONE
        val gPhase = if (gActive) gElapsed / gestureDuration.toFloat() else 0f

        var headBob = breathe * 0.5f
        var headTilt = sway * 0.15f
        if (gActive && gesture == Gesture.NOD) headBob += sin(gPhase * 12f) * 10f * (1f - gPhase)
        if (gActive && gesture == Gesture.SHAKE) headTilt += sin(gPhase * 14f) * 8f * (1f - gPhase)
        if (gActive && gesture == Gesture.BOW) headBob += sin(gPhase * Math.PI.toFloat()) * 22f
        if (emotion == Emotion.THINKING) headTilt += 4f

        val cx = w / 2f + sway
        val shoulderY = h * 0.78f + breathe
        val headR = w * 0.20f
        val headCx = cx + headTilt
        val headCy = h * 0.40f + headBob

        // ----- Brazos (detrás del torso) -----
        paint.strokeCap = Paint.Cap.ROUND
        val armW = w * 0.075f
        // Brazo izquierdo (del espectador: derecho del avatar) — saluda / celebra
        var leftHandX = cx - w * 0.30f
        var leftHandY = shoulderY + h * 0.16f
        if (gActive && (gesture == Gesture.WAVE || gesture == Gesture.CELEBRATE)) {
            val wave = sin(gPhase * 16f) * w * 0.05f
            leftHandX = cx - w * 0.33f + wave
            leftHandY = h * 0.30f + headBob
        }
        if (gActive && gesture == Gesture.THINK) {
            leftHandX = headCx - headR * 0.35f
            leftHandY = headCy + headR * 1.05f
        }
        paint.color = shirtColor
        paint.strokeWidth = armW
        canvas.drawLine(cx - w * 0.20f, shoulderY + 6f, leftHandX, leftHandY, paint)
        // Brazo derecho
        var rightHandX = cx + w * 0.30f
        var rightHandY = shoulderY + h * 0.16f
        if (gActive && gesture == Gesture.CELEBRATE) {
            rightHandX = cx + w * 0.33f - sin(gPhase * 16f) * w * 0.05f
            rightHandY = h * 0.30f + headBob
        }
        canvas.drawLine(cx + w * 0.20f, shoulderY + 6f, rightHandX, rightHandY, paint)
        // Manos
        paint.color = skinColor
        canvas.drawCircle(leftHandX, leftHandY, armW * 0.52f, paint)
        canvas.drawCircle(rightHandX, rightHandY, armW * 0.52f, paint)

        // ----- Torso / ropa -----
        paint.color = shirtColor
        val torso = RectF(cx - w * 0.26f, shoulderY, cx + w * 0.26f, h * 1.02f)
        canvas.drawRoundRect(torso, w * 0.12f, w * 0.12f, paint)
        paint.color = Color.argb(38, 0, 0, 0)
        canvas.drawRoundRect(RectF(cx - w * 0.26f, shoulderY, cx + w * 0.26f, shoulderY + 14f),
            w * 0.06f, w * 0.06f, paint)

        // ----- Cuello y cabeza -----
        paint.color = skinColor
        canvas.drawRect(headCx - headR * 0.28f, headCy + headR * 0.75f,
            headCx + headR * 0.28f, shoulderY + 8f, paint)
        canvas.drawCircle(headCx, headCy, headR, paint)
        // Orejas
        canvas.drawCircle(headCx - headR, headCy + headR * 0.08f, headR * 0.16f, paint)
        canvas.drawCircle(headCx + headR, headCy + headR * 0.08f, headR * 0.16f, paint)

        // ----- Pelo según estilo -----
        paint.color = hairColor
        when (hairStyle) {
            0 -> { // corto
                canvas.drawArc(RectF(headCx - headR, headCy - headR * 1.08f,
                    headCx + headR, headCy + headR * 0.25f), 180f, 180f, true, paint)
            }
            1 -> { // largo
                canvas.drawRoundRect(RectF(headCx - headR * 1.12f, headCy - headR * 1.05f,
                    headCx + headR * 1.12f, headCy + headR * 1.75f), headR * 0.5f, headR * 0.5f, paint)
                paint.color = skinColor
                canvas.drawCircle(headCx, headCy + headR * 0.12f, headR * 0.88f, paint)
            }
            2 -> { // moño
                canvas.drawArc(RectF(headCx - headR, headCy - headR * 1.08f,
                    headCx + headR, headCy + headR * 0.25f), 180f, 180f, true, paint)
                paint.color = hairColor
                canvas.drawCircle(headCx, headCy - headR * 1.12f, headR * 0.34f, paint)
            }
            else -> { // rizado: nube de círculos
                var i = -3
                while (i <= 3) {
                    canvas.drawCircle(headCx + i * headR * 0.26f,
                        headCy - headR * 0.88f + (if (i % 2 == 0) 0f else headR * 0.10f),
                        headR * 0.30f, paint)
                    i++
                }
            }
        }

        // ----- Cara -----
        val eyeY = headCy - headR * 0.05f
        val eyeDx = headR * 0.38f
        val eyeR = headR * 0.085f
        paint.color = Color.rgb(30, 22, 20)
        if (blinking || emotion == Emotion.HAPPY && speaking) {
            paint.strokeWidth = 4f
            canvas.drawLine(headCx - eyeDx - eyeR, eyeY, headCx - eyeDx + eyeR, eyeY, paint)
            canvas.drawLine(headCx + eyeDx - eyeR, eyeY, headCx + eyeDx + eyeR, eyeY, paint)
        } else if (emotion == Emotion.SURPRISED) {
            canvas.drawCircle(headCx - eyeDx, eyeY, eyeR * 1.5f, paint)
            canvas.drawCircle(headCx + eyeDx, eyeY, eyeR * 1.5f, paint)
        } else {
            canvas.drawCircle(headCx - eyeDx, eyeY, eyeR, paint)
            canvas.drawCircle(headCx + eyeDx, eyeY, eyeR, paint)
        }

        // Cejas según emoción
        paint.strokeWidth = 5f
        paint.color = hairColor
        val browY = eyeY - headR * 0.26f
        val browLift = when (emotion) {
            Emotion.SURPRISED, Emotion.EXCITED -> -8f
            Emotion.SAD -> 5f
            else -> 0f
        }
        canvas.drawLine(headCx - eyeDx - eyeR * 1.4f, browY + browLift,
            headCx - eyeDx + eyeR * 1.4f, browY + (if (emotion == Emotion.SAD) browLift + 5f else browLift), paint)
        canvas.drawLine(headCx + eyeDx - eyeR * 1.4f, browY + (if (emotion == Emotion.SAD) browLift + 5f else browLift),
            headCx + eyeDx + eyeR * 1.4f, browY + browLift, paint)

        // Mejillas si está feliz / tímido
        if (emotion == Emotion.HAPPY || emotion == Emotion.SHY || emotion == Emotion.EXCITED) {
            paint.color = Color.argb(70, 255, 120, 130)
            canvas.drawCircle(headCx - headR * 0.58f, headCy + headR * 0.34f, headR * 0.15f, paint)
            canvas.drawCircle(headCx + headR * 0.58f, headCy + headR * 0.34f, headR * 0.15f, paint)
        }

        // Nariz mínima
        paint.color = Color.argb(60, 90, 50, 30)
        paint.strokeWidth = 3.5f
        canvas.drawLine(headCx, headCy + headR * 0.10f, headCx - 3f, headCy + headR * 0.28f, paint)

        // Boca: habla (lip-sync) o expresión
        paint.color = Color.rgb(122, 52, 48)
        val mouthCx = headCx
        val mouthCy = headCy + headR * 0.48f
        if (speaking) {
            canvas.drawOval(RectF(mouthCx - 11f, mouthCy - mouthOpen / 2f,
                mouthCx + 11f, mouthCy + mouthOpen / 2f), paint)
        } else when (emotion) {
            Emotion.HAPPY, Emotion.EXCITED ->
                canvas.drawArc(RectF(mouthCx - 18f, mouthCy - 12f, mouthCx + 18f, mouthCy + 14f), 15f, 150f, false, paint.apply { style = Paint.Style.STROKE; strokeWidth = 5f })
            Emotion.SAD ->
                canvas.drawArc(RectF(mouthCx - 16f, mouthCy + 2f, mouthCx + 16f, mouthCy + 26f), 195f, 150f, false, paint.apply { style = Paint.Style.STROKE; strokeWidth = 5f })
            Emotion.SURPRISED ->
                canvas.drawCircle(mouthCx, mouthCy + 4f, 9f, paint)
            Emotion.THINKING -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
                canvas.drawLine(mouthCx - 12f, mouthCy + 4f, mouthCx + 12f, mouthCy + 1f, paint)
            }
            else -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
                canvas.drawArc(RectF(mouthCx - 14f, mouthCy - 8f, mouthCx + 14f, mouthCy + 10f), 20f, 140f, false, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }
}
