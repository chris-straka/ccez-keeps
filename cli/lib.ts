// The `keeps` CLI's logic, kept free of process/stdio so tests can drive it
// against the Worker in-process (tests/cli.test.ts). An agent is just
// another enrolled device: it carries a device token and talks the same
// sync contract the phone does (contracts/api.md). Reads pull the whole
// table (one GET, since=0); writes post tombstones/upserts with a fresh
// updatedAt so last-write-wins lands them.
import { newNote, type Note } from "../shared/note.js";
import type { Label } from "../shared/label.js";

export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>;

export interface KeepsClient {
  base: string;
  token: string;
  fetch: FetchLike;
}

export interface Snapshot {
  notes: Note[];
  labels: Label[];
  cursor: number;
}

async function call<T>(c: KeepsClient, path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${c.token}`);
  if (init.body !== undefined) headers.set("Content-Type", "application/json");
  const res = await c.fetch(`${c.base}${path}`, { ...init, headers });
  if (!res.ok) {
    let detail = "";
    try {
      detail = ((await res.json()) as { error?: string }).error ?? "";
    } catch {
      // non-JSON error body: status alone is the message
    }
    throw new Error(`${init.method ?? "GET"} ${path} -> ${res.status}${detail ? ` ${detail}` : ""}`);
  }
  return (await res.json()) as T;
}

/** Every note and label the server has, tombstones included. */
export async function pull(c: KeepsClient): Promise<Snapshot> {
  const [n, l] = await Promise.all([
    call<{ notes: Note[]; cursor: number }>(c, "/api/notes?since=0"),
    call<{ labels: Label[]; cursor: number }>(c, "/api/labels?since=0"),
  ]);
  return { notes: n.notes, labels: l.labels.filter((x) => !x.deleted), cursor: n.cursor };
}

export async function push(
  c: KeepsClient,
  upserts: Note[],
  tombstones: Note[],
  since: number,
): Promise<number> {
  const out = await call<{ applied: number }>(c, "/api/notes/sync", {
    method: "POST",
    body: JSON.stringify({ upserts, tombstones, since }),
  });
  return out.applied;
}

export function labelName(labels: Label[], id: string): string | undefined {
  return labels.find((l) => l.id === id)?.name;
}

export function findLabel(labels: Label[], name: string): Label | undefined {
  const want = name.trim().toLowerCase();
  return labels.find((l) => l.name.trim().toLowerCase() === want);
}

export interface ListFilter {
  /** Include archived notes (default: live, unarchived only). */
  archived?: boolean;
  /** Only trashed notes. */
  trash?: boolean;
  /** Label name, case-insensitive. */
  label?: string;
}

/** Notes that match the filter, newest edit first. */
export function select(snap: Snapshot, f: ListFilter = {}): Note[] {
  const label = f.label === undefined ? undefined : findLabel(snap.labels, f.label);
  if (f.label !== undefined && !label) return [];
  return snap.notes
    .filter((n) => (f.trash ? n.deleted : !n.deleted))
    .filter((n) => f.trash || f.archived || !n.archived)
    .filter((n) => !label || n.labelIds.includes(label.id))
    .sort((a, b) => b.updatedAt - a.updatedAt);
}

/**
 * Resolve ids the way git resolves short hashes: an exact id or a unique
 * prefix. Throws naming the offender so a typo never trashes a neighbour.
 */
export function resolveIds(notes: Note[], given: string[]): Note[] {
  return given.map((g) => {
    const exact = notes.find((n) => n.id === g);
    if (exact) return exact;
    const hits = notes.filter((n) => n.id.startsWith(g));
    if (hits.length === 1) return hits[0]!;
    if (hits.length === 0) throw new Error(`no note matches ${g}`);
    throw new Error(`${g} is ambiguous: ${hits.map((n) => n.id.slice(0, 8)).join(", ")}`);
  });
}

/** Body plus checklist rows, the way the owner reads it. */
export function noteText(n: Note): string {
  const rows = (n.checklist ?? []).map((i) => `- [${i.checked ? "x" : " "}] ${i.text}`);
  return [n.body, ...rows].filter((s) => s.trim() !== "").join("\n");
}

export function firstLine(s: string): string {
  return s.split("\n").find((l) => l.trim() !== "")?.trim() ?? "";
}

function stamp(ms: number): string {
  const d = new Date(ms);
  const p = (x: number) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** One line per note: short id, time, flags, labels, title or first line. */
export function formatList(notes: Note[], labels: Label[]): string {
  return notes
    .map((n) => {
      const flags = [n.pinned ? "pin" : "", n.archived ? "arch" : "", n.deleted ? "trash" : ""]
        .filter(Boolean)
        .join(",");
      const tags = n.labelIds
        .map((id) => labelName(labels, id))
        .filter((x): x is string => !!x)
        .map((x) => `#${x}`)
        .join(" ");
      const head = n.title.trim() || firstLine(noteText(n)) || "(empty)";
      const att = n.attachments.length > 0 ? ` [${n.attachments.length} img]` : "";
      return [n.id.slice(0, 8), stamp(n.updatedAt), flags, tags, head + att]
        .filter((x) => x !== "")
        .join("  ");
    })
    .join("\n");
}

/** Markdown for a set of notes (what `export` and `show` print). */
export function formatMarkdown(notes: Note[], labels: Label[]): string {
  return notes
    .map((n) => {
      const tags = n.labelIds
        .map((id) => labelName(labels, id))
        .filter((x): x is string => !!x)
        .join(", ");
      const meta = [
        `id: ${n.id}`,
        `edited: ${stamp(n.updatedAt)}`,
        tags ? `labels: ${tags}` : "",
        n.archived ? "archived" : "",
        n.deleted ? "trashed" : "",
        n.attachments.length > 0 ? `images: ${n.attachments.length}` : "",
      ]
        .filter(Boolean)
        .join(" · ");
      const title = n.title.trim() || "(untitled)";
      return `## ${title}\n\n${meta}\n\n${noteText(n)}\n`;
    })
    .join("\n");
}

/** A tombstone for `n`, timestamped now so it wins the merge. */
export function tombstone(n: Note, now: number): Note {
  return { ...n, deleted: true, updatedAt: now };
}

export function archived(n: Note, now: number): Note {
  return { ...n, archived: true, updatedAt: now };
}

export interface NewNoteInput {
  title?: string;
  body: string;
  labelIds?: string[];
  color?: string;
}

export function makeNote(input: NewNoteInput, id: string, now: number, minOrder: number): Note {
  return newNote({
    id,
    title: input.title ?? "",
    body: input.body,
    labelIds: input.labelIds ?? [],
    color: input.color ?? "default",
    order: minOrder - 1,
    updatedAt: now,
  });
}

export function minOrder(notes: Note[]): number {
  let m = 0;
  for (const n of notes) if (!n.deleted && (n.order ?? 0) < m) m = n.order ?? 0;
  return m;
}
