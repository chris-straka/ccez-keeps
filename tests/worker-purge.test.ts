// Trash retention: purgeTrash hard-deletes only tombstones older than the
// window, across all three lanes. In-memory SQLite, same as the lane suites.
import { beforeEach, describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { drawings, labels, notes } from "../db/schema.js";
import { purgeTrash, TRASH_RETENTION_MS } from "../worker/purge.js";

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
CREATE TABLE drawings (
  id text PRIMARY KEY NOT NULL,
  strokes text DEFAULT '[]' NOT NULL,
  updatedAt integer DEFAULT 0 NOT NULL,
  deleted integer DEFAULT 0 NOT NULL,
  seq integer DEFAULT 0 NOT NULL
);
CREATE TABLE labels (
  id text PRIMARY KEY NOT NULL,
  name text DEFAULT '' NOT NULL,
  color text DEFAULT 'default' NOT NULL,
  updatedAt integer DEFAULT 0 NOT NULL,
  deleted integer DEFAULT 0 NOT NULL,
  seq integer DEFAULT 0 NOT NULL
);`;

const NOW = 10_000_000_000;
const OLD = NOW - TRASH_RETENTION_MS - 1;
const FRESH = NOW - TRASH_RETENTION_MS + 60_000;

let db: ReturnType<typeof drizzle>;

beforeEach(() => {
  const sqlite = new Database(":memory:");
  sqlite.exec(DDL);
  db = drizzle(sqlite);
});

describe("purgeTrash", () => {
  test("removes only expired tombstones in every lane", async () => {
    await db.insert(notes).values([
      { id: "gone-old", updatedAt: OLD, deleted: 1 },
      { id: "gone-fresh", updatedAt: FRESH, deleted: 1 },
      { id: "live", updatedAt: OLD, deleted: 0 },
    ]);
    await db.insert(drawings).values([
      { id: "d-old", updatedAt: OLD, deleted: 1 },
      { id: "d-live", updatedAt: OLD, deleted: 0 },
    ]);
    await db.insert(labels).values([
      { id: "l-old", name: "x", updatedAt: OLD, deleted: 1 },
      { id: "l-fresh", name: "y", updatedAt: FRESH, deleted: 1 },
    ]);
    // Cast: tests hold the concrete bun driver; purge accepts the union seam.
    const counts = await purgeTrash(
      db as unknown as Parameters<typeof purgeTrash>[0],
      NOW,
    );
    expect(counts).toEqual({ notes: 1, drawings: 1, labels: 1 });
    const rest = (await db.select({ id: notes.id }).from(notes)).map((r) => r.id).sort();
    expect(rest).toEqual(["gone-fresh", "live"]);
    const restDrawings = (await db.select({ id: drawings.id }).from(drawings)).map((r) => r.id);
    expect(restDrawings).toEqual(["d-live"]);
    const restLabels = (await db.select({ id: labels.id }).from(labels)).map((r) => r.id);
    expect(restLabels).toEqual(["l-fresh"]);
  });

  test("empty trash purges nothing", async () => {
    await db.insert(notes).values([{ id: "live", updatedAt: OLD, deleted: 0 }]);
    const counts = await purgeTrash(
      db as unknown as Parameters<typeof purgeTrash>[0],
      NOW,
    );
    expect(counts).toEqual({ notes: 0, drawings: 0, labels: 0 });
  });
});
