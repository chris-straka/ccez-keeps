// Frozen conflict rule for drawings: same last-write-wins on updatedAt as
// notes (shared/sync.ts), over Drawing ids. Total order, symmetric.
import type { Drawing } from "./drawing.js";

/**
 * Winner of two versions of the same drawing id. Never mutates inputs.
 * Newer updatedAt wins; equal timestamps break to the lexicographically
 * larger JSON so devices converge without coordination.
 */
export function pickDrawingWinner(local: Drawing, remote: Drawing): Drawing {
  if (local.id !== remote.id) throw new Error("pickDrawingWinner: id mismatch");
  if (remote.updatedAt !== local.updatedAt) {
    return remote.updatedAt > local.updatedAt ? remote : local;
  }
  return JSON.stringify(remote) >= JSON.stringify(local) ? remote : local;
}

/**
 * Merge two lists by id with last-write-wins. A newer tombstone beats an
 * older live drawing and vice versa. Returns a new array, no duplicates.
 */
export function mergeDrawingLists(a: Drawing[], b: Drawing[]): Drawing[] {
  const byId = new Map<string, Drawing>();
  for (const drawing of [...a, ...b]) {
    const existing = byId.get(drawing.id);
    byId.set(drawing.id, existing ? pickDrawingWinner(existing, drawing) : drawing);
  }
  return [...byId.values()];
}

/** Rows with updatedAt strictly greater than `since`. */
export function drawingsChangedSince(drawings: Drawing[], since: number): Drawing[] {
  return drawings.filter((d) => d.updatedAt > since);
}
