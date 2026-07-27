package com.drawit.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drawit.core.color.Color
import com.drawit.core.document.BlendMode
import com.drawit.core.document.CornerStyle
import com.drawit.core.document.Fill
import com.drawit.core.document.GradientStop
import com.drawit.core.document.ShadowEffect
import com.drawit.core.document.Shape
import com.drawit.core.document.Stroke
import com.drawit.core.document.TextShape
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.Rect
import com.drawit.text.FontManager
import com.drawit.text.TextEngine
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shape properties editor with:
 *  - Fill type selector (None/Solid/Gradient Linear/Gradient Radial/Pattern)
 *  - Gradient stop editor (multi-stop, angle)
 *  - Pattern placement/tile scale
 *  - Stroke editor
 *  - Opacity + blend
 *  - Geometry (X/Y/W/H)
 *  - Rotation + pivot
 *  - Visibility/lock
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesPanel(
    editorState: EditorState,
    fontManager: FontManager,
    textEngine: TextEngine,
    onPickColor: (title: String, initial: Color, onSelected: (Color) -> Unit) -> Unit,
    onPickPatternImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = editorState.selectedShapes()
    val unit = editorState.document.displayUnit

    if (selected.isEmpty()) {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("No selection", style = MaterialTheme.typography.titleSmall)
            Text("Tap a shape with the Select tool to edit properties.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            EditorControls(editorState)
        }
        return
    }

    val first = selected.first()
    val bounds = editorState.selectionBounds()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (selected.size == 1) first.name else "${selected.size} objects",
            style = MaterialTheme.typography.titleSmall)

        // ---- Fill ----
        SectionLabel("Fill")

        val fillTypes = listOf("None" to Fill.None, "Solid" to Fill.Solid(Color.GRAY),
            "Linear Gradient" to Fill.Gradient.twoStop(Fill.Gradient.Type.LINEAR, Color.WHITE, Color.BLACK),
            "Radial Gradient" to Fill.Gradient.twoStop(Fill.Gradient.Type.RADIAL, Color.WHITE, Color.BLACK),
            "Pattern" to Fill.Pattern(imageId = "", placement = Fill.Pattern.Placement.TILE))

        val currentFillLabel = fillTypeLabel(first.fill)
        EnumDropdown(label = "Type", options = fillTypes.map { it.first }, selected = currentFillLabel,
            onSelect = { label ->
                val pair = fillTypes.first { it.first == label }
                if (label == "None") editorState.updateSelectedShapes("Fill None") { it.withFill(Fill.None) }
                else {
                    val newFill = when (val f = pair.second) {
                        is Fill.Gradient -> f
                        is Fill.Pattern -> f
                        is Fill.Solid -> {
                            val current = first.fill
                            if (current is Fill.Solid) current else f
                        }
                        else -> Fill.Solid(Color(0, 120, 215))
                    }
                    editorState.updateSelectedShapes("Fill Type") { it.withFill(newFill) }
                }
            })

        when (val fill = first.fill) {
            is Fill.Solid -> {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                     Box(Modifier.size(32.dp).background(
                         androidx.compose.ui.graphics.Color(fill.color.toArgb()))
                         .border(1.dp, MaterialTheme.colorScheme.outline)
                        .clickable {
                            onPickColor("Fill Color", fill.color) { color ->
                                editorState.updateSelectedShapes("Fill Color") {
                                    it.withFill(Fill.Solid(color))
                                }
                            }
                        }
                    )
                    Text(fill.color.toHexString(), style = MaterialTheme.typography.labelMedium)
                }
            }
            is Fill.Gradient -> GradientEditor(
                fill = fill,
                onUpdate = { g ->
                    editorState.updateSelectedShapes("Gradient") { it.withFill(g) }
                },
                onPickColor = { initial, onSelected ->
                    onPickColor("Gradient Stop Color", initial, onSelected)
                }
            )
            is Fill.Pattern -> PatternEditor(
                fill = fill,
                onUpdate = { p ->
                    editorState.updateSelectedShapes("Pattern") { it.withFill(p) }
                },
                onPickImage = onPickPatternImage
            )
            Fill.None -> {}
        }

        // ---- Stroke ----
        SectionLabel("Stroke")
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val strokeColor = first.stroke?.color
            Box(Modifier.size(32.dp).background(
                 strokeColor?.let { androidx.compose.ui.graphics.Color(it.toArgb()) }
                     ?: androidx.compose.ui.graphics.Color.Transparent)
                 .border(1.dp, MaterialTheme.colorScheme.outline)
                .clickable {
                    val initial = strokeColor ?: Color.BLACK
                    onPickColor("Stroke Color", initial) { color ->
                        editorState.updateSelectedShapes("Stroke Color") {
                            it.withStroke((it.stroke ?: Stroke()).copy(color = color))
                        }
                    }
                }
            )
            TextButton(onClick = {
                editorState.updateSelectedShapes("No Stroke") { it.withStroke(null) }
            }) { Text("None", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                editorState.updateSelectedShapes("Add Stroke") {
                    if (it.stroke == null) it.withStroke(Stroke()) else it
                }
            }) { Text("Add", style = MaterialTheme.typography.labelSmall) }
        }

        first.stroke?.let {
            NumericField(label = "Width (${unit.shortName})",
                value = unit.fromMm(it.width),
                onValue = { v ->
                    val mm = unit.toMm(v).coerceAtLeast(0.01f)
                    editorState.updateSelectedShapes("Stroke Width") { s ->
                        s.withStroke((s.stroke ?: Stroke()).copy(width = mm))
                    }
                })

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EnumDropdown(label = "Cap", options = Stroke.Cap.entries.map { it.name }, selected = it.cap.name,
                    onSelect = { n -> editorState.updateSelectedShapes("Cap") { s ->
                        s.withStroke((s.stroke ?: Stroke()).copy(cap = Stroke.Cap.valueOf(n))) } },
                    modifier = Modifier.weight(1f))
                EnumDropdown(label = "Join", options = Stroke.Join.entries.map { it.name }, selected = it.join.name,
                    onSelect = { n -> editorState.updateSelectedShapes("Join") { s ->
                        s.withStroke((s.stroke ?: Stroke()).copy(join = Stroke.Join.valueOf(n))) } },
                    modifier = Modifier.weight(1f))
            }
            EnumDropdown(label = "Dash", options = Stroke.DASH_PRESETS.keys.toList(),
                selected = Stroke.DASH_PRESETS.entries.firstOrNull { (_, v) -> v == it.dashPattern }?.key ?: "Solid",
                onSelect = { name ->
                    val p = Stroke.DASH_PRESETS[name] ?: emptyList()
                    editorState.updateSelectedShapes("Dash") { s ->
                        s.withStroke((s.stroke ?: Stroke()).copy(dashPattern = p)) }
                })
        }

        if (first is TextShape) {
            SectionLabel("Text")
            val fonts = fontManager.availableFonts()
            val selectedFont = fonts.firstOrNull { it.key == first.fontFamily }
            EnumDropdown(
                label = "Font",
                options = fonts.map { it.displayName },
                selected = selectedFont?.displayName ?: first.fontFamily,
                onSelect = { displayName ->
                    val key = fonts.firstOrNull { it.displayName == displayName }?.key
                    if (key != null) {
                        editorState.updateSelectedShapes("Font") { shape ->
                            if (shape is TextShape) {
                                textEngine.measure(shape.copy(fontFamily = key))
                            } else {
                                shape
                            }
                        }
                    }
                }
            )
            EnumDropdown(
                label = "Weight",
                options = TextShape.Weight.entries.map { it.displayName },
                selected = first.fontWeight.displayName,
                onSelect = { displayName ->
                    val weight = TextShape.Weight.entries.first { it.displayName == displayName }
                    editorState.updateSelectedShapes("Font Weight") { shape ->
                        if (shape is TextShape) {
                            textEngine.measure(shape.copy(fontWeight = weight))
                        } else {
                            shape
                        }
                    }
                }
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Italic", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = first.italic,
                    onCheckedChange = { italic ->
                        editorState.updateSelectedShapes("Italic") { shape ->
                            if (shape is TextShape) {
                                textEngine.measure(shape.copy(italic = italic))
                            } else {
                                shape
                            }
                        }
                    }
                )
            }
            NumericField(
                label = "Size (${unit.shortName})",
                value = unit.fromMm(first.textSize),
                onValue = { value ->
                    val sizeMm = unit.toMm(value).coerceAtLeast(0.5f)
                    editorState.updateSelectedShapes("Text Size") { shape ->
                        if (shape is TextShape) {
                            textEngine.measure(shape.copy(textSize = sizeMm))
                        } else {
                            shape
                        }
                    }
                }
            )
            EnumDropdown(
                label = "Alignment",
                options = TextShape.Align.entries.map { it.displayName },
                selected = first.align.displayName,
                onSelect = { displayName ->
                    val align = TextShape.Align.entries.first { it.displayName == displayName }
                    editorState.updateSelectedShapes("Text Alignment") { shape ->
                        if (shape is TextShape) {
                            textEngine.measure(shape.copy(align = align))
                        } else {
                            shape
                        }
                    }
                }
            )
            if (first.kind == TextShape.Kind.PARAGRAPH) {
                NumericField(
                    label = "Frame Width (${unit.shortName})",
                    value = unit.fromMm(first.frameWidth),
                    onValue = { value ->
                        val widthMm = unit.toMm(value).coerceAtLeast(1f)
                        editorState.updateSelectedShapes("Text Frame Width") { shape ->
                            if (shape is TextShape) {
                                textEngine.measure(shape.copy(frameWidth = widthMm))
                            } else {
                                shape
                            }
                        }
                    }
                )
            }
        }

        if (first is Shape.PolygonShape) {
            SectionLabel("Polygon")
            NumericField(
                label = "Sides",
                value = first.sides.toFloat(),
                onValue = { value ->
                    editorState.updateSelectedShapes("Polygon Sides") { shape ->
                        if (shape is Shape.PolygonShape) {
                            shape.copy(sides = value.roundToInt().coerceIn(3, 64))
                        } else {
                            shape
                        }
                    }
                }
            )
            NumericField(
                label = "Vertex rotation (°)",
                value = first.rotationDegrees,
                onValue = { value ->
                    editorState.updateSelectedShapes("Polygon Rotation") { shape ->
                        if (shape is Shape.PolygonShape) {
                            shape.copy(rotationDegrees = value)
                        } else {
                            shape
                        }
                    }
                }
            )
        }

        if (first is Shape.EllipseShape) {
            SectionLabel("Ellipse / Arc")
            NumericField(
                label = "Start (°)",
                value = first.startAngleDegrees,
                onValue = { value ->
                    editorState.updateSelectedShapes("Arc Start") { shape ->
                        if (shape is Shape.EllipseShape) {
                            shape.copy(startAngleDegrees = normalizeDegrees(value))
                        } else {
                            shape
                        }
                    }
                }
            )
            NumericField(
                label = "Sweep (°)",
                value = first.sweepDegrees,
                onValue = { value ->
                    editorState.updateSelectedShapes("Arc Sweep") { shape ->
                        if (shape is Shape.EllipseShape) {
                            shape.copy(sweepDegrees = value.coerceIn(0.1f, 360f))
                        } else {
                            shape
                        }
                    }
                }
            )
            Text(
                "Arc ratio ${(first.arcRatio * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall
            )
            Slider(
                value = first.arcRatio.coerceIn(0f, 0.95f),
                valueRange = 0f..0.95f,
                onValueChange = { ratio ->
                    editorState.updateSelectedShapes("Arc Ratio") { shape ->
                        if (shape is Shape.EllipseShape) {
                            shape.copy(arcRatio = ratio.coerceIn(0f, 0.95f))
                        } else {
                            shape
                        }
                    }
                }
            )
            Text(
                "Blue S/E/R handles on the canvas edit Start, End/Sweep and inner Ratio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (first is Shape.RectShape) {
            SectionLabel("Corners")
            EnumDropdown(
                label = "Treatment",
                options = CornerStyle.entries.map { it.displayName },
                selected = first.cornerStyle.displayName,
                onSelect = { displayName ->
                    val style = CornerStyle.entries.first { it.displayName == displayName }
                    editorState.updateSelectedShapes("Corner Treatment") { shape ->
                        if (shape is Shape.RectShape) {
                            shape.copy(cornerStyle = style)
                        } else {
                            shape
                        }
                    }
                }
            )
            NumericField(
                label = "Radius (${unit.shortName})",
                value = unit.fromMm(first.cornerRadius),
                onValue = { value ->
                    val radius = unit.toMm(value).coerceAtLeast(0f)
                    editorState.updateSelectedShapes("Corner Radius") { shape ->
                        if (shape is Shape.RectShape) {
                            shape.copy(cornerRadius = radius)
                        } else {
                            shape
                        }
                    }
                }
            )
        }

        EffectsEditor(
            editorState = editorState,
            first = first,
            unit = unit,
            onPickColor = onPickColor
        )

        // ---- Opacity + blend ----
        SectionLabel("Blend")
        Text("${(first.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
        Slider(value = first.opacity,
            onValueChange = { v -> editorState.updateSelectedShapes("Opacity") { it.withOpacity(v) } })

        EnumDropdown(label = "Blend Mode",
            options = BlendMode.entries.map { it.displayName }, selected = first.blendMode.displayName,
            onSelect = { n -> editorState.updateSelectedShapes("Blend") {
                it.withBlendMode(BlendMode.entries.first { m -> m.displayName == n }) } })

        // ---- Geometry + Rotation ----
        if (bounds != null) {
            SectionLabel("Geometry (${unit.shortName})")
            Text(
                "Blue corner handles resize in the object's local axes. " +
                    "The round handle rotates; orange diamond handles skew.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NumericField("X", unit.fromMm(bounds.left), { move(editorState, xMm = unit.toMm(it)) }, Modifier.weight(1f))
                NumericField("Y", unit.fromMm(bounds.top), { move(editorState, yMm = unit.toMm(it)) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NumericField("W", unit.fromMm(bounds.width), { scale(editorState, wMm = unit.toMm(it)) }, Modifier.weight(1f))
                NumericField("H", unit.fromMm(bounds.height), { scale(editorState, hMm = unit.toMm(it)) }, Modifier.weight(1f))
            }

            // Rotation
            SectionLabel("Rotation (°)")
            val rotDeg = rotationDegrees(first.transform)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                var pivotName by remember(editorState.selectedShapeIds) { mutableStateOf("Center") }
                val pivot = pivotFor(bounds, pivotName)
                NumericField(
                    label = "Angle",
                    value = rotDeg,
                    onValue = { setRotate(editorState, it, pivot) },
                    modifier = Modifier.weight(1f)
                )
                val pivots = listOf("Center", "TL", "TR", "BL", "BR")
                EnumDropdown(label = "Pivot", options = pivots, selected = pivotName,
                    onSelect = { pivotName = it }, modifier = Modifier.weight(1f))
            }
        }

        // ---- Visibility / Lock ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text("Visible", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = first.visible, onCheckedChange = { v ->
                editorState.updateSelectedShapes("Visibility") { it.withVisible(v) }
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text("Locked", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = first.locked, onCheckedChange = { v ->
                editorState.updateSelectedShapes("Lock") { it.withLocked(v) }
            })
        }

        EditorControls(editorState)
    }
}

