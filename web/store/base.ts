// Lane B: shared Store logic. Subclasses provide raw persistence;
// views, search, tombstone stamping, import merge, and fan-out live here
// once so MemoryStore (tests/fixtures) and IdbStore (prod) cannot drift.
import { isNote, type Note } from "../../shared/note.js";
import { mergeNoteLists } from "../../shared/sync.js";
import { inView, type NoteView, type Store } from "./types.js";
import { rankNotes } from "./search.js";

export abstract class BaseStore implements Store {
  readonly ready: Promise<void>;
  private listeners = new Set<() => void>();

  constructor() {
    this.ready = this.init();
  }

  protected abstract init(): Promise<void>;
  protected abstract loadAll(): Promise<Note[]>;
  protected abstract saveRaw(note: Note): Promise<void>;
  protected abstract dropRaw(id: string): Promise<void>;
  abstract getCursor(): Promise<number>;
  abstract setCursor(value: number): Promise<void>;
  abstract getPushMark(): Promise<number>;
  abstract setPushMark(value: number): Promise<void>;

  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => {
      this.listeners.delete(fn);
    };
  }

  protected notify(): void {
    for (const fn of this.listeners) fn();
  }

  async all(): Promise<Note[]> {
    await this.ready;
    return this.loadAll();
  }

  async list(view: NoteView): Promise<Note[]> {
    const notes = await this.all();
    const filtered = notes.filter((n) => inView(n, view));
    // Agenda order is fire time (overdue first), not pin/recency.
    if (view === "reminders") {
      return filtered.sort((a, b) => (a.reminderAt ?? Infinity) - (b.reminderAt ?? Infinity));
    }
    return filtered.sort(compareNotes);
  }

  async search(query: string, view: NoteView): Promise<Note[]> {
    return rankNotes(await this.list(view), query);
  }

  async get(id: string): Promise<Note | undefined> {
    const notes = await this.all();
    return notes.find((n) => n.id === id);
  }

  async put(note: Note): Promise<void> {
    await this.ready;
    if (!isNote(note)) throw new Error("put: not a Note");
    await this.saveRaw(note);
    this.notify();
  }

  private async stamp(id: string, patch: Partial<Note>): Promise<void> {
    await this.ready;
    const current = (await this.loadAll()).find((n) => n.id === id);
    if (!current) return;
    await this.saveRaw({ ...current, ...patch, updatedAt: Date.now() });
    this.notify();
  }

  remove(id: string): Promise<void> {
    return this.stamp(id, { deleted: true });
  }

  restore(id: string): Promise<void> {
    return this.stamp(id, { deleted: false, archived: false });
  }

  tombstone(id: string): Promise<void> {
    return this.stamp(id, { deleted: true });
  }

  async dropLocal(id: string): Promise<void> {
    await this.ready;
    await this.dropRaw(id);
    this.notify();
  }

  async exportJson(): Promise<string> {
    const notes = await this.all();
    return JSON.stringify({ version: 1, notes });
  }

  async importJson(json: string): Promise<{ imported: number }> {
    await this.ready;
    let parsed: unknown;
    try {
      parsed = JSON.parse(json);
    } catch {
      throw new Error("importJson: invalid JSON");
    }
    const incoming = (parsed as { notes?: unknown }).notes;
    if (!Array.isArray(incoming) || !incoming.every(isNote)) {
      throw new Error("importJson: notes must be Note[]");
    }
    const merged = mergeNoteLists(await this.loadAll(), incoming);
    let imported = 0;
    for (const note of merged) {
      await this.saveRaw(note);
      imported += 1;
    }
    this.notify();
    return { imported };
  }
}

/** Pinned first, then newest. */
export function compareNotes(a: Note, b: Note): number {
  if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
  return b.updatedAt - a.updatedAt;
}
