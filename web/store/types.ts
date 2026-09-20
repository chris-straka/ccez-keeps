// Lane B: the Store interface. Lane A renders against this (with
// MemoryStore in tests); IdbStore is the prod implementation.
// Rule: put() stores exactly what it is given (caller stamps updatedAt);
// remove()/restore() stamp a fresh updatedAt themselves.
import type { Note } from "../../shared/note.js";

export type NoteView =
  | "notes"
  | "archive"
  | "trash"
  | "reminders"
  | "labels"
  | "devices"
  | "settings";

/** Panels (labels/devices/settings) manage things; they never list notes. */
export function isPanelView(view: NoteView): boolean {
  return view === "labels" || view === "devices" || view === "settings";
}

export function inView(note: Note, view: NoteView): boolean {
  if (isPanelView(view)) return false;
  if (view === "trash") return note.deleted;
  if (note.deleted) return false;
  // Agenda spans notes + archive: a firing reminder must not hide.
  if (view === "reminders") return note.reminderAt !== null && note.reminderAt !== undefined;
  return view === "archive" ? note.archived : !note.archived;
}

export type SyncStatus = "idle" | "syncing" | "offline" | "error" | "auth" | "failed";

export interface Store {
  readonly ready: Promise<void>;
  /** All rows including tombstones (sync + views build on this). */
  all(): Promise<Note[]>;
  list(view: NoteView): Promise<Note[]>;
  search(query: string, view: NoteView, labelNames?: Record<string, string>): Promise<Note[]>;
  get(id: string): Promise<Note | undefined>;
  put(note: Note): Promise<void>;
  /** Soft delete: writes a tombstone with fresh updatedAt. */
  remove(id: string): Promise<void>;
  /** Un-delete + un-archive with fresh updatedAt. */
  restore(id: string): Promise<void>;
  /** Write a fresh tombstone without dropping the row. */
  tombstone(id: string): Promise<void>;
  /**
   * Hard-drop the local row. Call only after the tombstone has been
   * pushed (see SyncEngine.deleteForever); later pulls re-insert the
   * tombstone, which stays hidden by the views.
   */
  dropLocal(id: string): Promise<void>;
  getCursor(): Promise<number>;
  setCursor(value: number): Promise<void>;
  /** Max updatedAt successfully pushed (survives reloads; see SyncEngine). */
  getPushMark(): Promise<number>;
  setPushMark(value: number): Promise<void>;
  exportJson(): Promise<string>;
  importJson(json: string): Promise<{ imported: number }>;
  subscribe(fn: () => void): () => void;
}
