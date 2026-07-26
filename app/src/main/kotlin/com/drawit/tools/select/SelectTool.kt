package com.drawit.tools.select

import com.drawit.canvas.EditorState
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.Point
import com.drawit.core.geometry.Rect
import com.drawit.core.input.Button
import com.drawit.core.input.Tool
import com.drawit.core.input.ToolContext
import com.drawit.core.input.ToolEvent
import kotlin.math.roundToInt

class SelectTool(private val state: EditorState) : Tool {
    override val id = "select"; override val name = "Select"
    private var context: ToolContext? = null
    private var dragStart: Point? = null; private var lastPosition: Point? = null
    private var movedShapeIds: Set<String> = emptySet()
    private var originalTransforms: Map<String, Matrix> = emptyMap()
    private var isDragging = false; private var marqueeRect: Rect? = null
    private var hitShapeId: String? = null
    private var isRotating = false; private var rotationOriginals: Map<String, Matrix> = emptyMap()
    private var rotationCenter = Point.ZERO; private var rotationStartAngle = 0f
    private var accumulatedAngle = 0f

    override fun activate(context: ToolContext) { this.context = context }
    override fun deactivate() { cancelDrag(); cancelRotate() }

    override fun onEvent(event: ToolEvent): Boolean = when (event) {
        is ToolEvent.Down -> onDown(event); is ToolEvent.Move -> onMove(event)
        is ToolEvent.Up -> onUp(event); is ToolEvent.Key -> onKey(event)
        is ToolEvent.Cancel -> { cancelDrag(); cancelRotate(); true }
        else -> false
    }

    private fun onDown(event: ToolEvent.Down): Boolean {
        if (event.button != Button.PRIMARY) return false
        if (hitTestRotationHandle(event.position)?.also { startRotate(it) } != null) return true
        dragStart = event.position; lastPosition = event.position; isDragging = false
        val hit = hitTest(event.position); hitShapeId = hit
        if (hit != null) {
            if (hit !in state.selectedShapeIds)
                state.select(if (event.modifiers.shift) state.selectedShapeIds + hit else setOf(hit))
            movedShapeIds = state.selectedShapeIds
            originalTransforms = state.selectedShapes().associate { it.id to it.transform }
        }
        return true
    }

    private fun onMove(event: ToolEvent.Move): Boolean {
        if (isRotating) {
            val angle = kotlin.math.atan2((event.position.y - rotationCenter.y).toDouble(), (event.position.x - rotationCenter.x).toDouble()).toFloat() - rotationStartAngle
            val snap = if (event.modifiers.shift) snap15(accumulatedAngle + angle) else accumulatedAngle + angle
            state.setDocumentForDrag(computeRotatedDoc(snap)); context?.invalidate(); return true
        }
        val start = dragStart ?: return false; val last = lastPosition ?: return false
        if (!isDragging && event.position.distanceTo(start) < (context?.hitTolerance ?: 1f)) return true
        isDragging = true
        if (hitShapeId != null && movedShapeIds.isNotEmpty()) {
            val d = event.position - last; val t = Matrix.translate(d.x, d.y)
            var doc = state.document
            movedShapeIds.forEach { id -> doc.findShape(id)?.let { doc = doc.replaceShape(id, it.withTransform(t * it.transform)) } }
            state.setDocumentForDrag(doc)
        } else marqueeRect = Rect.fromPoints(start, event.position).also { context?.invalidate() }
        lastPosition = event.position; return true
    }

    private fun onUp(event: ToolEvent.Up): Boolean {
        if (isRotating) { finishRotate(); return true }
        val start = dragStart
        if (isDragging && movedShapeIds.isNotEmpty() && start != null) commitDrag(event.position - start)
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
        dragStart = null; isDragging = false; hitShapeId = null; movedShapeIds = emptySet()
        return true
    }

