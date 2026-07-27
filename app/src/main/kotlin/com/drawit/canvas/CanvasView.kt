package com.drawit.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.drawit.core.document.Shape
import com.drawit.core.geometry.Point
import com.drawit.core.input.Button
import com.drawit.core.input.Modifiers
import com.drawit.core.input.PointerType
import com.drawit.core.input.Tool
import com.drawit.core.input.ToolContext
import com.drawit.core.input.ToolEvent
import com.drawit.core.renderer.SkiaRenderer

/**
 * The document canvas — a custom View that:
 *  1. Renders the document via IRenderer (Skia for now)
 *  2. Converts ALL input (touch, stylus, mouse, keyboard) → normalized ToolEvents
 *  3. Handles viewport gestures (pinch zoom, two-finger pan) itself
 *  4. Delegates single-pointer interactions to the active Tool
 *
 * This is the single place where Android event weirdness is contained.
 * Everything downstream (tools, document) is pure Kotlin and testable.
 */
class CanvasView(context: Context) : View(context), ToolContext {

    var editorState: EditorState? = null
        set(value) {
            field = value
            invalidate()
        }

    var imageStore: com.drawit.file.ImageStore? = null
    var fontManager: com.drawit.text.FontManager? = null

    var activeTool: Tool? = null
        set(value) {
            field?.deactivate()
            field = value
            field?.activate(this)
            invalidate()
        }

    private val renderer: SkiaRenderer
        get() = _renderer ?: SkiaRenderer(imageStore, fontManager).also { _renderer = it }
    private var _renderer: SkiaRenderer? = null

    // --- Gesture state ---
    private var primaryPointerId = -1
    /** A second touch acts as Shift while the primary pointer keeps editing. */
    private var touchShiftPointerId = -1
    private var isViewportGesture = false
    private var lastGestureFocus = Point.ZERO
    private var lastGestureSpan = 0f
    private var keyboardRequested = false
    private var softwareEffectsEnabled = false

