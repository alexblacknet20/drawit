package com.drawit.file

import com.drawit.core.color.Color
import com.drawit.core.document.BlendMode
import com.drawit.core.document.ColorMode
import com.drawit.core.document.Document
import com.drawit.core.document.Fill
import com.drawit.core.document.GradientStop
import com.drawit.core.document.ImageShape
import com.drawit.core.document.Layer
import com.drawit.core.document.Margins
import com.drawit.core.document.Page
import com.drawit.core.document.Shape
import com.drawit.core.document.Stroke
import com.drawit.core.document.TextShape
import com.drawit.core.document.Unit
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.PathCommand
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Point
import com.drawit.core.geometry.Rect
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes Document ↔ JSON (used inside the .drawit ZIP container).
 * Android-only (org.json); keep geometry/document classes pure for JVM tests.
 */
object DocumentSerializer {

    const val FORMAT_VERSION = 2

    // ============================== WRITE ==============================

    fun toJson(doc: Document): JSONObject = JSONObject().apply {
        put("formatVersion", FORMAT_VERSION)
        put("id", doc.id)
        put("name", doc.name)
        put("dpi", doc.dpi.toDouble())
        put("colorMode", doc.colorMode.name)
        put("displayUnit", doc.displayUnit.name)
        put("activePageIndex", doc.activePageIndex)
        put("pages", JSONArray().apply { doc.pages.forEach { put(pageToJson(it)) } })
    }

    private fun pageToJson(page: Page): JSONObject = JSONObject().apply {
        put("id", page.id)
        put("name", page.name)
        put("width", page.width.toDouble())
        put("height", page.height.toDouble())
        put("bleed", marginsToJson(page.bleed))
        put("activeLayerId", page.activeLayerId)
        put("layers", JSONArray().apply { page.layers.forEach { put(layerToJson(it)) } })
    }

    private fun marginsToJson(m: Margins): JSONObject = JSONObject().apply {
        put("top", m.top.toDouble()); put("right", m.right.toDouble())
        put("bottom", m.bottom.toDouble()); put("left", m.left.toDouble())
    }

    private fun layerToJson(layer: Layer): JSONObject = JSONObject().apply {
        put("id", layer.id)
        put("name", layer.name)
        put("visible", layer.visible)
        put("locked", layer.locked)
        put("shapes", JSONArray().apply { layer.shapes.forEach { put(shapeToJson(it)) } })
    }

    private fun shapeToJson(shape: Shape): JSONObject = JSONObject().apply {
        put("type", when (shape) {
            is Shape.PathShape -> "path"
            is Shape.RectShape -> "rect"
            is Shape.EllipseShape -> "ellipse"
            is Shape.GroupShape -> "group"
            is ImageShape -> "image"
            is TextShape -> "text"
        })
        put("id", shape.id)
        put("name", shape.name)
        put("transform", matrixToJson(shape.transform))
        put("fill", fillToJson(shape.fill))
        put("stroke", shape.stroke?.let { strokeToJson(it) } ?: JSONObject.NULL)
        put("visible", shape.visible)
        put("locked", shape.locked)
        put("opacity", shape.opacity.toDouble())
        put("blendMode", shape.blendMode.name)
        when (shape) {
            is Shape.PathShape -> put("pathData", pathDataToJson(shape.pathData))
            is Shape.RectShape -> {
                put("rect", rectToJson(shape.rect))
                put("cornerRadius", shape.cornerRadius.toDouble())
            }
            is Shape.EllipseShape -> put("rect", rectToJson(shape.rect))
            is Shape.GroupShape -> put("children",
                JSONArray().apply { shape.children.forEach { put(shapeToJson(it)) } })
            is TextShape -> {
                put("text", shape.text)
                put("kind", shape.kind.name)
                put("fontFamily", shape.fontFamily)
                put("textSize", shape.textSize.toDouble())
                put("frameWidth", shape.frameWidth.toDouble())
                put("align", shape.align.name)
                put("lineSpacing", shape.lineSpacing.toDouble())
                put("measuredBounds", rectToJson(shape.measuredBounds))
            }
            is ImageShape -> {
                put("imageId", shape.imageId)
                put("rect", rectToJson(shape.rect))
            }
        }
    }

