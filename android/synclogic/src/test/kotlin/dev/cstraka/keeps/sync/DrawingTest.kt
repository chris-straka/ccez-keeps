package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrawingTest {

    private fun stroke(
        color: String = "#ffffff",
        width: Double = 7.0,
        points: List<DrawingPoint> = listOf(DrawingPoint(0.0, 0.0), DrawingPoint(1.0, 1.0)),
    ) = DrawingStroke(color, width, points)

    @Test
    fun validDrawing_passes() {
        assertTrue(isDrawing(newDrawing(id = "d1", strokes = listOf(stroke()))))
        assertTrue(isDrawing(newDrawing(id = "d1"))) // empty strokes are fine
    }

    @Test
    fun caps_rejectOversized() {
        assertFalse(isDrawing(newDrawing(id = "d", strokes = List(201) { stroke() })))
        assertFalse(
            isDrawing(
                newDrawing(
                    id = "d",
                    strokes = listOf(stroke(points = List(2001) { DrawingPoint(0.5, 0.5) })),
                ),
            ),
        )
        assertFalse(isDrawing(newDrawing(id = "d", strokes = listOf(stroke(width = 0.0)))))
        assertFalse(isDrawing(newDrawing(id = "d", strokes = listOf(stroke(width = 101.0)))))
        assertFalse(
            isDrawing(newDrawing(id = "d", strokes = listOf(stroke(color = "x".repeat(33))))),
        )
        assertFalse(
            isDrawing(
                newDrawing(
                    id = "d",
                    strokes = listOf(stroke(points = listOf(DrawingPoint(Double.NaN, 0.0)))),
                ),
            ),
        )
    }

    @Test
    fun drawingJson_matchesJsStringify() {
        val drawing = Drawing(
            id = "d1",
            strokes = listOf(
                DrawingStroke("#ffffff", 7.0, listOf(DrawingPoint(0.0, 0.0), DrawingPoint(0.5, 1.0))),
            ),
            updatedAt = 100L,
            deleted = false,
        )
        // Whole doubles print without a decimal point, exactly like
        // JSON.stringify on the web; key order matches declaration order.
        assertEquals(
            "{\"id\":\"d1\",\"strokes\":[{\"color\":\"#ffffff\",\"width\":7," +
                "\"points\":[{\"x\":0,\"y\":0},{\"x\":0.5,\"y\":1}]}]," +
                "\"updatedAt\":100,\"deleted\":false}",
            drawingJson(drawing),
        )
    }

    @Test
    fun pickDrawingWinner_newerUpdatedAtWins() {
        val old = newDrawing(id = "d", updatedAt = 100L)
        val new = newDrawing(id = "d", updatedAt = 200L)
        assertEquals(new, pickDrawingWinner(old, new))
        assertEquals(new, pickDrawingWinner(new, old))
    }

    @Test
    fun pickDrawingWinner_equalTimestamp_breaksToLargerJson() {
        val a = Drawing(id = "d", strokes = listOf(stroke(color = "#000000")), updatedAt = 100L)
        val b = Drawing(id = "d", strokes = listOf(stroke(color = "#ffffff")), updatedAt = 100L)
        // "#ffffff" > "#000000" lexicographically, so b wins from either side.
        assertEquals(b, pickDrawingWinner(a, b))
        assertEquals(b, pickDrawingWinner(b, a))
    }

    @Test
    fun mergeDrawingLists_tombstoneIsJustAnotherWrite() {
        val live = newDrawing(id = "d", updatedAt = 100L)
        val tomb = newDrawing(id = "d", updatedAt = 200L, deleted = true)
        assertEquals(listOf(tomb), mergeDrawingLists(listOf(live), listOf(tomb)))
        assertEquals(listOf(live), mergeDrawingLists(listOf(tomb.copy(updatedAt = 50L)), listOf(live)))
    }

    @Test
    fun drawingRefs_findsFullLineMarkersOnly() {
        assertEquals(
            listOf("abc", "ghi"),
            drawingRefs("hello\n![drawing](abc)\n![drawing](def) trailing\n![drawing](ghi)"),
        )
        assertEquals(emptyList(), drawingRefs("no markers **bold**"))
    }

    @Test
    fun drawSyncRequest_splitsUpsertsAndTombstones() {
        val dirty = listOf(
            newDrawing(id = "1", updatedAt = 100L),
            newDrawing(id = "2", updatedAt = 200L, deleted = true),
        )
        val decoded: DrawSyncRequest = apiJson.decodeFromString(encodeDrawSyncRequest(dirty, 7L))
        assertEquals(listOf("1"), decoded.upserts.map { it.id })
        assertEquals(listOf("2"), decoded.tombstones.map { it.id })
        assertEquals(7L, decoded.since)
    }

    @Test
    fun drawResponses_decodeWithDefaults() {
        val pull = decodeDrawPullResponse("""{"drawings":[],"cursor":42}""")
        assertEquals(42L, pull.cursor)
        assertTrue(pull.drawings.isEmpty())
        val push = decodeDrawSyncResponse("""{"applied":1,"deltas":[],"cursor":43}""")
        assertEquals(1, push.applied)
        assertEquals(43L, push.cursor)
    }
}
