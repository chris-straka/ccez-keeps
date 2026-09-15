// Lane C: D1 persistence for drawings. Same two-clocks contract as
// notes (see worker/notes.ts header) with an independent `_draw_seq`
// dissemination cursor. Strokes live as a JSON string in the row.
import { eq, gt, inArray, sql } from "drizzle-orm";
import type { BaseSQLiteDatabase } from "drizzle-orm/sqlite-core";
import { drawSeq, drawings, type DrawingRow } from "../db/schema.js";
import { isDrawing, type Drawing } from "../shared/drawing.js";
import { mergeDrawingLists, pickDrawingWinner } from "../shared/drawing-sync.js";
import type { NotesDb } from "./notes.js";

type AnyDb = BaseSQLiteDatabase<any, any, any, any>;

function base(db: NotesDb): AnyDb {
  return db as unknown as AnyDb;
}

export function rowToDrawing(row: DrawingRow): Drawing {
  const drawing = {
    id: row.id,
    strokes: JSON.parse(row.strokes) as unknown,
    updatedAt: row.updatedAt,
    deleted: row.deleted === 1,
  };
  if (!isDrawing(drawing)) throw new Error(`rowToDrawing: corrupt row ${row.id}`);
  return drawing;
}

export function drawingToRow(drawing: Drawing, seq: number): DrawingRow {
  return {
    id: drawing.id,
    strokes: JSON.stringify(drawing.strokes),
    updatedAt: drawing.updatedAt,
    deleted: drawing.deleted ? 1 : 0,
    seq,
  };
}

/** Reserve n consecutive draw-seq values; returns the first. */
async function nextSeqRange(db: NotesDb, n: number): Promise<number> {
  const q = base(db);
  await q.insert(drawSeq).values({ id: 1, v: 0 }).onConflictDoNothing();
  const bumped: Array<{ v: number }> = await q
    .update(drawSeq)
    .set({ v: sql`v + ${n}` })
    .where(eq(drawSeq.id, 1))
    .returning({ v: drawSeq.v });
  const max = bumped[0]?.v ?? n;
  return max - n + 1;
}

/** Delete + reinsert each row so every write mints a fresh seq. */
async function writeRows(
  db: NotesDb,
  rows: Array<{ drawing: Drawing; seq: number }>,
): Promise<void> {
  if (rows.length === 0) return;
  const q = base(db);
  const stmts = rows.flatMap(({ drawing, seq }) => [
    q.delete(drawings).where(eq(drawings.id, drawing.id)),
    q.insert(drawings).values(drawingToRow(drawing, seq)),
  ]);
  const batched = q as unknown as { batch?: (s: unknown[]) => Promise<unknown> };
  if (typeof batched.batch === "function") {
    await batched.batch(stmts);
  } else {
    for (const stmt of stmts) await stmt;
  }
}

export interface DrawingDeltas {
  drawings: Drawing[];
  cursor: number;
}

/** Rows written after `since`, in write order, plus the table watermark. */
export async function getDrawingDeltas(db: NotesDb, since: number): Promise<DrawingDeltas> {
  const q = base(db);
  const rows: DrawingRow[] = await q
    .select()
    .from(drawings)
    .where(gt(drawings.seq, since))
    .orderBy(drawings.seq);
  const peak: Array<{ m: number | null }> = await q
    .select({ m: sql<number | null>`max(${drawings.seq})` })
    .from(drawings);
  return { drawings: rows.map(rowToDrawing), cursor: peak[0]?.m ?? 0 };
}

export async function getDrawingsByIds(
  db: NotesDb,
  ids: string[],
): Promise<Map<string, Drawing>> {
  if (ids.length === 0) return new Map();
  const rows: DrawingRow[] = await base(db)
    .select()
    .from(drawings)
    .where(inArray(drawings.id, ids));
  return new Map(
    rows.map((r) => {
      const drawing = rowToDrawing(r);
      return [drawing.id, drawing] as const;
    }),
  );
}

export interface DrawingSyncResult {
  applied: number;
  deltas: Drawing[];
  cursor: number;
}

/**
 * Full server sync for drawings: identical semantics to notes applySync
 * (dedupe, LWW by updatedAt, fresh seq, deltas + watermark).
 */
export async function applyDrawingsSync(
  db: NotesDb,
  input: { upserts: Drawing[]; tombstones: Drawing[]; since: number },
): Promise<DrawingSyncResult> {
  const incoming = mergeDrawingLists([...input.upserts, ...input.tombstones], []);
  const ids = [...new Set(incoming.map((d) => d.id))];
  const existing = await getDrawingsByIds(db, ids);
  const toWrite: Drawing[] = [];
  const serverWins: Drawing[] = [];
  let applied = 0;
  for (const drawing of incoming) {
    const current = existing.get(drawing.id);
    if (!current) {
      toWrite.push(drawing);
      applied += 1;
      existing.set(drawing.id, drawing);
      continue;
    }
    const winner = pickDrawingWinner(current, drawing);
    if (winner === drawing) {
      toWrite.push(drawing);
      applied += 1;
      existing.set(drawing.id, drawing);
    } else {
      serverWins.push(current);
    }
  }
  if (toWrite.length > 0) {
    const firstSeq = await nextSeqRange(db, toWrite.length);
    await writeRows(
      db,
      toWrite.map((drawing, i) => ({ drawing, seq: firstSeq + i })),
    );
  }
  const { drawings: deltas, cursor } = await getDrawingDeltas(db, input.since);
  return { applied, deltas: mergeDrawingLists(deltas, serverWins), cursor };
}
