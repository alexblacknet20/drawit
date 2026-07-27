package com.drawit.tools.select

import com.drawit.canvas.EditorState
import com.drawit.core.document.Shape
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.Point
import com.drawit.core.geometry.Rect
import com.drawit.core.input.Button
import com.drawit.core.input.Tool
import com.drawit.core.input.ToolContext
import com.drawit.core.input.ToolEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class SelectTool(private val state: EditorState) : Tool {
    override val id = "select"
    override val name = "Select"

    private var context: ToolContext? = null
    private var dragStart: Point? = null
    private var lastPosition: Point? = null
    private var movedShapeIds: Set<String> = emptySet()
    private var originalTransforms: Map<String, Matrix> = emptyMap()
    private var originalSelectionBounds: Rect? = null
    private var currentDragDelta = Point.ZERO
    private var snapGuideX: Float? = null
    private var snapGuideY: Float? = null
    private var isDragging = false
    private var marqueeRect: Rect? = null
    private var hitShapeId: String? = null
    private var isRotating = false
    private var rotationOriginals: Map<String, Matrix> = emptyMap()
    private var rotationCenter = Point.ZERO
    private var rotationStartAngle = 0f
    private var accumulatedAngle = 0f
    private var resizeHandle: ResizeHandle? = null
    private var resizeOriginals: Map<String, Matrix> = emptyMap()
    private var resizeBounds: Rect? = null
    private var resizePivot = Point.ZERO
    private var resizeScaleX = 1f
    private var resizeScaleY = 1f
    private var resizeSingleShapeId: String? = null
    private var resizeLocalBounds: Rect? = null
    private var resizeLocalPivot: Point? = null
    private var ellipseControl: EllipseControl? = null
    private var ellipseOriginal: Shape.EllipseShape? = null
    private var ellipseResult: Shape.EllipseShape? = null
    private var skewControl: SkewControl? = null
    private var skewOriginal: Shape? = null
    private var skewResult: Shape? = null

    private enum class ResizeHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }
    private enum class EllipseControl { START, SWEEP, RATIO }
    private enum class SkewControl { HORIZONTAL, VERTICAL }
    private data class TransformCage(val corners: List<Point>) {
        val center: Point get() = corners.reduce { a, b -> a + b } / corners.size.toFloat()
        val topMid: Point get() = corners[0].midpoint(corners[1])
        val rightMid: Point get() = corners[1].midpoint(corners[2])
        val bottomMid: Point get() = corners[2].midpoint(corners[3])
        val leftMid: Point get() = corners[3].midpoint(corners[0])
    }

    override val isConstrainableGestureActive: Boolean
        get() = isRotating || resizeHandle != null || skewControl != null

    override fun activate(context: ToolContext) { this.context = context }
    override fun deactivate() {
        cancelDrag()
        cancelRotate()
        cancelResize()
        cancelEllipseControl()
        cancelSkew()
        context = null
    }

    override fun onEvent(event: ToolEvent): Boolean = when (event) {
        is ToolEvent.Down -> onDown(event)
        is ToolEvent.Move -> onMove(event)
        is ToolEvent.Up -> onUp(event)
        is ToolEvent.Key -> onKey(event)
        is ToolEvent.Cancel -> {
            cancelDrag()
            cancelRotate()
            cancelResize()
            cancelEllipseControl()
            cancelSkew()
            true
        }
        else -> false
    }

    private fun onDown(event: ToolEvent.Down): Boolean {
        if (event.button != Button.PRIMARY) return false
        if (hitTestEllipseControl(event.position)?.also { startEllipseControl(it) } != null) {
            return true
        }
        if (hitTestSkewControl(event.position)?.also { startSkew(it) } != null) return true
        if (hitTestRotationHandle(event.position)?.also { startRotate(it) } != null) return true
        if (hitTestResizeHandle(event.position)?.also { startResize(it) } != null) return true

        dragStart = event.position
        lastPosition = event.position
        isDragging = false
        marqueeRect = null
        movedShapeIds = emptySet()
        originalTransforms = emptyMap()
        originalSelectionBounds = null
        currentDragDelta = Point.ZERO
        snapGuideX = null
        snapGuideY = null

        val hit = hitTest(event.position)
        hitShapeId = hit
        if (hit != null) {
            if (hit !in state.selectedShapeIds) {
                state.select(if (event.modifiers.shift) state.selectedShapeIds + hit else setOf(hit))
            }
            movedShapeIds = state.selectedShapeIds
            originalTransforms = state.selectedShapes().associate { it.id to it.transform }
            originalSelectionBounds = state.selectionBounds()
        }
        return true
    }

    private fun onMove(event: ToolEvent.Move): Boolean {
        if (ellipseControl != null) {
            updateEllipseControl(event.position)
            return true
        }
        if (skewControl != null) {
            updateSkew(event.position)
            return true
        }
        if (isRotating) {
            val currentAngle = atan2(
                event.position.y - rotationCenter.y,
                event.position.x - rotationCenter.x
            )
            val rawAngle = normalizeAngle(currentAngle - rotationStartAngle)
            accumulatedAngle = if (event.modifiers.shift) snap15(rawAngle) else rawAngle
            state.setDocumentForDrag(computeRotatedDoc(accumulatedAngle))
            context?.invalidate()
            return true
        }
        if (resizeHandle != null) {
            updateResize(event.position, keepAspectRatio = event.modifiers.shift)
            return true
        }

        val start = dragStart ?: return false
        if (!isDragging && event.position.distanceTo(start) < (context?.hitTolerance ?: 1f)) return true
        isDragging = true
        if (hitShapeId != null && movedShapeIds.isNotEmpty()) {
            val rawDelta = event.position - start
            val delta = if (state.smartAlignmentsEnabled) {
                snapTranslation(rawDelta)
            } else {
                snapGuideX = null
                snapGuideY = null
                rawDelta
            }
            currentDragDelta = delta
            val t = Matrix.translate(delta.x, delta.y)
            var doc = restoreTransforms(state.document, originalTransforms)
            movedShapeIds.forEach { id ->
                doc.findShape(id)?.let {
                    doc = doc.replaceShape(id, it.withTransform(t * it.transform))
                }
            }
            state.setDocumentForDrag(doc)
        } else {
            marqueeRect = Rect.fromPoints(start, event.position)
            context?.invalidate()
        }
        lastPosition = event.position
        return true
    }

    private fun onUp(event: ToolEvent.Up): Boolean {
        if (ellipseControl != null) {
            finishEllipseControl()
            return true
        }
        if (skewControl != null) {
            finishSkew()
            return true
        }
        if (isRotating) { finishRotate(); return true }
        if (resizeHandle != null) { finishResize(); return true }
        if (isDragging && movedShapeIds.isNotEmpty()) commitDrag(currentDragDelta)
        else if (!isDragging) {
            val hit = hitTest(event.position)
            if (hit == null && !event.modifiers.shift) state.clearSelection()
            else if (hit != null && event.modifiers.shift) {
                val cur = state.selectedShapeIds; state.select(if (hit in cur) cur - hit else cur + hit)
            }
        }
        marqueeRect?.let { mr ->
            val hits = state.document.activePage.layers.filter { l -> l.visible && !l.locked }
                .flatMap { it.shapes }.filter { s -> s.visible && !s.locked && mr.intersects(s.bounds()) }.map { it.id }.toSet()
            state.select(if (event.modifiers.shift) state.selectedShapeIds + hits else hits)
            marqueeRect = null
        }
        resetDragState()
        return true
    }

    private fun onKey(event: ToolEvent.Key): Boolean {
        if (event.keyCode != android.view.KeyEvent.KEYCODE_ESCAPE) return false
        state.clearSelection()
        context?.invalidate()
        return true
    }

    // Parametric ellipse controls
    private fun hitTestEllipseControl(position: Point): EllipseControl? {
        val ellipse = state.selectedShapes().singleOrNull() as? Shape.EllipseShape ?: return null
        val toolContext = context ?: return null
        val screenPosition = toolContext.documentToScreen(position)
        val tolerancePx = state.controlHandleSizePx.coerceIn(3f, 14f) * 2f
        return ellipseControlPoints(ellipse)
            .minByOrNull { (_, point) ->
                toolContext.documentToScreen(point).distanceTo(screenPosition)
            }
            ?.takeIf { (_, point) ->
                toolContext.documentToScreen(point).distanceTo(screenPosition) <= tolerancePx
            }
            ?.first
    }

    private fun startEllipseControl(control: EllipseControl) {
        val ellipse = state.selectedShapes().singleOrNull() as? Shape.EllipseShape ?: return
        ellipseControl = control
        ellipseOriginal = ellipse
        ellipseResult = ellipse
    }

    private fun updateEllipseControl(documentPosition: Point) {
        val control = ellipseControl ?: return
        val original = ellipseOriginal ?: return
        val local = original.transform.invert().transform(documentPosition)
        val rect = original.rect
        val rx = (rect.width / 2f).coerceAtLeast(0.0001f)
        val ry = (rect.height / 2f).coerceAtLeast(0.0001f)
        val nx = (local.x - rect.centerX) / rx
        val ny = (local.y - rect.centerY) / ry
        val angle = normalizeDegrees(Math.toDegrees(atan2(ny, nx).toDouble()).toFloat())

        val updated = when (control) {
            EllipseControl.START -> original.copy(startAngleDegrees = angle)
            EllipseControl.SWEEP -> {
                var sweep = normalizeDegrees(angle - original.startAngleDegrees)
                if (sweep < 0.1f) sweep = 360f
                original.copy(sweepDegrees = sweep.coerceIn(0.1f, 360f))
            }
            EllipseControl.RATIO -> original.copy(
                arcRatio = sqrt(nx * nx + ny * ny).coerceIn(0f, 0.95f)
            )
        }
        ellipseResult = updated
        state.setDocumentForDrag(state.document.replaceShape(original.id, updated))
        context?.invalidate()
    }

    private fun finishEllipseControl() {
        val original = ellipseOriginal ?: return
        val result = ellipseResult ?: original
        state.setDocumentForDrag(state.document.replaceShape(original.id, original))
        if (result != original) {
            state.applyEdit("Edit Ellipse Arc") { it.replaceShape(original.id, result) }
        }
        resetEllipseControl()
        context?.invalidate()
    }

    private fun cancelEllipseControl() {
        val original = ellipseOriginal
        if (original != null) {
            state.setDocumentForDrag(state.document.replaceShape(original.id, original))
        }
        resetEllipseControl()
        context?.invalidate()
    }

    private fun resetEllipseControl() {
        ellipseControl = null
        ellipseOriginal = null
        ellipseResult = null
    }

    private fun ellipseControlPoints(
        ellipse: Shape.EllipseShape
    ): List<Pair<EllipseControl, Point>> {
        val rect = ellipse.rect
        val start = Math.toRadians(ellipse.startAngleDegrees.toDouble()).toFloat()
        val sweep = Math.toRadians(ellipse.sweepDegrees.toDouble()).toFloat()

        fun localPoint(angle: Float, ratio: Float): Point = Point(
            rect.centerX + cos(angle) * rect.width / 2f * ratio,
            rect.centerY + sin(angle) * rect.height / 2f * ratio
        )

        val startPoint = localPoint(start, 1f)
        val sweepRadius = if (ellipse.sweepDegrees >= 359.999f) 0.82f else 1f
        val sweepPoint = localPoint(start + sweep, sweepRadius)
        val ratioPoint = localPoint(
            start + sweep / 2f,
            ellipse.arcRatio.coerceAtLeast(0.18f)
        )
        return listOf(
            EllipseControl.START to ellipse.transform.transform(startPoint),
            EllipseControl.SWEEP to ellipse.transform.transform(sweepPoint),
            EllipseControl.RATIO to ellipse.transform.transform(ratioPoint)
        )
    }

    private fun normalizeDegrees(value: Float): Float {
        val result = value % 360f
        return if (result < 0f) result + 360f else result
    }

    // Separate local-axis skew controls for a single object.
    private fun hitTestSkewControl(position: Point): SkewControl? {
        if (state.selectedShapes().size != 1) return null
        val cage = transformCage() ?: return null
        val toolContext = context ?: return null
        val screenPosition = toolContext.documentToScreen(position)
        val tolerancePx = state.controlHandleSizePx.coerceIn(3f, 14f) * 1.8f
        return skewControlPoints(cage)
            .minByOrNull { (_, point) ->
                toolContext.documentToScreen(point).distanceTo(screenPosition)
            }
            ?.takeIf { (_, point) ->
                toolContext.documentToScreen(point).distanceTo(screenPosition) <= tolerancePx
            }
            ?.first
    }

    private fun startSkew(control: SkewControl) {
        val shape = state.selectedShapes().singleOrNull() ?: return
        skewControl = control
        skewOriginal = shape
        skewResult = shape
    }

    private fun updateSkew(documentPosition: Point) {
        val control = skewControl ?: return
        val original = skewOriginal ?: return
        val bounds = original.localBounds()
        val local = original.transform.invert().transform(documentPosition)
        val skew: Matrix
        val pivot: Point
        when (control) {
            SkewControl.HORIZONTAL -> {
                pivot = bounds.bottomLeft.midpoint(bounds.bottomRight)
                val source = bounds.topLeft.midpoint(bounds.topRight)
                val denominator = source.y - pivot.y
                val kx = if (abs(denominator) > 1e-6f) {
                    ((local.x - source.x) / denominator).coerceIn(-4f, 4f)
                } else {
                    0f
                }
                skew = Matrix.skew(kx, 0f)
            }
            SkewControl.VERTICAL -> {
                pivot = bounds.topLeft.midpoint(bounds.bottomLeft)
                val source = bounds.topRight.midpoint(bounds.bottomRight)
                val denominator = source.x - pivot.x
                val ky = if (abs(denominator) > 1e-6f) {
                    ((local.y - source.y) / denominator).coerceIn(-4f, 4f)
                } else {
                    0f
                }
                skew = Matrix.skew(0f, ky)
            }
        }
        val aroundPivot =
            Matrix.translate(pivot.x, pivot.y) *
                skew *
                Matrix.translate(-pivot.x, -pivot.y)
        val result = original.withTransform(original.transform * aroundPivot)
        skewResult = result
        state.setDocumentForDrag(state.document.replaceShape(original.id, result))
        context?.invalidate()
    }

    private fun finishSkew() {
        val original = skewOriginal ?: return
        val result = skewResult ?: original
        state.setDocumentForDrag(state.document.replaceShape(original.id, original))
        if (result != original) {
            state.applyEdit("Skew") { it.replaceShape(original.id, result) }
        }
        resetSkew()
        context?.invalidate()
    }

    private fun cancelSkew() {
        val original = skewOriginal
        if (original != null) {
            state.setDocumentForDrag(state.document.replaceShape(original.id, original))
        }
        resetSkew()
        context?.invalidate()
    }

    private fun resetSkew() {
        skewControl = null
        skewOriginal = null
        skewResult = null
    }

    private fun skewControlPoints(cage: TransformCage): List<Pair<SkewControl, Point>> =
        listOf(
            SkewControl.HORIZONTAL to cage.topMid,
            SkewControl.VERTICAL to cage.rightMid
        )

    // Rotation
    private fun hitTestRotationHandle(p: Point): Pair<Point,Point>? {
        val cage = transformCage() ?: return null
        val tol = context?.hitTolerance ?: 2f
        val handle = rotationHandle(cage, tol)
        return if (p.distanceTo(handle) <= tol * 1.4f) handle to cage.center else null
    }

    private fun startRotate(cPos: Pair<Point,Point>) {
        isRotating = true
        rotationOriginals = state.selectedShapes().associate { it.id to it.transform }
        rotationCenter = cPos.second
        rotationStartAngle = atan2(
            cPos.first.y - rotationCenter.y,
            cPos.first.x - rotationCenter.x
        )
        accumulatedAngle = 0f
    }

    private fun computeRotatedDoc(a: Float): com.drawit.core.document.Document {
        var doc = restoreTransforms(state.document, rotationOriginals)
        val rotation = Matrix.rotate(a, rotationCenter)
        rotationOriginals.keys.forEach { id ->
            doc.findShape(id)?.let {
                doc = doc.replaceShape(id, it.withTransform(rotation * it.transform))
            }
        }
        return doc
    }

    private fun finishRotate() {
        if (!isRotating) return
        val angle = accumulatedAngle
        val originals = rotationOriginals
        val center = rotationCenter

        state.setDocumentForDrag(restoreTransforms(state.document, originals))
        if (abs(angle) > 1e-6f) {
            state.applyEdit("Rotate") { base ->
                var result = base
                val rotation = Matrix.rotate(angle, center)
                originals.keys.forEach { id ->
                    result.findShape(id)?.let {
                        result = result.replaceShape(id, it.withTransform(rotation * it.transform))
                    }
                }
                result
            }
        }
        resetRotationState()
        context?.invalidate()
    }

    private fun cancelRotate() {
        if (!isRotating) return
        state.setDocumentForDrag(restoreTransforms(state.document, rotationOriginals))
        resetRotationState()
        context?.invalidate()
    }

    private fun resetRotationState() {
        isRotating = false
        rotationOriginals = emptyMap()
        accumulatedAngle = 0f
    }

    private fun restoreTransforms(
        source: com.drawit.core.document.Document,
        transforms: Map<String, Matrix>
    ): com.drawit.core.document.Document {
        var result = source
        transforms.forEach { (id, original) ->
            result.findShape(id)?.let {
                result = result.replaceShape(id, it.withTransform(original))
            }
        }
        return result
    }

    private fun snap15(angle: Float): Float {
        val degrees = Math.toDegrees(angle.toDouble())
        return Math.toRadians((degrees / 15.0).roundToInt() * 15.0).toFloat()
    }

    private fun normalizeAngle(angle: Float): Float {
        var result = angle
        val fullTurn = (2.0 * PI).toFloat()
        while (result > PI.toFloat()) result -= fullTurn
        while (result < -PI.toFloat()) result += fullTurn
        return result
    }

    private fun transformCage(): TransformCage? {
        val selected = state.selectedShapes()
        if (selected.isEmpty()) return null
        return if (selected.size == 1) {
            val shape = selected.single()
            TransformCage(shape.localBounds().corners().map(shape.transform::transform))
        } else {
            state.selectionBounds()?.let { TransformCage(it.corners()) }
        }
    }

    private fun rotationHandle(cage: TransformCage, tolerance: Float): Point {
        val outward = (cage.topMid - cage.center).normalized()
        return cage.topMid + outward * tolerance * 2.5f
    }

    // Resize
    private fun hitTestResizeHandle(p: Point): ResizeHandle? {
        val cage = transformCage() ?: return null
        val handleScale = state.controlHandleSizePx.coerceIn(3f, 14f) / 6f
        val tolerance = (context?.hitTolerance ?: 2f) * 1.25f * handleScale
        return cage.corners.mapIndexedNotNull { index, corner ->
            if (p.distanceTo(corner) <= tolerance) {
                when (index) {
                    0 -> ResizeHandle.TOP_LEFT
                    1 -> ResizeHandle.TOP_RIGHT
                    2 -> ResizeHandle.BOTTOM_RIGHT
                    else -> ResizeHandle.BOTTOM_LEFT
                }
            } else {
                null
            }
        }.firstOrNull()
    }

    private fun startResize(handle: ResizeHandle) {
        val bounds = state.selectionBounds() ?: return
        val selected = state.selectedShapes()
        resizeHandle = handle
        resizeBounds = bounds
        resizeOriginals = selected.associate { it.id to it.transform }
        if (selected.size == 1) {
            val shape = selected.single()
            val localBounds = shape.localBounds()
            resizeSingleShapeId = shape.id
            resizeLocalBounds = localBounds
            resizeLocalPivot = when (handle) {
                ResizeHandle.TOP_LEFT -> localBounds.bottomRight
                ResizeHandle.TOP_RIGHT -> localBounds.bottomLeft
                ResizeHandle.BOTTOM_RIGHT -> localBounds.topLeft
                ResizeHandle.BOTTOM_LEFT -> localBounds.topRight
            }
        }
        resizePivot = when (handle) {
            ResizeHandle.TOP_LEFT -> bounds.bottomRight
            ResizeHandle.TOP_RIGHT -> bounds.bottomLeft
            ResizeHandle.BOTTOM_RIGHT -> bounds.topLeft
            ResizeHandle.BOTTOM_LEFT -> bounds.topRight
        }
        resizeScaleX = 1f
        resizeScaleY = 1f
    }

    private fun updateResize(position: Point, keepAspectRatio: Boolean) {
        val bounds = resizeBounds ?: return
        val handle = resizeHandle ?: return
        val localBounds = resizeLocalBounds
        val localPivot = resizeLocalPivot
        val singleId = resizeSingleShapeId
        val originalTransform = singleId?.let(resizeOriginals::get)
        val workingPosition: Point
        val originalHandle: Point
        val workingPivot: Point
        if (localBounds != null && localPivot != null && originalTransform != null) {
            workingPosition = originalTransform.invert().transform(position)
            originalHandle = when (handle) {
                ResizeHandle.TOP_LEFT -> localBounds.topLeft
                ResizeHandle.TOP_RIGHT -> localBounds.topRight
                ResizeHandle.BOTTOM_RIGHT -> localBounds.bottomRight
                ResizeHandle.BOTTOM_LEFT -> localBounds.bottomLeft
            }
            workingPivot = localPivot
        } else {
            workingPosition = position
            originalHandle = when (handle) {
                ResizeHandle.TOP_LEFT -> bounds.topLeft
                ResizeHandle.TOP_RIGHT -> bounds.topRight
                ResizeHandle.BOTTOM_RIGHT -> bounds.bottomRight
                ResizeHandle.BOTTOM_LEFT -> bounds.bottomLeft
            }
            workingPivot = resizePivot
        }

        val originalDx = originalHandle.x - workingPivot.x
        val originalDy = originalHandle.y - workingPivot.y
        var sx = if (abs(originalDx) > 1e-6f) {
            (workingPosition.x - workingPivot.x) / originalDx
        } else {
            1f
        }
        var sy = if (abs(originalDy) > 1e-6f) {
            (workingPosition.y - workingPivot.y) / originalDy
        } else {
            1f
        }

        if (keepAspectRatio) {
            val uniform = if (abs(sx - 1f) >= abs(sy - 1f)) sx else sy
            sx = uniform
            sy = uniform
        }

        resizeScaleX = nonZeroScale(sx)
        resizeScaleY = nonZeroScale(sy)
        state.setDocumentForDrag(computeResizedDoc(resizeScaleX, resizeScaleY))
        context?.invalidate()
    }

    private fun computeResizedDoc(sx: Float, sy: Float): com.drawit.core.document.Document {
        var doc = restoreTransforms(state.document, resizeOriginals)
        val singleId = resizeSingleShapeId
        val localPivot = resizeLocalPivot
        if (singleId != null && localPivot != null) {
            val original = resizeOriginals[singleId] ?: return doc
            doc.findShape(singleId)?.let { shape ->
                val localScaling = Matrix.scale(sx, sy, localPivot)
                doc = doc.replaceShape(
                    singleId,
                    shape.withTransform(original * localScaling)
                )
            }
            return doc
        }
        val scaling = Matrix.scale(sx, sy, resizePivot)
        resizeOriginals.keys.forEach { id ->
            doc.findShape(id)?.let {
                doc = doc.replaceShape(id, it.withTransform(scaling * it.transform))
            }
        }
        return doc
    }

    private fun finishResize() {
        val originals = resizeOriginals
        val sx = resizeScaleX
        val sy = resizeScaleY
        val pivot = resizePivot
        val singleId = resizeSingleShapeId
        val localPivot = resizeLocalPivot
        state.setDocumentForDrag(restoreTransforms(state.document, originals))
        if (abs(sx - 1f) > 1e-6f || abs(sy - 1f) > 1e-6f) {
            state.applyEdit("Resize") { base ->
                var result = base
                if (singleId != null && localPivot != null) {
                    val original = originals[singleId]
                    result.findShape(singleId)?.let { shape ->
                        if (original != null) {
                            result = result.replaceShape(
                                singleId,
                                shape.withTransform(
                                    original * Matrix.scale(sx, sy, localPivot)
                                )
                            )
                        }
                    }
                } else {
                    val scaling = Matrix.scale(sx, sy, pivot)
                    originals.keys.forEach { id ->
                        result.findShape(id)?.let {
                            result = result.replaceShape(id, it.withTransform(scaling * it.transform))
                        }
                    }
                }
                result
            }
        }
        resetResizeState()
        context?.invalidate()
    }

    private fun cancelResize() {
        if (resizeHandle == null) return
        state.setDocumentForDrag(restoreTransforms(state.document, resizeOriginals))
        resetResizeState()
        context?.invalidate()
    }

    private fun resetResizeState() {
        resizeHandle = null
        resizeOriginals = emptyMap()
        resizeBounds = null
        resizeSingleShapeId = null
        resizeLocalBounds = null
        resizeLocalPivot = null
        resizeScaleX = 1f
        resizeScaleY = 1f
    }

    private fun nonZeroScale(value: Float): Float = when {
        value in 0f..0.01f -> 0.01f
        value < 0f && value > -0.01f -> -0.01f
        else -> value
    }

    // Move
    private fun commitDrag(d: Point) {
        val transforms = originalTransforms
        val ids = movedShapeIds
        state.setDocumentForDrag(restoreTransforms(state.document, transforms))
        state.applyEdit("Move") { base ->
            var result = base
            val translation = Matrix.translate(d.x, d.y)
            ids.forEach { id ->
                result.findShape(id)?.let {
                    result = result.replaceShape(id, it.withTransform(translation * it.transform))
                }
            }
            result
        }
        originalTransforms = emptyMap()
    }

    private fun snapTranslation(raw: Point): Point {
        val bounds = originalSelectionBounds ?: return raw
        val page = state.document.activePage
        val tolerance = (context?.hitTolerance ?: 1f) * 1.5f

        val movingX = floatArrayOf(
            bounds.left + raw.x,
            bounds.centerX + raw.x,
            bounds.right + raw.x
        )
        val movingY = floatArrayOf(
            bounds.top + raw.y,
            bounds.centerY + raw.y,
            bounds.bottom + raw.y
        )
        val targetX = mutableListOf(0f, page.width / 2f, page.width)
        val targetY = mutableListOf(0f, page.height / 2f, page.height)
        page.layers
            .asSequence()
            .filter { it.visible }
            .flatMap { it.shapes.asSequence() }
            .filter { it.visible && it.id !in movedShapeIds }
            .forEach { shape ->
                val b = shape.bounds()
                targetX += listOf(b.left, b.centerX, b.right)
                targetY += listOf(b.top, b.centerY, b.bottom)
            }

        val bestX = closestSnap(movingX, targetX, tolerance)
        val bestY = closestSnap(movingY, targetY, tolerance)
        snapGuideX = bestX?.second
        snapGuideY = bestY?.second
        return Point(
            raw.x + (bestX?.first ?: 0f),
            raw.y + (bestY?.first ?: 0f)
        )
    }

    private fun closestSnap(
        moving: FloatArray,
        targets: List<Float>,
        tolerance: Float
    ): Pair<Float, Float>? {
        var bestDistance = tolerance + 1f
        var best: Pair<Float, Float>? = null
        for (movingValue in moving) {
            for (target in targets) {
                val adjustment = target - movingValue
                val distance = abs(adjustment)
                if (distance <= tolerance && distance < bestDistance) {
                    bestDistance = distance
                    best = adjustment to target
                }
            }
        }
        return best
    }

    private fun cancelDrag() {
        if (dragStart == null && originalTransforms.isEmpty() && marqueeRect == null) return
        if (originalTransforms.isNotEmpty()) {
            state.setDocumentForDrag(restoreTransforms(state.document, originalTransforms))
        }
        resetDragState()
        context?.invalidate()
    }

    private fun resetDragState() {
        dragStart = null
        lastPosition = null
        isDragging = false
        marqueeRect = null
        hitShapeId = null
        movedShapeIds = emptySet()
        originalTransforms = emptyMap()
        originalSelectionBounds = null
        currentDragDelta = Point.ZERO
        snapGuideX = null
        snapGuideY = null
    }

    private fun hitTest(p: Point): String? {
        val tol = context?.hitTolerance ?: 1f
        val hr = Rect(p.x-tol,p.y-tol,p.x+tol,p.y+tol)
        return state.document.activePage.layers.asReversed().filter{it.visible&&!it.locked}.flatMap{it.shapes.asReversed()}.find{it.visible&&!it.locked&&hr.intersects(it.bounds())}?.id
    }

    override fun drawOverlay(canvas: Any, context: ToolContext) {
        val c = canvas as? android.graphics.Canvas ?: return
        val guidePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(222, 45, 125)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
        }
        snapGuideX?.let { x ->
            val top = context.documentToScreen(Point(x, 0f))
            val bottom = context.documentToScreen(Point(x, state.document.activePage.height))
            c.drawLine(top.x, top.y, bottom.x, bottom.y, guidePaint)
        }
        snapGuideY?.let { y ->
            val left = context.documentToScreen(Point(0f, y))
            val right = context.documentToScreen(Point(state.document.activePage.width, y))
            c.drawLine(left.x, left.y, right.x, right.y, guidePaint)
        }
        marqueeRect?.let { rect ->
            val tl=context.documentToScreen(rect.topLeft); val br=context.documentToScreen(rect.bottomRight)
            c.drawRect(tl.x,tl.y,br.x,br.y,android.graphics.Paint().apply{color=android.graphics.Color.argb(40,0,120,215);style=android.graphics.Paint.Style.FILL})
            c.drawRect(tl.x,tl.y,br.x,br.y,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(0,120,215);style=android.graphics.Paint.Style.STROKE;strokeWidth=1f})
        }
        transformCage()?.let { cage ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(0, 120, 215)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
            }
            val handleSize = state.controlHandleSizePx.coerceIn(3f, 14f)
            val screenCorners = cage.corners.map(context::documentToScreen)
            val cagePath = android.graphics.Path().apply {
                moveTo(screenCorners.first().x, screenCorners.first().y)
                screenCorners.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            c.drawPath(cagePath, paint)
            cage.corners.forEach { corner ->
                val screen = context.documentToScreen(corner)
                c.drawRect(
                    screen.x - handleSize,
                    screen.y - handleSize,
                    screen.x + handleSize,
                    screen.y + handleSize,
                    android.graphics.Paint(paint).apply {
                        style = android.graphics.Paint.Style.FILL
                        color = android.graphics.Color.WHITE
                    })
                c.drawRect(
                    screen.x - handleSize,
                    screen.y - handleSize,
                    screen.x + handleSize,
                    screen.y + handleSize,
                    paint
                )
            }
            val handle = context.documentToScreen(rotationHandle(cage, context.hitTolerance))
            val top = context.documentToScreen(cage.topMid)
            c.drawLine(top.x, top.y, handle.x, handle.y, paint)
            c.drawCircle(handle.x, handle.y, handleSize + 1f, android.graphics.Paint(paint).apply {
                style = android.graphics.Paint.Style.FILL
                color = android.graphics.Color.WHITE
            })
            c.drawCircle(handle.x, handle.y, handleSize + 1f, paint)

            if (state.selectedShapes().size == 1) {
                skewControlPoints(cage).forEach { (control, point) ->
                    val screen = context.documentToScreen(point)
                    val radius = handleSize + 2f
                    val diamond = android.graphics.Path().apply {
                        moveTo(screen.x, screen.y - radius)
                        lineTo(screen.x + radius, screen.y)
                        lineTo(screen.x, screen.y + radius)
                        lineTo(screen.x - radius, screen.y)
                        close()
                    }
                    val active = control == skewControl
                    c.drawPath(diamond, android.graphics.Paint(paint).apply {
                        style = android.graphics.Paint.Style.FILL
                        color = if (active) {
                            android.graphics.Color.rgb(222, 45, 125)
                        } else {
                            android.graphics.Color.rgb(255, 145, 0)
                        }
                    })
                    c.drawPath(diamond, paint)
                }
            }
        }

        val ellipse = state.selectedShapes().singleOrNull() as? Shape.EllipseShape
        if (ellipse != null) {
            val handleSize = state.controlHandleSizePx.coerceIn(3f, 14f)
            val center = context.documentToScreen(
                ellipse.transform.transform(ellipse.rect.center)
            )
            val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(150, 0, 120, 215)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = (handleSize * 1.4f).coerceAtLeast(9f)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            ellipseControlPoints(ellipse).forEach { (control, documentPoint) ->
                val screen = context.documentToScreen(documentPoint)
                c.drawLine(center.x, center.y, screen.x, screen.y, linePaint)
                c.drawCircle(
                    screen.x,
                    screen.y,
                    handleSize + 2f,
                    android.graphics.Paint(linePaint).apply {
                        style = android.graphics.Paint.Style.FILL
                        color = if (control == ellipseControl) {
                            android.graphics.Color.rgb(222, 45, 125)
                        } else {
                            android.graphics.Color.rgb(0, 120, 215)
                        }
                    }
                )
                val label = when (control) {
                    EllipseControl.START -> "S"
                    EllipseControl.SWEEP -> "E"
                    EllipseControl.RATIO -> "R"
                }
                c.drawText(
                    label,
                    screen.x,
                    screen.y - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                    labelPaint
                )
            }
        }
    }
}
