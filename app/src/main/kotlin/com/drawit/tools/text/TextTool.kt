package com.drawit.tools.text

import android.view.KeyEvent
import com.drawit.canvas.EditorState
import com.drawit.core.color.Color
import com.drawit.core.document.Fill
import com.drawit.core.document.TextShape
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.Point
import com.drawit.core.input.Tool
import com.drawit.core.input.ToolContext
import com.drawit.core.input.ToolEvent
import com.drawit.core.geometry.Rect
import com.drawit.text.TextEngine

/**
 * On-canvas text tool.
 *  - ARTISTIC mode: tap to place caret, type
 *  - PARAGRAPH mode: drag a frame, then type (wraps to frame width)
 *
 * Editing: caret navigation (arrows), selection (Shift+arrows), delete,
 * Enter = newline (artistic) / paragraph break (paragraph).
 * Commits to the document on every edit (undoable, coalesced by kind).
 */
class TextTool(
    private val state: EditorState,
    private val textEngine: TextEngine,
    private val mode: TextShape.Kind,
    private val defaultFont: String = "bundled:inter",
    private val defaultSize: Float = 8f // mm
) : Tool {

    override val id = if (mode == TextShape.Kind.ARTISTIC) "text" else "paragraph"
    override val name = if (mode == TextShape.Kind.ARTISTIC) "Text" else "Paragraph"

    var onEditingChanged: ((editing: Boolean) -> Unit)? = null

    private var context: ToolContext? = null
    private var editingShapeId: String? = null
    private var caretIndex = 0
    private var selectionAnchor: Int? = null

    // Frame-drag state (paragraph mode)
    private var frameDragStart: Point? = null
    private var frameDragCurrent: Point? = null

    /** True while a text object is being edited (for IME wiring). */
    val isEditing: Boolean get() = editingShapeId != null

    override fun activate(context: ToolContext) {
        this.context = context
    }

    override fun deactivate() {
        commitAndStopEditing()
    }

    // ================= Input =================

    override fun onEvent(event: ToolEvent): Boolean {
        return when (event) {
            is ToolEvent.Down -> onDown(event)
            is ToolEvent.Move -> onMove(event)
            is ToolEvent.Up -> onUp(event)
            is ToolEvent.Key -> onKey(event)
            is ToolEvent.Cancel -> { commitAndStopEditing(); true }
            else -> false
        }
    }

    private fun onDown(event: ToolEvent.Down): Boolean {
        val editing = editingShapeId?.let { state.document.findShape(it) as? TextShape }

        if (editing != null) {
            // Click inside the editing shape → move caret; outside → commit & stop
            val local = editing.transform.invert().transform(event.position)
            val layout = textEngine.layout(editing)
            if (layout.bounds.expandBy(2f).contains(local)) {
                caretIndex = textEngine.indexForPoint(editing, layout, local.x, local.y)
                selectionAnchor = null
                context?.invalidate()
                return true
            } else {
                commitAndStopEditing()
            }
        }

        // Start editing an existing text shape if hit
        val hitId = hitTestText(event.position)
        if (hitId != null) {
            val shape = state.document.findShape(hitId) as? TextShape ?: return false
            editingShapeId = hitId
            caretIndex = shape.text.length
            selectionAnchor = null
            onEditingChanged?.invoke(true)
            context?.invalidate()
            return true
        }

        // Otherwise create new
        if (mode == TextShape.Kind.PARAGRAPH) {
            frameDragStart = event.position
            frameDragCurrent = event.position
        } else {
            createTextAt(event.position, frameWidth = 0f)
        }
        return true
    }

    private fun onMove(event: ToolEvent.Move): Boolean {
        val start = frameDragStart ?: return false
        frameDragCurrent = event.position
        context?.invalidate()
        return true
    }

    private fun onUp(event: ToolEvent.Up): Boolean {
        val start = frameDragStart
        if (start != null) {
            frameDragStart = null
            frameDragCurrent = null
            val w = kotlin.math.abs(event.position.x - start.x)
            if (w > 2f) {
                createTextAt(Point(minOf(start.x, event.position.x), minOf(start.y, event.position.y)), w)
            } else {
                createTextAt(start, frameWidth = 60f) // default frame width on tap
            }
            return true
        }
        return false
    }

    private fun onKey(event: ToolEvent.Key): Boolean {
        val shapeId = editingShapeId ?: return false
        val shape = state.document.findShape(shapeId) as? TextShape ?: return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> { commitAndStopEditing(); return true }
            KeyEvent.KEYCODE_DEL -> { // Backspace
                val sel = selectionRange()
                if (sel != null) {
                    replaceText(shape, sel.first, sel.second, "")
                } else if (caretIndex > 0) {
                    replaceText(shape, caretIndex - 1, caretIndex, "")
                }
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                val sel = selectionRange()
                if (sel != null) replaceText(shape, sel.first, sel.second, "")
                else if (caretIndex < shape.text.length) replaceText(shape, caretIndex, caretIndex + 1, "")
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                replaceText(shape, caretIndex, selectionRange()?.second ?: caretIndex, "\n")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                val newIndex = (caretIndex + dir).coerceIn(0, shape.text.length)
                if (event.modifiers.shift) {
                    if (selectionAnchor == null) selectionAnchor = caretIndex
                } else {
                    selectionAnchor = null
                }
                caretIndex = newIndex
                context?.invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Move caret vertically by line
                val layout = textEngine.layout(shape)
                val (x, top, bottom) = textEngine.caretFor(shape, layout, caretIndex)
                val lineH = layout.lineHeight
                val targetY = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
                    top - lineH / 2f - 0.1f else bottom + lineH / 2f + 0.1f
                if (event.modifiers.shift && selectionAnchor == null) selectionAnchor = caretIndex
                if (!event.modifiers.shift) selectionAnchor = null
                caretIndex = textEngine.indexForPoint(shape, layout, x, targetY)
                context?.invalidate()
                return true
            }
            else -> {
                // Printable characters via KeyEvent unicode — but real text comes via IME commit.
                // Hardware keyboard letters arrive here; commit unicode if present.
                val unicode = event.unicodeChar
                if (unicode != 0) {
                    val ch = unicode.toChar().toString()
                    replaceText(shape, caretIndex, selectionRange()?.second ?: caretIndex, ch)
                    return true
                }
                return false
            }
        }
    }

    /** Called by CanvasView's InputConnection when IME commits text. */
    fun onImeCommit(text: String) {
        val shapeId = editingShapeId ?: return
        val shape = state.document.findShape(shapeId) as? TextShape ?: return
        replaceText(shape, caretIndex, selectionRange()?.second ?: caretIndex, text)
    }

    // ================= Editing internals =================

    private fun selectionRange(): Pair<Int, Int>? {
        val anchor = selectionAnchor ?: return null
        if (anchor == caretIndex) return null
        return minOf(anchor, caretIndex) to maxOf(anchor, caretIndex)
    }

    private fun createTextAt(position: Point, frameWidth: Float) {
        val shape = TextShape(
            text = "",
            kind = mode,
            fontFamily = defaultFont,
            textSize = defaultSize,
            frameWidth = frameWidth,
            transform = Matrix.translate(position.x, position.y),
            fill = Fill.Solid(Color.BLACK)
        )
        val measured = textEngine.measure(shape)
        state.addShape(measured)
        editingShapeId = measured.id
        caretIndex = 0
        selectionAnchor = null
        onEditingChanged?.invoke(true)
    }

    private fun replaceText(shape: TextShape, from: Int, to: Int, insert: String) {
        val newText = shape.text.substring(0, from) + insert + shape.text.substring(to)
        val newCaret = from + insert.length
        val updated = textEngine.measure(shape.copy(text = newText))

        // Update silently but register as one coalescing undo per continuous typing
        state.updateShape(shape.id, "Edit Text") { updated }
        editingShapeId = shape.id
        caretIndex = newCaret
        selectionAnchor = null
        context?.invalidate()
    }

    private fun commitAndStopEditing() {
        val shapeId = editingShapeId ?: return
        val shape = state.document.findShape(shapeId) as? TextShape
        // Remove empty text objects
        if (shape != null && shape.text.isEmpty()) {
            state.applyEdit("Remove Empty Text") { it.removeShape(shapeId) }
        }
        editingShapeId = null
        selectionAnchor = null
        onEditingChanged?.invoke(false)
        context?.invalidate()
    }

    private fun hitTestText(docPoint: Point): String? {
        val page = state.document.activePage
        for (layer in page.layers.asReversed()) {
            if (!layer.visible || layer.locked) continue
            for (shape in layer.shapes.asReversed()) {
                if (shape is TextShape && shape.visible && !shape.locked) {
                    val b = shape.bounds()
                    val expanded = Rect(b.left - 2f, b.top - 2f, b.right + 2f, b.bottom + 2f)
                    if (expanded.contains(docPoint)) return shape.id
                }
            }
        }
        return null
    }

    private fun com.drawit.core.geometry.Rect.expandBy(v: Float) =
        com.drawit.core.geometry.Rect(left - v, top - v, right + v, bottom + v)

    // ================= Overlay =================

    override fun drawOverlay(canvas: Any, context: ToolContext) {
        val c = canvas as? android.graphics.Canvas ?: return

        // Frame drag preview (paragraph)
        val start = frameDragStart
        val current = frameDragCurrent
        if (start != null && current != null) {
            val tl = context.documentToScreen(
                Point(minOf(start.x, current.x), minOf(start.y, current.y)))
            val br = context.documentToScreen(
                Point(maxOf(start.x, current.x), maxOf(start.y, current.y)))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(0, 120, 215)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.5f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
            }
            c.drawRect(tl.x, tl.y, br.x, br.y, paint)
            return
        }

        // Caret + selection
        val shapeId = editingShapeId ?: return
        val shape = state.document.findShape(shapeId) as? TextShape ?: return
        val layout = textEngine.layout(shape)

        // Selection highlight
        val sel = selectionRange()
        if (sel != null) {
            val (x1, top1, _) = textEngine.caretFor(shape, layout, sel.first)
            val (x2, _, bottom2) = textEngine.caretFor(shape, layout, sel.second)
            val p1 = context.documentToScreen(shape.transform.transform(Point(x1, top1)))
            val p2 = context.documentToScreen(shape.transform.transform(Point(x2, bottom2)))
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(60, 0, 120, 215)
                style = android.graphics.Paint.Style.FILL
            }
            c.drawRect(minOf(p1.x, p2.x), p1.y, maxOf(p1.x, p2.x), p2.y, paint)
        }

        // Caret
        val (x, top, bottom) = textEngine.caretFor(shape, layout, caretIndex)
        val pTop = context.documentToScreen(shape.transform.transform(Point(x, top)))
        val pBottom = context.documentToScreen(shape.transform.transform(Point(x, bottom)))
        val caretPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 2f
        }
        c.drawLine(pTop.x, pTop.y, pBottom.x, pBottom.y, caretPaint)
    }
}