    // --- Selection overlay paint ---
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(0, 120, 215)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(0, 120, 215)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // Hardware is the default; Edge Blur switches this view to software
        // because BlurMaskFilter is not reliable on older hardware canvases.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        editorState?.setCanvasSize(w.toFloat(), h.toFloat())
    }

    // ================================================================
    // Rendering
    // ================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = editorState ?: return
        val needsSoftwareEffects = state.document.activePage.layers
            .asSequence()
            .filter { it.visible }
            .flatMap { it.shapes.asSequence() }
            .any(::hasEdgeBlur)
        if (needsSoftwareEffects != softwareEffectsEnabled) {
            softwareEffectsEnabled = needsSoftwareEffects
            setLayerType(
                if (needsSoftwareEffects) LAYER_TYPE_SOFTWARE else LAYER_TYPE_HARDWARE,
                null
            )
        }

        // Desk background
        canvas.drawColor(AndroidColor.rgb(60, 63, 65))

        renderer.setTarget(canvas)
        renderer.render(state.document, state.viewMatrix)

        // SelectTool draws the interactive transform box itself. Other tools
        // keep a lightweight outline for the current selection.
        if (activeTool !is com.drawit.tools.select.SelectTool &&
            activeTool !is com.drawit.tools.node.NodeEditTool) {
            drawSelectionOverlay(canvas, state)
        }

        // Tool overlay
        activeTool?.drawOverlay(canvas, this)
    }

    private fun drawSelectionOverlay(canvas: Canvas, state: EditorState) {
        for (shape in state.selectedShapes()) {
            val bounds = shape.bounds()
            val corners = bounds.corners().map { state.documentToScreen(it) }
            val path = android.graphics.Path().apply {
                moveTo(corners[0].x, corners[0].y)
                for (i in 1..3) lineTo(corners[i].x, corners[i].y)
                close()
            }
            canvas.drawPath(path, selectionPaint)

            // Corner handles
            val handleSize = state.controlHandleSizePx.coerceIn(3f, 14f)
            for (corner in corners) {
                canvas.drawRect(
                    corner.x - handleSize, corner.y - handleSize,
                    corner.x + handleSize, corner.y + handleSize,
                    handlePaint
                )
                canvas.drawRect(
                    corner.x - handleSize, corner.y - handleSize,
                    corner.x + handleSize, corner.y + handleSize,
                    handleStrokePaint
                )
            }
        }
    }

    // ================================================================
    // Input: touch + stylus
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val state = editorState ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    requestFocus()
                    primaryPointerId = event.getPointerId(0)
                    touchShiftPointerId = -1
                    isViewportGesture = false
                    val p = screenPoint(event, 0)
                    dispatchToTool(
                        ToolEvent.Down(
                            position = state.screenToDocument(p),
                            pointerType = pointerTypeOf(event, 0),
                            button = buttonOf(event),
                            modifiers = modifiersOf(event),
                            pressure = pressureOf(event, 0),
                            tilt = tiltOf(event, 0),
                            timestamp = event.eventTime
                        )
                    )
                } else {
                    if (activeTool?.isConstrainableGestureActive == true) {
                        // While resizing/rotating/drawing, the second touch can
                        // be placed anywhere and behaves like Shift.
                        touchShiftPointerId = event.getPointerId(event.actionIndex)
                        dispatchPrimaryMove(event, state)
                    } else {
                        // Otherwise two touches control the viewport.
                        dispatchToTool(ToolEvent.Cancel(event.eventTime))
                        isViewportGesture = true
                        primaryPointerId = -1
                        lastGestureFocus = gestureFocus(event)
                        lastGestureSpan = gestureSpan(event)
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isViewportGesture && event.pointerCount >= 2) {
                    val focus = gestureFocus(event)
                    val span = gestureSpan(event)
                    state.pan(
                        focus.x - lastGestureFocus.x,
                        focus.y - lastGestureFocus.y
                    )
                    if (lastGestureSpan > 0f && span > 0f) {
                        state.zoomAt(focus, span / lastGestureSpan)
                    }
                    lastGestureFocus = focus
                    lastGestureSpan = span
                    invalidate()
                } else {
                    dispatchPrimaryMove(event, state)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val upPointerId = event.getPointerId(event.actionIndex)
                if (isViewportGesture) {
                    if (event.pointerCount <= 2) {
                        isViewportGesture = false
                        lastGestureSpan = 0f
                    }
                } else if (upPointerId == primaryPointerId) {
                    val idx = event.actionIndex
                    val p = screenPoint(event, idx)
                    dispatchToTool(
                        ToolEvent.Up(
                            position = state.screenToDocument(p),
                            pointerType = pointerTypeOf(event, idx),
                            button = buttonOf(event),
                            modifiers = modifiersOf(event),
                            pressure = pressureOf(event, idx),
                            timestamp = event.eventTime
                        )
                    )
                    primaryPointerId = -1
                } else if (upPointerId == touchShiftPointerId) {
                    touchShiftPointerId = -1
                    dispatchPrimaryMove(event, state)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dispatchToTool(ToolEvent.Cancel(event.eventTime))
                primaryPointerId = -1
                touchShiftPointerId = -1
                isViewportGesture = false
                lastGestureSpan = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ================================================================
    // Input: mouse (hover, wheel)
    // ================================================================

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val state = editorState ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                val p = Point(event.x, event.y)
                dispatchToTool(
                    ToolEvent.Hover(
                        position = state.screenToDocument(p),
                        pointerType = pointerTypeOf(event, 0),
                        modifiers = modifiersOf(event),
                        tilt = tiltOf(event, 0),
                        timestamp = event.eventTime
                    )
                )
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val state = editorState ?: return false
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val p = Point(event.x, event.y)

            val modifiers = modifiersOf(event)
            if (modifiers.ctrl) {
                // Ctrl+wheel = zoom (standard in graphics apps)
                val factor = if (vScroll > 0) 1.1f else 1f / 1.1f
                state.zoomAt(p, factor)
            } else {
                // Wheel = vertical pan, Shift+wheel = horizontal pan
                val scrollScale = 40f
                if (modifiers.shift) {
                    state.pan(-vScroll * scrollScale, 0f)
                } else {
                    state.pan(-hScroll * scrollScale, -vScroll * scrollScale)
                }
            }
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ================================================================
    // Input: keyboard
    // ================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val state = editorState ?: return false
        val modifiers = Modifiers(
            shift = event.isShiftPressed,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed
        )
        val toolEvent = ToolEvent.Key(
            keyCode = keyCode,
            modifiers = modifiers,
            unicodeChar = event.getUnicodeChar(event.metaState),
            timestamp = event.eventTime
        )

        // Global shortcuts
        when {
            modifiers.ctrl && keyCode == KeyEvent.KEYCODE_Z && !modifiers.shift -> {
                state.undo(); invalidate(); return true
            }
            modifiers.ctrl && (keyCode == KeyEvent.KEYCODE_Y ||
                    (keyCode == KeyEvent.KEYCODE_Z && modifiers.shift)) -> {
                state.redo(); invalidate(); return true
            }
        }

        // While editing text, deletion, arrows, Enter and printable keys belong
        // to the text tool rather than to object deletion/nudging.
        val textTool = activeTool as? com.drawit.tools.text.TextTool
        if (textTool?.isEditing == true && dispatchToTool(toolEvent)) return true

        when {
            keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> {
                state.removeSelected()
                invalidate()
                return true
            }
            keyCode in KeyEvent.KEYCODE_DPAD_LEFT..KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Arrow-key nudge
                val nudge = when {
                    modifiers.shift -> 10f
                    modifiers.alt -> 0.1f
                    else -> 1f
                }
                val delta = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> Point(-nudge, 0f)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> Point(nudge, 0f)
                    KeyEvent.KEYCODE_DPAD_UP -> Point(0f, -nudge)
                    KeyEvent.KEYCODE_DPAD_DOWN -> Point(0f, nudge)
                    else -> Point.ZERO
                }
                nudgeSelection(delta)
                return true
            }
        }

        // Delegate remaining keys to the active tool
        return dispatchToTool(toolEvent) || super.onKeyDown(keyCode, event)
    }

    private fun nudgeSelection(delta: Point) {
        val state = editorState ?: return
        val selected = state.selectedShapes()
        if (selected.isEmpty()) {
            // No selection: pan viewport instead
            state.pan(-delta.x * 50f, -delta.y * 50f)
            invalidate()
            return
        }
        state.applyEdit("Nudge") { doc ->
            var result = doc
            for (shape in selected) {
                val moved = shape.withTransform(
                    com.drawit.core.geometry.Matrix.translate(delta.x, delta.y) * shape.transform
                )
                result = result.replaceShape(shape.id, moved)
            }
            result
        }
        invalidate()
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun dispatchToTool(event: ToolEvent): Boolean {
        val handled = activeTool?.onEvent(event) ?: false
        invalidate()
        return handled
    }

    private fun screenPoint(event: MotionEvent, index: Int): Point =
        Point(event.getX(index), event.getY(index))

    private fun dispatchPrimaryMove(event: MotionEvent, state: EditorState) {
        val index = event.findPointerIndex(primaryPointerId)
        if (index < 0 || index == event.actionIndex &&
            event.actionMasked == MotionEvent.ACTION_POINTER_UP) return
        val point = screenPoint(event, index)
        dispatchToTool(
            ToolEvent.Move(
                position = state.screenToDocument(point),
                pointerType = pointerTypeOf(event, index),
                buttonsDown = setOf(Button.PRIMARY),
                modifiers = modifiersOf(event),
                pressure = pressureOf(event, index),
                tilt = tiltOf(event, index),
                timestamp = event.eventTime
            )
        )
    }

    private fun hasEdgeBlur(shape: Shape): Boolean =
        shape.effects.edgeBlurRadius > 0.001f ||
            (shape is Shape.GroupShape && shape.children.any(::hasEdgeBlur))

    private fun pointerTypeOf(event: MotionEvent, index: Int): PointerType =
        when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_FINGER -> PointerType.TOUCH
            MotionEvent.TOOL_TYPE_STYLUS -> PointerType.STYLUS
            MotionEvent.TOOL_TYPE_MOUSE -> PointerType.MOUSE
            MotionEvent.TOOL_TYPE_ERASER -> PointerType.STYLUS
            else -> PointerType.UNKNOWN
        }

    private fun buttonOf(event: MotionEvent): Button = when {
        event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> Button.SECONDARY
        event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> Button.MIDDLE
        event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 -> Button.STYLUS_BUTTON
        else -> Button.PRIMARY
    }

    private fun modifiersOf(event: MotionEvent): Modifiers {
        val meta = event.metaState
        return Modifiers(
            shift = meta and KeyEvent.META_SHIFT_ON != 0 || touchShiftPointerId >= 0,
            ctrl = meta and KeyEvent.META_CTRL_ON != 0,
            alt = meta and KeyEvent.META_ALT_ON != 0,
            meta = meta and KeyEvent.META_META_ON != 0
        )
    }

    private fun pressureOf(event: MotionEvent, index: Int): Float =
        if (event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS) {
            event.getPressure(index).coerceIn(0f, 1f)
        } else 1f

    private fun tiltOf(event: MotionEvent, index: Int): Float =
        event.getAxisValue(MotionEvent.AXIS_TILT, index)

    private fun gestureFocus(event: MotionEvent): Point {
        var x = 0f
        var y = 0f
        repeat(event.pointerCount) { index ->
            x += event.getX(index)
            y += event.getY(index)
        }
        return Point(x / event.pointerCount, y / event.pointerCount)
    }

    private fun gestureSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // --- IME support (for TextTool) ---

    override fun onCheckIsTextEditor(): Boolean =
        (activeTool as? com.drawit.tools.text.TextTool)?.isEditing == true

    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? {
        val textTool = activeTool as? com.drawit.tools.text.TextTool ?: return null
        if (!textTool.isEditing) return null
        outAttrs?.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs?.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.toString()?.let { textTool.onImeCommit(it) }
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                return textTool.onImeDelete(beforeLength, afterLength) ||
                    super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    fun showKeyboard() {
        if (!onCheckIsTextEditor()) return
        if (keyboardRequested) return
        keyboardRequested = true
        post {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.restartInput(this)
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        keyboardRequested = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    // --- ToolContext implementation ---

    override fun documentToScreen(p: Point): Point =
        editorState?.documentToScreen(p) ?: p

    override fun screenToDocument(p: Point): Point =
        editorState?.screenToDocument(p) ?: p

    override val zoom: Float
        get() = editorState?.zoom ?: 1f

    override fun invalidate() {
        super.invalidate()
    }

    override val hitTolerance: Float
        get() = 12f / (editorState?.zoom ?: 1f) / 3.7795275591f // ~12px constant
}
