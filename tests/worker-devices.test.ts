// plan-android.md phase 1: device-token auth, exercised through real HTTP
// (Hono app.request) against in-memory SQLite. Dev-bypass app proves the
// happy path; a locked app (ACCESS env set) proves the dual gate.
import { beforeEach, describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { newNote } from "../shared/note.js";
import { hashDeviceToken } from "../worker/devices.js";
import { deviceTokens } from "../db/schema.js";
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
);`;

let db: NotesDb;
let app: ReturnType<typeof createApp>;
let locked: ReturnType<typeof createApp>;

beforeEach(() => {
  const sqlite = new Database(":memory:");
  sqlite.exec(DDL);
  db = drizzle(sqlite);
  const assetsFetch = () => new Response("stub", { status: 404 });
  app = createApp({ db, assetsFetch, access: {} });
  locked = createApp({
    db,
    assetsFetch,
    access: { teamDomain: "team.example.com", aud: "aud" },
  });
});

async function enroll(target = app, deviceName = "pixel") {
  const res = await target.request("http://localhost/api/devices/enroll", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ deviceName }),
  });
  return { status: res.status, body: (await res.json()) as Record<string, unknown> };
}

describe("POST /api/devices/enroll", () => {
  test("mints a token + id on the dev-bypass app", async () => {
    const { status, body } = await enroll();
    expect(status).toBe(200);
    expect(typeof body["deviceId"]).toBe("string");
    expect(typeof body["token"]).toBe("string");
    expect((body["token"] as string).length).toBeGreaterThan(20);
  });

  test("each enroll mints a distinct token", async () => {
    const first = await enroll();
    const second = await enroll();
    expect(first.body["token"]).not.toBe(second.body["token"]);
    expect(first.body["deviceId"]).not.toBe(second.body["deviceId"]);
  });

  test("stores only the hash, never the raw token", async () => {
    const { body } = await enroll();
    const rows = await (
      db as unknown as {
        select: () => { from: (t: typeof deviceTokens) => Promise<Array<{ tokenHash: string }>> };
      }
    )
      .select()
      .from(deviceTokens);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.tokenHash).toBe(await hashDeviceToken(body["token"] as string));
    expect(rows[0]?.tokenHash).not.toContain(body["token"] as string);
  });

  test("rejects overlong device names and bad JSON", async () => {
    const long = await app.request("http://localhost/api/devices/enroll", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceName: "x".repeat(121) }),
    });
    expect(long.status).toBe(400);
    const bad = await app.request("http://localhost/api/devices/enroll", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "not-json{{{",
    });
    expect(bad.status).toBe(400);
  });

  test("locked app: enroll without Access JWT is 401 (no Bearer bootstrap)", async () => {
    // A device token must never mint another device: enroll skips the
    // Bearer path entirely, so this is 401 even with no other credentials.
    const res = await locked.request("http://localhost/api/devices/enroll", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceName: "pixel" }),
    });
    expect(res.status).toBe(401);
  });
});

describe("Bearer device gate on sync routes", () => {
  test("enrolled token syncs on the locked app; revoked token 401s", async () => {
    // Enroll via the dev-bypass app sharing the same DB (locked enroll
    // needs a real Access JWT, which has no network-free fixture here).
    const { body } = await enroll();
    const auth = { Authorization: `Bearer ${body["token"]}` };

    const push = await locked.request("http://localhost/api/notes/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...auth },
      body: JSON.stringify({
        upserts: [newNote({ id: "1", title: "t", body: "b", updatedAt: 1000 })],
      }),
    });
    expect(push.status).toBe(200);

    const pull = await locked.request("http://localhost/api/notes?since=0", {
      headers: auth,
    });
    expect(pull.status).toBe(200);
    expect(((await pull.json()) as { notes: unknown[] }).notes).toHaveLength(1);

    // Wrong token fails closed.
    const forged = await locked.request("http://localhost/api/notes?since=0", {
      headers: { Authorization: "Bearer nope" },
    });
    expect(forged.status).toBe(401);

    // Revoke via Bearer-authenticated management call, then sync 401s.
    const revoke = await locked.request("http://localhost/api/devices/revoke", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...auth },
      body: JSON.stringify({ deviceId: body["deviceId"] }),
    });
    expect(revoke.status).toBe(200);

    const after = await locked.request("http://localhost/api/notes?since=0", {
      headers: auth,
    });
    expect(after.status).toBe(401);
  });

  test("revoking an unknown device is 404", async () => {
    const res = await app.request("http://localhost/api/devices/revoke", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "does-not-exist" }),
    });
    expect(res.status).toBe(404);
  });

  test("code endpoint rejects non-keeps callbacks", async () => {
    const res = await app.request("http://localhost/api/devices/code?to=https://evil.example.com/");
    expect(res.status).toBe(400);
    const missing = await app.request("http://localhost/api/devices/code");
    expect(missing.status).toBe(400);
  });

  test("locked app: code without Access JWT is 401 (Bearer cannot mint codes)", async () => {
    const res = await locked.request("http://localhost/api/devices/code?to=keeps://enroll");
    expect(res.status).toBe(401);
  });

  test("code -> 302 to keeps:// callback -> exchange mints a device, once", async () => {
    // Mint via the dev-bypass app (same DB): locked minting needs a real
    // Access JWT, which has no network-free fixture. Exchange runs pre-auth
    // so it and everything after exercise the locked app for real.
    const code = await app.request("http://localhost/api/devices/code?to=keeps://enroll");
    expect(code.status).toBe(302);
    const location = code.headers.get("location") ?? "";
    expect(location.startsWith("keeps://enroll?code=")).toBe(true);
    const raw = new URL(location.replace("keeps://", "https://x/")).searchParams.get("code") ?? "";

    const first = await locked.request("http://localhost/api/devices/exchange", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: raw, deviceName: "pixel" }),
    });
    expect(first.status).toBe(200);
    const firstBody = (await first.json()) as Record<string, unknown>;
    expect(typeof firstBody["token"]).toBe("string");

    // Single use: the same code is gone on retry.
    const second = await locked.request("http://localhost/api/devices/exchange", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: raw }),
    });
    expect(second.status).toBe(404);

    // The minted token syncs on the locked app (exchange ran pre-auth).
    const authed = await locked.request("http://localhost/api/notes?since=0", {
      headers: { Authorization: `Bearer ${firstBody["token"]}` },
    });
    expect(authed.status).toBe(200);
  });

  test("exchange rejects unknown codes and bad bodies", async () => {
    const unknown = await app.request("http://localhost/api/devices/exchange", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: "never-minted" }),
    });
    expect(unknown.status).toBe(404);
    const bad = await app.request("http://localhost/api/devices/exchange", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({}),
    });
    expect(bad.status).toBe(400);
  });

  test("device list hides hashes", async () => {
    const { body } = await enroll(app, "pixel-9");
    const res = await app.request("http://localhost/api/devices");
    const list = (await res.json()) as { devices: Array<Record<string, unknown>> };
    expect(res.status).toBe(200);
    expect(list.devices).toHaveLength(1);
    expect(list.devices[0]?.["id"]).toBe(body["deviceId"]);
    expect(list.devices[0]?.["deviceName"]).toBe("pixel-9");
    expect("tokenHash" in (list.devices[0] ?? {})).toBe(false);
    expect("token" in (list.devices[0] ?? {})).toBe(false);
  });
});

describe("POST /api/devices/rename + /rotate", () => {
  test("rename updates the device list; unknown id is 404", async () => {
    const { body } = await enroll(app, "pixel-old");
    const id = body["deviceId"] as string;
    const res = await app.request("http://localhost/api/devices/rename", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: id, deviceName: "pixel-new" }),
    });
    expect(res.status).toBe(200);
    const list = (await (
      await app.request("http://localhost/api/devices")
    ).json()) as { devices: Array<Record<string, unknown>> };
    expect(list.devices[0]?.["deviceName"]).toBe("pixel-new");

    const missing = await app.request("http://localhost/api/devices/rename", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "does-not-exist", deviceName: "x" }),
    });
    expect(missing.status).toBe(404);
  });

  test("rename rejects overlong names and bad bodies", async () => {
    const long = await app.request("http://localhost/api/devices/rename", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "x", deviceName: "y".repeat(121) }),
    });
    expect(long.status).toBe(400);
    const bad = await app.request("http://localhost/api/devices/rename", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceName: "no-id" }),
    });
    expect(bad.status).toBe(400);
  });

  test("rotate swaps the credential: new token syncs, old token 401s", async () => {
    const { body } = await enroll(app, "pixel");
    const id = body["deviceId"] as string;
    const oldAuth = { Authorization: `Bearer ${body["token"]}` };
    const res = await locked.request("http://localhost/api/devices/rotate", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...oldAuth },
      body: JSON.stringify({ deviceId: id }),
    });
    expect(res.status).toBe(200);
    const rotated = (await res.json()) as Record<string, unknown>;
    expect(typeof rotated["token"]).toBe("string");
    expect(rotated["token"]).not.toBe(body["token"]);

    const fresh = await locked.request("http://localhost/api/notes?since=0", {
      headers: { Authorization: `Bearer ${rotated["token"]}` },
    });
    expect(fresh.status).toBe(200);
    const stale = await locked.request("http://localhost/api/notes?since=0", {
      headers: oldAuth,
    });
    expect(stale.status).toBe(401);
  });

  test("rotate on unknown or revoked devices is 404", async () => {
    const missing = await app.request("http://localhost/api/devices/rotate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "does-not-exist" }),
    });
    expect(missing.status).toBe(404);

    const { body } = await enroll(app, "pixel");
    const id = body["deviceId"] as string;
    await app.request("http://localhost/api/devices/revoke", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: id }),
    });
    const revoked = await app.request("http://localhost/api/devices/rotate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: id }),
    });
    expect(revoked.status).toBe(404);
  });
});
