package dev.cstraka.keeps.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note

/**
 * Drop slot for a dragged note card: Archive, Trash, or one label.
 */
sealed interface NoteDropTarget {
    data object Archive : NoteDropTarget
    data object Trash : NoteDropTarget
    data class LabelDrop(val labelId: String) : NoteDropTarget
}

/**
 * Routes a card drop through the same mutations as the card buttons:
 * archive/trash go to their callbacks, a label goes through the
 * label-save callback. Unknown or already-attached labels are no-ops.
 */
fun applyNoteDrop(
    target: NoteDropTarget,
    note: Note,
    labels: List<Label>,
    onArchive: (Note) -> Unit,
    onTrash: (Note) -> Unit,
    onAddLabel: (Note, Label) -> Unit,
) {
    when (target) {
        NoteDropTarget.Archive -> onArchive(note)
        NoteDropTarget.Trash -> onTrash(note)
        is NoteDropTarget.LabelDrop -> {
            val lab = labels.firstOrNull { it.id == target.labelId } ?: return
            if (lab.id !in note.labelIds) onAddLabel(note, lab)
        }
    }
}

/**
 * Filing bar shown while a note card is dragged. Public for the
 * drag-to-file instrumented test; the app only uses it through
 * NotesScreen.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NoteDropTargets(
    labels: List<Label>,
    onDrop: (NoteDropTarget) -> Unit,
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        modifier = modifier.testTag("DropTargets"),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            DropChip(
                target = NoteDropTarget.Archive,
                icon = Icons.Filled.Archive,
                text = "Archive",
                contentDescription = "Drop on Archive",
                onDrop = onDrop,
                onDragEnd = onCancel,
            )
            DropChip(
                target = NoteDropTarget.Trash,
                icon = Icons.Filled.Delete,
                text = "Trash",
                contentDescription = "Drop on Trash",
                onDrop = onDrop,
                onDragEnd = onCancel,
            )
            for (lab in labels) {
                DropChip(
                    target = NoteDropTarget.LabelDrop(lab.id),
                    icon = Icons.Filled.Label,
                    text = lab.name,
                    contentDescription = "Drop on label ${lab.name}",
                    onDrop = onDrop,
                    onDragEnd = onCancel,
                )
            }
            // Dismisses the bar when the drag is cancelled outside a target.
            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Hide drop targets",
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DropChip(
    target: NoteDropTarget,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    contentDescription: String,
    onDrop: (NoteDropTarget) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    var hovered by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (hovered) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { true },
            target = object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    hovered = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    hovered = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    hovered = false
                    onDragEnd()
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    hovered = false
                    onDrop(target)
                    return true
                }
            },
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text)
        }
    }
}
