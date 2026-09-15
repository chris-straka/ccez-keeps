// Pure ranked search shared by both clients. Substring-based, no deps.
// Rank: title-prefix (0) > title-substring (1) > body match (2) >
// checklist-item / attachment-name / label-name match (3); ties break by
// recency (newest updatedAt first). Never mutates inputs. The Kotlin port
// in android/synclogic Search.kt must keep the same tiers and order.
import type { Note } from "../../shared/note.js";

export function rankNotes(
  notes: Note[],
  query: string,
  labelNames: Record<string, string> = {},
): Note[] {
  const q = query.trim().toLowerCase();
  if (q.length === 0) return [...notes];
  const scored: { note: Note; rank: number }[] = [];
  for (const note of notes) {
    const title = note.title.toLowerCase();
    let rank = -1;
    if (title.startsWith(q)) rank = 0;
    else if (title.includes(q)) rank = 1;
    else if (note.body.toLowerCase().includes(q)) rank = 2;
    else if (matchesExtras(note, q, labelNames)) rank = 3;
    if (rank >= 0) scored.push({ note, rank });
  }
  scored.sort(
    (a, b) => a.rank - b.rank || b.note.updatedAt - a.note.updatedAt,
  );
  return scored.map((s) => s.note);
}

/** True when checklist items, attachment names, or label names hit q. */
function matchesExtras(
  note: Note,
  q: string,
  labelNames: Record<string, string>,
): boolean {
  for (const item of note.checklist ?? []) {
    if (item.text.toLowerCase().includes(q)) return true;
  }
  for (const a of note.attachments ?? []) {
    if (a.name.toLowerCase().includes(q)) return true;
  }
  for (const id of note.labelIds ?? []) {
    if ((labelNames[id] ?? "").toLowerCase().includes(q)) return true;
  }
  return false;
}
