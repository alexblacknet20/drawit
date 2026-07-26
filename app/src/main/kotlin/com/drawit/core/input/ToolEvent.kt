package com.drawit.core.input

import com.drawit.core.geometry.Point

/**
 * Input source — tools must behave identically regardless of source.
 */
enum class PointerType {
    TOUCH,
    STYLUS,
    MOUSE,
    TRACKPAD,
    UNKNOWN
}

/**
 * Mouse button or stylus button state.
 */
enum class Button { PRIMARY, SECONDARY, MIDDLE, STYLUS_BUTTON, NONE }

/**
 * Keyboard modifiers active during an event.
 */
data class Modifiers(
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false
) {
    val any: Boolean get() = shift || ctrl || alt || meta
    companion object { val NONE = Modifiers() }
}

/**
 * Normalized input event delivered to tools.
 * All coordinates are already converted to DOCUMENT space by the canvas —
 * tools never see raw screen pixels or MotionEvents.
 */
sealed class ToolEvent {
    abstract val position: Point        // document coordinates
    abstract val pointerType: PointerType
    abstract val modifiers: Modifiers
    abstract val pressure: Float        // 0.0–1.0 (1.0 for mouse/touch)
    abstract val tilt: Float            // radians, stylus only
    abstract val timestamp: Long

    data class Down(
        override val position: Point,
        override val pointerType: PointerType,
        val button: Button = Button.PRIMARY,
        override val modifiers: Modifiers = Modifiers.NONE,
        override val pressure: Float = 1f,
        override val tilt: Float = 0f,
        override val timestamp: Long = 0L
    ) : ToolEvent()

    data class Move(
        override val position: Point,
        override val pointerType: PointerType,
        val buttonsDown: Set<Button> = emptySet(),
        override val modifiers: Modifiers = Modifiers.NONE,
        override val pressure: Float = 1f,
        override val tilt: Float = 0f,
        override val timestamp: Long = 0L
    ) : ToolEvent()

    data class Up(
        override val position: Point,
        override val pointerType: PointerType,
        val button: Button = Button.PRIMARY,
        override val modifiers: Modifiers = Modifiers.NONE,
        override val pressure: Float = 1f,
        override val tilt: Float = 0f,
        override val timestamp: Long = 0L
    ) : ToolEvent()

    /** Mouse hover (no button) or stylus hover. */
    data class Hover(
        override val position: Point,
        override val pointerType: PointerType,
        override val modifiers: Modifiers = Modifiers.NONE,
        override val pressure: Float = 0f,
        override val tilt: Float = 0f,
        override val timestamp: Long = 0L
    ) : ToolEvent()

    /** Scroll wheel (mouse) — typically zoom. */
    data class Wheel(
        override val position: Point,
        val deltaX: Float,
        val deltaY: Float,
        override val modifiers: Modifiers = Modifiers.NONE,
        override val timestamp: Long = 0L
    ) : ToolEvent() {
        override val pointerType = PointerType.MOUSE
        override val pressure = 0f
        override val tilt = 0f
    }

    /** Key press while tool active (shortcuts, nudge, Esc-cancel, Enter-commit). */
    data class Key(
        val keyCode: Int,
        override val modifiers: Modifiers = Modifiers.NONE,
        val unicodeChar: Int = 0,
        override val timestamp: Long = 0L
    ) : ToolEvent() {
        override val position = Point.ZERO
        override val pointerType = PointerType.UNKNOWN
        override val pressure = 0f
        override val tilt = 0f
    }

    /** Second finger / pinch data for viewport gestures (handled by canvas, not tools). */
    data class Gesture(
        val type: GestureType,
        val focus: Point,              // document-space gesture focus point
        val scaleDelta: Float = 1f,
        val rotationDelta: Float = 0f,
        val translationDelta: Point = Point.ZERO,
        override val timestamp: Long = 0L
    ) : ToolEvent() {
        override val position = focus
        override val pointerType = PointerType.TOUCH
        override val modifiers = Modifiers.NONE
        override val pressure = 0f
        override val tilt = 0f
    }

    enum class GestureType { PINCH_START, PINCH, PINCH_END, DOUBLE_TAP, LONG_PRESS }

    /** Cancel current gesture (e.g., palm rejection, system gesture steal). */
    data class Cancel(
        override val timestamp: Long = 0L
    ) : ToolEvent() {
        override val position = Point.ZERO
        override val pointerType = PointerType.UNKNOWN
        override val modifiers = Modifiers.NONE
        override val pressure = 0f
        override val tilt = 0f
    }
}
