// Lane C: sync API implementation. Route shapes frozen in contracts/api.md.
import { Hono } from "hono";
import type {
  D1Database,
  ExecutionContext,
} from "@cloudflare/workers-types/2023-07-01";
import { drizzle } from "drizzle-orm/d1";
import { isNote, type Note } from "../shared/note.js";
import { isDrawing, type Drawing } from "../shared/drawing.js";
import { isLabel, type Label } from "../shared/label.js";
import { applyDrawingsSync, getDrawingDeltas } from "./drawings.js";
import { applyLabelsSync, getLabelDeltas } from "./labels.js";
import { checkAccess, type AccessEnv } from "./auth.js";
import {
  checkDeviceToken,
  claimEnrollCode,
  enrollDevice,
  listDevices,
  mintEnrollCode,
  renameDevice,
  revokeDevice,
  rotateDeviceToken,
} from "./devices.js";
import { applySync, getDeltas, type NotesDb } from "./notes.js";

export interface WorkerEnv {
  // Minimal surface we use; avoids DOM-vs-Workers lib clashes.
  ASSETS: { fetch: (request: Request) => Promise<Response> | Response };
  DB: D1Database;
  /** Agent D sets both in prod; unset = local-dev bypass with warning. */
  ACCESS_TEAM_DOMAIN?: string;
  ACCESS_AUD?: string;
}

export interface AppDeps {
  db: NotesDb;
  assetsFetch: (request: Request) => Promise<Response> | Response;
  access: AccessEnv;
}

function readNotes(value: unknown): Note[] | null {
  if (!Array.isArray(value)) return null;
  if (!value.every(isNote)) return null;
  return value;
}

function readSince(value: unknown): number | null {
  if (value === undefined) return 0;
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) return null;
  return value;
}

function readDrawings(value: unknown): Drawing[] | null {
  if (!Array.isArray(value)) return null;
  if (!value.every(isDrawing)) return null;
  return value;
}

function readLabels(value: unknown): Label[] | null {
  if (!Array.isArray(value)) return null;
  if (!value.every(isLabel)) return null;
  return value;
}

