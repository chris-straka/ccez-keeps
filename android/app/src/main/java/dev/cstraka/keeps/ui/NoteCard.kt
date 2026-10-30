package dev.cstraka.keeps.ui

import androidx.compose.foundation.Image
import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.cstraka.keeps.data.attachmentBitmap
import dev.cstraka.keeps.sync.Attachment
import dev.cstraka.keeps.sync.ChecklistItem
import dev.cstraka.keeps.sync.Drawing
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.labelTint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DRAWING_REF_RE = Regex("""^!\[drawing\]\(([^)\s]+)\)$""")

private sealed interface BodyBlock {
    data class Text(val text: AnnotatedString) : BodyBlock
    data class Drawing(val id: String) : BodyBlock
}

/**
 * Note body with drawing markers resolved to thumbnails. Marker lines
 * become canvases (web parity: same strokes, same 3:2 scale); text lines
 * render through the shared Markdown subset. Unknown ids mean the drawing
 * has not synced down yet.
 */
@Composable
private fun NoteBody(body: String, drawings: Map<String, Drawing>) {
    val blocks = remember(body, drawings) {
        val out = mutableListOf<BodyBlock>()
        val chunk = StringBuilder()
        fun flushText() {
            if (chunk.isNotEmpty()) {
                out.add(BodyBlock.Text(richText(chunk.toString().removeSuffix("\n"))))
                chunk.clear()
            }
        }
        for (line in body.split("\n")) {
            val ref = DRAWING_REF_RE.matchEntire(line.trim())?.groupValues?.get(1)
            if (ref != null) {
                flushText()
                out.add(BodyBlock.Drawing(ref))
            } else {
                chunk.append(line).append("\n")
            }
        }
        flushText()
        out.toList()
    }
    Column {
        for (block in blocks) {
            when (block) {
                is BodyBlock.Text -> Text(block.text)
                is BodyBlock.Drawing -> {
                    val strokes = drawings[block.id]?.takeUnless { it.deleted }?.strokes
                    if (strokes != null) {
                        DrawingThumbnail(strokes)
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Text(
                            "[drawing — not synced yet]",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
            }
        }
    }
}

/** Short display form for a reminder fire time ("Jun 3, 4:30 PM"). */
fun formatReminder(at: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(at))

private const val CHECKLIST_PREVIEW_LIMIT = 5
private const val ATTACHMENT_PREVIEW_LIMIT = 3

/** Read-only item rows for the card; hidden when the note has no checklist. */
@Composable
private fun ChecklistPreview(checklist: List<ChecklistItem>?) {
    if (checklist == null) return
    Column {
        for (item in checklist.take(CHECKLIST_PREVIEW_LIMIT)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.checked) "☑" else "☐",
                    color = Color.Gray,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item.text.ifBlank { " " },
                    fontSize = 13.sp,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    color = if (item.checked) Color.Gray else Color.Unspecified,
                )
            }
        }
        val extra = checklist.size - CHECKLIST_PREVIEW_LIMIT
        if (extra > 0) Text("+$extra more", fontSize = 11.sp, color = Color.Gray)
    }
}

/** Thumbnail strip for the card; hidden when there are no attachments. */
@Composable
private fun AttachmentStrip(attachments: List<Attachment>) {
    if (attachments.isEmpty()) return
    val shown = attachments.take(ATTACHMENT_PREVIEW_LIMIT)
    Column {
        for (a in shown) {
            val bitmap = remember(a.thumbUrl) { attachmentBitmap(a.thumbUrl)?.asImageBitmap() }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = a.name,
                    contentScale = ContentScale.Crop,
                    modifier = if (shown.size == 1) {
                        Modifier.fillMaxWidth().heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(6.dp))
                    } else {
                        Modifier.size(64.dp).clip(RoundedCornerShape(6.dp))
                    },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        val extra = attachments.size - ATTACHMENT_PREVIEW_LIMIT
        if (extra > 0) Text("+$extra more", fontSize = 11.sp, color = Color.Gray)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun NoteCard(
    note: Note,
    drawings: Map<String, Drawing>,
    labels: List<Label> = emptyList(),
    filter: NoteFilter,
    dark: Boolean = true,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    /** Tap on the card's title: opens with the caret in the title. */
    onOpenTitle: () -> Unit = onOpen,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    onDragStart: (Note) -> Unit = {},
) {
    val attached = remember(note.labelIds, labels) {
        note.labelIds.mapNotNull { id -> labels.firstOrNull { it.id == id } }
    }
    val dragTile = noteColor(note.color, dark).copy(alpha = 0.85f)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(noteColor(note.color, dark))
            // Long-press starts a system drag (the node detects it and
            // calls this factory for the payload; the drop bar appears via
            // onDragStart). Plain tap still opens the editor below. The
            // drag shadow is drawn here, a plain tile in the note's colour:
            // the default one records the card into a layer, and cards the
            // grid reused after a scroll came back drawing only their
            // background (owner, 2026-10-30).
            .dragAndDropSource(drawDragDecoration = {
                drawRoundRect(
                    color = dragTile,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }, transferData = {
                onDragStart(note)
                DragAndDropTransferData(
                    ClipData.newPlainText("Keeps note", note.id),
                )
            })
            .combinedClickable(onClick = onOpen),
    ) {
        // No animateContentSize here: lazy grids reuse card nodes while
        // scrolling, and a size animation left over from the previous
        // occupant mis-measures the staggered lanes (cards vanish until
        // the next layout pass).
        Column(Modifier.padding(12.dp)) {
            if (note.title.isNotBlank()) {
                Text(
                    note.title,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenTitle),
                )
                Spacer(Modifier.height(4.dp))
            }
            AttachmentStrip(note.attachments)
            if (note.checklist != null) {
                ChecklistPreview(note.checklist)
            } else if (note.body.isNotBlank()) {
                NoteBody(note.body, drawings)
            }
            if (attached.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (lab in attached) {
                        val tint = Color(labelTint(lab.color, dark))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, tint, RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(lab.name, fontSize = 11.sp, color = tint)
                        }
                    }
                }
            }
            note.reminderAt?.let { at ->
                val overdue = at < System.currentTimeMillis()
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Notifications, contentDescription = null,
                        tint = if (overdue) Color(0xFFEF9A9A) else Color.Gray,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        formatReminder(at) +
                            if (overdue) " • Overdue" else "" +
                            when (note.repeat) {
                                "daily" -> " • Daily"
                                "weekly" -> " • Weekly"
                                else -> ""
                            },
                        fontSize = 11.sp,
                        color = if (overdue) Color(0xFFEF9A9A) else Color.Gray,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (filter != NoteFilter.TRASH) {
                    IconButton(onClick = onPin, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.PushPin, contentDescription = if (note.pinned) "Unpin" else "Pin",
                            tint = if (note.pinned) MaterialTheme.colorScheme.onSurface else Color.Gray)
                    }
                    IconButton(onClick = onArchive, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (note.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                            contentDescription = "Archive",
                        )
                    }
                    IconButton(onClick = onTrash, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                } else {
                    TextButton(onClick = onRestore) { Text("Restore") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDeleteForever) { Text("Delete forever") }
                }
            }
        }
    }
}
