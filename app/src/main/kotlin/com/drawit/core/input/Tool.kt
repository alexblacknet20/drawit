package com.drawit.core.input

import com.drawit.core.geometry.Point

/**
 * Context given to tools: access to document ops + canvas state.
 * Kept as an interface so tools are testable without Android.
 */
interface ToolContext {
    /** Convert document point → screen point (for overlays). */
    fun documentToScreen(p: Point): Point
    /** Convert screen point → document point. */
    fun screenToDocument(p: Point): Point
    /** Current zoom factor (pixels per document unit). */
    val zoom: Float
    /** Request canvas redraw. */
    fun invalidate()
    /** Hit-test tolerance in document units (screen-constant). */
    val hitTolerance: Float
}

/**
 * A drawing/editing tool. Receives ONLY normalized ToolEvents —
 * never raw Android events — so every tool automatically works
 * with touch, stylus, mouse, and keyboard.
 */
interface Tool {
    val id: String
    val name: String

    /** Called when the tool becomes active. */
    fun activate(context: ToolContext) {}

    /** Called when switching away. Cancel any in-progress operation. */
    fun deactivate() {}

    /** Handle an input event. Return true if consumed. */
    fun onEvent(event: ToolEvent): Boolean

    /** Draw tool-specific overlay (rubber bands, previews, handles). */
    fun drawOverlay(canvas: Any, context: ToolContext) {}

    /** Cursor to show for mouse hover (Android PointerIcon type). */
    val cursorType: Int get() = 1000 // TYPE_DEFAULT

    /**
     * True while a primary-pointer gesture can use Shift as a constraint.
     * CanvasView uses this to distinguish touch-Shift from a two-finger
     * viewport gesture.
     */
    val isConstrainableGestureActive: Boolean get() = false
}
