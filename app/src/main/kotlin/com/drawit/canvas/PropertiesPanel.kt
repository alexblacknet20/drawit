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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drawit.core.color.Color
import com.drawit.core.document.BlendMode
import com.drawit.core.document.Fill
import com.drawit.core.document.GradientStop
import com.drawit.core.document.Shape
import com.drawit.core.document.Stroke
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.Rect
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
    onPickFillColor: (Color) -> Unit,
    onPickStrokeColor: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = editorState.selectedShapes()
    val unit = editorState.document.displayUnit

    if (selected.isEmpty()) {
        Column(modifier = modifier.padding(16.dp)) {
            Text("No selection", style = MaterialTheme.typography.titleSmall)
            Text("Tap a shape with the Select tool to edit properties.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        .clickable { onPickFillColor(fill.color) }
                    )
                    Text(fill.color.toHexString(), style = MaterialTheme.typography.labelMedium)
                }
            }
            is Fill.Gradient -> GradientEditor(
                fill = fill,
                onUpdate = { g ->
                    editorState.updateSelectedShapes("Gradient") { it.withFill(g) }
                },
                onPickColor = onPickFillColor
            )
            is Fill.Pattern -> PatternEditor(
                fill = fill,
                onUpdate = { p ->
                    editorState.updateSelectedShapes("Pattern") { it.withFill(p) }
                },
                onPickImage = { /* handled in CanvasScreen's image import */ }
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
                .clickable { strokeColor?.let { onPickStrokeColor(it) } }
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
                var rotText by remember(rotDeg) { mutableStateOf(trimNum(rotDeg)) }
                OutlinedTextField(value = rotText, onValueChange = { t ->
                    rotText = t; t.toFloatOrNull()?.let { setRotate(editorState, it, bounds.center) }
                }, label = { Text("Angle") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f))
                // Pivot dropdown
                val pivots = listOf("Center", "TL", "TR", "BL", "BR")
                val selPivot = "Center" // v1 fixed to center
                EnumDropdown(label = "Pivot", options = pivots, selected = selPivot,
                    onSelect = {}, modifier = Modifier.weight(1f))
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
    }
}

// ---- Gradient editor ----

@Composable
private fun GradientEditor(fill: Fill.Gradient, onUpdate: (Fill.Gradient) -> Unit, onPickColor: (Color) -> Unit) {
    Text("Stops", style = MaterialTheme.typography.labelMedium)
    fill.stops.sortedBy { it.position }.forEach { stop ->
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()) {
            // Color patch
            Box(Modifier.size(24.dp).background(androidx.compose.ui.graphics.Color(stop.color.toArgb()))
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .clickable { onPickColor(stop.color) })
            // Position slider
            Slider(value = stop.position, onValueChange = { pos ->
                val idx = fill.stops.indexOf(stop)
                if (idx >= 0) {
                    val newStops = fill.stops.toMutableList().also { it[idx] = stop.copy(position = pos) }
                    onUpdate(fill.copy(stops = newStops))
                }
            }, modifier = Modifier.weight(1f))
            Text("${(stop.position*100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
            // Remove button (if >2 stops)
            if (fill.stops.size > 2) {
                IconButton(onClick = {
                    onUpdate(fill.copy(stops = fill.stops.filter { it != stop }))
                }, modifier = Modifier.size(24.dp)) {
                    Text("✕", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    // Add stop
    if (fill.stops.size < 8) {
        TextButton(onClick = {
            val midPos = fill.stops.map { it.position }.let { (it.max()+it.min())/2f }
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
    OutlinedTextField(value = text, onValueChange = { t -> text = t; t.toFloatOrNull()?.let(onValue) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth())
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
    if (v == v.toLong().toFloat()) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

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

private fun setRotate(es: EditorState, degrees: Float, pivot: com.drawit.core.geometry.Point) {
    val rad = Math.toRadians(degrees.toDouble()).toFloat()
    val r = Matrix.rotate(rad, pivot)
    es.updateSelectedShapes("Rotate") {
        // Remove existing rotation part and apply new; simplify: pre-multiply rotation
        it.withTransform(r * it.transform)
    }
}
