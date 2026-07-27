package com.drawit.canvas

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.drawit.core.color.Color as DrawItColor
import com.drawit.core.document.Fill
import com.drawit.core.document.Shape
import com.drawit.core.document.TextShape
import com.drawit.core.geometry.Matrix
import com.drawit.file.DrawItFile
import com.drawit.file.ImageStore
import com.drawit.file.PdfExporter
import com.drawit.file.RasterExporter
import com.drawit.file.SvgImporter
import com.drawit.shapes.BooleanOps
import com.drawit.text.FontManager
import com.drawit.text.TextEngine
import com.drawit.tools.pen.BezierPenTool
import com.drawit.tools.pen.PenTool
import com.drawit.tools.node.NodeEditTool
import com.drawit.tools.select.SelectTool
import com.drawit.tools.shape.ShapeTool
import com.drawit.tools.text.TextTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DockTab { OBJECTS, PROPERTIES, ARTBOARDS }
private data class ColorDialogRequest(
    val title: String,
    val initial: DrawItColor,
    val onConfirm: (DrawItColor) -> Unit
)
private data class OpenedDocument(
    val document: com.drawit.core.document.Document,
    val canSaveToSource: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasScreen() {
    val editorState = remember { EditorState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageStore = remember { ImageStore(context) }
    val fontManager = remember { FontManager(context) }
    val textEngine = remember { TextEngine(fontManager) }

    // UI state
    var showNewDocDialog by remember { mutableStateOf(false) }
    var colorDialogRequest by remember { mutableStateOf<ColorDialogRequest?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var phoneSheet by remember { mutableStateOf<DockTab?>(null) }
    var dockTab by remember { mutableStateOf(DockTab.OBJECTS) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isEditingText by remember { mutableStateOf(false) }

    // ---- File open/save via Storage Access Framework ----

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = queryDisplayName(context, uri) ?: "document"
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        when (DrawItFile.detectType(name)) {
                            DrawItFile.Type.DRAWIT -> OpenedDocument(
                                DrawItFile.read(bytes.inputStream(), imageStore, fontManager),
                                canSaveToSource = true
                            )
                            DrawItFile.Type.SVG -> OpenedDocument(
                                SvgImporter.import(bytes.inputStream(), name),
                                canSaveToSource = false
                            )
                            DrawItFile.Type.UNKNOWN ->
                                runCatching {
                                    OpenedDocument(
                                        DrawItFile.read(bytes.inputStream(), imageStore, fontManager),
                                        canSaveToSource = true
                                    )
                                }.recoverCatching {
                                    OpenedDocument(
                                        SvgImporter.import(bytes.inputStream(), name),
                                        canSaveToSource = false
                                    )
                                }.getOrElse {
                                    throw IllegalArgumentException("Unsupported file type: $name", it)
                                }
                        }
                    } ?: throw IllegalArgumentException("Cannot open file")
                }
            }.onSuccess { opened ->
                editorState.loadDocument(
                    opened.document,
                    if (opened.canSaveToSource) uri else null
                )
                statusMessage = "Opened: ${opened.document.name}"
            }.onFailure { e ->
                statusMessage = "Open failed: ${e.message}"
            }
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DrawItFile.MIME_TYPE)
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        DrawItFile.write(editorState.document, out, imageStore, fontManager)
                    } ?: throw IllegalArgumentException("Cannot write file")
                }
            }.onSuccess {
                editorState.markSaved(uri)
                statusMessage = "Saved"
            }.onFailure { e ->
                statusMessage = "Save failed: ${e.message}"
            }
        }
    }

    val pdfExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        PdfExporter.write(
                            editorState.document,
                            output,
                            imageStore,
                            fontManager
                        )
                    } ?: throw IllegalStateException("Cannot write PDF")
                }
            }.onSuccess {
                statusMessage = "All artboards exported to print PDF"
            }.onFailure {
                statusMessage = "PDF export failed: ${it.message}"
            }
        }
    }

    val activePdfExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        PdfExporter.write(
                            document = editorState.document,
                            output = output,
                            imageStore = imageStore,
                            fontManager = fontManager,
                            pageIndices = listOf(editorState.document.activePageIndex)
                        )
                    } ?: throw IllegalStateException("Cannot write PDF")
                }
            }.onSuccess {
                statusMessage = "Active artboard exported to print PDF"
            }.onFailure {
                statusMessage = "PDF export failed: ${it.message}"
            }
        }
    }

    val pngExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        RasterExporter.write(
                            document = editorState.document,
                            output = output,
                            imageStore = imageStore,
                            fontManager = fontManager,
                            format = RasterExporter.Format.PNG
                        )
                    } ?: throw IllegalStateException("Cannot write PNG")
                }
            }.onSuccess { result ->
                statusMessage =
                    "PNG exported: ${result.widthPx}×${result.heightPx}, " +
                        "${result.effectiveDpi.toInt()} DPI"
            }.onFailure {
                statusMessage = "PNG export failed: ${it.message}"
            }
        }
    }

    val jpgExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        RasterExporter.write(
                            document = editorState.document,
                            output = output,
                            imageStore = imageStore,
                            fontManager = fontManager,
                            format = RasterExporter.Format.JPEG,
                            jpegQuality = 95
                        )
                    } ?: throw IllegalStateException("Cannot write JPG")
                }
            }.onSuccess { result ->
                statusMessage =
                    "JPG exported: ${result.widthPx}×${result.heightPx}, " +
                        "${result.effectiveDpi.toInt()} DPI"
            }.onFailure {
                statusMessage = "JPG export failed: ${it.message}"
            }
        }
    }

    // Image import
    val imageOpenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val id = imageStore.importImage(input)
                        val name = queryDisplayName(context, uri) ?: "Image"
                        val bmp = imageStore.get(id)
                        val wMm = (bmp?.width?.toFloat() ?: 100f) * 25.4f / 96f
                        val hMm = (bmp?.height?.toFloat() ?: 100f) * 25.4f / 96f
                        val cx = editorState.screenToDocument(com.drawit.core.geometry.Point(editorState.canvasSizePx.x/2,editorState.canvasSizePx.y/2))
                        com.drawit.core.document.ImageShape(name=name.removeSuffix(".png").removeSuffix(".jpg"),imageId=id,
                            rect=com.drawit.core.geometry.Rect(cx.x-wMm/2,cx.y-hMm/2,cx.x+wMm/2,cx.y+hMm/2))
                    } ?: throw IllegalStateException("Cannot open image")
                }
            }.onSuccess { editorState.addShape(it) }.onFailure { statusMessage = "Import: ${it.message}" }
        }
    }

    val svgImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = queryDisplayName(context, uri) ?: "Imported SVG"
                    val imported = context.contentResolver.openInputStream(uri)?.use { input ->
                        SvgImporter.import(input, name)
                    } ?: throw IllegalStateException("Cannot open SVG")
                    name to imported
                }
            }.onSuccess { (name, imported) ->
                val children = imported.activePage.layers
                    .filter { it.visible }
                    .flatMap { it.shapes }
                    .filter { it.visible }
                if (children.isEmpty()) {
                    statusMessage = "SVG contains no supported objects"
                } else {
                    val bounds = com.drawit.core.geometry.Rect.unionAll(
                        children.map { it.bounds() }
                    )
                    val page = editorState.document.activePage
                    val viewportCenter = editorState.screenToDocument(
                        com.drawit.core.geometry.Point(
                            editorState.canvasSizePx.x / 2f,
                            editorState.canvasSizePx.y / 2f
                        )
                    )
                    val target = if (
                        viewportCenter.x in 0f..page.width &&
                        viewportCenter.y in 0f..page.height
                    ) {
                        viewportCenter
                    } else {
                        com.drawit.core.geometry.Point(page.width / 2f, page.height / 2f)
                    }
                    editorState.addShape(
                        Shape.GroupShape(
                            name = name.substringBeforeLast('.').ifBlank { "Imported SVG" },
                            children = children,
                            transform = Matrix.translate(
                                target.x - bounds.centerX,
                                target.y - bounds.centerY
                            )
                        )
                    )
                    statusMessage = "SVG imported: ${children.size} object(s)"
                }
            }.onFailure {
                statusMessage = "SVG import failed: ${it.message}"
            }
        }
    }

    val patternImageOpenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        imageStore.importImage(input)
                    } ?: throw IllegalStateException("Cannot open image")
                }
            }.onSuccess { imageId ->
                editorState.updateSelectedShapes("Pattern Image") { shape ->
                    val pattern = shape.fill as? Fill.Pattern ?: Fill.Pattern(imageId = imageId)
                    shape.withFill(pattern.copy(imageId = imageId))
                }
                statusMessage = "Pattern image selected"
            }.onFailure {
                statusMessage = "Pattern import: ${it.message}"
            }
        }
    }

    // Font import
    val fontOpenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val name = queryDisplayName(context, uri) ?: "font"
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        fontManager.importFont(it, name)
                    } ?: throw IllegalStateException("Cannot open font")
                }
            }.onSuccess { statusMessage = "Font imported" }.onFailure { statusMessage = "Font: ${it.message}" }
        }
    }

    fun save() {
        val uri = editorState.currentFileUri
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            DrawItFile.write(editorState.document, out, imageStore, fontManager)
                        } ?: throw IllegalStateException("Cannot write file")
                    }
                }.onSuccess { statusMessage = "Saved" }
                    .onFailure { statusMessage = "Save failed: ${it.message}" }
            }
        } else {
            saveAsLauncher.launch("${editorState.document.name}.drawit")
        }
    }

    // ---- Color dialog wiring ----

    colorDialogRequest?.let { request ->
        ColorDialog(
            initial = request.initial,
            title = request.title,
            onDismiss = { colorDialogRequest = null },
            onConfirm = { color ->
                request.onConfirm(color)
                colorDialogRequest = null
            }
        )
    }

    // ---- New document dialog ----

    if (showNewDocDialog) {
        NewDocumentDialog(
            onDismiss = { showNewDocDialog = false },
            onCreate = { name, w, h, landscape, bleed, mode, unit, dpi ->
                editorState.newDocument(name, w, h, landscape, bleed, mode, unit, dpi)
                showNewDocDialog = false
            }
        )
    }

    // ---- Layout: adaptive dock (wide) vs bottom sheets (narrow) ----

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 700.dp

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(editorState.document.name, maxLines = 1)
                },
                navigationIcon = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.Menu, "File menu")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("New…") }, onClick = {
                                showMenu = false; showNewDocDialog = true
                            })
                            DropdownMenuItem(text = { Text("Open…") }, onClick = {
                                showMenu = false
                                openLauncher.launch(arrayOf("*/*"))
                            })
                            DropdownMenuItem(text = { Text("Save") }, onClick = {
                                showMenu = false; save()
                            })
                            DropdownMenuItem(text = { Text("Save As…") }, onClick = {
                                showMenu = false
                                saveAsLauncher.launch("${editorState.document.name}.drawit")
                            })
                            DropdownMenuItem(text = { Text("Export All Artboards PDF…") }, onClick = {
                                showMenu = false
                                pdfExportLauncher.launch("${editorState.document.name}.pdf")
                            })
                            DropdownMenuItem(text = { Text("Export Active Artboard PDF…") }, onClick = {
                                showMenu = false
                                val page = editorState.document.activePage
                                activePdfExportLauncher.launch(
                                    "${editorState.document.name}-${safeFileName(page.name)}.pdf"
                                )
                            })
                            DropdownMenuItem(text = { Text("Export Active Artboard PNG…") }, onClick = {
                                showMenu = false
                                val page = editorState.document.activePage
                                pngExportLauncher.launch(
                                    "${editorState.document.name}-${safeFileName(page.name)}.png"
                                )
                            })
                            DropdownMenuItem(text = { Text("Export Active Artboard JPG…") }, onClick = {
                                showMenu = false
                                val page = editorState.document.activePage
                                jpgExportLauncher.launch(
                                    "${editorState.document.name}-${safeFileName(page.name)}.jpg"
                                )
                            })
                            DropdownMenuItem(text = { Text("Import SVG…") }, onClick = {
                                showMenu = false
                                svgImportLauncher.launch(
                                    arrayOf("image/svg+xml", "application/xml", "text/xml")
                                )
                            })
                            DropdownMenuItem(text = { Text("Import Image…") }, onClick = {
                                showMenu = false
                                imageOpenLauncher.launch(arrayOf("image/*"))
                            })
                            DropdownMenuItem(text = { Text("Import Font…") }, onClick = {
                                showMenu = false
                                fontOpenLauncher.launch(arrayOf("*/*"))
                            })
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (editorState.smartAlignmentsEnabled) {
                                            "Smart Alignments: On"
                                        } else {
                                            "Smart Alignments: Off"
                                        }
                                    )
                                },
                                onClick = {
                                    editorState.smartAlignmentsEnabled =
                                        !editorState.smartAlignmentsEnabled
                                    statusMessage = if (editorState.smartAlignmentsEnabled) {
                                        "Smart Alignments enabled"
                                    } else {
                                        "Smart Alignments disabled"
                                    }
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { editorState.undo() }, enabled = editorState.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                    }
                    IconButton(onClick = { editorState.redo() }, enabled = editorState.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                    }
                    if (!isWide) {
                        IconButton(onClick = {
                            phoneSheet = if (phoneSheet == DockTab.OBJECTS) null else DockTab.OBJECTS
                        }) {
                            Icon(Icons.Default.Layers, "Objects")
                        }
                        IconButton(onClick = {
                            phoneSheet = if (phoneSheet == DockTab.PROPERTIES) null else DockTab.PROPERTIES
                        }) {
                            Icon(Icons.Default.Tune, "Properties")
                        }
                        IconButton(onClick = {
                            phoneSheet = if (phoneSheet == DockTab.ARTBOARDS) null else DockTab.ARTBOARDS
                        }) {
                            Icon(Icons.Default.CropSquare, "Artboards")
                        }
                    }
                }
            )

            Row(modifier = Modifier.fillMaxSize()) {
                // Canvas area
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AndroidView(
                        factory = { ctx ->
                            CanvasView(ctx).apply {
                                this.editorState = editorState
                                this.imageStore = imageStore
                                this.fontManager = fontManager
                                activeTool = SelectTool(editorState)
                            }
                        },
                        update = { view ->
                            view.editorState = editorState
                            view.imageStore = imageStore
                            view.fontManager = fontManager
                            editorState.documentVersion.let { view.invalidate() }
                            editorState.viewportVersion.let { view.invalidate() }
                            // Keyboard for text editing
                            if (isEditingText) view.showKeyboard() else view.hideKeyboard()
                            editorState.pendingToolForCanvas?.let { tool ->
                                editorState.pendingToolForCanvas = null
                                view.activeTool = tool
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Context actions for grouping, PowerClip and boolean ops.
                    if (editorState.canGroup() ||
                        editorState.canUngroup() ||
                        editorState.canPowerClip() ||
                        editorState.canReleasePowerClip() ||
                        editorState.canBoolean()) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                    shape = MaterialTheme.shapes.medium
                                )
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (editorState.canGroup()) {
                                androidx.compose.material3.TextButton(
                                    onClick = { editorState.groupSelected() }
                                ) { Text("Group") }
                            }
                            if (editorState.canUngroup()) {
                                androidx.compose.material3.TextButton(
                                    onClick = { editorState.ungroupSelected() }
                                ) { Text("Ungroup") }
                            }
                            if (editorState.canPowerClip()) {
                                androidx.compose.material3.TextButton(
                                    onClick = { editorState.createPowerClip() }
                                ) { Text("PowerClip") }
                            }
                            if (editorState.canReleasePowerClip()) {
                                androidx.compose.material3.TextButton(
                                    onClick = { editorState.releasePowerClip() }
                                ) { Text("Release Clip") }
                            }
                            if (editorState.canBoolean()) {
                                BooleanOps.Op.entries.forEach { op ->
                                    androidx.compose.material3.TextButton(
                                        onClick = { editorState.combineSelected(op) }
                                    ) {
                                        Text(
                                            op.displayName,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Status toast
                    statusMessage?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 48.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        androidx.compose.runtime.LaunchedEffect(msg) {
                            kotlinx.coroutines.delay(2500)
                            statusMessage = null
                        }
                    }

                    // Bottom tool bar
                    ToolBar(
                        editorState = editorState,
                        textEngine = textEngine,
                        onEditingTextChanged = { isEditingText = it },
                        onStatusMessage = { statusMessage = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }

                // Side dock (wide screens)
                if (isWide) {
                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        TabRow(selectedTabIndex = dockTab.ordinal) {
                            Tab(
                                selected = dockTab == DockTab.OBJECTS,
                                onClick = { dockTab = DockTab.OBJECTS },
                                text = { Text("Objects") }
                            )
                            Tab(
                                selected = dockTab == DockTab.PROPERTIES,
                                onClick = { dockTab = DockTab.PROPERTIES },
                                text = { Text("Properties") }
                            )
                            Tab(
                                selected = dockTab == DockTab.ARTBOARDS,
                                onClick = { dockTab = DockTab.ARTBOARDS },
                                text = { Text("Artboards") }
                            )
                        }
                        when (dockTab) {
                            DockTab.OBJECTS -> ObjectManagerPanel(editorState)
                            DockTab.PROPERTIES -> PropertiesPanel(
                                editorState = editorState,
                                fontManager = fontManager,
                                textEngine = textEngine,
                                onPickColor = { title, initial, onSelected ->
                                    colorDialogRequest = ColorDialogRequest(title, initial, onSelected)
                                },
                                onPickPatternImage = {
                                    patternImageOpenLauncher.launch(arrayOf("image/*"))
                                }
                            )
                            DockTab.ARTBOARDS -> ArtboardsPanel(editorState)
                        }
                    }
                }
            }
        }

        // Bottom sheets (narrow screens)
        phoneSheet?.let { tab ->
            ModalBottomSheet(onDismissRequest = { phoneSheet = null }) {
                when (tab) {
                    DockTab.OBJECTS -> ObjectManagerPanel(editorState)
                    DockTab.PROPERTIES -> PropertiesPanel(
                        editorState = editorState,
                        fontManager = fontManager,
                        textEngine = textEngine,
                        onPickColor = { title, initial, onSelected ->
                            colorDialogRequest = ColorDialogRequest(title, initial, onSelected)
                        },
                        onPickPatternImage = {
                            patternImageOpenLauncher.launch(arrayOf("image/*"))
                        }
                    )
                    DockTab.ARTBOARDS -> ArtboardsPanel(editorState)
                }
            }
        }
    }
}

@Composable
private fun ToolBar(
    editorState: EditorState,
    textEngine: TextEngine,
    onEditingTextChanged: (Boolean) -> Unit,
    onStatusMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val toolHolder = remember { ToolHolder() }

    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.large
            )
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ToolButton(icon = { Icon(Icons.Default.NearMe, "Select") },
            selected = toolHolder.activeToolId == "select", contentDescription = "Select") {
            toolHolder.activeToolId = "select"
            toolHolder.pendingTool = SelectTool(editorState)
        }
        ToolButton(icon = { Icon(Icons.Default.Create, "Node Editor") },
            selected = toolHolder.activeToolId == "node", contentDescription = "Node Editor") {
            onStatusMessage(
                if (editorState.selectedShapes().size == 1) {
                    "Node Editor converts the selected object to curves. Drag nodes; Delete removes one; Undo restores it."
                } else {
                    "Node Editor: select exactly one object first."
                }
            )
            toolHolder.activeToolId = "node"
            toolHolder.pendingTool = NodeEditTool(editorState, textEngine)
        }
        ToolButton(icon = { Icon(Icons.Default.Brush, "Freehand") },
            selected = toolHolder.activeToolId == "pen", contentDescription = "Freehand Pen") {
            toolHolder.activeToolId = "pen"
            toolHolder.pendingTool = PenTool(editorState)
        }
        ToolButton(icon = { Icon(Icons.Default.Polyline, "Bezier") },
            selected = toolHolder.activeToolId == "bezier", contentDescription = "Bezier Pen") {
            toolHolder.activeToolId = "bezier"
            toolHolder.pendingTool = BezierPenTool(editorState)
        }
        ToolButton(icon = { Icon(Icons.Default.CropSquare, "Rectangle") },
            selected = toolHolder.activeToolId == "rect", contentDescription = "Rectangle") {
            toolHolder.activeToolId = "rect"
            toolHolder.pendingTool = ShapeTool(editorState, ShapeTool.Mode.RECTANGLE)
        }
        ToolButton(icon = { Icon(Icons.Default.Circle, "Ellipse") },
            selected = toolHolder.activeToolId == "ellipse", contentDescription = "Ellipse") {
            toolHolder.activeToolId = "ellipse"
            toolHolder.pendingTool = ShapeTool(editorState, ShapeTool.Mode.ELLIPSE)
        }
        ToolButton(icon = { Icon(Icons.Default.ChangeHistory, "Polygon") },
            selected = toolHolder.activeToolId == "polygon", contentDescription = "Polygon") {
            toolHolder.activeToolId = "polygon"
            toolHolder.pendingTool = ShapeTool(editorState, ShapeTool.Mode.POLYGON)
        }
        ToolButton(icon = { Icon(Icons.Default.TextFields, "Text") },
            selected = toolHolder.activeToolId == "text", contentDescription = "Text") {
            toolHolder.activeToolId = "text"
            toolHolder.pendingTool = TextTool(editorState, textEngine, TextShape.Kind.ARTISTIC).also {
                it.onEditingChanged = { onEditingTextChanged(it) }
            }
        }
        ToolButton(icon = { Icon(Icons.Default.Article, "Paragraph") },
            selected = toolHolder.activeToolId == "paragraph", contentDescription = "Paragraph") {
            toolHolder.activeToolId = "paragraph"
            toolHolder.pendingTool = TextTool(editorState, textEngine, TextShape.Kind.PARAGRAPH).also {
                it.onEditingChanged = { onEditingTextChanged(it) }
            }
        }

        FilledIconButton(
            onClick = { editorState.removeSelected() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.error
            )
        ) { Icon(Icons.Default.Delete, "Delete") }

        FilledIconButton(
            onClick = { editorState.zoomToFit() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Icon(Icons.Default.ZoomInMap, "Zoom to Fit") }

        androidx.compose.material3.TextButton(
            onClick = { editorState.zoomTo100Percent() }
        ) {
            Text("100%")
        }
        androidx.compose.material3.TextButton(
            onClick = { editorState.centerSelectionOrArtboard() }
        ) {
            Text("Center")
        }
    }

    toolHolder.pendingTool?.let { tool ->
        toolHolder.pendingTool = null
        editorState.pendingToolForCanvas = tool
    }
}

@Composable
private fun ToolButton(
    icon: @Composable () -> Unit,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    FilledIconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) { icon() }
}

private class ToolHolder {
    var activeToolId by mutableStateOf("select")
    var pendingTool: com.drawit.core.input.Tool? = null
}

private fun safeFileName(value: String): String =
    value.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "Artboard" }

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
