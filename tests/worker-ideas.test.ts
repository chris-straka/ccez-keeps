// Ideas feed (worker/ideas.ts): its own token, this route only, read-only,
// only notes labelled Ideas, only their text. Through real HTTP against
// in-memory SQLite. DDL mirrors db/migrations 0000..0008.
import { beforeEach, describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { isIdeasLabelName, type Label } from "../shared/label.js";
import { newNote, type Note } from "../shared/note.js";
import { enrollDevice, hashDeviceToken } from "../worker/devices.js";
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
  checklist text,
  attachments text DEFAULT '[]' NOT NULL,
  seq integer DEFAULT 0 NOT NULL,
  sortOrder real DEFAULT 0 NOT NULL
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


const TOKEN = "ideas-reader-test-token";
let db: NotesDb;
let app: ReturnType<typeof createApp>;

beforeEach(async () => {
  const sqlite = new Database(":memory:");
  sqlite.exec(DDL);
  db = drizzle(sqlite);
  app = createApp({
    db,
    assetsFetch: () => new Response("stub", { status: 404 }),
    // Real Access config: requests without a JWT must fail on other routes.
    access: { teamDomain: "team.example.com", aud: "aud" },
    ideasReaderHash: await hashDeviceToken(TOKEN),
  });
});

const label = (id: string, name: string, deleted = false): Label => ({
  id, name, color: "default", updatedAt: 1000, deleted,
});

async function seed(device: string, notes: Note[], labels: Label[]) {
  const auth = { Authorization: `Bearer ${device}`, "Content-Type": "application/json" };
  const l = await app.request("http://localhost/api/labels/sync", {
    method: "POST", headers: auth, body: JSON.stringify({ upserts: labels, tombstones: [] }),
  });
  expect(l.status).toBe(200);
  const n = await app.request("http://localhost/api/notes/sync", {
    method: "POST", headers: auth, body: JSON.stringify({ upserts: notes, tombstones: [] }),
  });
  expect(n.status).toBe(200);
}

function getIdeas(token: string | null, since?: number) {
  const q = since === undefined ? "" : `?since=${since}`;
  return app.request(`http://localhost/api/ideas${q}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

describe("isIdeasLabelName", () => {
  test("accepts the button's label and hand-made spellings", () => {
    for (const n of ["Ideas", "ideas", "Idea", " IDEAS "]) expect(isIdeasLabelName(n)).toBe(true);
    for (const n of ["Idea list", "my ideas", "ide", ""]) expect(isIdeasLabelName(n)).toBe(false);
  });
});

describe("GET /api/ideas", () => {
  test("returns only live notes with an ideas label, text only", async () => {
    const { token: device } = await enrollDevice(db, "test phone");
    await seed(
      device,
      [
        newNote({ id: "a", title: "", body: "video on rent", labelIds: ["L1"], updatedAt: 2000,
          attachments: [], checklist: [{ id: "c", text: "check StatCan", checked: false }] }),
        newNote({ id: "b", title: "groceries", body: "milk", labelIds: ["L2"], updatedAt: 2000 }),
        newNote({ id: "c", title: "hand-labelled", body: "x", labelIds: ["L3", "L2"], updatedAt: 3000 }),
        newNote({ id: "d", title: "trashed idea", body: "y", labelIds: ["L1"], updatedAt: 3000, deleted: true }),
        newNote({ id: "e", title: "dead label", body: "z", labelIds: ["L4"], updatedAt: 3000 }),
      ],
      [label("L1", "Ideas"), label("L2", "shopping"), label("L3", "idea"), label("L4", "Ideas", true)],
    );
    const res = await getIdeas(TOKEN);
    expect(res.status).toBe(200);
    const body = (await res.json()) as { ideas: Array<Record<string, unknown>> };
    expect(body.ideas.map((i) => i["id"])).toEqual(["a", "c"]);
    expect(body.ideas[0]).toEqual({
      id: "a", title: "", text: "video on rent\n- [ ] check StatCan", labels: ["Ideas"],
      archived: false, updatedAt: 2000,
    });
    expect(body.ideas[1]?.["labels"]).toEqual(["idea", "shopping"]);
    const later = (await (await getIdeas(TOKEN, 2000)).json()) as { ideas: unknown[] };
    expect(later.ideas.length).toBe(1);
  });

  test("rejects a missing or wrong token, and every other credential", async () => {
    expect((await getIdeas(null)).status).toBe(401);
    expect((await getIdeas("nope")).status).toBe(401);
    const { token: device } = await enrollDevice(db, "phone");
    expect((await getIdeas(device)).status).toBe(401);
  });

  test("the ideas token opens no other route and cannot write", async () => {
    for (const path of ["/api/notes", "/api/labels", "/api/drawings", "/api/devices"]) {
      const res = await app.request(`http://localhost${path}`, {
        headers: { Authorization: `Bearer ${TOKEN}` },
      });
      expect(res.status).toBe(401);
    }
    const write = await app.request("http://localhost/api/ideas", {
      method: "POST", headers: { Authorization: `Bearer ${TOKEN}` }, body: "{}",
    });
    expect(write.status).toBe(405);
  });

  test("fails closed when the secret is unset", async () => {
    const closed = createApp({
      db, assetsFetch: () => new Response("", { status: 404 }), access: {},
    });
    const res = await closed.request("http://localhost/api/ideas", {
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    expect(res.status).toBe(503);
  });
});
