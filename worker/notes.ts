// Lane C: D1 persistence seam. All queries run through this module so the
// route layer (index.ts) and tests share one mapping + merge path.
// Accepts either the D1 driver (prod) or bun:sqlite driver (tests) — both
// expose the same select/insert/update/delete builders.
//
// Two clocks, two jobs (frozen contract):
// - updatedAt (client wall clock): decides conflicts, last-write-wins.
// - seq (server counter): decides dissemination. Every write deletes +
//   reinserts to mint a fresh seq, so a late-arriving old-timestamp row is
//   still newer than every outstanding cursor and can never go unseen.
import { eq, gt, inArray, sql } from "drizzle-orm";
import type { BunSQLiteDatabase } from "drizzle-orm/bun-sqlite";
import type { DrizzleD1Database } from "drizzle-orm/d1";
import type { BaseSQLiteDatabase } from "drizzle-orm/sqlite-core";
import { notes, syncSeq, type NoteRow } from "../db/schema.js";
import { isNote, type Note } from "../shared/note.js";
import { mergeNoteLists, pickWinner } from "../shared/sync.js";

export type NotesDb = BunSQLiteDatabase | DrizzleD1Database;

/**
 * Both drivers share one declaration of every query builder on the base
 * class; calling through it avoids union-signature friction. Results are
 * annotated at each call site.
 */
type AnyDb = BaseSQLiteDatabase<any, any, any, any>;

function base(db: NotesDb): AnyDb {
  return db as unknown as AnyDb;
}

function parseLabelIds(raw: unknown, id: string): string[] {
  // Tolerant of pre-migration rows (missing column) and NULL: they carry
  // no labels. Anything else malformed is corruption, not a default.
  if (raw === undefined || raw === null) return [];
  if (typeof raw !== "string") throw new Error(`rowToNote: corrupt row ${id}`);
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error(`rowToNote: corrupt row ${id}`);
  }
  if (!Array.isArray(parsed) || !parsed.every((v) => typeof v === "string")) {
    throw new Error(`rowToNote: corrupt row ${id}`);
  }
  return parsed;
}

function parseRepeat(raw: unknown, id: string): "daily" | "weekly" | null {
  // Tolerant of pre-migration rows (missing column): they fire once.
  if (raw === undefined || raw === null) return null;
  if (raw === "daily" || raw === "weekly") return raw;
  throw new Error(`rowToNote: corrupt row ${id}`);
}

export function rowToNote(row: NoteRow): Note {
  const note = {
    id: row.id,
    title: row.title,
    body: row.body,
    color: row.color,
    pinned: row.pinned === 1,
    archived: row.archived === 1,
    updatedAt: row.updatedAt,
    deleted: row.deleted === 1,
    labelIds: parseLabelIds(row.labelIds, row.id),
    reminderAt: row.reminderAt ?? null,
    repeat: parseRepeat(row.repeat, row.id),
  };
  if (!isNote(note)) throw new Error(`rowToNote: corrupt row ${row.id}`);
  return note;
}

export function noteToRow(note: Note, seq: number): NoteRow {
  return {
    id: note.id,
    title: note.title,
    body: note.body,
    color: note.color,
    pinned: note.pinned ? 1 : 0,
    archived: note.archived ? 1 : 0,
    updatedAt: note.updatedAt,
    deleted: note.deleted ? 1 : 0,
    labelIds: JSON.stringify(note.labelIds),
    reminderAt: note.reminderAt,
    repeat: note.repeat,
    seq,
  };
}

/** Reserve n consecutive seq values; returns the first. */
async function nextSeqRange(db: NotesDb, n: number): Promise<number> {
  const q = base(db);
  await q.insert(syncSeq).values({ id: 1, v: 0 }).onConflictDoNothing();
  const bumped: Array<{ v: number }> = await q
    .update(syncSeq)
    .set({ v: sql`v + ${n}` })
    .where(eq(syncSeq.id, 1))
    .returning({ v: syncSeq.v });
  const max = bumped[0]?.v ?? n;
  return max - n + 1;
}

