package com.drawit.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/** Named, independently-sized document pages used as exportable artboards. */
@Composable
fun ArtboardsPanel(editorState: EditorState) {
    val document = editorState.document
    val activePage = document.activePage
    var name by remember(activePage.id, activePage.name) {
        mutableStateOf(activePage.name)
    }
    var width by remember(activePage.id, activePage.width) {
        mutableStateOf(formatDimension(activePage.width))
    }
    var height by remember(activePage.id, activePage.height) {
        mutableStateOf(formatDimension(activePage.height))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Artboards", style = MaterialTheme.typography.titleMedium)
        Text(
            "Each artboard has its own name and physical size. PNG/JPG export the active artboard; PDF can export one or all.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        document.pages.forEachIndexed { index, page ->
            val selected = index == document.activePageIndex
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        MaterialTheme.shapes.small
                    )
                    .clickable { editorState.setActiveArtboard(index) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    "${index + 1}. ${page.name}",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "${formatDimension(page.width)} × ${formatDimension(page.height)} mm",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { editorState.addArtboard() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Add")
            }
            OutlinedButton(
                onClick = { editorState.removeActiveArtboard() },
                enabled = document.pages.size > 1,
                modifier = Modifier.weight(1f)
            ) {
                Text("Delete")
            }
        }

        Text("Active artboard", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = width,
                onValueChange = { width = it },
                label = { Text("Width (mm)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = height,
                onValueChange = { height = it },
                label = { Text("Height (mm)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = {
                val parsedWidth = width.replace(',', '.').toFloatOrNull()
                val parsedHeight = height.replace(',', '.').toFloatOrNull()
                if (parsedWidth != null && parsedHeight != null) {
                    editorState.updateActiveArtboard(name, parsedWidth, parsedHeight)
                }
            },
            enabled = width.replace(',', '.').toFloatOrNull()?.let { it >= 1f } == true &&
                height.replace(',', '.').toFloatOrNull()?.let { it >= 1f } == true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Apply name and size")
        }
    }
}

private fun formatDimension(value: Float): String {
    val rounded = value.toInt()
    return if (value == rounded.toFloat()) rounded.toString() else "%.2f".format(value)
}
