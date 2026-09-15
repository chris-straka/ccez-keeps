// SERVER-MODELS suite: Note.labelIds/reminderAt validation + labels lane,
// through real HTTP (Hono app.request) against in-memory SQLite.
// DDL mirrors db/migrations 0000..0005 (incl. notes.labelIds/reminderAt,
// labels, _label_seq).
import { beforeEach, describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { isLabel, mergeLabelLists, pickLabelWinner, type Label } from "../shared/label.js";
import { isNote, newNote, type Note } from "../shared/note.js";
import { createApp } from "../worker/index.js";
import type { NotesDb } from "../worker/notes.js";

const DDL = `CREATE TABLE notes (
  id text PRIMARY KEY NOT NULL,
  title text DEFAULT '' NOT NULL,
  body text DEFAULT '' NOT NULL,
  color text DEFAULT 'default' NOT NULL,
  pinned integer DEFAULT 0 NOT NULL,
  archived integer DEFAULT 0 NOT NULL,
  updatedAt integer DEFAULT 0 NOT NULL,
  deleted integer DEFAULT 0 NOT NULL,
  labelIds text DEFAULT '[]' NOT NULL,
  reminderAt integer,
  repeat text,
  seq integer DEFAULT 0 NOT NULL
);
CREATE TABLE _sync_seq (
  id integer PRIMARY KEY NOT NULL,
  v integer DEFAULT 0 NOT NULL
);
CREATE TABLE device_tokens (
  id text PRIMARY KEY NOT NULL,
  tokenHash text NOT NULL UNIQUE,
  deviceName text DEFAULT '' NOT NULL,
  createdAt integer DEFAULT 0 NOT NULL,
  lastSeenAt integer DEFAULT 0 NOT NULL,
  revoked integer DEFAULT 0 NOT NULL
);
CREATE TABLE enroll_codes (
  id text PRIMARY KEY NOT NULL,
  codeHash text NOT NULL UNIQUE,
  createdAt integer DEFAULT 0 NOT NULL,
  used integer DEFAULT 0 NOT NULL
);
CREATE TABLE drawings (
  id text PRIMARY KEY NOT NULL,
  strokes text DEFAULT '[]' NOT NULL,
  updatedAt integer DEFAULT 0 NOT NULL,
  deleted integer DEFAULT 0 NOT NULL,
  seq integer DEFAULT 0 NOT NULL
);
CREATE TABLE _draw_seq (
  id integer PRIMARY KEY NOT NULL,
  v integer DEFAULT 0 NOT NULL
);
CREATE TABLE labels (
  id text PRIMARY KEY NOT NULL,
  name text DEFAULT '' NOT NULL,
  color text DEFAULT 'default' NOT NULL,
  updatedAt integer DEFAULT 0 NOT NULL,
  deleted integer DEFAULT 0 NOT NULL,
  seq integer DEFAULT 0 NOT NULL
);
CREATE TABLE _label_seq (
  id integer PRIMARY KEY NOT NULL,
  v integer DEFAULT 0 NOT NULL
);`;

let db: NotesDb;
let app: ReturnType<typeof createApp>;

beforeEach(() => {
  const sqlite = new Database(":memory:");
  sqlite.exec(DDL);
  db = drizzle(sqlite);
  app = createApp({
    db,
    assetsFetch: () => new Response("stub", { status: 404 }),
    access: {},
  });
});

const note = (overrides: Partial<Note> & { id: string }): Note =>
  newNote({ title: "t", body: "b", updatedAt: 1000, ...overrides });

const label = (overrides: Partial<Label> & { id: string }): Label => ({
  name: "work",
  color: "default",
  updatedAt: 1000,
  deleted: false,
  ...overrides,
});

async function postNotesSync(payload: unknown) {
  const res = await app.request("http://localhost/api/notes/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  return { status: res.status, body: (await res.json()) as Record<string, unknown> };
}

async function postLabelsSync(payload: unknown) {
  const res = await app.request("http://localhost/api/labels/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  return { status: res.status, body: (await res.json()) as Record<string, unknown> };
}

describe("isNote labelIds/reminderAt", () => {
  test("defaults to empty labels and no reminder", () => {
    const n = newNote({ id: "a" });
    expect(n.labelIds).toEqual([]);
    expect(n.reminderAt).toBeNull();
    expect(isNote(n)).toBe(true);
  });

  test("accepts populated fields", () => {
    expect(
      isNote(note({ id: "x", labelIds: ["l1", "l2"], reminderAt: 1700000000000 })),
    ).toBe(true);
    expect(isNote(note({ id: "x", reminderAt: 0 }))).toBe(true);
  });

  test("rejects labelIds violations and cap overflow", () => {
    expect(isNote({ ...note({ id: "x" }), labelIds: "l1" })).toBe(false);
    expect(isNote({ ...note({ id: "x" }), labelIds: [42] })).toBe(false);
    expect(isNote({ ...note({ id: "x" }), labelIds: [""] })).toBe(true);
    expect(isNote({ ...note({ id: "x" }), labelIds: ["x".repeat(65)] })).toBe(false);
    expect(
      isNote({ ...note({ id: "x" }), labelIds: Array.from({ length: 21 }, (_, i) => `l${i}`) }),
    ).toBe(false);
    expect(
      isNote({ ...note({ id: "x" }), labelIds: Array.from({ length: 20 }, (_, i) => `l${i}`) }),
    ).toBe(true);
  });

  test("rejects reminderAt violations", () => {
    for (const reminderAt of [-1, Number.NaN, Number.POSITIVE_INFINITY, "soon", true]) {
      expect(isNote({ ...note({ id: "x" }), reminderAt })).toBe(false);
    }
    expect(isNote({ ...note({ id: "x" }), reminderAt: null })).toBe(true);
  });
});

describe("isLabel + merge", () => {
  test("accepts a valid label, rejects junk and cap overflow", () => {
    expect(isLabel(label({ id: "l1" }))).toBe(true);
    expect(isLabel(null)).toBe(false);
    expect(isLabel({ ...label({ id: "l1" }), id: "" })).toBe(false);
    expect(isLabel({ ...label({ id: "l1" }), name: "" })).toBe(false);
    expect(isLabel({ ...label({ id: "l1" }), name: "x".repeat(121) })).toBe(false);
    expect(isLabel({ ...label({ id: "l1" }), color: "x".repeat(33) })).toBe(false);
    expect(isLabel({ ...label({ id: "l1" }), updatedAt: Number.NaN })).toBe(false);
    expect(isLabel({ ...label({ id: "l1" }), deleted: 0 })).toBe(false);
  });

  test("LWW by updatedAt with deterministic tiebreak, tombstones included", () => {
    const oldL = label({ id: "l", name: "old", updatedAt: 100 });
    const newL = label({ id: "l", name: "new", updatedAt: 200 });
    expect(pickLabelWinner(oldL, newL).name).toBe("new");
    expect(pickLabelWinner(newL, oldL).name).toBe("new");
    const tomb = label({ id: "l", name: "new", updatedAt: 300, deleted: true });
    expect(pickLabelWinner(newL, tomb).deleted).toBe(true);
    expect(() => pickLabelWinner(oldL, label({ id: "other" }))).toThrow("id mismatch");
    const merged = mergeLabelLists([oldL], [newL, label({ id: "m" })]);
    expect(merged).toHaveLength(2);
    expect(merged.find((l) => l.id === "l")?.name).toBe("new");
  });
});

describe("labels sync", () => {
  test("push stores rows and pull returns them with a cursor", async () => {
    const push = await postLabelsSync({
      upserts: [label({ id: "l1", updatedAt: 100 })],
      tombstones: [],
    });
    expect(push.status).toBe(200);
    expect(push.body["applied"]).toBe(1);
    const pull = await app.request("http://localhost/api/labels?since=0");
    expect(pull.status).toBe(200);
    const body = (await pull.json()) as { labels: Label[]; cursor: number };
    expect(body.labels).toHaveLength(1);
    expect(body.labels[0]).toMatchObject({ id: "l1", name: "work" });
    expect(body.cursor).toBeGreaterThan(0);
    const again = await app.request(`http://localhost/api/labels?since=${body.cursor}`);
    expect(((await again.json()) as { labels: unknown[] }).labels).toHaveLength(0);
  });

  test("cursor slices by write order, not by timestamp", async () => {
    const first = await postLabelsSync({ upserts: [label({ id: "a", updatedAt: 100 })] });
    const cursor1 = first.body["cursor"] as number;
    await postLabelsSync({ upserts: [label({ id: "b", updatedAt: 50 })] });
    const res = await app.request(`http://localhost/api/labels?since=${cursor1}`);
    const body = (await res.json()) as { labels: Label[] };
    expect(body.labels.map((l) => l.id)).toEqual(["b"]);
  });

  test("stale label loses with server version in deltas; newer tombstone deletes", async () => {
    const seed = await postLabelsSync({
      upserts: [label({ id: "l", name: "new", updatedAt: 200 })],
    });
    const stale = await postLabelsSync({
      upserts: [label({ id: "l", name: "stale", updatedAt: 100 })],
      since: seed.body["cursor"],
    });
    expect(stale.body["applied"]).toBe(0);
    expect((stale.body["deltas"] as Label[]).find((l) => l.id === "l")?.name).toBe("new");
    const gone = await postLabelsSync({
      upserts: [],
      tombstones: [label({ id: "l", name: "new", updatedAt: 300, deleted: true })],
    });
    expect(gone.body["applied"]).toBe(1);
    expect((gone.body["deltas"] as Label[]).find((l) => l.id === "l")?.deleted).toBe(true);
  });

  test("rejects malformed bodies and invalid labels", async () => {
    const badJson = await app.request("http://localhost/api/labels/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "not-json{{{",
    });
    expect(badJson.status).toBe(400);
    expect((await postLabelsSync({ upserts: [{ id: "x" }] })).status).toBe(400);
    expect((await postLabelsSync({ upserts: "nope" })).status).toBe(400);
    expect((await app.request("http://localhost/api/labels?since=nope")).status).toBe(400);
    expect(
      (
        await postLabelsSync({
          upserts: [],
          tombstones: [label({ id: "t", updatedAt: 1, deleted: false })],
        })
      ).status,
    ).toBe(400);
  });

  test("labels lane is gated like notes once ACCESS env is set", async () => {
    const locked = createApp({
      db,
      assetsFetch: () => new Response("stub", { status: 404 }),
      access: { teamDomain: "team.example.com", aud: "aud" },
    });
    expect((await locked.request("http://localhost/api/labels")).status).toBe(401);
    const post = await locked.request("http://localhost/api/labels/sync", {
      method: "POST",
      body: "{}",
    });
    expect(post.status).toBe(401);
  });
});

describe("notes carrying labelIds/reminderAt", () => {
  test("survive a sync round-trip unchanged", async () => {
    const full = note({ id: "n", labelIds: ["l1", "l2"], reminderAt: 1700000000000 });
    const push = await postNotesSync({ upserts: [full] });
    expect(push.status).toBe(200);
    expect(push.body["applied"]).toBe(1);
    const deltas = push.body["deltas"] as Note[];
    expect(deltas.find((n) => n.id === "n")).toMatchObject({
      labelIds: ["l1", "l2"],
      reminderAt: 1700000000000,
    });
    const pull = (await (
      await app.request("http://localhost/api/notes?since=0")
    ).json()) as { notes: Note[] };
    expect(pull.notes.find((n) => n.id === "n")).toMatchObject({
      labelIds: ["l1", "l2"],
      reminderAt: 1700000000000,
    });
  });

  test("reminderAt null persists; stale reminder edit loses; newer reminder wins", async () => {
    await postNotesSync({ upserts: [note({ id: "r", updatedAt: 100 })] });
    let pull = (await (
      await app.request("http://localhost/api/notes?since=0")
    ).json()) as { notes: Note[] };
    expect(pull.notes.find((n) => n.id === "r")?.reminderAt).toBeNull();
    const set = await postNotesSync({
      upserts: [note({ id: "r", updatedAt: 200, reminderAt: 5000 })],
    });
    expect(set.body["applied"]).toBe(1);
    const stale = await postNotesSync({
      upserts: [note({ id: "r", updatedAt: 150, reminderAt: 9999 })],
      since: set.body["cursor"],
    });
    expect(stale.body["applied"]).toBe(0);
    expect((stale.body["deltas"] as Note[]).find((n) => n.id === "r")?.reminderAt).toBe(5000);
    pull = (await (
      await app.request("http://localhost/api/notes?since=0")
    ).json()) as { notes: Note[] };
    expect(pull.notes.find((n) => n.id === "r")?.reminderAt).toBe(5000);
  });

  test("tombstone carrying labels/reminder deletes; live revive restores fields", async () => {
    await postNotesSync({
      upserts: [note({ id: "t", updatedAt: 100, labelIds: ["l1"], reminderAt: 42 })],
    });
    const del = await postNotesSync({
      tombstones: [note({ id: "t", updatedAt: 200, deleted: true, labelIds: ["l1"], reminderAt: 42 })],
    });
    expect(del.body["applied"]).toBe(1);
    const revive = await postNotesSync({
      upserts: [note({ id: "t", updatedAt: 300, labelIds: ["l2"], reminderAt: null })],
    });
    expect(revive.body["applied"]).toBe(1);
    const pull = (await (
      await app.request("http://localhost/api/notes?since=0")
    ).json()) as { notes: Note[] };
    expect(pull.notes.find((n) => n.id === "t")).toMatchObject({
      deleted: false,
      labelIds: ["l2"],
      reminderAt: null,
    });
  });

  test("notes sync rejects invalid labelIds/reminderAt like any malformed note", async () => {
    expect(
      (await postNotesSync({ upserts: [{ ...note({ id: "x" }), labelIds: ["x".repeat(65)] }] }))
        .status,
    ).toBe(400);
    expect(
      (await postNotesSync({ upserts: [{ ...note({ id: "x" }), reminderAt: -5 }] })).status,
    ).toBe(400);
  });
});