    private fun matrixToJson(m: Matrix): JSONArray =
        JSONArray(listOf(m.a, m.b, m.c, m.d, m.e, m.f).map { it.toDouble() })

    private fun rectToJson(r: Rect): JSONObject = JSONObject().apply {
        put("left", r.left.toDouble()); put("top", r.top.toDouble())
        put("right", r.right.toDouble()); put("bottom", r.bottom.toDouble())
    }

    private fun fillToJson(fill: Fill): JSONObject = JSONObject().apply {
        when (fill) {
            is Fill.None -> put("type", "none")
            is Fill.Solid -> { put("type", "solid"); put("color", fill.color.toHexString(true)) }
            is Fill.Gradient -> {
                put("type", "gradient"); put("gradientType", fill.type.name)
                put("angle", fill.angleDegrees.toDouble())
                put("stops", JSONArray().apply { fill.stops.forEach { stop ->
                    put(JSONObject().apply { put("position", stop.position.toDouble()); put("color", stop.color.toHexString(true)) })
                }})
            }
            is Fill.Pattern -> {
                put("type", "pattern"); put("imageId", fill.imageId)
                put("placement", fill.placement.name); put("tileScale", fill.tileScale.toDouble())
            }
        }
    }

    private fun strokeToJson(s: Stroke): JSONObject = JSONObject().apply {
        put("color", s.color.toHexString(includeAlpha = true))
        put("width", s.width.toDouble())
        put("cap", s.cap.name)
        put("join", s.join.name)
        put("miterLimit", s.miterLimit.toDouble())
        put("dash", JSONArray().apply { s.dashPattern.forEach { put(it.toDouble()) } })
    }

    private fun pathDataToJson(path: PathData): JSONObject = JSONObject().apply {
        put("fillRule", path.fillRule.name)
        put("commands", JSONArray().apply {
            path.commands.forEach { cmd ->
                put(JSONObject().apply {
                    when (cmd) {
                        is PathCommand.MoveTo -> { put("t", "M"); putPoint(cmd.point) }
                        is PathCommand.LineTo -> { put("t", "L"); putPoint(cmd.point) }
                        is PathCommand.CubicTo -> {
                            put("t", "C"); putPoint(cmd.cp1); putPoint2(cmd.cp2); putPoint3(cmd.end)
                        }
                        is PathCommand.QuadTo -> { put("t", "Q"); putPoint(cmd.cp); putPoint2(cmd.end) }
                        PathCommand.Close -> put("t", "Z")
                    }
                })
            }
        })
    }

    private fun JSONObject.putPoint(p: Point) { put("x", p.x.toDouble()); put("y", p.y.toDouble()) }
    private fun JSONObject.putPoint2(p: Point) { put("x2", p.x.toDouble()); put("y2", p.y.toDouble()) }
    private fun JSONObject.putPoint3(p: Point) { put("x3", p.x.toDouble()); put("y3", p.y.toDouble()) }

    // ============================== READ ==============================

    fun fromJson(json: JSONObject): Document {
        val version = json.optInt("formatVersion", 1)
        require(version <= FORMAT_VERSION) { "Unsupported format version $version" }
        return Document(
            id = json.optString("id", Shape.newId()),
            name = json.optString("name", "Untitled"),
            dpi = json.optDouble("dpi", 96.0).toFloat(),
            colorMode = ColorMode.valueOf(json.optString("colorMode", "RGB")),
            displayUnit = Unit.fromName(json.optString("displayUnit", "MM")),
            activePageIndex = json.optInt("activePageIndex", 0),
            pages = jsonArrayToList(json.getJSONArray("pages")) { pageFromJson(it) }
        )
    }

    private fun pageFromJson(j: JSONObject): Page = Page(
        id = j.optString("id", Shape.newId()),
        name = j.optString("name", "Page"),
        width = j.optDouble("width", 210.0).toFloat(),
        height = j.optDouble("height", 297.0).toFloat(),
        bleed = j.optJSONObject("bleed")?.let { marginsFromJson(it) } ?: Margins.ZERO,
        activeLayerId = j.optString("activeLayerId", ""),
        layers = jsonArrayToList(j.getJSONArray("layers")) { layerFromJson(it) }
    ).let { p ->
        // Guard: ensure active layer id is valid
        if (p.layers.none { it.id == p.activeLayerId }) p.copy(activeLayerId = p.layers.first().id)
        else p
    }

