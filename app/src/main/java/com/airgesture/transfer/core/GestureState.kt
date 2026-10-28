package com.airgesture.transfer.core

/**
 * The three canonical states of the hands-free interaction model.
 */
enum class GestureState(val label: String) {
    /** Open (or loosely detected) hand: the file strip is browsable. */
    IDLE("Reposo"),

    /** Firm fist held: the highlighted file is locked as the active payload. */
    GRAB_FIST("Puño cerrado"),

    /** Fist driven forward past the acceleration threshold: payload is released. */
    THROW_VECTOR("Lanzamiento")
}

/**
 * Edge events produced by [com.airgesture.transfer.vision.GestureInterpreter].
 * They are one-shot: the interpreter emits each event exactly once per action.
 */
enum class GestureEvent {
    NONE,
    /** A firm fist was just confirmed (finger tips converged into the palm). */
    FIST_CLOSED,
    /** The hand opened again: selection is released, browsing is re-armed. */
    HAND_OPENED,
    /** Open-hand lateral swipe to the left: previous file. */
    SWIPE_PREV,
    /** Open-hand lateral swipe to the right: next file. */
    SWIPE_NEXT,
    /** Forward thrust confirmed while holding the fist. */
    THROW
}