@Composable
private fun EditorControls(editorState: EditorState) {
    SectionLabel("Editor controls")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Smart Alignments", style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = editorState.smartAlignmentsEnabled,
            onCheckedChange = { editorState.smartAlignmentsEnabled = it }
        )
    }
    Text(
        "Handle size ${editorState.controlHandleSizePx.roundToInt()} px",
        style = MaterialTheme.typography.labelSmall
    )
    Slider(
        value = editorState.controlHandleSizePx.coerceIn(3f, 14f),
        valueRange = 3f..14f,
        steps = 10,
        onValueChange = { editorState.controlHandleSizePx = it.coerceIn(3f, 14f) }
    )
}

@Composable
private fun EffectsEditor(
    editorState: EditorState,
    first: Shape,
    unit: com.drawit.core.document.Unit,
    onPickColor: (title: String, initial: Color, onSelected: (Color) -> Unit) -> Unit
) {
    SectionLabel("Effects")

    EffectToggle(
        label = "Drop shadow",
        checked = first.effects.dropShadow != null,
        onChecked = { enabled ->
            editorState.updateSelectedShapes("Drop Shadow") { shape ->
                shape.withEffects(
                    shape.effects.copy(
                        dropShadow = if (enabled) {
                            shape.effects.dropShadow ?: ShadowEffect()
                        } else {
                            null
                        }
                    )
                )
            }
        }
    )
    first.effects.dropShadow?.let { shadow ->
        ShadowControls(
            shadow = shadow,
            unit = unit,
            onPickColor = { initial, selected ->
                onPickColor("Drop shadow color", initial, selected)
            },
            onUpdate = { updated ->
                editorState.updateSelectedShapes("Drop Shadow Settings") { shape ->
                    shape.withEffects(
                        shape.effects.copy(
                            dropShadow = (shape.effects.dropShadow ?: ShadowEffect()).copy(
                                offsetX = updated.offsetX,
                                offsetY = updated.offsetY,
                                blurRadius = updated.blurRadius,
                                color = updated.color,
                                opacity = updated.opacity
                            )
                        )
                    )
                }
            }
        )
    }

    NumericField(
        label = "Edge blur (${unit.shortName})",
        value = unit.fromMm(first.effects.edgeBlurRadius),
        onValue = { displayed ->
            val radius = unit.toMm(displayed).coerceAtLeast(0f)
            editorState.updateSelectedShapes("Edge Blur") { shape ->
                shape.withEffects(shape.effects.copy(edgeBlurRadius = radius))
            }
        }
    )

    EffectToggle(
        label = "Inside shadow",
        checked = first.effects.innerShadow != null,
        onChecked = { enabled ->
            editorState.updateSelectedShapes("Inside Shadow") { shape ->
                shape.withEffects(
                    shape.effects.copy(
                        innerShadow = if (enabled) {
                            shape.effects.innerShadow ?: ShadowEffect(
                                offsetX = 1.5f,
                                offsetY = 1.5f,
                                blurRadius = 2.5f,
                                opacity = 0.35f
                            )
                        } else {
                            null
                        }
                    )
                )
            }
        }
    )
    first.effects.innerShadow?.let { shadow ->
        ShadowControls(
            shadow = shadow,
            unit = unit,
            onPickColor = { initial, selected ->
                onPickColor("Inside shadow color", initial, selected)
            },
            onUpdate = { updated ->
                editorState.updateSelectedShapes("Inside Shadow Settings") { shape ->
                    shape.withEffects(
                        shape.effects.copy(
                            innerShadow = (shape.effects.innerShadow ?: ShadowEffect()).copy(
                                offsetX = updated.offsetX,
                                offsetY = updated.offsetY,
                                blurRadius = updated.blurRadius,
                                color = updated.color,
                                opacity = updated.opacity
                            )
                        )
                    )
                }
            }
        )
    }

    Text(
        "Noise ${(first.effects.noiseAmount * 100f).roundToInt()}%",
        style = MaterialTheme.typography.labelSmall
    )
    Slider(
        value = first.effects.noiseAmount.coerceIn(0f, 1f),
        onValueChange = { amount ->
            editorState.updateSelectedShapes("Noise") { shape ->
                shape.withEffects(shape.effects.copy(noiseAmount = amount.coerceIn(0f, 1f)))
            }
        }
    )
}

