package com.drawit.canvas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drawit.core.document.Layer
import com.drawit.core.document.Shape

/**
 * Object Manager (Corel-style): layers with their shapes,
 * visibility/lock toggles, selection, z-order raise/lower.
 */
@Composable
fun ObjectManagerPanel(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val page = editorState.document.activePage

    Column(modifier = modifier.padding(8.dp)) {
        // Header + add layer
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(page.name, style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { editorState.addLayer() }) {
                Icon(Icons.Default.Add, "Add Layer")
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Render layers topmost-first (like Corel's docker)
            items(page.layers.asReversed()) { layer ->
                LayerBlock(
                    layer = layer,
                    isActive = layer.id == page.activeLayerId,
                    selectedIds = editorState.selectedShapeIds,
                    onSelectLayer = { editorState.setActiveLayer(layer.id) },
                    onToggleVisible = { editorState.setLayerVisible(layer.id, !layer.visible) },
                    onToggleLock = { editorState.setLayerLocked(layer.id, !layer.locked) },
                    onSelectShape = { id, additive ->
                        val cur = editorState.selectedShapeIds
                        editorState.select(
                            if (additive) (if (id in cur) cur - id else cur + id) else setOf(id)
                        )
                    },
                    onRaise = { id -> editorState.moveShapeInLayer(id, +1) },
                    onLower = { id -> editorState.moveShapeInLayer(id, -1) }
                )
            }
        }
    }
}

@Composable
private fun LayerBlock(
    layer: Layer,
    isActive: Boolean,
    selectedIds: Set<String>,
    onSelectLayer: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleLock: () -> Unit,
    onSelectShape: (String, Boolean) -> Unit,
    onRaise: (String) -> Unit,
    onLower: (String) -> Unit
) {
    Column {
        // Layer header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelectLayer)
                .padding(vertical = 4.dp)
        ) {
            IconButton(onClick = onToggleVisible, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    "Layer visibility",
                    modifier = Modifier.size(18.dp),
                    tint = if (layer.visible) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
            IconButton(onClick = onToggleLock, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Lock, "Layer lock",
                    modifier = Modifier.size(16.dp),
                    tint = if (layer.locked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
            Text(
                layer.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${layer.shapes.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Shapes (topmost first)
        if (layer.visible) {
            layer.shapes.asReversed().forEach { shape ->
                ShapeRow(
                    shape = shape,
                    selected = shape.id in selectedIds,
                    onSelect = { additive -> onSelectShape(shape.id, additive) },
                    onRaise = { onRaise(shape.id) },
                    onLower = { onLower(shape.id) }
                )
            }
        }
    }
}

@Composable
private fun ShapeRow(
    shape: Shape,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onRaise: () -> Unit,
    onLower: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp)
            .clickable { onSelect(false) }
            .padding(vertical = 2.dp)
    ) {
        Text(
            shape.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else if (!shape.visible) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            IconButton(onClick = onRaise, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Raise", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onLower, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Lower", modifier = Modifier.size(16.dp))
            }
        }
    }
}
