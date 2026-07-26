package com.drawit.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drawit.core.document.ColorMode
import com.drawit.core.document.Margins
import com.drawit.core.document.PagePreset
import com.drawit.core.document.Unit

/**
 * New Document dialog: presets by category or fully custom size,
 * with units, orientation, bleed, DPI, and color mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDocumentDialog(
    onDismiss: () -> kotlin.Unit,
    onCreate: (
        name: String, widthMm: Float, heightMm: Float, landscape: Boolean,
        bleed: Margins, colorMode: ColorMode, unit: Unit, dpi: Float
    ) -> kotlin.Unit
) {
    var name by remember { mutableStateOf("Untitled") }
    var category by remember { mutableStateOf(PagePreset.Category.PRINT) }
    var preset by remember { mutableStateOf(PagePreset.ALL.first { it.name == "A4" }) }
    var customMode by remember { mutableStateOf(false) }

    var unit by remember { mutableStateOf(Unit.MM) }
    var widthText by remember { mutableStateOf("210") }
    var heightText by remember { mutableStateOf("297") }
    var landscape by remember { mutableStateOf(false) }
    var bleedText by remember { mutableStateOf("0") }
    var dpiText by remember { mutableStateOf("300") }
    var colorMode by remember { mutableStateOf(ColorMode.RGB) }

    fun applyPreset(p: PagePreset) {
        preset = p
        customMode = false
        landscape = p.landscape
        widthText = trimNum(unit.fromMm(p.widthMm))
        heightText = trimNum(unit.fromMm(p.heightMm))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Document") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Category chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PagePreset.Category.entries.filter { it != PagePreset.Category.CUSTOM }
                        .forEach { cat ->
                            FilterChip(
                                selected = category == cat && !customMode,
                                onClick = {
                                    category = cat
                                    PagePreset.byCategory(cat).firstOrNull()?.let { applyPreset(it) }
                                },
                                label = { Text(cat.displayName, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    FilterChip(
                        selected = customMode,
                        onClick = { customMode = true },
                        label = { Text("Custom", style = MaterialTheme.typography.labelSmall) }
                    )
                }

                // Preset picker
                if (!customMode) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${preset.name}  (${preset.displaySize})",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Preset") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            PagePreset.byCategory(category).forEach { p ->
                                DropdownMenuItem(
                                    text = { Text("${p.name} — ${p.displaySize}") },
                                    onClick = { applyPreset(p); expanded = false }
                                )
                            }
                        }
                    }
                }

                // Size fields + unit
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it; customMode = true },
                        label = { Text("Width") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it; customMode = true },
                        label = { Text("Height") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    var unitExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = unitExpanded,
                        onExpandedChange = { unitExpanded = it },
                        modifier = Modifier.weight(0.9f)
                    ) {
                        OutlinedTextField(
                            value = unit.shortName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Unit") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                            modifier = Modifier.menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = unitExpanded,
                            onDismissRequest = { unitExpanded = false }
                        ) {
                            Unit.entries.forEach { u ->
                                DropdownMenuItem(
                                    text = { Text(u.displayName) },
                                    onClick = {
                                        // Convert current values to new unit
                                        val wMm = unit.toMm(widthText.toFloatOrNull() ?: 0f)
                                        val hMm = unit.toMm(heightText.toFloatOrNull() ?: 0f)
                                        unit = u
                                        widthText = trimNum(u.fromMm(wMm))
                                        heightText = trimNum(u.fromMm(hMm))
                                        unitExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Orientation + color mode
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !landscape,
                        onClick = { landscape = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("Portrait") }
                    SegmentedButton(
                        selected = landscape,
                        onClick = { landscape = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("Landscape") }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ColorMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = colorMode == mode,
                            onClick = { colorMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = i, count = ColorMode.entries.size)
                        ) { Text(mode.displayName) }
                    }
                }

                // Bleed + DPI
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bleedText,
                        onValueChange = { bleedText = it },
                        label = { Text("Bleed (mm)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dpiText,
                        onValueChange = { dpiText = it },
                        label = { Text("DPI") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = unit.toMm(widthText.toFloatOrNull() ?: 210f)
                val h = unit.toMm(heightText.toFloatOrNull() ?: 297f)
                val bleed = bleedText.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                val dpi = dpiText.toFloatOrNull()?.coerceIn(72f, 1200f) ?: 300f
                onCreate(name.ifBlank { "Untitled" }, w, h, landscape,
                    Margins.uniform(bleed), colorMode, unit, dpi)
            }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun trimNum(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString()
    else "%.2f".format(v).trimEnd('0').trimEnd('.')
