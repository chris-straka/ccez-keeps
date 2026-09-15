// Drizzle schema is the single source of truth for the D1 table.
// Column set must match contracts/data.md. Lane C: regenerate migrations
// with `bun run db:generate` after any (plan-approved) change here.
import { integer, sqliteTable, text } from "drizzle-orm/sqlite-core";

export const notes = sqliteTable("notes", {
  id: text("id").primaryKey(),
  title: text("title").notNull().default(""),
  body: text("body").notNull().default(""),
  color: text("color").notNull().default("default"),
  pinned: integer("pinned").notNull().default(0),
  archived: integer("archived").notNull().default(0),
  updatedAt: integer("updatedAt").notNull().default(0),
  deleted: integer("deleted").notNull().default(0),
  // Attached label ids as a JSON string array (rides note sync free).
  labelIds: text("labelIds").notNull().default("[]"),
  // Reminder fire time, unix epoch ms; NULL = no reminder (no server timing).
  reminderAt: integer("reminderAt"),
  // Repeat rule; NULL = fires once. Validated, never interpreted server-side.
  repeat: text("repeat"),
  // Server-assigned write sequence (dissemination order). Conflicts are
  // still decided by updatedAt (wall clock, LWW); seq only decides WHAT
  // the client hasn't seen, so late-arriving old-timestamp rows stay
  // visible. Every write deletes + reinserts to mint a fresh seq.
  seq: integer("seq").notNull().default(0),
});

/** Single-row (id=1) monotonic counter backing notes.seq. */
export const syncSeq = sqliteTable("_sync_seq", {
  id: integer("id").primaryKey(),
  v: integer("v").notNull().default(0),
});

/**
 * Per-device sync credentials (plan-android.md). One row per enrolled phone.
 * Only the SHA-256 of the raw token is stored; the raw token is returned
 * exactly once at enroll time and never persisted. `revoked` fail-closes.
 */
export const deviceTokens = sqliteTable("device_tokens", {
  id: text("id").primaryKey(),
  tokenHash: text("tokenHash").notNull().unique(),
  deviceName: text("deviceName").notNull().default(""),
  createdAt: integer("createdAt").notNull().default(0),
  lastSeenAt: integer("lastSeenAt").notNull().default(0),
  revoked: integer("revoked").notNull().default(0),
});

export type DeviceTokenRow = typeof deviceTokens.$inferSelect;

/**
 * Single-use enrollment codes (plan-android.md). A Custom Tab cannot hand
 * the Access JWT to the app (httpOnly browser cookie), so the logged-in
 * browser session mints a code and redirects to keeps://enroll?code=...;
 * the app exchanges it once for a device token. Codes live 10 minutes.
 */
export const enrollCodes = sqliteTable("enroll_codes", {
  id: text("id").primaryKey(),
  codeHash: text("codeHash").notNull().unique(),
  createdAt: integer("createdAt").notNull().default(0),
  used: integer("used").notNull().default(0),
});

export type EnrollCodeRow = typeof enrollCodes.$inferSelect;

/**
 * Vector drawings (plan.md amendment). Strokes are a JSON array of
 * { color, width, points: [{ x, y }] } with normalized 0..1 coordinates.
 * Sync mirrors notes with its own seq cursor (draw_seq).
 */
export const drawings = sqliteTable("drawings", {
  id: text("id").primaryKey(),
  strokes: text("strokes").notNull().default("[]"),
  updatedAt: integer("updatedAt").notNull().default(0),
  deleted: integer("deleted").notNull().default(0),
  seq: integer("seq").notNull().default(0),
});

/** Single-row (id=1) monotonic counter backing drawings.seq. */
export const drawSeq = sqliteTable("_draw_seq", {
  id: integer("id").primaryKey(),
  v: integer("v").notNull().default(0),
});

/**
 * Label definitions (plan.md amendment). Notes attach them by id via
 * Note.labelIds; sync mirrors notes with its own seq cursor (label_seq).
 */
export const labels = sqliteTable("labels", {
  id: text("id").primaryKey(),
  name: text("name").notNull().default(""),
  color: text("color").notNull().default("default"),
  updatedAt: integer("updatedAt").notNull().default(0),
  deleted: integer("deleted").notNull().default(0),
  seq: integer("seq").notNull().default(0),
});

/** Single-row (id=1) monotonic counter backing labels.seq. */
export const labelSeq = sqliteTable("_label_seq", {
  id: integer("id").primaryKey(),
  v: integer("v").notNull().default(0),
});

export type LabelRow = typeof labels.$inferSelect;

export type DrawingRow = typeof drawings.$inferSelect;

export type NoteRow = typeof notes.$inferSelect;
