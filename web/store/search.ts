// Pure ranked search shared by both clients. Substring-based, no deps.
// Rank: title-prefix (0) > title-substring (1) > body match (2);
// ties break by recency (newest updatedAt first). Never mutates inputs.
import type { Note } from "../../shared/note.js";

export function rankNotes(notes: Note[], query: string): Note[] {
  const q = query.trim().toLowerCase();
  if (q.length === 0) return [...notes];
  const scored: { note: Note; rank: number }[] = [];
  for (const note of notes) {
    const title = note.title.toLowerCase();
    let rank = -1;
    if (title.startsWith(q)) rank = 0;
    else if (title.includes(q)) rank = 1;
    else if (note.body.toLowerCase().includes(q)) rank = 2;
    if (rank >= 0) scored.push({ note, rank });
  }
  scored.sort(
    (a, b) => a.rank - b.rank || b.note.updatedAt - a.note.updatedAt,
  );
  return scored.map((s) => s.note);
}
