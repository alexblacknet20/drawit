package com.drawit.file

import com.drawit.core.color.Color
import com.drawit.core.document.Document
import com.drawit.core.document.Fill
import com.drawit.core.document.Layer
import com.drawit.core.document.Page
import com.drawit.core.document.Shape
import com.drawit.core.document.Stroke
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Point
import com.drawit.core.geometry.Rect
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Basic SVG importer: paths, rect/circle/ellipse/line/poly shapes, groups,
 * transforms, fill/stroke styles. Flattens groups; ignores text/images/defs.
 * SVG pixels map 1:1 to document px, then px→mm at 96dpi.
 */
object SvgImporter {

    private const val PX_PER_MM = 96f / 25.4f

    fun import(input: InputStream, fileName: String = "Imported"): Document {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)

        var viewBox: Rect? = null
        var rootWidth: Float? = null
        var rootHeight: Float? = null
        val shapes = mutableListOf<Shape>()
        val groupStack = ArrayDeque<Matrix>()
        groupStack.addLast(Matrix.IDENTITY)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "svg" -> {
                            viewBox = parser.attr("viewBox")?.let { parseViewBox(it) }
                            rootWidth = parser.attr("width")?.let { parseLength(it) }
                            rootHeight = parser.attr("height")?.let { parseLength(it) }
                        }
                        "g" -> {
                            val t = parseTransform(parser.attr("transform"))
                            groupStack.addLast(groupStack.last() * t)
                        }
                        "path" -> parser.attr("d")?.let { d ->
                            val data = SvgPathParser.parse(d)
                            if (!data.isEmpty) {
                                shapes.add(Shape.PathShape(
                                    name = "Path",
                                    pathData = data,
                                    transform = groupStack.last(),
                                    fill = fillOf(parser),
                                    stroke = strokeOf(parser),
                                    opacity = opacityOf(parser)
                                ))
                            }
                        }
                        "rect" -> {
                            val x = parser.attr("x")?.toFloatOrNull() ?: 0f
                            val y = parser.attr("y")?.toFloatOrNull() ?: 0f
                            val w = parser.attr("width")?.toFloatOrNull() ?: 0f
                            val h = parser.attr("height")?.toFloatOrNull() ?: 0f
                            if (w > 0 && h > 0) {
                                shapes.add(Shape.RectShape(
                                    name = "Rect",
                                    rect = Rect(x, y, x + w, y + h),
                                    cornerRadius = parser.attr("rx")?.toFloatOrNull() ?: 0f,
                                    transform = groupStack.last(),
                                    fill = fillOf(parser), stroke = strokeOf(parser),
                                    opacity = opacityOf(parser)
                                ))
                            }
                        }
                        "circle", "ellipse" -> {
                            val cx = parser.attr("cx")?.toFloatOrNull() ?: 0f
                            val cy = parser.attr("cy")?.toFloatOrNull() ?: 0f
                            val rx = parser.attr("rx")?.toFloatOrNull()
                                ?: parser.attr("r")?.toFloatOrNull() ?: 0f
                            val ry = parser.attr("ry")?.toFloatOrNull()
                                ?: parser.attr("r")?.toFloatOrNull() ?: 0f
                            if (rx > 0 && ry > 0) {
                                shapes.add(Shape.EllipseShape(
                                    name = if (parser.name == "circle") "Circle" else "Ellipse",
                                    rect = Rect(cx - rx, cy - ry, cx + rx, cy + ry),
                                    transform = groupStack.last(),
                                    fill = fillOf(parser), stroke = strokeOf(parser),
                                    opacity = opacityOf(parser)
                                ))
                            }
                        }
                        "line" -> {
                            val p1 = Point(parser.attr("x1").f, parser.attr("y1").f)
                            val p2 = Point(parser.attr("x2").f, parser.attr("y2").f)
                            shapes.add(Shape.PathShape(
                                name = "Line",
                                pathData = PathData.EMPTY.moveTo(p1).lineTo(p2),
                                transform = groupStack.last(),
                                fill = Fill.None, stroke = strokeOf(parser) ?: Stroke(),
                                opacity = opacityOf(parser)
                            ))
                        }
                        "polyline", "polygon" -> {
                            val pts = parsePoints(parser.attr("points") ?: "")
                            if (pts.size >= 2) {
                                var data = PathData.EMPTY.moveTo(pts[0])
                                pts.drop(1).forEach { data = data.lineTo(it) }
                                if (parser.name == "polygon") data = data.close()
                                shapes.add(Shape.PathShape(
                                    name = if (parser.name == "polygon") "Polygon" else "Polyline",
                                    pathData = data,
                                    transform = groupStack.last(),
                                    fill = fillOf(parser), stroke = strokeOf(parser),
                                    opacity = opacityOf(parser)
                                ))
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase() == "g" && groupStack.size > 1) {
                        groupStack.removeLast()
                    }
                }
            }
            event = parser.next()
        }

        // Convert SVG px space → mm document space
        val pxToMm = Matrix.scale(1f / PX_PER_MM)
        val mmShapes = shapes.map { it.withTransform(pxToMm * it.transform) }

        // Page size from viewBox or width/height (in px → mm)
        val (pw, ph) = when {
            viewBox != null -> viewBox.width / PX_PER_MM to viewBox.height / PX_PER_MM
            rootWidth != null && rootHeight != null ->
                rootWidth / PX_PER_MM to rootHeight / PX_PER_MM
            else -> {
                val bounds = Rect.unionAll(mmShapes.map { it.bounds() })
                (if (bounds.width > 0) bounds.width else 210f) to
                        (if (bounds.height > 0) bounds.height else 297f)
            }
        }

        return Document(
            name = fileName.removeSuffix(".svg"),
            pages = listOf(Page(
                name = "Page 1",
                width = pw,
                height = ph,
                layers = listOf(Layer(name = "Imported", shapes = mmShapes))
            ))
        )
    }

    // ---------------- style parsing ----------------

    private fun XmlPullParser.attr(name: String): String? =
        getAttributeValue(null, name)

    private val String?.f: Float get() = this?.toFloatOrNull() ?: 0f

    private fun fillOf(p: XmlPullParser): Fill {
        val style = p.attr("style")
        val fillAttr = styleAttr(style, "fill") ?: p.attr("fill")
        val color = parseColor(fillAttr) ?: return Fill.Solid(Color.BLACK) // SVG default fill
        val opacity = (styleAttr(style, "fill-opacity") ?: p.attr("fill-opacity"))?.toFloatOrNull() ?: 1f
        return if (color.a == 0) Fill.None
        else Fill.Solid(color.withAlpha((color.a * opacity).toInt().coerceIn(0, 255)))
    }

    private fun strokeOf(p: XmlPullParser): Stroke? {
        val style = p.attr("style")
        val strokeAttr = styleAttr(style, "stroke") ?: p.attr("stroke")
        val color = parseColor(strokeAttr) ?: return null // SVG default: no stroke
        if (color.a == 0) return null
        val width = (styleAttr(style, "stroke-width") ?: p.attr("stroke-width"))
            ?.let { parseLength(it) } ?: 1f
        val opacity = (styleAttr(style, "stroke-opacity") ?: p.attr("stroke-opacity"))
            ?.toFloatOrNull() ?: 1f
        return Stroke(
            color = color.withAlpha((color.a * opacity).toInt().coerceIn(0, 255)),
            width = width / PX_PER_MM
        )
    }

    private fun opacityOf(p: XmlPullParser): Float =
        (styleAttr(p.attr("style"), "opacity") ?: p.attr("opacity"))
            ?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f

    /** Extract property from inline style string ("fill:#fff;stroke:none"). */
    private fun styleAttr(style: String?, key: String): String? {
        if (style == null) return null
        return style.split(";")
            .mapNotNull {
                val idx = it.indexOf(':')
                if (idx > 0) it.substring(0, idx).trim() to it.substring(idx + 1).trim() else null
            }
            .firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
    }

    private val NAMED_COLORS = mapOf(
        "black" to Color.BLACK, "white" to Color.WHITE, "red" to Color.RED,
        "green" to Color(0, 128, 0), "blue" to Color.BLUE, "yellow" to Color(255, 255, 0),
        "cyan" to Color(0, 255, 255), "magenta" to Color(255, 0, 255),
        "gray" to Color.GRAY, "grey" to Color.GRAY, "orange" to Color(255, 165, 0),
        "purple" to Color(128, 0, 128), "brown" to Color(165, 42, 42),
        "pink" to Color(255, 192, 203), "lime" to Color(0, 255, 0), "navy" to Color(0, 0, 128)
    )

    private fun parseColor(value: String?): Color? {
        if (value == null) return null
        val v = value.trim().lowercase()
        if (v == "none" || v == "transparent") return Color.TRANSPARENT
        if (v.startsWith("#")) return runCatching { Color.fromHex(v) }.getOrNull()
        if (v.startsWith("rgb(") && v.endsWith(")")) {
            val parts = v.removePrefix("rgb(").removeSuffix(")")
                .split(",").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size >= 3) return Color(parts[0], parts[1], parts[2])
        }
        return NAMED_COLORS[v]
    }

    private fun parseViewBox(vb: String): Rect? {
        val parts = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (parts.size != 4) return null
        return Rect(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
    }

    /** Parse SVG length (px, mm, cm, in, pt) → px float. */
    private fun parseLength(s: String): Float? {
        val m = Regex("^([\\d.+-]+)(px|mm|cm|in|pt)?$").find(s.trim()) ?: return null
        val value = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues.getOrNull(2) ?: "px") {
            "mm" -> value * PX_PER_MM
            "cm" -> value * 10f * PX_PER_MM
            "in" -> value * 96f
            "pt" -> value * 96f / 72f
            else -> value
        }
    }

    private fun parsePoints(s: String): List<Point> {
        val nums = s.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        return nums.chunked(2).mapNotNull { if (it.size == 2) Point(it[0], it[1]) else null }
    }

    private fun parseTransform(s: String?): Matrix {
        if (s == null) return Matrix.IDENTITY
        var result = Matrix.IDENTITY
        val regex = Regex("(matrix|translate|scale|rotate)\\s*\\(([^)]*)\\)")
        for (match in regex.findAll(s)) {
            val args = match.groupValues[2].split(Regex("[\\s,]+"))
                .mapNotNull { it.toFloatOrNull() }
            val m = when (match.groupValues[1]) {
                "matrix" -> if (args.size == 6)
                    Matrix(a = args[0], b = args[1], c = args[2], d = args[3], e = args[4], f = args[5])
                else Matrix.IDENTITY
                "translate" -> Matrix.translate(args.getOrElse(0) { 0f }, args.getOrElse(1) { 0f })
                "scale" -> {
                    val sx = args.getOrElse(0) { 1f }
                    Matrix.scale(sx, args.getOrElse(1) { sx })
                }
                "rotate" -> {
                    val rad = Math.toRadians(args.getOrElse(0) { 0f }.toDouble()).toFloat()
                    if (args.size >= 3) Matrix.rotate(rad, Point(args[1], args[2]))
                    else Matrix.rotate(rad)
                }
                else -> Matrix.IDENTITY
            }
            result = result * m
        }
        return result
    }
}
