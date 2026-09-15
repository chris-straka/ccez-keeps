package dev.cstraka.keeps.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Kotlin port of the labels amendment (contracts/api.md, contracts/data.md).
 * Field names and JSON keys match the web/Worker shapes exactly; labels sync
 * on their own lane with an independent seq cursor, same LWW rule as notes.
 */
@Serializable
data class Label(
    val id: String,
    val name: String = "",
    val color: String = "default",
    /** Unix epoch milliseconds, client-assigned. Last-write-wins key. */
    val updatedAt: Long = 0L,
    /** Soft delete. True = tombstone; tombstones sync like labels. */
    val deleted: Boolean = false,
) {
    init {
        require(id.isNotEmpty()) { "Label id must not be empty" }
    }
}

object LabelLimits {
    const val MAX_NAME_LENGTH = 120
    const val MAX_COLOR_LENGTH = 32
}

/** Client-side validation mirroring the server's label checks. */
fun isLabel(label: Label): Boolean =
    label.id.isNotEmpty() &&
        label.name.isNotEmpty() &&
        label.name.length <= LabelLimits.MAX_NAME_LENGTH &&
        label.color.length <= LabelLimits.MAX_COLOR_LENGTH

fun newLabel(
    id: String,
    name: String = "",
    color: String = "default",
    updatedAt: Long = System.currentTimeMillis(),
    deleted: Boolean = false,
): Label = Label(id, name, color, updatedAt, deleted)

private val labelJsonCodec = Json { encodeDefaults = true }

fun labelJson(label: Label): String = labelJsonCodec.encodeToString(label)

/**
 * Winner of two versions of the same label id. Never mutates inputs. Total
 * order, symmetric in argument order: newer updatedAt wins, ties break to
 * the lexicographically larger JSON (same rule as notes and drawings).
 */
fun pickLabelWinner(local: Label, remote: Label): Label {
    require(local.id == remote.id) { "pickLabelWinner: id mismatch" }
    if (remote.updatedAt != local.updatedAt) {
        return if (remote.updatedAt > local.updatedAt) remote else local
    }
    return if (labelJson(remote) >= labelJson(local)) remote else local
}

/** Merge two lists by id with last-write-wins. No duplicates, no mutation. */
fun mergeLabelLists(a: List<Label>, b: List<Label>): List<Label> {
    val byId = LinkedHashMap<String, Label>()
    for (label in a + b) {
        val existing = byId[label.id]
        byId[label.id] = if (existing != null) pickLabelWinner(existing, label) else label
    }
    return byId.values.toList()
}

/** Rows with updatedAt strictly greater than [since] (delta-pull cursor). */
fun labelsChangedSince(labels: List<Label>, since: Long): List<Label> =
    labels.filter { it.updatedAt > since }
