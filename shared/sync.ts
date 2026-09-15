// Frozen conflict rule (Phase 0): last-write-wins on updatedAt.
// Tiebreak: lexicographically larger id wins (deterministic across devices).
// Shared by lane B (local merge of server deltas) and lane C (server merge
// of client upserts) so both sides converge identically.

import type { Note } from "./note.js";

/**
 * Winner of two versions of the same note id. Never mutates inputs.
 * Total order, symmetric in argument order: newer updatedAt wins; on
 * equal timestamps the lexicographically larger JSON wins, so two
 * devices holding the same two versions converge without coordination.
 */
export function pickWinner(local: Note, remote: Note): Note {
  if (local.id !== remote.id) throw new Error("pickWinner: id mismatch");
  if (remote.updatedAt !== local.updatedAt) {
    return remote.updatedAt > local.updatedAt ? remote : local;
  }
  return JSON.stringify(remote) >= JSON.stringify(local) ? remote : local;
}

/**
 * Merge two lists by id with last-write-wins. A newer tombstone
 * (deleted: true) beats an older live note and vice versa — deletion is
 * just another write. Returns a new array, no duplicates, no mutation.
 */
export function mergeNoteLists(a: Note[], b: Note[]): Note[] {
  const byId = new Map<string, Note>();
  for (const note of [...a, ...b]) {
    const existing = byId.get(note.id);
    byId.set(note.id, existing ? pickWinner(existing, note) : note);
  }
  return [...byId.values()];
}

/** Rows with updatedAt strictly greater than `since` (delta-pull cursor). */
export function changedSince(notes: Note[], since: number): Note[] {
  return notes.filter((n) => n.updatedAt > since);
}