    private fun onKey(event: ToolEvent.Key): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) state.clearSelection(); return true
    }

    // Rotation
    private fun hitTestRotationHandle(p: Point): Pair<Point,Point>? {
        val bounds = state.selectionBounds() ?: return null; val tol = (context?.hitTolerance ?: 2f) * 2.5f
        return bounds.corners().find { Rect(it.x-tol,it.y-tol,it.x+tol,it.y+tol).contains(p) }?.let { it to bounds.center }
    }
    private fun startRotate(cPos: Pair<Point,Point>) {
        isRotating = true; rotationOriginals = state.selectedShapes().associate { it.id to it.transform }
        rotationCenter = cPos.second; rotationStartAngle = kotlin.math.atan2((cPos.first.y-rotationCenter.y).toDouble(),(cPos.first.x-rotationCenter.x).toDouble()).toFloat(); accumulatedAngle = 0f
    }
    private fun computeRotatedDoc(a: Float): com.drawit.core.document.Document {
        var d = state.document; rotationOriginals.forEach { (id,orig) -> d.findShape(id)?.let { d = d.replaceShape(id,it.withTransform(orig)) } }
        val r = Matrix.rotate(a,rotationCenter); state.selectedShapeIds.forEach { id -> d.findShape(id)?.let { d = d.replaceShape(id,it.withTransform(r * it.transform)) } }
        return d; accumulatedAngle = a
    }
    private fun finishRotate() { if(isRotating){state.applyEdit("Rotate"){computeRotatedDoc(accumulatedAngle)}; isRotating=false; rotationOriginals=emptyMap(); accumulatedAngle=0f} }
    private fun cancelRotate() { if(!isRotating)return; var d=state.document; rotationOriginals.forEach{(id,orig)->d.findShape(id)?.let{d=d.replaceShape(id,it.withTransform(orig))}}; state.setDocumentForDrag(d); isRotating=false; rotationOriginals=emptyMap() }
    private fun snap15(a:Float)=(Math.toRadians((Math.toDegrees(a.toDouble())/15f).roundToInt()*15f.toDouble())).toFloat()

    // Move
    private fun commitDrag(d: Point) {
        var restored = state.document; originalTransforms.forEach{(id,orig)->restored.findShape(id)?.let{restored=restored.replaceShape(id,it.withTransform(orig))}}
        state.setDocumentForDrag(restored); state.applyEdit("Move"){doc->var r=doc; movedShapeIds.forEach{id->r.findShape(id)?.let{r=r.replaceShape(id,it.withTransform(Matrix.translate(d.x,d.y)*it.transform))}};r}
    }
    private fun cancelDrag() { var d=state.document; originalTransforms.forEach{(id,orig)->d.findShape(id)?.let{d=d.replaceShape(id,it.withTransform(orig))}}; state.setDocumentForDrag(d); dragStart=null;isDragging=false }

    private fun hitTest(p: Point): String? {
        val tol=context?.hitTolerance?:1f; val hr=Rect(p.x-tol,p.y-tol,p.x+tol,p.y+tol)
        return state.document.activePage.layers.asReversed().filter{it.visible&&!it.locked}.flatMap{it.shapes.asReversed()}.find{it.visible&&!it.locked&&hr.intersects(it.bounds())}?.id
    }

    override fun drawOverlay(canvas: Any, context: ToolContext) {
        val c = canvas as? android.graphics.Canvas ?: return
        marqueeRect?.let { rect ->
            val tl=context.documentToScreen(rect.topLeft); val br=context.documentToScreen(rect.bottomRight)
            c.drawRect(tl.x,tl.y,br.x,br.y,android.graphics.Paint().apply{color=android.graphics.Color.argb(40,0,120,215);style=android.graphics.Paint.Style.FILL})
            c.drawRect(tl.x,tl.y,br.x,br.y,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(0,120,215);style=android.graphics.Paint.Style.STROKE;strokeWidth=1f})
        }
        state.selectionBounds()?.let { bounds ->
            val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(0,120,215);style=android.graphics.Paint.Style.STROKE;strokeWidth=1.5f}
            val r=(context.hitTolerance?:2f)*2.5f
            for(corner in bounds.corners()){ val s=context.documentToScreen(corner); c.drawOval(s.x-r,s.y-r,s.x+r,s.y+r,p) }
        }
    }
}
