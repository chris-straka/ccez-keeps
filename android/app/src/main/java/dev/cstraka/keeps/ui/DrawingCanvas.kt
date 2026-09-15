package dev.cstraka.keeps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import dev.cstraka.keeps.sync.DrawingLimits
import dev.cstraka.keeps.sync.DrawingPoint
import dev.cstraka.keeps.sync.DrawingStroke

/** Pen palette mirrors the web drawing dialog. */
private val PEN_COLORS = listOf("#ffffff", "#ff5a5a", "#ffd60a", "#30d158", "#0a84ff")
private val PEN_WIDTHS = listOf(3.0, 7.0, 14.0)

fun penColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color.White
}

/**
 * Paint vector strokes into this scope. Scale matches the web renderer:
 * widths are px at a 600-wide canvas, points are 0..1 fractions of the
 * surface — so the same drawing looks identical on web and Android.
 */
fun DrawScope.drawStrokes(strokes: List<DrawingStroke>) {
    for (stroke in strokes) {
        if (stroke.points.size < 2) continue
        val path = Path().apply {
            moveTo(stroke.points[0].x.toFloat() * size.width, stroke.points[0].y.toFloat() * size.height)
            for (i in 1 until stroke.points.size) {
                lineTo(stroke.points[i].x.toFloat() * size.width, stroke.points[i].y.toFloat() * size.height)
            }
        }
        drawPath(
            path = path,
            color = penColor(stroke.color),
            style = Stroke(
                width = (stroke.width * size.width / 600.0).toFloat(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** Read-only thumbnail for note cards (3:2, same as the web canvas). */
@Composable
fun DrawingThumbnail(strokes: List<DrawingStroke>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .testTag("drawing-thumbnail"),
    ) {
        drawStrokes(strokes)
    }
}

/**
 * Freehand editor: draws new strokes over [initial] (empty for a fresh
 * drawing), emits normalized 0..1 strokes on save. Caps mirror
 * DrawingLimits so nothing unsavable can be produced.
 */
@Composable
fun DrawingEditorDialog(
    initial: List<DrawingStroke> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (List<DrawingStroke>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val strokes = remember { mutableStateListOf<DrawingStroke>().apply { addAll(initial) } }
    var color by remember { mutableStateOf(PEN_COLORS[0]) }
    var width by remember { mutableStateOf(PEN_WIDTHS[1]) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 2f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .testTag("draw-canvas")
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(canvasSize) {
                            awaitEachGesture {
                                if (strokes.size >= DrawingLimits.MAX_STROKES) return@awaitEachGesture
                                val down = awaitFirstDown()
                                val w = canvasSize.width.coerceAtLeast(1).toDouble()
                                val h = canvasSize.height.coerceAtLeast(1).toDouble()
                                val points = mutableListOf(
                                    DrawingPoint(
                                        (down.position.x / w).coerceIn(0.0, 1.0),
                                        (down.position.y / h).coerceIn(0.0, 1.0),
                                    ),
                                )
                                drag(down.id) { change ->
                                    if (points.size < DrawingLimits.MAX_POINTS_PER_STROKE) {
                                        points.add(
                                            DrawingPoint(
                                                (change.position.x / w).coerceIn(0.0, 1.0),
                                                (change.position.y / h).coerceIn(0.0, 1.0),
                                            ),
                                        )
                                    }
                                    change.consume()
                                }
                                val draft = DrawingStroke(color, width, points.toList())
                                val snapshot = strokes.toList() + draft
                                strokes.clear()
                                strokes.addAll(snapshot)
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawStrokes(strokes) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in PEN_COLORS) {
                        Box(
                            Modifier.size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(penColor(c))
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    color = c
                                }
                                .padding(if (color == c) 6.dp else 0.dp),
                        ) {
                            if (color == c) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.85f)),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (w in PEN_WIDTHS) {
                        TextButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            width = w
                        }, modifier = Modifier.weight(1f)) {
                            Text(if (width == w) "● $w" else "$w")
                        }
                    }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Undo") }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            strokes.clear()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSave(strokes.toList())
                },
                modifier = Modifier.testTag("drawing-save"),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("drawing-cancel"),
            ) { Text("Cancel") }
        },
    )
}
