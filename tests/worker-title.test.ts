// Note titles (worker/title.ts): POST /api/title answers a few words for a
// note's text, only for enrolled devices or an Access session.
import { beforeEach, describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { enrollDevice } from "../worker/devices.js";
import { createApp } from "../worker/index.js";
import type { NotesDb } from "../worker/notes.js";
import { cleanTitle, museTitler } from "../worker/title.js";

const DDL = `CREATE TABLE device_tokens (
  id text PRIMARY KEY NOT NULL,
  tokenHash text NOT NULL UNIQUE,
  deviceName text DEFAULT '' NOT NULL,
  createdAt integer DEFAULT 0 NOT NULL,
  lastSeenAt integer DEFAULT 0 NOT NULL,
  revoked integer DEFAULT 0 NOT NULL
);`;

let db: NotesDb;
const asked: string[] = [];
const make = (titler?: (t: string) => Promise<string | null>) =>
  createApp({
    db,
    assetsFetch: () => new Response("stub", { status: 404 }),
    access: { teamDomain: "team.example.com", aud: "aud" },
    titler,
  });

beforeEach(() => {
  const sqlite = new Database(":memory:");
  sqlite.exec(DDL);
  db = drizzle(sqlite);
  asked.length = 0;
});

const post = (app: ReturnType<typeof make>, token: string | null, body: unknown) =>
  app.request("http://localhost/api/title", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });

describe("POST /api/title", () => {
  test("names a note's text for an enrolled device", async () => {
    const app = make(async (t) => {
      asked.push(t);
      return "Liste de courses";
    });
    const { token } = await enrollDevice(db, "phone");
    const res = await post(app, token, { text: "lait, œufs, farine" });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ title: "Liste de courses" });
    expect(asked).toEqual(["lait, œufs, farine"]);
  });

  test("refuses callers with neither a device token nor Access", async () => {
    const app = make(async () => "x");
    expect((await post(app, null, { text: "hi" })).status).toBe(401);
  });

  test("503 without a key, 400 without text", async () => {
    const { token } = await enrollDevice(db, "phone");
    expect((await post(make(), token, { text: "hi" })).status).toBe(503);
    expect((await post(make(async () => "x"), token, { text: " " })).status).toBe(400);
  });
});

describe("museTitler", () => {
  test("asks Muse at minimal thinking and cleans the answer", async () => {
    let sent: { model?: string; reasoning_effort?: string } = {};
    const fake = (async (_url: string, init: RequestInit) => {
      sent = JSON.parse(String(init.body));
      return new Response(
        JSON.stringify({ choices: [{ message: { content: '"Réunion de lundi."' } }] }),
      );
    }) as unknown as typeof fetch;
    expect(await museTitler("k", fake)("ordre du jour")).toBe("Réunion de lundi");
    expect(sent.model).toBe("muse-spark-1.3-contributor");
    expect(sent.reasoning_effort).toBe("minimal");
  });

  test("cleanTitle drops labels and refuses long answers", () => {
    expect(cleanTitle("Titre : Voyage à Lyon")).toBe("Voyage à Lyon");
    expect(cleanTitle("x".repeat(90))).toBeNull();
  });
});
