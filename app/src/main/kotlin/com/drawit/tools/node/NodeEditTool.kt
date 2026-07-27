package com.drawit.tools.node

import android.graphics.PathMeasure
import android.view.KeyEvent
import com.drawit.canvas.EditorState
import com.drawit.core.document.Fill
import com.drawit.core.document.ImageShape
import com.drawit.core.document.Shape
import com.drawit.core.document.TextShape
import com.drawit.core.geometry.PathCommand
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Point
import com.drawit.core.input.Button
import com.drawit.core.input.Tool
import com.drawit.core.input.ToolContext
import com.drawit.core.input.ToolEvent
import com.drawit.text.TextEngine
import kotlin.math.ceil

/**
 * Destructive curve editor. Parametric/vector objects are converted to a
 * PathShape before their end points and control points can be moved.
 */
class NodeEditTool(
    private val state: EditorState,
    private val textEngine: TextEngine
) : Tool {

    override val id = "node-edit"
    override val name = "Node Editor"

    private enum class Role { END, CONTROL_1, CONTROL_2 }
    private data class NodeRef(val commandIndex: Int, val role: Role)

    private var context: ToolContext? = null
    private var selectedNode: NodeRef? = null
    private var dragOriginal: Shape.PathShape? = null
    private var dragResult: Shape.PathShape? = null

    override fun activate(context: ToolContext) {
        this.context = context
        ensureEditablePath()
    }

    override fun deactivate() {
        cancelDrag()
        selectedNode = null
        context = null
    }

    override fun onEvent(event: ToolEvent): Boolean = when (event) {
        is ToolEvent.Down -> onDown(event)
        is ToolEvent.Move -> onMove(event)
        is ToolEvent.Up -> onUp()
        is ToolEvent.Key -> onKey(event)
        is ToolEvent.Cancel -> {
            cancelDrag()
            true
        }
        else -> false
    }

    private fun onDown(event: ToolEvent.Down): Boolean {
        if (event.button != Button.PRIMARY) return false
        val shape = ensureEditablePath() ?: return false
        val node = nearestNode(shape, event.position) ?: return false
        selectedNode = node
        dragOriginal = shape
        dragResult = shape
        context?.invalidate()
        return true
    }

    private fun onMove(event: ToolEvent.Move): Boolean {
        val node = selectedNode ?: return false
        val original = dragOriginal ?: return false
        val localPoint = original.transform.invert().transform(event.position)
        val updated = original.copy(pathData = replaceNode(original.pathData, node, localPoint))
        dragResult = updated
        state.setDocumentForDrag(state.document.replaceShape(original.id, updated))
        context?.invalidate()
        return true
    }

    private fun onUp(): Boolean {
        val original = dragOriginal ?: return selectedNode != null
        val result = dragResult ?: original
        state.setDocumentForDrag(state.document.replaceShape(original.id, original))
        if (result != original) {
            state.applyEdit("Edit Nodes") { it.replaceShape(original.id, result) }
        }
        dragOriginal = null
        dragResult = null
        context?.invalidate()
        return true
    }

    private fun onKey(event: ToolEvent.Key): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> {
                cancelDrag()
                selectedNode = null
                context?.invalidate()
                true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                val node = selectedNode ?: return false
                val shape = ensureEditablePath() ?: return false
                if (shape.pathData.commands.size <= 2) return true
                val commands = shape.pathData.commands.toMutableList()
                commands.removeAt(node.commandIndex)
                state.updateShape(shape.id, "Delete Node") {
                    shape.copy(pathData = shape.pathData.copy(commands = commands))
                }
                selectedNode = null
                context?.invalidate()
                true
            }
            else -> false
        }
    }

    private fun cancelDrag() {
        val original = dragOriginal ?: return
        state.setDocumentForDrag(state.document.replaceShape(original.id, original))
        dragOriginal = null
        dragResult = null
        context?.invalidate()
    }

    private fun ensureEditablePath(): Shape.PathShape? {
        val selected = state.selectedShapes().singleOrNull() ?: return null
        if (selected is Shape.PathShape) return selected

        val path = when (selected) {
            is TextShape -> textOutline(selected)
            is Shape.GroupShape -> combinePaths(selected.children.map(::flattenToParent))
            else -> selected.localPath()
        }
        if (path.isEmpty) return null

        val firstChild = (selected as? Shape.GroupShape)?.children?.firstOrNull()
        val converted = Shape.PathShape(
            id = selected.id,
            name = "${selected.name} Curves",
            pathData = path,
            transform = selected.transform,
            fill = when (selected) {
                is ImageShape -> Fill.Pattern(
                    imageId = selected.imageId,
                    placement = Fill.Pattern.Placement.STRETCH
                )
                is Shape.GroupShape ->
                    if (selected.fill != Fill.None) selected.fill else firstChild?.fill ?: Fill.None
                else -> selected.fill
            },
            stroke = selected.stroke ?: firstChild?.stroke,
            visible = selected.visible,
            locked = selected.locked,
            opacity = selected.opacity,
            blendMode = selected.blendMode,
            effects = selected.effects
        )
        state.updateShape(selected.id, "Convert to Curves") { converted }
        return converted
    }

    private fun flattenToParent(shape: Shape): PathData {
        val local = if (shape is Shape.GroupShape) {
            combinePaths(shape.children.map(::flattenToParent))
        } else {
            shape.localPath()
        }
        return local.transform(shape.transform)
    }

    private fun combinePaths(paths: List<PathData>): PathData =
        PathData(
            commands = paths.flatMap { it.commands },
            fillRule = paths.firstOrNull()?.fillRule ?: PathData.FillRule.NON_ZERO
        )

    private fun textOutline(shape: TextShape): PathData {
        val layout = textEngine.layout(shape)
        val androidPath = android.graphics.Path()
        layout.lines.forEach { line ->
            val x = textEngine.lineXOffset(shape, line, layout)
            layout.paint.getTextPath(
                line.text,
                0,
                line.text.length,
                x,
                line.baselineY,
                androidPath
            )
        }

        var output = PathData.EMPTY
        val measure = PathMeasure(androidPath, false)
        val coordinates = FloatArray(2)
        do {
            val length = measure.length
            if (length > 0f) {
                val segments = ceil(length / 0.5f).toInt().coerceIn(4, 4096)
                repeat(segments + 1) { index ->
                    measure.getPosTan(length * index / segments, coordinates, null)
                    val point = Point(coordinates[0], coordinates[1])
                    output = if (index == 0) output.moveTo(point) else output.lineTo(point)
                }
                if (measure.isClosed) output = output.close()
            }
        } while (measure.nextContour())
        return output
    }

    private fun nearestNode(shape: Shape.PathShape, documentPoint: Point): NodeRef? {
        val handleScale = state.controlHandleSizePx.coerceIn(3f, 14f) / 6f
        val tolerance = (context?.hitTolerance ?: 2f) * 1.6f * handleScale
        return nodes(shape.pathData).minByOrNull { (_, point) ->
            shape.transform.transform(point).distanceTo(documentPoint)
        }?.takeIf { (_, point) ->
            shape.transform.transform(point).distanceTo(documentPoint) <= tolerance
        }?.first
    }

    private fun nodes(path: PathData): List<Pair<NodeRef, Point>> =
        buildList {
            path.commands.forEachIndexed { index, command ->
                when (command) {
                    is PathCommand.MoveTo ->
                        add(NodeRef(index, Role.END) to command.point)
                    is PathCommand.LineTo ->
                        add(NodeRef(index, Role.END) to command.point)
                    is PathCommand.CubicTo -> {
                        add(NodeRef(index, Role.CONTROL_1) to command.cp1)
                        add(NodeRef(index, Role.CONTROL_2) to command.cp2)
                        add(NodeRef(index, Role.END) to command.end)
                    }
                    is PathCommand.QuadTo -> {
                        add(NodeRef(index, Role.CONTROL_1) to command.cp)
                        add(NodeRef(index, Role.END) to command.end)
                    }
                    PathCommand.Close -> Unit
                }
            }
        }

    private fun replaceNode(path: PathData, node: NodeRef, point: Point): PathData {
        val commands = path.commands.toMutableList()
        commands[node.commandIndex] = when (val command = commands[node.commandIndex]) {
            is PathCommand.MoveTo -> command.copy(point = point)
            is PathCommand.LineTo -> command.copy(point = point)
            is PathCommand.CubicTo -> when (node.role) {
                Role.CONTROL_1 -> command.copy(cp1 = point)
                Role.CONTROL_2 -> command.copy(cp2 = point)
                Role.END -> command.copy(end = point)
            }
            is PathCommand.QuadTo -> when (node.role) {
                Role.CONTROL_1, Role.CONTROL_2 -> command.copy(cp = point)
                Role.END -> command.copy(end = point)
            }
            PathCommand.Close -> command
        }
        return path.copy(commands = commands)
    }

    override fun drawOverlay(canvas: Any, context: ToolContext) {
        val target = canvas as? android.graphics.Canvas ?: return
        val helpPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 15f
            setShadowLayer(3f, 0f, 1f, android.graphics.Color.BLACK)
        }
        val shape = state.selectedShapes().singleOrNull() as? Shape.PathShape
        if (shape == null) {
            target.drawText(
                "Node Editor: select exactly one object first",
                18f,
                34f,
                helpPaint
            )
            return
        }
        target.drawText(
            "Node Editor: drag squares/round handles • Delete removes selected node • Undo restores conversion",
            18f,
            34f,
            helpPaint
        )
        val handleSize = state.controlHandleSizePx.coerceIn(3f, 14f)
        val nodePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.WHITE
        }
        val selectedPaint = android.graphics.Paint(nodePaint).apply {
            color = android.graphics.Color.rgb(0, 120, 215)
        }
        val outlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.5f
            color = android.graphics.Color.rgb(0, 120, 215)
        }
        val handleLinePaint = android.graphics.Paint(outlinePaint).apply {
            strokeWidth = 1f
            color = android.graphics.Color.argb(150, 0, 120, 215)
        }

        fun screen(point: Point): Point =
            context.documentToScreen(shape.transform.transform(point))

        var current = Point.ZERO
        var subpathStart = Point.ZERO
        shape.pathData.commands.forEach { command ->
            when (command) {
                is PathCommand.MoveTo -> {
                    current = command.point
                    subpathStart = command.point
                }
                is PathCommand.LineTo -> current = command.point
                is PathCommand.CubicTo -> {
                    val from = screen(current)
                    val cp1 = screen(command.cp1)
                    val cp2 = screen(command.cp2)
                    val end = screen(command.end)
                    target.drawLine(from.x, from.y, cp1.x, cp1.y, handleLinePaint)
                    target.drawLine(cp2.x, cp2.y, end.x, end.y, handleLinePaint)
                    current = command.end
                }
                is PathCommand.QuadTo -> {
                    val from = screen(current)
                    val control = screen(command.cp)
                    val end = screen(command.end)
                    target.drawLine(from.x, from.y, control.x, control.y, handleLinePaint)
                    target.drawLine(control.x, control.y, end.x, end.y, handleLinePaint)
                    current = command.end
                }
                PathCommand.Close -> current = subpathStart
            }
        }

        nodes(shape.pathData).forEach { (ref, localPoint) ->
            val screen = screen(localPoint)
            val paint = if (ref == selectedNode) selectedPaint else nodePaint
            if (ref.role == Role.END) {
                target.drawRect(
                    screen.x - handleSize, screen.y - handleSize,
                    screen.x + handleSize, screen.y + handleSize,
                    paint
                )
                target.drawRect(
                    screen.x - handleSize, screen.y - handleSize,
                    screen.x + handleSize, screen.y + handleSize,
                    outlinePaint
                )
            } else {
                target.drawCircle(screen.x, screen.y, handleSize * 0.9f, paint)
                target.drawCircle(screen.x, screen.y, handleSize * 0.9f, outlinePaint)
            }
        }
    }
}
