// Lane B: prod Store backed by IndexedDB via the `idb` promise wrapper.
// Schema (frozen in contracts/data.md): db `ccez-keeps` v2, store `notes`
// keyed by id, `by-updatedAt` index; `meta` store holds the sync cursors;
// v2 adds the `drawings` store (existing v1 databases upgrade in place).
import { openDB, type DBSchema, type IDBPDatabase } from "idb";
import type { Drawing } from "../../shared/drawing.js";
import { isDrawing } from "../../shared/drawing.js";
import type { Note } from "../../shared/note.js";
import { BaseStore } from "./base.js";
import type { DrawingStore } from "./drawings.js";

export const IDB_NAME = "ccez-keeps";
export const IDB_VERSION = 2;

interface KeepsDb extends DBSchema {
  notes: { key: string; value: Note; indexes: { "by-updatedAt": number } };
  drawings: { key: string; value: Drawing };
  meta: { key: string; value: { key: string; value: number } };
}

const CURSOR_KEY = "syncCursor";
const PUSH_MARK_KEY = "pushMark";
const DRAWINGS_CURSOR_KEY = "drawingsCursor";
const DRAWINGS_PUSH_MARK_KEY = "drawingsPushMark";

export class IdbStore extends BaseStore implements DrawingStore {
  private db: IDBPDatabase<KeepsDb> | undefined;

  protected async init(): Promise<void> {
    this.db = await openDB<KeepsDb>(IDB_NAME, IDB_VERSION, {
      upgrade(database, oldVersion) {
        if (oldVersion < 1) {
          const notes = database.createObjectStore("notes", { keyPath: "id" });
          notes.createIndex("by-updatedAt", "updatedAt");
          database.createObjectStore("meta", { keyPath: "key" });
        }
        if (oldVersion < 2) {
          database.createObjectStore("drawings", { keyPath: "id" });
        }
      },
    });
  }

  private requireDb(): IDBPDatabase<KeepsDb> {
    if (!this.db) throw new Error("IdbStore not ready");
    return this.db;
  }

  protected async loadAll(): Promise<Note[]> {
    return this.requireDb().getAll("notes");
  }

  protected async saveRaw(note: Note): Promise<void> {
    await this.requireDb().put("notes", note);
  }

  protected async dropRaw(id: string): Promise<void> {
    await this.requireDb().delete("notes", id);
  }

  async getCursor(): Promise<number> {
    await this.ready;
    const row = await this.requireDb().get("meta", CURSOR_KEY);
    return row?.value ?? 0;
  }

  async setCursor(value: number): Promise<void> {
    await this.ready;
    await this.requireDb().put("meta", { key: CURSOR_KEY, value });
  }

  async getPushMark(): Promise<number> {
    await this.ready;
    const row = await this.requireDb().get("meta", PUSH_MARK_KEY);
    return row?.value ?? 0;
  }

  async setPushMark(value: number): Promise<void> {
    await this.ready;
    await this.requireDb().put("meta", { key: PUSH_MARK_KEY, value });
  }

  /** Release the connection (tests; lets deleteDatabase proceed). */
  async close(): Promise<void> {
    this.db?.close();
    this.db = undefined;
  }

  async allDrawings(): Promise<Drawing[]> {
    await this.ready;
    return this.requireDb().getAll("drawings");
  }

  async getDrawing(id: string): Promise<Drawing | undefined> {
    await this.ready;
    return this.requireDb().get("drawings", id);
  }

  async putDrawing(drawing: Drawing): Promise<void> {
    await this.ready;
    if (!isDrawing(drawing)) throw new Error("putDrawing: not a Drawing");
    await this.requireDb().put("drawings", drawing);
    this.notify();
  }

  private async metaNumber(key: string): Promise<number> {
    await this.ready;
    const row = await this.requireDb().get("meta", key);
    return row?.value ?? 0;
  }

  private async setMetaNumber(key: string, value: number): Promise<void> {
    await this.ready;
    await this.requireDb().put("meta", { key, value });
  }

  getDrawingsCursor(): Promise<number> {
    return this.metaNumber(DRAWINGS_CURSOR_KEY);
  }

  setDrawingsCursor(value: number): Promise<void> {
    return this.setMetaNumber(DRAWINGS_CURSOR_KEY, value);
  }

  getDrawingsPushMark(): Promise<number> {
    return this.metaNumber(DRAWINGS_PUSH_MARK_KEY);
  }

  setDrawingsPushMark(value: number): Promise<void> {
    return this.setMetaNumber(DRAWINGS_PUSH_MARK_KEY, value);
  }
}
