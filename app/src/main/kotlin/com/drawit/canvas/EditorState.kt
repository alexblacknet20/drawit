package com.drawit.canvas

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.drawit.core.document.ColorMode
import com.drawit.core.document.Document
import com.drawit.core.document.Fill
import com.drawit.core.document.Layer
import com.drawit.core.document.Margins
import com.drawit.core.document.Page
import com.drawit.core.document.Shape
import com.drawit.core.document.Unit
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.Point
import com.drawit.core.geometry.Rect
import com.drawit.core.undo.SnapshotCommand
import com.drawit.core.undo.UndoManager
import com.drawit.shapes.BooleanOps

/**
 * Central editor state: document, viewport, selection, undo.
 * Compose-observable; CanvasView redraws when [document] changes.
 */
class EditorState {

    var document by mutableStateOf(Document())
        private set

    var viewMatrix by mutableStateOf(Matrix.IDENTITY)
        private set

    var selectedShapeIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var canvasSizePx by mutableStateOf(Point(0f, 0f))
        private set

    /** Monotonic counter bumped on every document change — CanvasView watches it. */
    var documentVersion by mutableStateOf(0L)
        private set

    /** Monotonic counter bumped on viewport-only changes so AndroidView redraws. */
    var viewportVersion by mutableStateOf(0L)
        private set

    /** Currently open file (for quick Save); null = never saved. */
    var currentFileUri by mutableStateOf<Uri?>(null)
        private set

    /** Tool requested by UI; consumed by CanvasView on next frame. */
    var pendingToolForCanvas by mutableStateOf<com.drawit.core.input.Tool?>(null)

    /** Enables edge/centre snapping and alignment guides while objects move. */
    var smartAlignmentsEnabled by mutableStateOf(true)

    /** Half-size of transform/node handles in physical screen pixels. */
    var controlHandleSizePx by mutableStateOf(6f)

    val undoManager = UndoManager(maxDepth = 100)

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    init {
        undoManager.onStateChanged = { u, r ->
            canUndo = u
            canRedo = r
        }
    }

    // --- Viewport (pixels per mm at zoom 1.0) ---
    private val baseScale = 3.7795275591f // 96 dpi / 25.4 mm-per-inch

    val zoom: Float get() = viewMatrix.scaleX / baseScale

    fun setCanvasSize(widthPx: Float, heightPx: Float) {
        val wasZero = canvasSizePx.x <= 0f
        canvasSizePx = Point(widthPx, heightPx)
        if (wasZero && widthPx > 0f) zoomToFit()
    }

    fun zoomToFit() {
        val page = document.activePage
        if (canvasSizePx.x <= 0f || canvasSizePx.y <= 0f) return
        val margin = 0.85f
        val scaleX = canvasSizePx.x * margin / page.width
        val scaleY = canvasSizePx.y * margin / page.height
        val scale = minOf(scaleX, scaleY)
        val offsetX = (canvasSizePx.x - page.width * scale) / 2f
        val offsetY = (canvasSizePx.y - page.height * scale) / 2f
        viewMatrix = Matrix(a = scale, d = scale, e = offsetX, f = offsetY)
        viewportVersion++
    }

    fun pan(dxPx: Float, dyPx: Float) {
        viewMatrix = viewMatrix.copy(e = viewMatrix.e + dxPx, f = viewMatrix.f + dyPx)
        viewportVersion++
    }

    fun zoomAt(focusScreen: Point, factor: Float) {
        val newScale = (viewMatrix.a * factor).coerceIn(0.05f * baseScale, 40f * baseScale)
        val actualFactor = newScale / viewMatrix.a
        viewMatrix = Matrix.scale(actualFactor, actualFactor, focusScreen) * viewMatrix
        viewportVersion++
    }

    /** Set a true 100% view while keeping the current viewport centre fixed. */
    fun zoomTo100Percent() {
        if (canvasSizePx.x <= 0f || canvasSizePx.y <= 0f) return
        val screenCenter = Point(canvasSizePx.x / 2f, canvasSizePx.y / 2f)
        val documentCenter = screenToDocument(screenCenter)
        viewMatrix = Matrix(
            a = baseScale,
            d = baseScale,
            e = screenCenter.x - documentCenter.x * baseScale,
            f = screenCenter.y - documentCenter.y * baseScale
        )
        viewportVersion++
    }

    /** Centre the selected object(s), or the active artboard if nothing is selected. */
    fun centerSelectionOrArtboard() {
        val target = selectionBounds()?.center ?: document.activePage.size.center
        centerDocumentPoint(target)
    }

