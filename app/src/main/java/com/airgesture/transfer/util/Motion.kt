package com.airgesture.transfer.util

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView

/**
 * Small motion vocabulary, shared by every screen.
 *
 * 2.0 is not a redesign of the layout: it is the difference between "the app
 * answers when I touch it" and "the app redraws itself". Three primitives are
 * enough for that, and all of them respect the system's animation scale, so a
 * phone with animations off (MIUI ships that way sometimes) degrades to instant
 * text instead of a frozen one.
 */
object Motion {

    /** Fast out, soft landing — the curve iOS uses for interactive elements. */
    val Spring = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    /** Slower, for panels that enter the screen. */
    val Gentle = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private fun animatorsEnabled(): Boolean =
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

    /**
     * Presses the view under the finger instead of only tinting it. Returning
     * false keeps the click listener working, so this is safe on anything.
     */
    fun pressScale(view: View, pressedScale: Float = 0.94f) {
        if (view.getTag(TAG_PRESS) == true) return
        view.setTag(TAG_PRESS, true)
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> target.animate()
                    .scaleX(pressedScale)
                    .scaleY(pressedScale)
                    .setDuration(90L)
                    .setInterpolator(Spring)
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(240L)
                        .setInterpolator(Spring)
                        .start()
            }
            false
        }
    }

    /**
     * Replaces text with a short cross-fade, but only when it actually changed:
     * the transfer state emits several times per second and re-animating the
     * same string would make the status line flicker.
     */
    fun crossfade(view: TextView, value: CharSequence, duration: Long = 150L) {
        if (view.text?.toString() == value.toString()) return
        if (!animatorsEnabled() || !view.isLaidOut) {
            view.text = value
            return
        }
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(duration / 2)
            .withEndAction {
                view.text = value
                view.animate().alpha(1f).setDuration(duration / 2).start()
            }
            .start()
    }

    /** Keyed tag (Int id-space) so it can never collide with a view's plain tag. */
    private const val TAG_PRESS = 0x0A6E0001
}
