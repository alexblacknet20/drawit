package com.drawit.file

import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Point

/**
 * Parses SVG path `d` attribute strings into PathData.
 * Supports M/L/H/V/C/S/Q/T/Z (absolute + relative).
 * Arc (A) commands are approximated as straight lines to the endpoint (Phase 2: proper arcs).
 */
object SvgPathParser {

    fun parse(d: String): PathData {
        val tokens = tokenize(d)
        var path = PathData.EMPTY
        var current = Point.ZERO
        var subpathStart = Point.ZERO
        var lastCubicCp: Point? = null   // for S smooth curve reflection
        var lastQuadCp: Point? = null    // for T smooth curve reflection
        var i = 0
        var cmd = ' '

        fun num(): Float = tokens[i++].toFloat()
        fun point(): Point = Point(num(), num())
        fun rel(p: Point): Point = current + p

        while (i < tokens.size) {
            // Command letter or implicit repeat of previous command
            if (tokens[i].length == 1 && tokens[i][0].isLetter()) {
                cmd = tokens[i++][0]
            } else when (cmd) {
                'M' -> cmd = 'L'  // implicit lineto after moveto
                'm' -> cmd = 'l'
            }

            when (cmd) {
                'M' -> { current = point(); subpathStart = current; path = path.moveTo(current) }
                'm' -> { current = rel(point()); subpathStart = current; path = path.moveTo(current) }
                'L' -> { current = point(); path = path.lineTo(current) }
                'l' -> { current = rel(point()); path = path.lineTo(current) }
                'H' -> { current = Point(num(), current.y); path = path.lineTo(current) }
                'h' -> { current = Point(current.x + num(), current.y); path = path.lineTo(current) }
                'V' -> { current = Point(current.x, num()); path = path.lineTo(current) }
                'v' -> { current = Point(current.x, current.y + num()); path = path.lineTo(current) }
                'C' -> {
                    val cp1 = point(); val cp2 = point(); val end = point()
                    path = path.cubicTo(cp1, cp2, end)
                    lastCubicCp = cp2; current = end
                }
                'c' -> {
                    val cp1 = rel(point()); val cp2 = rel(point()); val end = rel(point())
                    path = path.cubicTo(cp1, cp2, end)
                    lastCubicCp = cp2; current = end
                }
                'S' -> {
                    val cp1 = lastCubicCp?.let { current * 2f - it } ?: current
                    val cp2 = point(); val end = point()
                    path = path.cubicTo(cp1, cp2, end)
                    lastCubicCp = cp2; current = end
                }
                's' -> {
                    val cp1 = lastCubicCp?.let { current * 2f - it } ?: current
                    val cp2 = rel(point()); val end = rel(point())
                    path = path.cubicTo(cp1, cp2, end)
                    lastCubicCp = cp2; current = end
                }
                'Q' -> {
                    val cp = point(); val end = point()
                    path = path.quadTo(cp, end)
                    lastQuadCp = cp; current = end
                }
                'q' -> {
                    val cp = rel(point()); val end = rel(point())
                    path = path.quadTo(cp, end)
                    lastQuadCp = cp; current = end
                }
                'T' -> {
                    val cp = lastQuadCp?.let { current * 2f - it } ?: current
                    val end = point()
                    path = path.quadTo(cp, end)
                    lastQuadCp = cp; current = end
                }
                't' -> {
                    val cp = lastQuadCp?.let { current * 2f - it } ?: current
                    val end = rel(point())
                    path = path.quadTo(cp, end)
                    lastQuadCp = cp; current = end
                }
                'A', 'a' -> {
                    // rx ry x-axis-rotation large-arc-flag sweep-flag x y
                    num(); num(); num(); num(); num()
                    val end = if (cmd == 'a') rel(point()) else point()
                    path = path.lineTo(end)  // approximated; proper arc-to-bezier in Phase 2
                    current = end
                }
                'Z', 'z' -> { path = path.close(); current = subpathStart }
                else -> { i++ } // skip unknown token safely
            }

            if (cmd != 'C' && cmd != 'c' && cmd != 'S' && cmd != 's') lastCubicCp = null
            if (cmd != 'Q' && cmd != 'q' && cmd != 'T' && cmd != 't') lastQuadCp = null
        }
        return path
    }

    /** Split `d` into command letters and numeric values. */
    private fun tokenize(d: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < d.length) {
            val ch = d[i]
            when {
                ch.isLetter() -> { tokens.add(ch.toString()); i++ }
                ch.isDigit() || ch == '-' || ch == '+' || ch == '.' -> {
                    var j = i
                    var seenDot = false
                    var seenExp = false
                    while (j < d.length) {
                        val c = d[j]
                        when {
                            c.isDigit() -> j++
                            c == '.' && !seenDot && !seenExp -> { seenDot = true; j++ }
                            (c == 'e' || c == 'E') && !seenExp && j > i -> {
                                seenExp = true; j++
                                if (j < d.length && (d[j] == '-' || d[j] == '+')) j++
                            }
                            else -> break
                        }
                    }
                    tokens.add(d.substring(i, j))
                    i = j
                }
                else -> i++ // whitespace, commas
            }
        }
        return tokens
    }
}
