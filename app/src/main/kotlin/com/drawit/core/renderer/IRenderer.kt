package com.drawit.core.renderer

import com.drawit.core.document.Document
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.Rect

/**
 * Renderer abstraction — the entire document/talks-to-tools code depends
 * only on this interface, never on Skia/OpenGL directly.
 *
 * Phase 1: SkiaRenderer (Android Canvas, hardware accelerated)
 * Phase 3+: GlRenderer (tiled, LOD, huge documents)
 */
interface IRenderer {

    /**
     * Render the active page of the document.
     * @param viewMatrix screen = viewMatrix × document coordinates
     * @param dirtyRect if non-null, only redraw this region (screen space)
     */
    fun render(document: Document, viewMatrix: Matrix, dirtyRect: Rect? = null)

    /** Render a single overlay layer (selection boxes, tool previews). */
    fun renderOverlay(draw: (Any) -> Unit)

    /** Set the render target (Surface/Canvas). Implementation-defined. */
    fun setTarget(target: Any?)

    /** Release resources. */
    fun dispose()

    /** Renderer capabilities — used to decide features like LOD. */
    val capabilities: RenderCapabilities
}

data class RenderCapabilities(
    val supportsTiling: Boolean = false,
    val supportsLod: Boolean = false,
    val maxTextureSize: Int = 0,
    val hardwareAccelerated: Boolean = true
)
