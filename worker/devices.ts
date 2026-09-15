// plan-android.md: per-device sync credentials. The raw token is generated
// here, SHA-256-hashed for storage, and returned exactly once at enroll
// time. Every later check compares hashes only; revoked rows fail closed.
import { and, eq } from "drizzle-orm";
import type { BunSQLiteDatabase } from "drizzle-orm/bun-sqlite";
import type { DrizzleD1Database } from "drizzle-orm/d1";
import type { BaseSQLiteDatabase } from "drizzle-orm/sqlite-core";
import { deviceTokens, enrollCodes, type DeviceTokenRow } from "../db/schema.js";
import { base64UrlEncode } from "./auth.js";

export type DevicesDb = BunSQLiteDatabase | DrizzleD1Database;

type AnyDb = BaseSQLiteDatabase<any, any, any, any>;

function base(db: DevicesDb): AnyDb {
  return db as unknown as AnyDb;
}

function newId(): string {
  return crypto.randomUUID();
}

/** 256-bit random token, base64url-encoded (no padding). */
export function generateDeviceToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

export async function hashDeviceToken(token: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(token),
  );
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export interface EnrolledDevice {
  deviceId: string;
  /** Raw token: returned once, never stored. Caller must deliver it. */
  token: string;
}

export async function enrollDevice(
  db: DevicesDb,
  deviceName: string,
  nowMs: number = Date.now(),
): Promise<EnrolledDevice> {
  const token = generateDeviceToken();
  const deviceId = newId();
  await base(db)
    .insert(deviceTokens)
    .values({
      id: deviceId,
      tokenHash: await hashDeviceToken(token),
      deviceName,
      createdAt: nowMs,
      lastSeenAt: nowMs,
      revoked: 0,
    });
  return { deviceId, token };
}

export interface DeviceInfo {
  id: string;
  deviceName: string;
  createdAt: number;
  lastSeenAt: number;
  revoked: boolean;
}

function toInfo(row: DeviceTokenRow): DeviceInfo {
  return {
    id: row.id,
    deviceName: row.deviceName,
    createdAt: row.createdAt,
    lastSeenAt: row.lastSeenAt,
    revoked: row.revoked === 1,
  };
}

export async function listDevices(db: DevicesDb): Promise<DeviceInfo[]> {
  const rows: DeviceTokenRow[] = await base(db).select().from(deviceTokens);
  return rows.map(toInfo);
}

/** Returns true when a live row was revoked. */
export async function revokeDevice(db: DevicesDb, deviceId: string): Promise<boolean> {
  const updated: Array<{ id: string }> = await base(db)
    .update(deviceTokens)
    .set({ revoked: 1 })
    .where(eq(deviceTokens.id, deviceId))
    .returning({ id: deviceTokens.id });
  return updated.length > 0;
}

/** Rename a device (phone-side settings, web device list). */
export async function renameDevice(
  db: DevicesDb,
  deviceId: string,
  deviceName: string,
): Promise<boolean> {
  const updated: Array<{ id: string }> = await base(db)
    .update(deviceTokens)
    .set({ deviceName })
    .where(eq(deviceTokens.id, deviceId))
    .returning({ id: deviceTokens.id });
  return updated.length > 0;
}

/**
 * Rotate a device's credential: mints a fresh raw token and replaces the
 * stored hash. The old token stops working immediately. Revoked or unknown
 * ids fail closed with null (no row touched).
 */
export async function rotateDeviceToken(
  db: DevicesDb,
  deviceId: string,
  nowMs: number = Date.now(),
): Promise<string | null> {
  const rows: DeviceTokenRow[] = await base(db)
    .select()
    .from(deviceTokens)
    .where(eq(deviceTokens.id, deviceId));
  const row = rows[0];
  if (!row || row.revoked === 1) return null;
  const token = generateDeviceToken();
  try {
    await base(db)
      .update(deviceTokens)
      .set({ tokenHash: await hashDeviceToken(token), lastSeenAt: nowMs })
      .where(eq(deviceTokens.id, deviceId));
  } catch {
    return null;
  }
  return token;
}

/** Single-use enrollment codes live this long before they stop working. */
export const ENROLL_CODE_TTL_MS = 10 * 60 * 1000;

export function generateEnrollCode(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

/**
 * Mint a single-use enrollment code. The raw code goes to the logged-in
 * browser session (which 302-redirects it to the app); only its SHA-256 is
 * stored. Test seam: pass nowMs explicitly.
 */
export async function mintEnrollCode(
  db: DevicesDb,
  nowMs: number = Date.now(),
): Promise<string> {
  const code = generateEnrollCode();
  await base(db)
    .insert(enrollCodes)
    .values({
      id: newId(),
      codeHash: await hashDeviceToken(code),
      createdAt: nowMs,
      used: 0,
    });
  return code;
}

export type ExchangeResult = { ok: true } | { ok: false; message: string };

/**
 * Redeem a code for enrollment. Marks it used FIRST so a concurrent double
 * redeem cannot mint two devices; validates expiry after claiming.
 */
export async function claimEnrollCode(
  db: DevicesDb,
  code: string,
  nowMs: number = Date.now(),
): Promise<ExchangeResult> {
  const hash = await hashDeviceToken(code);
  const q = base(db);
  const claimed: Array<{ id: string; createdAt: number }> = await q
    .update(enrollCodes)
    .set({ used: 1 })
    .where(and(eq(enrollCodes.codeHash, hash), eq(enrollCodes.used, 0)))
    .returning({ id: enrollCodes.id, createdAt: enrollCodes.createdAt });
  const row = claimed[0];
  if (!row) return { ok: false, message: "unknown code" };
  if (nowMs - row.createdAt > ENROLL_CODE_TTL_MS) {
    return { ok: false, message: "expired code" };
  }
  return { ok: true };
}

/** Remove spent/expired codes; housekeeping, safe to run any time. */
export async function pruneEnrollCodes(
  db: DevicesDb,
  nowMs: number = Date.now(),
): Promise<void> {
  const q = base(db);
  const rows = await q.select().from(enrollCodes);
  for (const row of rows) {
    if (row.used === 1 || nowMs - row.createdAt > ENROLL_CODE_TTL_MS) {
      await q.delete(enrollCodes).where(eq(enrollCodes.id, row.id));
    }
  }
}

function readBearer(req: Request): string | null {
  const header = req.headers.get("Authorization");
  if (!header) return null;
  const match = /^Bearer (.+)$/.exec(header.trim());
  const token = match?.[1]?.trim();
  return token ? token : null;
}

/**
 * Device-token check for the /api/* gate. Returns the device id when the
 * Bearer token matches an unrevoked row (bumping lastSeenAt), else null.
 * Never throws: storage errors fail closed to null.
 */
export async function checkDeviceToken(
  req: Request,
  db: DevicesDb,
  nowMs: number = Date.now(),
): Promise<string | null> {
  const token = readBearer(req);
  if (!token) return null;
  try {
    const hash = await hashDeviceToken(token);
    const rows: DeviceTokenRow[] = await base(db)
      .select()
      .from(deviceTokens)
      .where(eq(deviceTokens.tokenHash, hash));
    const row = rows[0];
    if (!row || row.revoked === 1) return null;
    await base(db)
      .update(deviceTokens)
      .set({ lastSeenAt: nowMs })
      .where(eq(deviceTokens.id, row.id));
    return row.id;
  } catch {
    return null;
  }
}
