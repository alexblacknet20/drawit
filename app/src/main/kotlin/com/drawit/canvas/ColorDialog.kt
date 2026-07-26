package com.drawit.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drawit.core.color.Color
import kotlin.math.roundToInt

/** Session-persistent recent colors. */
object RecentColors {
    val colors = mutableStateListOf<Color>()
    fun push(c: Color) {
        colors.remove(c)
        colors.add(0, c)
        while (colors.size > 12) colors.removeLast()
    }
}

/** Built-in swatch palettes. */
object Palettes {
    val material: List<Color> = listOf(
        Color(244, 67, 54), Color(233, 30, 99), Color(156, 39, 176), Color(103, 58, 183),
        Color(63, 81, 181), Color(33, 150, 243), Color(3, 169, 244), Color(0, 188, 212),
        Color(0, 150, 136), Color(76, 175, 80), Color(139, 195, 74), Color(205, 220, 57),
        Color(255, 235, 59), Color(255, 193, 7), Color(255, 152, 0), Color(255, 87, 34),
        Color(121, 85, 72), Color(158, 158, 158), Color(96, 125, 139), Color(0, 0, 0)
    )
    val grayscale: List<Color> = (0..10).map {
        val v = (it * 25.5f).roundToInt(); Color(v, v, v)
    }
    val vinylStarter: List<Color> = listOf(
        Color(255, 0, 0), Color(200, 16, 46), Color(255, 102, 0), Color(255, 200, 0),
        Color(255, 242, 0), Color(0, 166, 80), Color(0, 104, 56), Color(0, 174, 239),
        Color(0, 82, 155), Color(43, 57, 144), Color(102, 45, 145), Color(236, 0, 140),
        Color(255, 255, 255), Color(240, 240, 240), Color(128, 128, 128), Color(0, 0, 0)
    )
}

/**
 * Two-tab color dialog: Palette (swatches + recents) / Mixer (HSV + hex + CMYK).
 */
@Composable
fun ColorDialog(
    initial: Color,
    title: String = "Color",
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Preview strip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(androidx.compose.ui.graphics.Color(initial.toArgb()))
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                    )
                    Text("→")
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(androidx.compose.ui.graphics.Color(current.toArgb()))
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                    )
                    Text(current.toHexString(includeAlpha = true),
                        style = MaterialTheme.typography.labelMedium)
                }

                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Palette") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Mixer") })
                }

                when (tab) {
                    0 -> PaletteTab(current) { current = it }
                    1 -> MixerTab(current) { current = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                RecentColors.push(current)
                onConfirm(current)
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PaletteTab(current: Color, onPick: (Color) -> Unit) {
    Column(modifier = Modifier.height(340.dp)) {
        if (RecentColors.colors.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 4.dp))
            SwatchRow(RecentColors.colors, current, onPick)
        }
        Text("Vinyl", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 4.dp))
        SwatchGrid(Palettes.vinylStarter, current, onPick, modifier = Modifier.weight(1f))
        Text("Material", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 4.dp))
        SwatchGrid(Palettes.material, current, onPick, modifier = Modifier.weight(1f))
        Text("Grayscale", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 4.dp))
        SwatchRow(Palettes.grayscale, current, onPick)
    }
}

@Composable
private fun SwatchRow(colors: List<Color>, current: Color, onPick: (Color) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.take(12).forEach { c -> Swatch(c, current == c, onPick) }
    }
}

@Composable
private fun SwatchGrid(
    colors: List<Color>, current: Color, onPick: (Color) -> Unit, modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        items(colors) { c -> Swatch(c, current == c, onPick) }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onPick: (Color) -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(androidx.compose.ui.graphics.Color(color.toArgb()))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            )
            .clickable { onPick(color) }
    )
}