export function createApp(deps: AppDeps) {
  const app = new Hono<{ Variables: { deviceId: string } }>();

  // Dual gate (plan-android.md): web callers present the Access JWT (as
  // before); enrolled phones present `Authorization: Bearer <device-token>`.
  // Enroll itself always requires Access, so a device token can never mint
  // another device token.
  app.use("/api/*", async (c, next) => {
    // The exchange endpoint is pre-auth: the single-use code IS the
    // credential, so neither gate applies to it.
    if (c.req.path === "/api/devices/exchange") {
      await next();
      return;
    }
    if (
      !c.req.path.startsWith("/api/devices/enroll") &&
      !c.req.path.startsWith("/api/devices/code")
    ) {
      const deviceId = await checkDeviceToken(c.req.raw, deps.db);
      if (deviceId) {
        c.set("deviceId", deviceId);
        await next();
        return;
      }
    }
    const result = await checkAccess(c.req.raw, deps.access);
    if (!result.ok) return c.json({ error: result.message }, result.status);
    await next();
  });

  // Device enrollment + management. Enroll requires the Access gate above;
  // list/revoke accept either gate (web or an enrolled phone managing peers).
  app.post("/api/devices/enroll", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: "body must be JSON" }, 400);
    }
    const name = (body ?? {}) as Record<string, unknown>;
    const deviceName =
      typeof name["deviceName"] === "string" ? name["deviceName"] : "";
    if (deviceName.length > 120) {
      return c.json({ error: "deviceName must be at most 120 chars" }, 400);
    }
    const { deviceId, token } = await enrollDevice(deps.db, deviceName);
    return c.json({ deviceId, token });
  });

  app.get("/api/devices", async (c) => {
    return c.json({ devices: await listDevices(deps.db) });
  });

  // Enrollment-code bridge for the Android Custom Tab flow. The logged-in
  // browser session hits this (Access gate above), gets a 302 to the app's
  // keeps:// callback with a single-use code, and the app exchanges it.
  app.get("/api/devices/code", async (c) => {
    const to = c.req.query("to") ?? "";
    if (!to.startsWith("keeps://")) {
      return c.json({ error: "to must be a keeps:// callback URL" }, 400);
    }
    const code = await mintEnrollCode(deps.db);
    const sep = to.includes("?") ? "&" : "?";
    return c.redirect(`${to}${sep}code=${encodeURIComponent(code)}`, 302);
  });

  app.post("/api/devices/exchange", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: "body must be JSON" }, 400);
    }
    const { code, deviceName } = (body ?? {}) as Record<string, unknown>;
    if (typeof code !== "string" || code.length === 0) {
      return c.json({ error: "body must be { code: string }" }, 400);
    }
    const name = typeof deviceName === "string" ? deviceName : "";
    if (name.length > 120) {
      return c.json({ error: "deviceName must be at most 120 chars" }, 400);
    }
    const claimed = await claimEnrollCode(deps.db, code);
    if (!claimed.ok) {
      const status = claimed.message === "expired code" ? 410 : 404;
      return c.json({ error: claimed.message }, status);
    }
    const { deviceId, token } = await enrollDevice(deps.db, name);
    return c.json({ deviceId, token });
  });

  app.post("/api/devices/revoke", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: "body must be JSON" }, 400);
    }
    const { deviceId } = (body ?? {}) as Record<string, unknown>;
    if (typeof deviceId !== "string" || deviceId.length === 0) {
      return c.json({ error: "body must be { deviceId: string }" }, 400);
    }
    const revoked = await revokeDevice(deps.db, deviceId);
    if (!revoked) return c.json({ error: "unknown device" }, 404);
    return c.json({ revoked: true });
  });

  app.post("/api/devices/rename", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: "body must be JSON" }, 400);
    }
    const { deviceId, deviceName } = (body ?? {}) as Record<string, unknown>;
    if (typeof deviceId !== "string" || deviceId.length === 0) {
      return c.json({ error: "body must be { deviceId: string }" }, 400);
    }
    if (typeof deviceName !== "string" || deviceName.length > 120) {
      return c.json({ error: "deviceName must be a string of at most 120 chars" }, 400);
    }
    const renamed = await renameDevice(deps.db, deviceId, deviceName);
    if (!renamed) return c.json({ error: "unknown device" }, 404);
    return c.json({ renamed: true });
  });

  app.post("/api/devices/rotate", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: "body must be JSON" }, 400);
    }
    const { deviceId } = (body ?? {}) as Record<string, unknown>;
    if (typeof deviceId !== "string" || deviceId.length === 0) {
      return c.json({ error: "body must be { deviceId: string }" }, 400);
    }
    const token = await rotateDeviceToken(deps.db, deviceId);
    if (!token) return c.json({ error: "unknown device" }, 404);
    return c.json({ deviceId, token });
  });

  // Frozen: GET /api/notes?since=<cursor> -> { notes, cursor }
  app.get("/api/notes", async (c) => {
    const since = readSince(
      c.req.query("since") === undefined ? undefined : Number(c.req.query("since")),
    );
    if (since === null) {
      return c.json({ error: "since must be a non-negative number" }, 400);
    }
    const { notes, cursor } = await getDeltas(deps.db, since);
    return c.json({ notes, cursor });
  });

  // Frozen: POST /api/notes/sync -> { applied, deltas, serverTime }
  app.post("/api/notes/sync", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: "body must be JSON" }, 400);
    }
    const { upserts, tombstones, since } = (body ?? {}) as Record<string, unknown>;
    const upsertNotes = readNotes(upserts ?? []);
    const tombstoneNotes = readNotes(tombstones ?? []);
    const sinceTs = readSince(since);
    if (!upsertNotes || !tombstoneNotes || sinceTs === null) {
      return c.json(
        { error: "body must be { upserts: Note[], tombstones: Note[], since?: number }" },
        400,
      );
    }
    if (!tombstoneNotes.every((n) => n.deleted)) {
      return c.json({ error: "tombstones must have deleted: true" }, 400);
    }
    const { applied, deltas, cursor } = await applySync(deps.db, {
      upserts: upsertNotes,
      tombstones: tombstoneNotes,
      since: sinceTs,
    });
    return c.json({ applied, deltas, cursor });
  });

  // Drawings mirror (plan.md amendment): independent seq cursor, same gate.
  app.get("/api/drawings", async (c) => {
    const since = readSince(
      c.req.query("since") === undefined ? undefined : Number(c.req.query("since")),
    );
    if (since === null) {
      return c.json({ error: "since must be a non-negative number" }, 400);
    }
    const { drawings, cursor } = await getDrawingDeltas(deps.db, since);
    return c.json({ drawings, cursor });
  });

  app.post("/api/drawings/sync", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: "body must be JSON" }, 400);
    }
    const { upserts, tombstones, since } = (body ?? {}) as Record<string, unknown>;
    const upsertDrawings = readDrawings(upserts ?? []);
    const tombstoneDrawings = readDrawings(tombstones ?? []);
    const sinceTs = readSince(since);
    if (!upsertDrawings || !tombstoneDrawings || sinceTs === null) {
      return c.json(
        { error: "body must be { upserts: Drawing[], tombstones: Drawing[], since?: number }" },
        400,
      );
    }
    if (!tombstoneDrawings.every((d) => d.deleted)) {
      return c.json({ error: "tombstones must have deleted: true" }, 400);
    }
    const { applied, deltas, cursor } = await applyDrawingsSync(deps.db, {
      upserts: upsertDrawings,
      tombstones: tombstoneDrawings,
      since: sinceTs,
    });
    return c.json({ applied, deltas, cursor });
  });

  // Labels mirror (plan.md amendment): independent seq cursor, same gate.
  app.get("/api/labels", async (c) => {
    const since = readSince(
      c.req.query("since") === undefined ? undefined : Number(c.req.query("since")),
    );
    if (since === null) {
      return c.json({ error: "since must be a non-negative number" }, 400);
    }
    const { labels, cursor } = await getLabelDeltas(deps.db, since);
    return c.json({ labels, cursor });
  });

  app.post("/api/labels/sync", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: "body must be JSON" }, 400);
    }
    const { upserts, tombstones, since } = (body ?? {}) as Record<string, unknown>;
    const upsertLabels = readLabels(upserts ?? []);
    const tombstoneLabels = readLabels(tombstones ?? []);
    const sinceTs = readSince(since);
    if (!upsertLabels || !tombstoneLabels || sinceTs === null) {
      return c.json(
        { error: "body must be { upserts: Label[], tombstones: Label[], since?: number }" },
        400,
      );
    }
    if (!tombstoneLabels.every((l) => l.deleted)) {
      return c.json({ error: "tombstones must have deleted: true" }, 400);
    }
    const { applied, deltas, cursor } = await applyLabelsSync(deps.db, {
      upserts: upsertLabels,
      tombstones: tombstoneLabels,
      since: sinceTs,
    });
    return c.json({ applied, deltas, cursor });
  });

  return app;
}

export default {
  fetch(request: Request, env: WorkerEnv, _ctx: ExecutionContext) {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/")) {
      const app = createApp({
        db: drizzle(env.DB),
        assetsFetch: (req) => env.ASSETS.fetch(req),
        access: { teamDomain: env.ACCESS_TEAM_DOMAIN, aud: env.ACCESS_AUD },
      });
      return app.fetch(request, env);
    }
    return env.ASSETS.fetch(request);
  },
};
