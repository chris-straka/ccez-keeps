// Lane C regression suite for the drawings mirror endpoints, through real
// HTTP against in-memory SQLite. Same shape as worker-sync.test.ts.
import { beforeEach, describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import type { Drawing } from "../shared/drawing.js";
import { createApp } from "../worker/index.js";
import type { NotesDb } from "../worker/notes.js";

// Full DDL: the /api/* gate touches device_tokens, routes touch the rest.
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

const drawing = (overrides: Partial<Drawing> & { id: string }): Drawing => ({
  strokes: [{ color: "white", width: 4, points: [{ x: 0, y: 0 }, { x: 1, y: 1 }] }],
  updatedAt: 1000,
  deleted: false,
  ...overrides,
});

async function postDrawingsSync(payload: unknown) {
  const res = await app.request("http://localhost/api/drawings/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  return { status: res.status, body: (await res.json()) as Record<string, unknown> };
}

describe("drawings sync", () => {
  test("push stores rows and pull returns them with a cursor", async () => {
    const push = await postDrawingsSync({
      upserts: [drawing({ id: "d1", updatedAt: 100 })],
      tombstones: [],
    });
    expect(push.status).toBe(200);
    expect(push.body["applied"]).toBe(1);
    const pull = await app.request("http://localhost/api/drawings?since=0");
    expect(pull.status).toBe(200);
    const body = (await pull.json()) as { drawings: Drawing[]; cursor: number };
    expect(body.drawings).toHaveLength(1);
    expect(body.drawings[0]?.strokes).toHaveLength(1);
    expect(body.cursor).toBeGreaterThan(0);
    const again = await app.request(
      `http://localhost/api/drawings?since=${body.cursor}`,
    );
    expect(((await again.json()) as { drawings: unknown[] }).drawings).toHaveLength(0);
  });

  test("stale drawing loses; newer tombstone deletes", async () => {
    await postDrawingsSync({ upserts: [drawing({ id: "d", updatedAt: 200 })], tombstones: [] });
    const stale = await postDrawingsSync({
      upserts: [drawing({ id: "d", updatedAt: 100 })],
      tombstones: [],
    });
    expect(stale.body["applied"]).toBe(0);
    const gone = await postDrawingsSync({
      upserts: [],
      tombstones: [drawing({ id: "d", updatedAt: 300, deleted: true })],
    });
    expect(gone.body["applied"]).toBe(1);
    const deltas = gone.body["deltas"] as Drawing[];
    expect(deltas.find((d) => d.id === "d")?.deleted).toBe(true);
  });

  test("rejects malformed bodies and invalid drawings", async () => {
    const badJson = await app.request("http://localhost/api/drawings/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "not-json{{{",
    });
    expect(badJson.status).toBe(400);
    const badShape = await postDrawingsSync({ upserts: [{ id: "x" }], tombstones: [] });
    expect(badShape.status).toBe(400);
    const badSince = await app.request("http://localhost/api/drawings?since=nope");
    expect(badSince.status).toBe(400);
    const liveTombstone = await postDrawingsSync({
      upserts: [],
      tombstones: [drawing({ id: "t", updatedAt: 1, deleted: false })],
    });
    expect(liveTombstone.status).toBe(400);
  });
});
