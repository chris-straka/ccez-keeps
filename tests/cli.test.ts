// The keeps CLI's logic (cli/lib.ts) driven against the real Worker app
// in-process: an agent with a device token lists, reads, trashes, archives
// and adds notes through the same sync contract the phone uses.
import { beforeEach, describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { deviceTokens, labels } from "../db/schema.js";
import { newNote } from "../shared/note.js";
import { hashDeviceToken } from "../worker/devices.js";
import { createApp } from "../worker/index.js";
import type { NotesDb } from "../worker/notes.js";
import {
  formatList,
  formatMarkdown,
  makeNote,
  minOrder,
  pull,
  push,
  resolveIds,
  select,
  tombstone,
  archived,
  type KeepsClient,
} from "../cli/lib.js";

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
CREATE TABLE _sync_seq (id integer PRIMARY KEY NOT NULL, v integer DEFAULT 0 NOT NULL);
CREATE TABLE labels (
  id text PRIMARY KEY NOT NULL,
  name text DEFAULT '' NOT NULL,
  color text DEFAULT 'default' NOT NULL,
  updatedAt integer DEFAULT 0 NOT NULL,
  deleted integer DEFAULT 0 NOT NULL,
  seq integer DEFAULT 0 NOT NULL
);
CREATE TABLE _label_seq (id integer PRIMARY KEY NOT NULL, v integer DEFAULT 0 NOT NULL);
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
);`;

const TOKEN = "agent-token-for-tests";
const IDEAS = "label-ideas";
let db: NotesDb;
let client: KeepsClient;

beforeEach(async () => {
  const sqlite = new Database(":memory:");
  sqlite.exec(DDL);
  db = drizzle(sqlite);
  // Locked app: the Access gate is live, so only the device token opens it.
  const app = createApp({
    db,
    assetsFetch: () => new Response("stub", { status: 404 }),
    access: { teamDomain: "team.cloudflareaccess.com", aud: "aud" },
  });
  await (db as any).insert(deviceTokens).values({
    id: "agent",
    tokenHash: await hashDeviceToken(TOKEN),
    deviceName: "claude on z",
    createdAt: 1,
    lastSeenAt: 1,
    revoked: 0,
  });
  await (db as any)
    .insert(labels)
    .values({ id: IDEAS, name: "Ideas", color: "default", updatedAt: 1, deleted: 0, seq: 1 });
  client = {
    base: "http://keeps.test",
    token: TOKEN,
    fetch: async (url, init) => app.request(url, init),
  };
  // Seed through the API like a phone would.
  const seed = [
    newNote({ id: "aaaa-1", title: "ccez-llm", body: "first", updatedAt: 10 }),
    newNote({ id: "aaab-2", title: "", body: "an idea\nmore", labelIds: [IDEAS], updatedAt: 20 }),
    newNote({ id: "bbbb-3", title: "old", body: "archived", archived: true, updatedAt: 30 }),
    newNote({ id: "cccc-4", title: "gone", body: "", deleted: true, updatedAt: 40 }),
  ];
  await push(client, seed, [], 0);
});

describe("keeps CLI against the worker", () => {
  test("a wrong token is refused by the locked app", async () => {
    await expect(pull({ ...client, token: "nope" })).rejects.toThrow("401");
  });

  test("ls shows live notes newest first; archived and trash on request", async () => {
    const snap = await pull(client);
    expect(select(snap).map((n) => n.id)).toEqual(["aaab-2", "aaaa-1"]);
    expect(select(snap, { archived: true }).map((n) => n.id)).toEqual([
      "bbbb-3",
      "aaab-2",
      "aaaa-1",
    ]);
    expect(select(snap, { trash: true }).map((n) => n.id)).toEqual(["cccc-4"]);
    expect(select(snap, { label: "ideas" }).map((n) => n.id)).toEqual(["aaab-2"]);
    expect(select(snap, { label: "nope" })).toEqual([]);
    const text = formatList(select(snap), snap.labels);
    expect(text).toContain("aaab-2");
    expect(text).toContain("#Ideas");
    expect(text).toContain("an idea");
    expect(text).toContain("ccez-llm");
  });

  test("ids resolve by unique prefix and refuse ambiguity", async () => {
    const snap = await pull(client);
    expect(resolveIds(snap.notes, ["aaab"]).map((n) => n.id)).toEqual(["aaab-2"]);
    expect(() => resolveIds(snap.notes, ["aaa"])).toThrow("ambiguous");
    expect(() => resolveIds(snap.notes, ["zzz"])).toThrow("no note");
  });

  test("rm trashes through sync and the web view no longer lists it", async () => {
    const snap = await pull(client);
    const target = resolveIds(snap.notes, ["aaaa"])[0]!;
    const applied = await push(client, [], [tombstone(target, 1_000)], snap.cursor);
    expect(applied).toBe(1);
    const after = await pull(client);
    expect(select(after).map((n) => n.id)).toEqual(["aaab-2"]);
    expect(select(after, { trash: true }).map((n) => n.id)).toEqual(["aaaa-1", "cccc-4"]);
  });

  test("an older tombstone loses to a newer edit (LWW is intact)", async () => {
    const snap = await pull(client);
    const target = resolveIds(snap.notes, ["aaab"])[0]!;
    const applied = await push(client, [], [tombstone(target, 5)], snap.cursor);
    expect(applied).toBe(0);
    expect(select(await pull(client)).map((n) => n.id)).toContain("aaab-2");
  });

  test("archive keeps the note but takes it out of the default list", async () => {
    const snap = await pull(client);
    const target = resolveIds(snap.notes, ["aaab"])[0]!;
    await push(client, [archived(target, 1_000)], [], snap.cursor);
    const after = await pull(client);
    expect(select(after).map((n) => n.id)).toEqual(["aaaa-1"]);
    expect(select(after, { archived: true }).map((n) => n.id)).toContain("aaab-2");
  });

  test("add lands a note on top with the asked label", async () => {
    const snap = await pull(client);
    const note = makeNote(
      { title: "from an agent", body: "done: see PR", labelIds: [IDEAS] },
      "new-1",
      2_000,
      minOrder(snap.notes),
    );
    expect(note.order).toBe(-1);
    expect(await push(client, [note], [], snap.cursor)).toBe(1);
    const after = await pull(client);
    expect(select(after, { label: "Ideas" }).map((n) => n.id)).toEqual(["new-1", "aaab-2"]);
    const md = formatMarkdown(select(after, { label: "Ideas" }), after.labels);
    expect(md).toContain("## from an agent");
    expect(md).toContain("labels: Ideas");
    expect(md).toContain("done: see PR");
  });
});