    private fun marginsFromJson(j: JSONObject) = Margins(
        top = j.optDouble("top", 0.0).toFloat(),
        right = j.optDouble("right", 0.0).toFloat(),
        bottom = j.optDouble("bottom", 0.0).toFloat(),
        left = j.optDouble("left", 0.0).toFloat()
    )

    private fun layerFromJson(j: JSONObject): Layer = Layer(
        id = j.optString("id", Shape.newId()),
        name = j.optString("name", "Layer"),
        visible = j.optBoolean("visible", true),
        locked = j.optBoolean("locked", false),
        shapes = jsonArrayToList(j.getJSONArray("shapes")) { shapeFromJson(it) }
    )

    private fun shapeFromJson(j: JSONObject): Shape {
        val common = ShapeCommon(
            id = j.optString("id", Shape.newId()),
            name = j.optString("name", "Shape"),
            transform = matrixFromJson(j.getJSONArray("transform")),
            fill = fillFromJson(j.getJSONObject("fill")),
            stroke = if (j.isNull("stroke")) null else strokeFromJson(j.getJSONObject("stroke")),
            visible = j.optBoolean("visible", true),
            locked = j.optBoolean("locked", false),
            opacity = j.optDouble("opacity", 1.0).toFloat(),
            blendMode = BlendMode.fromName(j.optString("blendMode", "NORMAL"))
        )
        return when (j.getString("type")) {
            "path" -> Shape.PathShape(
                id = common.id, name = common.name, transform = common.transform,
                fill = common.fill, stroke = common.stroke, visible = common.visible,
                locked = common.locked, opacity = common.opacity, blendMode = common.blendMode,
                pathData = pathDataFromJson(j.getJSONObject("pathData"))
            )
            "rect" -> Shape.RectShape(
                id = common.id, name = common.name, transform = common.transform,
                fill = common.fill, stroke = common.stroke, visible = common.visible,
                locked = common.locked, opacity = common.opacity, blendMode = common.blendMode,
                rect = rectFromJson(j.getJSONObject("rect")),
                cornerRadius = j.optDouble("cornerRadius", 0.0).toFloat()
            )
            "ellipse" -> Shape.EllipseShape(
                id = common.id, name = common.name, transform = common.transform,
                fill = common.fill, stroke = common.stroke, visible = common.visible,
                locked = common.locked, opacity = common.opacity, blendMode = common.blendMode,
                rect = rectFromJson(j.getJSONObject("rect"))
            )
            "group" -> Shape.GroupShape(
                id = common.id, name = common.name, transform = common.transform,
                fill = common.fill, stroke = common.stroke, visible = common.visible,
                locked = common.locked, opacity = common.opacity, blendMode = common.blendMode,
                children = jsonArrayToList(j.getJSONArray("children")) { shapeFromJson(it) }
            )
            "text" -> TextShape(
                id = common.id, name = common.name, transform = common.transform,
                fill = common.fill, stroke = common.stroke, visible = common.visible,
                locked = common.locked, opacity = common.opacity, blendMode = common.blendMode,
                text = j.optString("text", ""),
                kind = runCatching { TextShape.Kind.valueOf(j.optString("kind","ARTISTIC")) }.getOrDefault(TextShape.Kind.ARTISTIC),
                fontFamily = j.optString("fontFamily", "sans-serif"),
                textSize = j.optDouble("textSize", 12.0).toFloat(),
                frameWidth = j.optDouble("frameWidth", 0.0).toFloat(),
                align = runCatching { TextShape.Align.valueOf(j.optString("align","LEFT")) }.getOrDefault(TextShape.Align.LEFT),
                lineSpacing = j.optDouble("lineSpacing", 1.2).toFloat(),
                measuredBounds = j.optJSONObject("measuredBounds")?.let { rectFromJson(it) } ?: com.drawit.core.geometry.Rect.EMPTY
            )
            "image" -> ImageShape(
                id = common.id, name = common.name, transform = common.transform,
                fill = common.fill, stroke = common.stroke, visible = common.visible,
                locked = common.locked, opacity = common.opacity, blendMode = common.blendMode,
                imageId = j.optString("imageId", ""),
                rect = j.optJSONObject("rect")?.let { rectFromJson(it) } ?: com.drawit.core.geometry.Rect.EMPTY
            )
            else -> throw IllegalArgumentException("Unknown shape type: ${j.getString("type")}")
        }
    }

