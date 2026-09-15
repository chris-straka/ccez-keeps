// Trash retention: tombstones older than TRASH_RETENTION_MS are hard-deleted
// by the nightly cron (wrangler.jsonc triggers -> scheduled() in index.ts).
// Sync contract note: a client offline longer than the retention window
// that later pushes a stale live copy resurrects that row as a fresh
// update — standard tombstone-expiry semantics, same as Keep's trash.
import { and, eq, lt } from "drizzle-orm";
import type { BaseSQLiteDatabase } from "drizzle-orm/sqlite-core";
import { drawings, labels, notes } from "../db/schema.js";
import type { NotesDb } from "./notes.js";

export const TRASH_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;

export interface PurgeCounts {
  notes: number;
  drawings: number;
  labels: number;
}

type AnyDb = BaseSQLiteDatabase<any, any, any, any>;

export async function purgeTrash(
  db: NotesDb,
  nowMs: number,
  maxAgeMs: number = TRASH_RETENTION_MS,
): Promise<PurgeCounts> {
  const base = db as unknown as AnyDb;
  const cutoff = nowMs - maxAgeMs;
  const expired = (table: typeof notes | typeof drawings | typeof labels) =>
    and(eq(table.deleted, 1), lt(table.updatedAt, cutoff));
  const goneNotes = await base.delete(notes).where(expired(notes)).returning({ id: notes.id });
  const goneDrawings = await base
    .delete(drawings)
    .where(expired(drawings))
    .returning({ id: drawings.id });
  const goneLabels = await base
    .delete(labels)
    .where(expired(labels))
    .returning({ id: labels.id });
  return { notes: goneNotes.length, drawings: goneDrawings.length, labels: goneLabels.length };
}