@Composable
private fun MixerTab(current: Color, onChange: (Color) -> Unit) {
    // Decompose current color into HSV + alpha for editing
    val hsv = remember(current) { FloatArray(3).also { android.graphics.Color.colorToHSV(current.toArgb(), it) } }
    var hue by remember(current) { mutableStateOf(hsv[0]) }
    var sat by remember(current) { mutableStateOf(hsv[1]) }
    var value by remember(current) { mutableStateOf(hsv[2]) }
    var alpha by remember(current) { mutableStateOf(current.a / 255f) }
    var hexText by remember(current) { mutableStateOf(current.toHexString()) }

    fun emit(h: Float = hue, s: Float = sat, v: Float = value, a: Float = alpha) {
        val argb = android.graphics.Color.HSVToColor((a * 255).roundToInt(), floatArrayOf(h, s, v))
        onChange(Color.fromArgb(argb))
    }

    Column(
        modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // SV square
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .pointerInput(hue) {
                    detectDragGestures { change, _ ->
                        val s = (change.position.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        sat = s; value = v; emit(s = s, v = v)
                    }
                }
                .pointerInput(hue) {
                    detectTapGestures { offset ->
                        val s = (offset.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        sat = s; value = v; emit(s = s, v = v)
                    }
                }
        ) {
            val hueColor = androidx.compose.ui.graphics.Color(
                android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    Brush.horizontalGradient(
                        listOf(androidx.compose.ui.graphics.Color.White, hueColor)
                    )
                )
                drawRect(
                    Brush.verticalGradient(
                        listOf(androidx.compose.ui.graphics.Color.Transparent,
                            androidx.compose.ui.graphics.Color.Black)
                    )
                )
            }
            // Indicator
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White,
                    radius = 8.dp.toPx(),
                    center = Offset(sat * size.width, (1f - value) * size.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Hue slider
        Text("Hue", style = MaterialTheme.typography.labelSmall)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(
                    Brush.horizontalGradient(
                        (0..6).map {
                            androidx.compose.ui.graphics.Color(
                                android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))
                            )
                        }
                    )
                )
        )
        Slider(value = hue / 360f, onValueChange = { hue = it * 360f; emit() })

        // Alpha
        Text("Opacity: ${(alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
        Slider(value = alpha, onValueChange = { alpha = it; emit() })

        // Hex
        OutlinedTextField(
            value = hexText,
            onValueChange = { text ->
                hexText = text
                runCatching { Color.fromHex(text) }.getOrNull()?.let { c ->
                    val hsv2 = FloatArray(3)
                    android.graphics.Color.colorToHSV(c.toArgb(), hsv2)
                    hue = hsv2[0]; sat = hsv2[1]; value = hsv2[2]
                    onChange(c.withAlpha((alpha * 255).roundToInt()))
                }
            },
            label = { Text("Hex (#RRGGBB)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // CMYK inputs
        Text("CMYK", style = MaterialTheme.typography.labelSmall)
        val cmyk = current.toCmyk()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("C" to cmyk.c, "M" to cmyk.m, "Y" to cmyk.y, "K" to cmyk.k).forEach { (label, v) ->
                var text by remember(current) { mutableStateOf((v * 100).roundToInt().toString()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { t ->
                        text = t
                        val pct = t.toFloatOrNull()?.coerceIn(0f, 100f) ?: return@OutlinedTextField
                        val nc = if (label == "C") pct / 100f else current.toCmyk().c
                        val nm = if (label == "M") pct / 100f else current.toCmyk().m
                        val ny = if (label == "Y") pct / 100f else current.toCmyk().y
                        val nk = if (label == "K") pct / 100f else current.toCmyk().k
                        onChange(cmykToRgb(nc, nm, ny, nk).withAlpha((alpha * 255).roundToInt()))
                    },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Naive CMYK → RGB (preview only; export uses ICC later). */
private fun cmykToRgb(c: Float, m: Float, y: Float, k: Float): Color {
    val r = (255f * (1f - c) * (1f - k)).roundToInt()
    val g = (255f * (1f - m) * (1f - k)).roundToInt()
    val b = (255f * (1f - y) * (1f - k)).roundToInt()
    return Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}