@Composable
private fun EffectToggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ShadowControls(
    shadow: ShadowEffect,
    unit: com.drawit.core.document.Unit,
    onPickColor: (Color, (Color) -> Unit) -> Unit,
    onUpdate: (ShadowEffect) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(androidx.compose.ui.graphics.Color(shadow.color.toArgb()))
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .clickable {
                    onPickColor(shadow.color) { selected ->
                        onUpdate(shadow.copy(color = selected))
                    }
                }
        )
        Text("Color", style = MaterialTheme.typography.labelMedium)
        Text(
            "${(shadow.opacity * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall
        )
    }
    Slider(
        value = shadow.opacity.coerceIn(0f, 1f),
        onValueChange = { onUpdate(shadow.copy(opacity = it.coerceIn(0f, 1f))) }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumericField(
            "Offset X",
            unit.fromMm(shadow.offsetX),
            { onUpdate(shadow.copy(offsetX = unit.toMm(it))) },
            Modifier.weight(1f)
        )
        NumericField(
            "Offset Y",
            unit.fromMm(shadow.offsetY),
            { onUpdate(shadow.copy(offsetY = unit.toMm(it))) },
            Modifier.weight(1f)
        )
    }
    NumericField(
        "Blur (${unit.shortName})",
        unit.fromMm(shadow.blurRadius),
        { onUpdate(shadow.copy(blurRadius = unit.toMm(it).coerceAtLeast(0f))) }
    )
}

