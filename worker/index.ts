// Lane C: sync API implementation. Route shapes frozen in contracts/api.md.
import { Hono } from "hono";
import type {
  D1Database,
  ExecutionContext,
  ScheduledEvent,
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
import { purgeTrash } from "./purge.js";
import { checkIdeasReader, listIdeas } from "./ideas.js";

export interface WorkerEnv {
  // Minimal surface we use; avoids DOM-vs-Workers lib clashes.
  ASSETS: { fetch: (request: Request) => Promise<Response> | Response };
  DB: D1Database;
  /** Agent D sets both in prod; unset = local-dev bypass with warning. */
  ACCESS_TEAM_DOMAIN?: string;
  ACCESS_AUD?: string;
  /** SHA-256 hex of the ideas reader token (wrangler secret); unset = 503. */
  IDEAS_READER_SHA256?: string;
}

export interface AppDeps {
  db: NotesDb;
  assetsFetch: (request: Request) => Promise<Response> | Response;
  access: AccessEnv;
  /** SHA-256 hex of the read-only ideas token (worker/ideas.ts). */
  ideasReaderHash?: string | undefined;
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
    // The ideas feed has its own credential and accepts nothing else:
    // neither an Access session nor a device token opens it, and its
    // token opens no other route (worker/ideas.ts).
    if (c.req.path === "/api/ideas") {
      if (c.req.method !== "GET") return c.json({ error: "read-only" }, 405);
      const ideas = await checkIdeasReader(c.req.raw, deps.ideasReaderHash);
      if (!ideas.ok) return c.json({ error: ideas.message }, ideas.status);
      await next();
      return;
    }
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
    if (!result.ok) {
      // /api/* is Bypassed at the edge (docs/access.md), so a fresh
      // browser never sees the Access login here. Bounce the Custom Tab
      // through /enroll, which Access does gate; it logs in there and
      // comes back with the session cookie. `retry` stops a loop.
      const url = new URL(c.req.url);
      const to = url.searchParams.get("to") ?? "";
      if (
        result.status === 401 &&
        url.pathname === "/api/devices/code" &&
        to.startsWith("keeps://") &&
        !url.searchParams.has("retry")
      ) {
        return c.redirect(`/enroll?to=${encodeURIComponent(to)}`, 302);
      }
      return c.json({ error: result.message }, result.status);
    }
    await next();
  });

  // Access-gated landing for the phone's Custom Tab (see the gate above).
  // Reaching it means Access already logged the browser in; hand it back
  // to the code bridge, which now sees the CF_Authorization cookie.
  app.get("/enroll", async (c) => {
    const to = c.req.query("to") ?? "";
    if (!to.startsWith("keeps://")) {
      return c.json({ error: "to must be a keeps:// callback URL" }, 400);
    }
    const result = await checkAccess(c.req.raw, deps.access);
    if (!result.ok) return c.json({ error: result.message }, result.status);
    return c.redirect(
      `/api/devices/code?to=${encodeURIComponent(to)}&retry=1`,
      302,
    );
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

  // GET /api/ideas?since=<updatedAt ms> -> { ideas, serverTime }
  app.get("/api/ideas", async (c) => {
    const since = readSince(
      c.req.query("since") === undefined ? undefined : Number(c.req.query("since")),
    );
    if (since === null) {
      return c.json({ error: "since must be a non-negative number" }, 400);
    }
    return c.json({ ideas: await listIdeas(deps.db, since), serverTime: Date.now() });
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
    if (url.pathname.startsWith("/api/") || url.pathname === "/enroll") {
      const app = createApp({
        db: drizzle(env.DB),
        assetsFetch: (req) => env.ASSETS.fetch(req),
        access: { teamDomain: env.ACCESS_TEAM_DOMAIN, aud: env.ACCESS_AUD },
        ideasReaderHash: env.IDEAS_READER_SHA256,
      });
      return app.fetch(request, env);
    }
    return env.ASSETS.fetch(request);
  },

  /** Nightly trash expiry (wrangler.jsonc triggers). No auth needed. */
  async scheduled(
    _event: ScheduledEvent,
    env: WorkerEnv,
    _ctx: ExecutionContext,
  ): Promise<void> {
    await purgeTrash(drizzle(env.DB), Date.now());
  },
};
