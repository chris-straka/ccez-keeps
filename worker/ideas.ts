// Read-only ideas feed for mediaforge's daily intake (GET /api/ideas).
//
// The narrowest path that works: its own bearer credential, accepted on
// this one route and nowhere else. The Worker stores only the SHA-256 of
// that token (secret IDEAS_READER_SHA256). It is not a device token, so
// every other /api/* route rejects it, and this route accepts nothing but
// it. The response carries only notes labelled Ideas (any spelling
// isIdeasLabelName accepts), only their text: no attachments, drawings,
// reminders or other notes. Unset secret = 503, never open.
import { eq } from "drizzle-orm";
import type { BaseSQLiteDatabase } from "drizzle-orm/sqlite-core";
import { labels, notes, type LabelRow, type NoteRow } from "../db/schema.js";
import { isIdeasLabelName } from "../shared/label.js";
import { hashDeviceToken } from "./devices.js";
import { rowToNote, type NotesDb } from "./notes.js";

type AnyDb = BaseSQLiteDatabase<any, any, any, any>;

export interface Idea {
  id: string;
  title: string;
  /** Body text plus checklist rows ("- [ ] text"), as the owner wrote it. */
  text: string;
  /** Names of every label on the note (one of them is the ideas label). */
  labels: string[];
  archived: boolean;
  updatedAt: number;
}

export type IdeasAuth = { ok: true } | { ok: false; status: 401 | 503; message: string };

/** Constant-time compare of two hex digests of equal length. */
function sameDigest(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function checkIdeasReader(
  req: Request,
  readerHash: string | undefined,
): Promise<IdeasAuth> {
  if (!readerHash) return { ok: false, status: 503, message: "ideas reader not configured" };
  const header = req.headers.get("Authorization") ?? "";
  const match = /^Bearer (\S+)$/.exec(header);
  if (!match) return { ok: false, status: 401, message: "missing ideas reader token" };
  const hash = await hashDeviceToken(match[1] as string);
  if (!sameDigest(hash, readerHash.trim().toLowerCase())) {
    console.warn("[keeps] ideas reader denied: wrong token");
    return { ok: false, status: 401, message: "wrong ideas reader token" };
  }
  return { ok: true };
}

/** Live notes carrying an ideas label, changed after `since` (updatedAt ms). */
export async function listIdeas(db: NotesDb, since: number): Promise<Idea[]> {
  const q = db as unknown as AnyDb;
  const labelRows: LabelRow[] = await q.select().from(labels).where(eq(labels.deleted, 0));
  const names = new Map(labelRows.map((l) => [l.id, l.name]));
  const ideaIds = new Set(labelRows.filter((l) => isIdeasLabelName(l.name)).map((l) => l.id));
  if (ideaIds.size === 0) return [];
  const rows: NoteRow[] = await q.select().from(notes).where(eq(notes.deleted, 0));
  const ideas: Idea[] = [];
  for (const row of rows) {
    if (row.updatedAt <= since) continue;
    const note = rowToNote(row);
    if (!note.labelIds.some((id) => ideaIds.has(id))) continue;
    const checklist = (note.checklist ?? []).map((i) => `- [${i.checked ? "x" : " "}] ${i.text}`);
    ideas.push({
      id: note.id,
      title: note.title,
      text: [note.body, ...checklist].filter((s) => s.trim() !== "").join("\n"),
      labels: note.labelIds.map((id) => names.get(id)).filter((n): n is string => !!n),
      archived: note.archived,
      updatedAt: note.updatedAt,
    });
  }
  return ideas.sort((a, b) => a.updatedAt - b.updatedAt);
}
