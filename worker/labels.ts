// Lane C: D1 persistence for label definitions. Same two-clocks contract
// as notes (see worker/notes.ts header) with an independent `_label_seq`
// dissemination cursor.
import { eq, gt, inArray, sql } from "drizzle-orm";
import type { BaseSQLiteDatabase } from "drizzle-orm/sqlite-core";
import { labelSeq, labels, type LabelRow } from "../db/schema.js";
import { isLabel, mergeLabelLists, pickLabelWinner, type Label } from "../shared/label.js";
import type { NotesDb } from "./notes.js";

type AnyDb = BaseSQLiteDatabase<any, any, any, any>;

function base(db: NotesDb): AnyDb {
  return db as unknown as AnyDb;
}

export function rowToLabel(row: LabelRow): Label {
  const label = {
    id: row.id,
    name: row.name,
    color: row.color,
    updatedAt: row.updatedAt,
    deleted: row.deleted === 1,
  };
  if (!isLabel(label)) throw new Error(`rowToLabel: corrupt row ${row.id}`);
  return label;
}

export function labelToRow(label: Label, seq: number): LabelRow {
  return {
    id: label.id,
    name: label.name,
    color: label.color,
    updatedAt: label.updatedAt,
    deleted: label.deleted ? 1 : 0,
    seq,
  };
}

/** Reserve n consecutive label-seq values; returns the first. */
async function nextSeqRange(db: NotesDb, n: number): Promise<number> {
  const q = base(db);
  await q.insert(labelSeq).values({ id: 1, v: 0 }).onConflictDoNothing();
  const bumped: Array<{ v: number }> = await q
    .update(labelSeq)
    .set({ v: sql`v + ${n}` })
    .where(eq(labelSeq.id, 1))
    .returning({ v: labelSeq.v });
  const max = bumped[0]?.v ?? n;
  return max - n + 1;
}

/** Delete + reinsert each row so every write mints a fresh seq. */
async function writeRows(
  db: NotesDb,
  rows: Array<{ label: Label; seq: number }>,
): Promise<void> {
  if (rows.length === 0) return;
  const q = base(db);
  const stmts = rows.flatMap(({ label, seq }) => [
    q.delete(labels).where(eq(labels.id, label.id)),
    q.insert(labels).values(labelToRow(label, seq)),
  ]);
  const batched = q as unknown as { batch?: (s: unknown[]) => Promise<unknown> };
  if (typeof batched.batch === "function") {
    await batched.batch(stmts);
  } else {
    for (const stmt of stmts) await stmt;
  }
}

export interface LabelDeltas {
  labels: Label[];
  cursor: number;
}

/** Rows written after `since`, in write order, plus the table watermark. */
export async function getLabelDeltas(db: NotesDb, since: number): Promise<LabelDeltas> {
  const q = base(db);
  const rows: LabelRow[] = await q
    .select()
    .from(labels)
    .where(gt(labels.seq, since))
    .orderBy(labels.seq);
  const peak: Array<{ m: number | null }> = await q
    .select({ m: sql<number | null>`max(${labels.seq})` })
    .from(labels);
  return { labels: rows.map(rowToLabel), cursor: peak[0]?.m ?? 0 };
}

export async function getLabelsByIds(
  db: NotesDb,
  ids: string[],
): Promise<Map<string, Label>> {
  if (ids.length === 0) return new Map();
  const rows: LabelRow[] = await base(db)
    .select()
    .from(labels)
    .where(inArray(labels.id, ids));
  return new Map(
    rows.map((r) => {
      const label = rowToLabel(r);
      return [label.id, label] as const;
    }),
  );
}

export interface LabelSyncResult {
  applied: number;
  deltas: Label[];
  cursor: number;
}

/**
 * Full server sync for labels: identical semantics to notes applySync
 * (dedupe, LWW by updatedAt, fresh seq, deltas + watermark).
 */
export async function applyLabelsSync(
  db: NotesDb,
  input: { upserts: Label[]; tombstones: Label[]; since: number },
): Promise<LabelSyncResult> {
  const incoming = mergeLabelLists([...input.upserts, ...input.tombstones], []);
  const ids = [...new Set(incoming.map((l) => l.id))];
  const existing = await getLabelsByIds(db, ids);
  const toWrite: Label[] = [];
  const serverWins: Label[] = [];
  let applied = 0;
  for (const label of incoming) {
    const current = existing.get(label.id);
    if (!current) {
      toWrite.push(label);
      applied += 1;
      existing.set(label.id, label);
      continue;
    }
    const winner = pickLabelWinner(current, label);
    if (winner === label) {
      toWrite.push(label);
      applied += 1;
      existing.set(label.id, label);
    } else {
      serverWins.push(current);
    }
  }
  if (toWrite.length > 0) {
    const firstSeq = await nextSeqRange(db, toWrite.length);
    await writeRows(
      db,
      toWrite.map((label, i) => ({ label, seq: firstSeq + i })),
    );
  }
  const { labels: deltas, cursor } = await getLabelDeltas(db, input.since);
  return { applied, deltas: mergeLabelLists(deltas, serverWins), cursor };
}