// ---- Gradient editor ----

@Composable
private fun GradientEditor(
    fill: Fill.Gradient,
    onUpdate: (Fill.Gradient) -> Unit,
    onPickColor: (initial: Color, onSelected: (Color) -> Unit) -> Unit
) {
    Text("Stops", style = MaterialTheme.typography.labelMedium)
    fill.stops.withIndex().sortedBy { it.value.position }.forEach { indexedStop ->
        val originalIndex = indexedStop.index
        val stop = indexedStop.value
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()) {
            // Color patch
            Box(Modifier.size(24.dp).background(androidx.compose.ui.graphics.Color(stop.color.toArgb()))
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .clickable {
                    onPickColor(stop.color) { selectedColor ->
                        val newStops = fill.stops.toMutableList().also {
                            it[originalIndex] = stop.copy(color = selectedColor)
                        }
                        onUpdate(fill.copy(stops = newStops))
                    }
                })
            // Position slider
            Slider(value = stop.position, onValueChange = { pos ->
                val newStops = fill.stops.toMutableList().also {
                    it[originalIndex] = stop.copy(position = pos)
                }
                onUpdate(fill.copy(stops = newStops))
            }, modifier = Modifier.weight(1f))
            Text("${(stop.position*100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
            // Remove button (if >2 stops)
            if (fill.stops.size > 2) {
                IconButton(onClick = {
                    onUpdate(fill.copy(stops = fill.stops.filterIndexed { index, _ ->
                        index != originalIndex
                    }))
                }, modifier = Modifier.size(24.dp)) {
                    Text("✕", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    // Add stop
    if (fill.stops.size < 8) {
        TextButton(onClick = {
            val sorted = fill.stops.sortedBy { it.position }
            val largestGap = sorted.zipWithNext().maxByOrNull { (a, b) ->
                b.position - a.position
            }
            val midPos = largestGap?.let { (a, b) ->
                (a.position + b.position) / 2f
            } ?: 0.5f
            onUpdate(fill.copy(stops = fill.stops + GradientStop(midPos, Color.GRAY)))
        }) { Text("+ Add Stop", style = MaterialTheme.typography.labelSmall) }
    }
    // Angle (linear only)
    if (fill.type == Fill.Gradient.Type.LINEAR) {
        var angleText by remember(fill) { mutableStateOf(trimNum(fill.angleDegrees)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Angle:", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(value = angleText, onValueChange = { t ->
                angleText = t; t.toFloatOrNull()?.let { onUpdate(fill.copy(angleDegrees = it)) }
            }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            Text("°", style = MaterialTheme.typography.labelSmall)
        }
    }
    // Reverse
    TextButton(onClick = { onUpdate(fill.reversed()) }) {
        Text("Reverse Stops", style = MaterialTheme.typography.labelSmall)
    }
}

// ---- Pattern editor ----

@Composable
private fun PatternEditor(fill: Fill.Pattern, onUpdate: (Fill.Pattern) -> Unit, onPickImage: () -> Unit) {
    Text("Image ID: ${fill.imageId.take(8)}", style = MaterialTheme.typography.labelSmall)
    TextButton(onClick = onPickImage) { Text("Choose Image…", style = MaterialTheme.typography.labelSmall) }
    EnumDropdown(label = "Placement", options = Fill.Pattern.Placement.entries.map { it.displayName },
        selected = fill.placement.displayName,
        onSelect = { n -> onUpdate(fill.copy(placement = Fill.Pattern.Placement.entries.first { it.displayName == n })) })
    if (fill.placement == Fill.Pattern.Placement.TILE) {
        var scaleText by remember(fill) { mutableStateOf(trimNum(fill.tileScale)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scale:", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(value = scaleText, onValueChange = { t ->
                scaleText = t; t.toFloatOrNull()?.coerceAtLeast(0.1f)?.let { onUpdate(fill.copy(tileScale = it)) }
            }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
    }
}

// ---- Helpers ----

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun NumericField(label: String, value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(trimNum(value)) }
    var wasFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    fun commit() {
        text.toFloatOrNull()?.let(onValue)
    }
    OutlinedTextField(value = text, onValueChange = { text = it },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focusManager.clearFocus()
        }),
        modifier = modifier.fillMaxWidth().onFocusChanged { state ->
            if (wasFocused && !state.isFocused) commit()
            wasFocused = state.isFocused
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdown(label: String, options: List<String>, selected: String,
                          onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(value = selected, onValueChange = {}, readOnly = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); expanded = false }) }
        }
    }
}

private fun fillTypeLabel(fill: Fill): String = when (fill) {
    is Fill.None -> "None"
    is Fill.Solid -> "Solid"
    is Fill.Gradient -> if (fill.type == Fill.Gradient.Type.LINEAR) "Linear Gradient" else "Radial Gradient"
    is Fill.Pattern -> "Pattern"
}

private fun trimNum(v: Float): String =
    if (v == v.toLong().toFloat()) {
        v.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

private fun move(es: EditorState, xMm: Float? = null, yMm: Float? = null) {
    val b = es.selectionBounds() ?: return
    val d = Matrix.translate((xMm ?: b.left) - b.left, (yMm ?: b.top) - b.top)
    es.updateSelectedShapes("Move") { it.withTransform(d * it.transform) }
}

private fun scale(es: EditorState, wMm: Float? = null, hMm: Float? = null) {
    val b = es.selectionBounds() ?: return
    if (b.width <= 0f || b.height <= 0f) return
    val sx = if (wMm != null && wMm > 0f) wMm / b.width else 1f
    val sy = if (hMm != null && hMm > 0f) hMm / b.height else 1f
    val t = Matrix.scale(sx, sy, b.topLeft)
    es.updateSelectedShapes("Scale") { it.withTransform(t * it.transform) }
}

private fun rotationDegrees(m: Matrix): Float {
    val angle = Math.atan2(m.b.toDouble(), m.a.toDouble())
    return Math.toDegrees(angle).toFloat()
}

private fun normalizeDegrees(value: Float): Float {
    val normalized = value % 360f
    return if (normalized < 0f) normalized + 360f else normalized
}

private fun setRotate(es: EditorState, degrees: Float, pivot: com.drawit.core.geometry.Point) {
    val currentDegrees = es.selectedShapes().firstOrNull()?.let { rotationDegrees(it.transform) } ?: return
    val deltaRad = Math.toRadians((degrees - currentDegrees).toDouble()).toFloat()
    val rotation = Matrix.rotate(deltaRad, pivot)
    es.updateSelectedShapes("Rotate") {
        it.withTransform(rotation * it.transform)
    }
}

private fun pivotFor(bounds: Rect, name: String): com.drawit.core.geometry.Point = when (name) {
    "TL" -> bounds.topLeft
    "TR" -> bounds.topRight
    "BL" -> bounds.bottomLeft
    "BR" -> bounds.bottomRight
    else -> bounds.center
}
