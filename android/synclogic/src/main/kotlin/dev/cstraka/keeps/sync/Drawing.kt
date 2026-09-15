package dev.cstraka.keeps.sync

import kotlinx.serialization.Serializable

/**
 * Kotlin port of shared/drawing.ts (contract amendment). Field names and
 * JSON keys match the web/Worker shapes exactly; points are normalized
 * 0..1 coordinates so any client can paint them at any size.
 */
@Serializable
data class DrawingPoint(val x: Double = 0.0, val y: Double = 0.0)

@Serializable
data class DrawingStroke(
    val color: String = "#ffffff",
    val width: Double = 7.0,
    val points: List<DrawingPoint> = emptyList(),
)

@Serializable
data class Drawing(
    val id: String,
    val strokes: List<DrawingStroke> = emptyList(),
    /** Unix epoch milliseconds, client-assigned. Last-write-wins key. */
    val updatedAt: Long = 0L,
    /** Soft delete. True = tombstone; tombstones sync like drawings. */
    val deleted: Boolean = false,
) {
    init {
        require(id.isNotEmpty()) { "Drawing id must not be empty" }
    }
}

object DrawingLimits {
    const val MAX_STROKES = 200
    const val MAX_POINTS_PER_STROKE = 2000
    const val MAX_WIDTH = 100.0
    const val MAX_COLOR_LENGTH = 32
}

/** Caps mirror DRAWING_LIMITS; rejects NaN/Infinite coordinates. */
fun isDrawing(drawing: Drawing): Boolean {
    if (drawing.strokes.size > DrawingLimits.MAX_STROKES) return false
    return drawing.strokes.all { stroke ->
        stroke.color.length <= DrawingLimits.MAX_COLOR_LENGTH &&
            stroke.width.isFinite() &&
            stroke.width > 0.0 &&
            stroke.width <= DrawingLimits.MAX_WIDTH &&
            stroke.points.size <= DrawingLimits.MAX_POINTS_PER_STROKE &&
            stroke.points.all { it.x.isFinite() && it.y.isFinite() }
    }
}

fun newDrawing(
    id: String,
    strokes: List<DrawingStroke> = emptyList(),
    updatedAt: Long = System.currentTimeMillis(),
    deleted: Boolean = false,
): Drawing = Drawing(id, strokes, updatedAt, deleted)

/**
 * JS-canonical JSON of a drawing, matching JSON.stringify() on the web for
 * the same logical value: whole doubles print without a decimal point
 * ("7", not "7.0"). Used ONLY for the equal-timestamp tiebreak so both
 * platforms pick the same winner; transport uses the kotlinx DTOs.
 */
private fun jsNum(value: Double): String =
    if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e21)
        value.toLong().toString()
    else value.toString()

private fun jsStr(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

fun drawingJson(drawing: Drawing): String = buildString {
    append("{\"id\":")
    append(jsStr(drawing.id))
    append(",\"strokes\":[")
    drawing.strokes.forEachIndexed { i, stroke ->
        if (i > 0) append(',')
        append("{\"color\":")
        append(jsStr(stroke.color))
        append(",\"width\":")
        append(jsNum(stroke.width))
        append(",\"points\":[")
        stroke.points.forEachIndexed { j, point ->
            if (j > 0) append(',')
            append("{\"x\":")
            append(jsNum(point.x))
            append(",\"y\":")
            append(jsNum(point.y))
            append('}')
        }
        append("]}")
    }
    append("],\"updatedAt\":")
    append(drawing.updatedAt.toString())
    append(",\"deleted\":")
    append(if (drawing.deleted) "true" else "false")
    append('}')
}

/**
 * Winner of two versions of the same drawing id (port of
 * shared/drawing-sync.ts). Never mutates inputs. Total order, symmetric.
 */
fun pickDrawingWinner(local: Drawing, remote: Drawing): Drawing {
    require(local.id == remote.id) { "pickDrawingWinner: id mismatch" }
    if (remote.updatedAt != local.updatedAt) {
        return if (remote.updatedAt > local.updatedAt) remote else local
    }
    return if (drawingJson(remote) >= drawingJson(local)) remote else local
}

/** Merge two lists by id with last-write-wins. No duplicates, no mutation. */
fun mergeDrawingLists(a: List<Drawing>, b: List<Drawing>): List<Drawing> {
    val byId = LinkedHashMap<String, Drawing>()
    for (drawing in a + b) {
        val existing = byId[drawing.id]
        byId[drawing.id] = if (existing != null) pickDrawingWinner(existing, drawing) else drawing
    }
    return byId.values.toList()
}

/** Rows with updatedAt strictly greater than [since] (delta-pull cursor). */
fun drawingsChangedSince(drawings: List<Drawing>, since: Long): List<Drawing> =
    drawings.filter { it.updatedAt > since }

/** Ids referenced by full-line `![drawing](id)` markers in a body. */
private val drawingRefRe = Regex("""^!\[drawing\]\(([^)\s]+)\)$""")

fun drawingRefs(body: String): List<String> = body.lineSequence()
    .map { drawingRefRe.matchEntire(it.trim())?.groupValues?.get(1) }
    .filterNotNull()
    .toList()