/** Delete + reinsert each row so every write mints a fresh seq. */
async function writeRows(db: NotesDb, rows: Array<{ note: Note; seq: number }>): Promise<void> {
  if (rows.length === 0) return;
  const q = base(db);
  const stmts = rows.flatMap(({ note, seq }) => [
    q.delete(notes).where(eq(notes.id, note.id)),
    q.insert(notes).values(noteToRow(note, seq)),
  ]);
  // D1 executes the whole write set atomically; other drivers run it
  // statement by statement (tests only — no concurrent writers there).
  const batched = q as unknown as { batch?: (s: unknown[]) => Promise<unknown> };
  if (typeof batched.batch === "function") {
    await batched.batch(stmts);
  } else {
    for (const stmt of stmts) await stmt;
  }
}

export interface Deltas {
  notes: Note[];
  cursor: number;
}

/** Rows written after `since`, in write order, plus the table watermark. */
export async function getDeltas(db: NotesDb, since: number): Promise<Deltas> {
  const q = base(db);
  const rows: NoteRow[] = await q
    .select()
    .from(notes)
    .where(gt(notes.seq, since))
    .orderBy(notes.seq);
  const peak: Array<{ m: number | null }> = await q
    .select({ m: sql<number | null>`max(${notes.seq})` })
    .from(notes);
  return { notes: rows.map(rowToNote), cursor: peak[0]?.m ?? 0 };
}

export async function getByIds(db: NotesDb, ids: string[]): Promise<Map<string, Note>> {
  if (ids.length === 0) return new Map();
  const rows: NoteRow[] = await base(db)
    .select()
    .from(notes)
    .where(inArray(notes.id, ids));
  return new Map(
    rows.map((r) => {
      const note = rowToNote(r);
      return [note.id, note] as const;
    }),
  );
}

export interface SyncPlan {
  toWrite: Note[];
  /** Stored versions that beat the client's copy — must reach the client. */
  serverWins: Note[];
  /** Incoming rows that became the stored version. */
  applied: number;
}

/** Pure per-id LWW between stored rows and one incoming batch. */
export function computeSyncPlan(existing: Map<string, Note>, incoming: Note[]): SyncPlan {
  const toWrite: Note[] = [];
  const serverWins: Note[] = [];
  let applied = 0;
  for (const note of incoming) {
    const current = existing.get(note.id);
    if (!current) {
      toWrite.push(note);
      applied += 1;
      existing.set(note.id, note);
      continue;
    }
    const winner = pickWinner(current, note);
    if (winner === note) {
      toWrite.push(note);
      applied += 1;
      existing.set(note.id, note);
    } else {
      serverWins.push(current);
    }
  }
  return { toWrite, serverWins, applied };
}

export interface SyncResult {
  applied: number;
  deltas: Note[];
  cursor: number;
}

/**
 * Full server sync: dedupe batch, LWW against stored rows (by updatedAt),
 * persist winners with fresh seq, return deltas (rows newer than `since`
 * plus any stored version that beat the client's copy) and the watermark.
 */
export async function applySync(
  db: NotesDb,
  input: { upserts: Note[]; tombstones: Note[]; since: number },
): Promise<SyncResult> {
  // Intra-batch duplicates collapse by the same LWW rule (deterministic).
  const incoming = mergeNoteLists([...input.upserts, ...input.tombstones], []);
  const ids = [...new Set(incoming.map((n) => n.id))];
  const existing = await getByIds(db, ids);
  const { toWrite, serverWins, applied } = computeSyncPlan(existing, incoming);
  if (toWrite.length > 0) {
    const firstSeq = await nextSeqRange(db, toWrite.length);
    await writeRows(
      db,
      toWrite.map((note, i) => ({ note, seq: firstSeq + i })),
    );
  }
  const { notes, cursor } = await getDeltas(db, input.since);
  return { applied, deltas: mergeNoteLists(notes, serverWins), cursor };
}