    private data class ShapeCommon(
        val id: String, val name: String, val transform: Matrix, val fill: Fill,
        val stroke: Stroke?, val visible: Boolean, val locked: Boolean,
        val opacity: Float, val blendMode: BlendMode
    )

    private fun matrixFromJson(a: JSONArray): Matrix = Matrix(
        a = a.getDouble(0).toFloat(), b = a.getDouble(1).toFloat(),
        c = a.getDouble(2).toFloat(), d = a.getDouble(3).toFloat(),
        e = a.getDouble(4).toFloat(), f = a.getDouble(5).toFloat()
    )

    private fun rectFromJson(j: JSONObject): Rect = Rect(
        left = j.getDouble("left").toFloat(), top = j.getDouble("top").toFloat(),
        right = j.getDouble("right").toFloat(), bottom = j.getDouble("bottom").toFloat()
    )

    private fun fillFromJson(j: JSONObject): Fill = when (j.getString("type")) {
        "solid" -> Fill.Solid(Color.fromHex(j.getString("color")))
        "gradient" -> {
            val type = Fill.Gradient.Type.valueOf(j.optString("gradientType", "LINEAR"))
            val stops = jsonArrayToList(j.getJSONArray("stops")) { stop ->
                GradientStop(
                    stop.getDouble("position").toFloat(),
                    Color.fromHex(stop.getString("color"))
                )
            }
            Fill.Gradient(type, stops, j.optDouble("angle", 0.0).toFloat())
        }
        "pattern" -> Fill.Pattern(
            imageId = j.optString("imageId", ""),
            placement = Fill.Pattern.Placement.valueOf(j.optString("placement", "TILE")),
            tileScale = j.optDouble("tileScale", 1.0).toFloat()
        )
        else -> Fill.None
    }

    private fun strokeFromJson(j: JSONObject): Stroke = Stroke(
        color = Color.fromHex(j.getString("color")),
        width = j.optDouble("width", 1.0).toFloat(),
        cap = Stroke.Cap.valueOf(j.optString("cap", "BUTT")),
        join = Stroke.Join.valueOf(j.optString("join", "MITER")),
        miterLimit = j.optDouble("miterLimit", 4.0).toFloat(),
        dashPattern = j.optJSONArray("dash")?.let { arr ->
            (0 until arr.length()).map { arr.getDouble(it).toFloat() }
        } ?: emptyList()
    )

    private fun pathDataFromJson(j: JSONObject): PathData {
        val rule = PathData.FillRule.valueOf(j.optString("fillRule", "NON_ZERO"))
        val cmds = mutableListOf<PathCommand>()
        val arr = j.getJSONArray("commands")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            when (c.getString("t")) {
                "M" -> cmds.add(PathCommand.MoveTo(readPoint(c, "x", "y")))
                "L" -> cmds.add(PathCommand.LineTo(readPoint(c, "x", "y")))
                "C" -> cmds.add(PathCommand.CubicTo(
                    readPoint(c, "x", "y"), readPoint(c, "x2", "y2"), readPoint(c, "x3", "y3")
                ))
                "Q" -> cmds.add(PathCommand.QuadTo(readPoint(c, "x", "y"), readPoint(c, "x2", "y2")))
                "Z" -> cmds.add(PathCommand.Close)
            }
        }
        return PathData(cmds, rule)
    }

    private fun readPoint(j: JSONObject, xk: String, yk: String): Point =
        Point(j.getDouble(xk).toFloat(), j.getDouble(yk).toFloat())

    private fun <T> jsonArrayToList(arr: JSONArray, map: (JSONObject) -> T): List<T> =
        (0 until arr.length()).map { map(arr.getJSONObject(it)) }
}
