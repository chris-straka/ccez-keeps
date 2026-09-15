// Lane C regression suite: the sync API contract, exercised through real
// HTTP (Hono app.request) against in-memory SQLite. Uses the bun:sqlite
// driver; prod swaps in the D1 driver behind the same NotesDb seam.
import { beforeEach, describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { newNote, type Note } from "../shared/note.js";
import { createApp } from "../worker/index.js";
import type { NotesDb } from "../worker/notes.js";

// Must match db/migrations/0000_*..0006_* (kept inline: tests cannot run
// drizzle-kit, and drift here fails loudly against prod DDL).
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
    access: {}, // dev bypass; auth enforcement is tested separately below
  });
});

interface PullBody {
  notes: Note[];
  cursor: number;
}

interface PushBody {
  applied: number;
  deltas: Note[];
  cursor: number;
}

async function getNotes(since?: number) {
  const qs = since === undefined ? "" : `?since=${since}`;
  const res = await app.request(`http://localhost/api/notes${qs}`);
  return { status: res.status, body: (await res.json()) as PullBody };
}

async function postSync(payload: unknown) {
  const res = await app.request("http://localhost/api/notes/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  return { status: res.status, body: (await res.json()) as PushBody };
}

const note = (overrides: Partial<Note> & { id: string }) =>
  newNote({ title: "t", body: "b", updatedAt: 1000, ...overrides });

describe("GET /api/notes", () => {
  test("empty store returns no notes and the zero cursor", async () => {
    const { status, body } = await getNotes();
    expect(status).toBe(200);
    expect(body.notes).toEqual([]);
    expect(body.cursor).toBe(0);
  });

  test("cursor slices by write order, not by timestamp", async () => {
    const first = await postSync({ upserts: [note({ id: "1", updatedAt: 100 })] });
    const cursor1 = first.body.cursor;
    expect(cursor1).toBeGreaterThan(0);
    // A late-arriving row with an OLDER timestamp still gets a newer seq.
    await postSync({ upserts: [note({ id: "2", updatedAt: 50 })] });
    const { body } = await getNotes(cursor1);
    expect(body.notes.map((n) => n.id)).toEqual(["2"]);
    expect(body.cursor).toBeGreaterThan(cursor1);
    // And the cursor from a full pull sees nothing new.
    expect((await getNotes(body.cursor)).body.notes).toEqual([]);
  });

  test("rejects bad since", async () => {
    for (const since of ["-1", "abc"]) {
      const res = await app.request(`http://localhost/api/notes?since=${since}`);
      expect(res.status).toBe(400);
    }
  });
});

describe("POST /api/notes/sync", () => {
  test("stores upserts and echoes them as deltas with a cursor", async () => {
    const { status, body } = await postSync({
      upserts: [note({ id: "1" }), note({ id: "2" })],
    });
    expect(status).toBe(200);
    expect(body.applied).toBe(2);
    expect(body.deltas.map((n) => n.id).sort()).toEqual(["1", "2"]);
    expect(body.cursor).toBe(2);
  });

  test("stale edit loses on updatedAt; server version reaches client in deltas", async () => {
    const seed = await postSync({
      upserts: [note({ id: "1", body: "new", updatedAt: 200 })],
    });
    const { body } = await postSync({
      upserts: [note({ id: "1", body: "stale", updatedAt: 100 })],
      since: seed.body.cursor,
    });
    expect(body.applied).toBe(0);
    // The stored row's seq predates `since`, but the conflict loser must
    // still arrive: the client sent a write that did not stick.
    expect(body.deltas).toHaveLength(1);
    expect(body.deltas[0]?.body).toBe("new");
    const stored = await getNotes(0);
    expect(stored.body.notes.find((n) => n.id === "1")?.body).toBe("new");
  });

  test("newer tombstone deletes; a live edit arriving later still wins", async () => {
    await postSync({ upserts: [note({ id: "1", body: "live", updatedAt: 100 })] });
    const del = await postSync({
      tombstones: [note({ id: "1", deleted: true, updatedAt: 200 })],
    });
    expect(del.body.applied).toBe(1);
    expect((await getNotes(0)).body.notes[0]?.deleted).toBe(true);

    // The tombstone (updatedAt 150) is older than the live edit (300):
    // wall-clock LWW revives the note even though it arrives in one batch.
    const revive = await postSync({
      upserts: [note({ id: "1", body: "resurrect?", updatedAt: 300 })],
      tombstones: [note({ id: "1", deleted: true, updatedAt: 150 })],
    });
    expect(revive.body.applied).toBe(1);
    expect((await getNotes(0)).body.notes[0]?.body).toBe("resurrect?");
  });

  test("intra-batch duplicate ids collapse deterministically", async () => {
    const { body } = await postSync({
      upserts: [
        note({ id: "1", body: "older", updatedAt: 100 }),
        note({ id: "1", body: "newer", updatedAt: 200 }),
        note({ id: "2", body: "newer", updatedAt: 200 }),
        note({ id: "2", body: "older", updatedAt: 100 }),
      ],
    });
    expect(body.applied).toBe(2);
    const stored = new Map(
      (await getNotes(0)).body.notes.map((n) => [n.id, n.body]),
    );
    expect(stored.get("1")).toBe("newer");
    expect(stored.get("2")).toBe("newer");
  });

  test("rejects malformed bodies", async () => {
    expect((await postSync({ upserts: "nope" })).status).toBe(400);
    expect((await postSync({ upserts: [{ id: 1 }] })).status).toBe(400);
    expect(
      (
        await postSync({
          tombstones: [note({ id: "1", deleted: false })],
        })
      ).status,
    ).toBe(400);
    expect((await postSync({ upserts: [], since: -5 })).status).toBe(400);
    const res = await app.request("http://localhost/api/notes/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "not-json{{{",
    });
    expect(res.status).toBe(400);
  });
});

describe("access gate", () => {
  test("401 without token once ACCESS env is set", async () => {
    const locked = createApp({
      db,
      assetsFetch: () => new Response("stub", { status: 404 }),
      access: { teamDomain: "team.example.com", aud: "aud" },
    });
    const get = await locked.request("http://localhost/api/notes");
    expect(get.status).toBe(401);
    const post = await locked.request("http://localhost/api/notes/sync", {
      method: "POST",
      body: "{}",
    });
    expect(post.status).toBe(401);
  });
});