    private fun centerDocumentPoint(point: Point) {
        if (canvasSizePx.x <= 0f || canvasSizePx.y <= 0f) return
        val screenCenter = Point(canvasSizePx.x / 2f, canvasSizePx.y / 2f)
        val transformed = viewMatrix.transform(point)
        viewMatrix = viewMatrix.copy(
            e = viewMatrix.e + screenCenter.x - transformed.x,
            f = viewMatrix.f + screenCenter.y - transformed.y
        )
        viewportVersion++
    }

    fun screenToDocument(p: Point): Point = viewMatrix.invert().transform(p)
    fun documentToScreen(p: Point): Point = viewMatrix.transform(p)

    // ================= Document lifecycle =================

    /** Create a new document from New-Document dialog parameters. */
    fun newDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        landscape: Boolean = false,
        bleed: Margins = Margins.ZERO,
        colorMode: ColorMode = ColorMode.RGB,
        displayUnit: Unit = Unit.MM,
        dpi: Float = 96f
    ) {
        val w = if (landscape) maxOf(widthMm, heightMm) else minOf(widthMm, heightMm)
        val h = if (landscape) minOf(widthMm, heightMm) else maxOf(widthMm, heightMm)
        document = Document(
            name = name,
            dpi = dpi,
            colorMode = colorMode,
            displayUnit = displayUnit,
            pages = listOf(Page(name = "Page 1", width = w, height = h, bleed = bleed))
        )
        currentFileUri = null
        afterDocumentReplaced()
    }

    /** Replace document with one loaded from disk. */
    fun loadDocument(doc: Document, fileUri: Uri?) {
        document = doc
        currentFileUri = fileUri
        afterDocumentReplaced()
    }

    fun markSaved(uri: Uri) {
        currentFileUri = uri
    }

    // ================= Artboards / pages =================

    fun setActiveArtboard(index: Int) {
        if (index !in document.pages.indices || index == document.activePageIndex) return
        document = document.copy(activePageIndex = index)
        selectedShapeIds = emptySet()
        documentVersion++
        zoomToFit()
    }

    fun addArtboard(
        name: String = "Artboard ${document.pages.size + 1}",
        width: Float = document.activePage.width,
        height: Float = document.activePage.height
    ) {
        val page = Page(
            name = name.ifBlank { "Artboard ${document.pages.size + 1}" },
            width = width.coerceAtLeast(1f),
            height = height.coerceAtLeast(1f)
        )
        applyEdit("Add Artboard") { doc ->
            doc.copy(pages = doc.pages + page, activePageIndex = doc.pages.size)
        }
        selectedShapeIds = emptySet()
        zoomToFit()
    }

    fun removeActiveArtboard() {
        if (document.pages.size <= 1) return
        val index = document.activePageIndex
        applyEdit("Delete Artboard") { it.removePage(index) }
        selectedShapeIds = emptySet()
        zoomToFit()
    }

    fun updateActiveArtboard(name: String, width: Float, height: Float) {
        val cleanedName = name.trim().ifBlank {
            "Artboard ${document.activePageIndex + 1}"
        }
        applyEdit("Edit Artboard") { doc ->
            doc.updateActivePage {
                it.copy(
                    name = cleanedName,
                    width = width.coerceAtLeast(1f),
                    height = height.coerceAtLeast(1f)
                )
            }
        }
        zoomToFit()
    }

    private fun afterDocumentReplaced() {
        undoManager.clear()
        selectedShapeIds = emptySet()
        documentVersion++
        zoomToFit()
    }

    // ================= Document edits (undoable) =================

    fun applyEdit(description: String, transform: (Document) -> Document) {
        val before = document
        val after = transform(before)
        if (before === after || before == after) return
        undoManager.execute(SnapshotCommand(
            description = description,
            before = before,
            after = after,
            apply = { doc -> setDocumentInternal(doc as Document) }
        ))
        documentVersion++
    }

    fun addShape(shape: Shape) {
        applyEdit("Add ${shape.name}") { it.addShape(shape) }
        selectedShapeIds = setOf(shape.id)
    }

    fun removeSelected() {
        if (selectedShapeIds.isEmpty()) return
        applyEdit("Delete") { doc ->
            selectedShapeIds.fold(doc) { d, id -> d.removeShape(id) }
        }
        selectedShapeIds = emptySet()
    }

    fun updateShape(shapeId: String, description: String, transform: (Shape) -> Shape) {
        val shape = document.findShape(shapeId) ?: return
        applyEdit(description) { doc ->
            doc.replaceShape(shapeId, transform(shape))
        }
    }

    /** Apply an edit to ALL currently selected shapes as one undo step. */
    fun updateSelectedShapes(description: String, transform: (Shape) -> Shape) {
        if (selectedShapeIds.isEmpty()) return
        applyEdit(description) { doc ->
            selectedShapeIds.fold(doc) { d, id ->
                val shape = d.findShape(id)
                if (shape != null) d.replaceShape(id, transform(shape)) else d
            }
        }
    }

    // ================= Layers =================

    fun addLayer() {
        applyEdit("Add Layer") { doc ->
            doc.updateActivePage { page ->
                page.addLayer("Layer ${page.layers.size + 1}")
            }
        }
    }

    fun removeLayer(layerId: String) {
        applyEdit("Remove Layer") { doc ->
            doc.updateActivePage { it.removeLayer(layerId) }
        }
    }

    fun setLayerVisible(layerId: String, visible: Boolean) {
        applyEdit(if (visible) "Show Layer" else "Hide Layer") { doc ->
            doc.updateActivePage { page ->
                page.updateLayer(layerId) { it.copy(visible = visible) }
            }
        }
    }

    fun setLayerLocked(layerId: String, locked: Boolean) {
        applyEdit(if (locked) "Lock Layer" else "Unlock Layer") { doc ->
            doc.updateActivePage { page ->
                page.updateLayer(layerId) { it.copy(locked = locked) }
            }
        }
    }

    fun setShapeVisible(shapeId: String, visible: Boolean) {
        updateShape(shapeId, if (visible) "Show Object" else "Hide Object") {
            it.withVisible(visible)
        }
        if (!visible) selectedShapeIds = selectedShapeIds - shapeId
    }

    fun setShapeLocked(shapeId: String, locked: Boolean) {
        updateShape(shapeId, if (locked) "Lock Object" else "Unlock Object") {
            it.withLocked(locked)
        }
        if (locked) selectedShapeIds = selectedShapeIds - shapeId
    }

    fun renameLayer(layerId: String, name: String) {
        applyEdit("Rename Layer") { doc ->
            doc.updateActivePage { page ->
                page.updateLayer(layerId) { it.copy(name = name) }
            }
        }
    }

    fun setActiveLayer(layerId: String) {
        applyEdit("Set Active Layer") { doc ->
            doc.updateActivePage { it.setActiveLayer(layerId) }
        }
    }

    fun moveShapeInLayer(shapeId: String, direction: Int) {
        applyEdit(if (direction > 0) "Raise" else "Lower") { doc ->
            doc.updateActivePage { page ->
                page.layers.forEach { layer ->
                    val idx = layer.shapes.indexOfFirst { it.id == shapeId }
                    if (idx >= 0) {
                        return@updateActivePage page.updateLayer(layer.id) {
                            it.moveShape(shapeId, idx + direction)
                        }
                    }
                }
                page
            }
        }
    }

    // ================= Groups / PowerClip =================

    private data class LayerSelection(
        val layer: Layer,
        val indexedShapes: List<IndexedValue<Shape>>
    )

    private fun selectedTopLevelShapesInOneLayer(): LayerSelection? {
        if (selectedShapeIds.isEmpty()) return null
        document.activePage.layers.forEach { layer ->
            val indexed = layer.shapes.withIndex()
                .filter { it.value.id in selectedShapeIds }
            if (indexed.size == selectedShapeIds.size) {
                return LayerSelection(layer, indexed)
            }
        }
        return null
    }

    fun canGroup(): Boolean =
        selectedShapeIds.size >= 2 && selectedTopLevelShapesInOneLayer() != null

    fun groupSelected() {
        val selection = selectedTopLevelShapesInOneLayer() ?: return
        if (selection.indexedShapes.size < 2) return
        val ids = selection.indexedShapes.map { it.value.id }.toSet()
        val group = Shape.GroupShape(
            name = "Group",
            children = selection.indexedShapes.sortedBy { it.index }.map { it.value }
        )
        replaceSelectionWithShape(selection.layer, ids, selection.indexedShapes.maxOf { it.index }, group)
    }

    fun canUngroup(): Boolean =
        selectedShapes().singleOrNull() is Shape.GroupShape &&
            (selectedShapes().single() as Shape.GroupShape).clipPath == null

    fun ungroupSelected() {
        val group = selectedShapes().singleOrNull() as? Shape.GroupShape ?: return
        if (group.clipPath != null) return
        replaceGroupWithChildren(group, includeClipFrame = false)
    }

    fun canPowerClip(): Boolean {
        val selection = selectedTopLevelShapesInOneLayer() ?: return false
        if (selection.indexedShapes.size < 2) return false
        return !selection.indexedShapes.maxBy { it.index }.value.path().isEmpty
    }

    /**
     * Uses the topmost selected object as the clipping container and places
     * every other selected object inside it.
     */
    fun createPowerClip() {
        val selection = selectedTopLevelShapesInOneLayer() ?: return
        if (selection.indexedShapes.size < 2) return
        val container = selection.indexedShapes.maxBy { it.index }
        val clipPath = container.value.path()
        if (clipPath.isEmpty) return
        val content = selection.indexedShapes
            .filter { it.value.id != container.value.id }
            .sortedBy { it.index }
            .map { it.value }
        val powerClip = Shape.GroupShape(
            name = "PowerClip",
            children = content,
            clipPath = clipPath,
            fill = Fill.None,
            stroke = container.value.stroke
        )
        val ids = selection.indexedShapes.map { it.value.id }.toSet()
        replaceSelectionWithShape(selection.layer, ids, container.index, powerClip)
    }

    fun canReleasePowerClip(): Boolean =
        (selectedShapes().singleOrNull() as? Shape.GroupShape)?.clipPath != null

    fun releasePowerClip() {
        val group = selectedShapes().singleOrNull() as? Shape.GroupShape ?: return
        if (group.clipPath == null) return
        replaceGroupWithChildren(group, includeClipFrame = true)
    }

    private fun replaceSelectionWithShape(
        layer: Layer,
        removedIds: Set<String>,
        topIndex: Int,
        replacement: Shape
    ) {
        val insertionIndex = layer.shapes
            .take(topIndex + 1)
            .count { it.id !in removedIds }
        val newShapes = layer.shapes.filterNot { it.id in removedIds }
            .toMutableList()
            .apply { add(insertionIndex.coerceIn(0, size), replacement) }
        applyEdit(if (replacement is Shape.GroupShape && replacement.clipPath != null) {
            "Create PowerClip"
        } else {
            "Group"
        }) { doc ->
            doc.updateActivePage { page ->
                page.updateLayer(layer.id) { it.copy(shapes = newShapes) }
            }
        }
        selectedShapeIds = setOf(replacement.id)
    }

    private fun replaceGroupWithChildren(group: Shape.GroupShape, includeClipFrame: Boolean) {
        val page = document.activePage
        val layer = page.layers.firstOrNull { candidate ->
            candidate.shapes.any { it.id == group.id }
        } ?: return
        val groupIndex = layer.shapes.indexOfFirst { it.id == group.id }
        if (groupIndex < 0) return

        val restored = group.children.map { child ->
            child.withTransform(group.transform * child.transform)
                .withOpacity(child.opacity * group.opacity)
                .withBlendMode(
                    if (group.blendMode == com.drawit.core.document.BlendMode.NORMAL) {
                        child.blendMode
                    } else {
                        group.blendMode
                    }
                )
        }.toMutableList()

        if (includeClipFrame) {
            group.clipPath?.let { clip ->
                restored += Shape.PathShape(
                    name = "PowerClip Frame",
                    pathData = clip,
                    transform = group.transform,
                    fill = Fill.None,
                    stroke = group.stroke,
                    opacity = group.opacity,
                    blendMode = group.blendMode,
                    effects = group.effects
                )
            }
        }

        val newShapes = layer.shapes.toMutableList().apply {
            removeAt(groupIndex)
            addAll(groupIndex, restored)
        }
        applyEdit(if (includeClipFrame) "Release PowerClip" else "Ungroup") { doc ->
            doc.updateActivePage { activePage ->
                activePage.updateLayer(layer.id) { it.copy(shapes = newShapes) }
            }
        }
        selectedShapeIds = restored.map { it.id }.toSet()
    }

    // ================= Booleans =================

    /** Selected shapes in z-order (bottom first) from the active page. */
    fun selectedShapesInZOrder(): List<Shape> {
        val page = document.activePage
        return page.layers.flatMap { it.shapes }
            .filter { it.id in selectedShapeIds }
    }

    fun canBoolean(): Boolean =
        selectedShapesInZOrder().count { !it.localPath().isEmpty } >= 2

    fun combineSelected(op: BooleanOps.Op) {
        val shapes = selectedShapesInZOrder().filter { !it.localPath().isEmpty }
        val result = BooleanOps.combine(shapes, op) ?: return
        applyEdit(op.displayName) { doc ->
            var d = doc
            for (s in shapes) d = d.removeShape(s.id)
            d.addShape(result)
        }
        selectedShapeIds = setOf(result.id)
    }

    // ================= Selection =================

    fun select(ids: Set<String>) { selectedShapeIds = ids }
    fun clearSelection() { selectedShapeIds = emptySet() }

    fun undo() { if (undoManager.undo()) documentVersion++ }
    fun redo() { if (undoManager.redo()) documentVersion++ }

    fun selectedShapes(): List<Shape> =
        selectedShapeIds.mapNotNull { document.findShape(it) }

    fun selectionBounds(): Rect? {
        val bounds = selectedShapes().map { it.bounds() }
        return if (bounds.isEmpty()) null else Rect.unionAll(bounds)
    }

    /**
     * Live-update document WITHOUT recording undo (in-progress drags).
     * Tool commits a proper undoable command when the gesture completes.
     */
    fun setDocumentForDrag(doc: Document) {
        document = doc
        documentVersion++
    }

    private fun setDocumentInternal(doc: Document) {
        document = doc
        documentVersion++
    }
}
