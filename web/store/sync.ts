// Lane B: background sync between the Store and the Worker API.
// Push-then-pull per flush; watermarks: pull cursor (max seen updatedAt)
// and push mark (max pushed updatedAt) are persisted separately so an
// unpushed edit survives reloads and can never hide below the cursor.
// The drawings lane mirrors notes with its own cursor/pushMark pair and
// endpoints; both lanes flush together. fetchFn/online are injectable;
// tests never touch the network.
import { isDrawing, type Drawing } from "../../shared/drawing.js";
import { mergeDrawingLists } from "../../shared/drawing-sync.js";
import { isNote, type Note } from "../../shared/note.js";
import { mergeNoteLists } from "../../shared/sync.js";
import type { DrawingStore } from "./drawings.js";
import type { Store, SyncStatus } from "./types.js";

export interface SyncDeps {
  fetchFn?: typeof fetch;
  debounceMs?: number;
  onStatus?: (status: SyncStatus) => void;
  /** Defaults to navigator.onLine when available, else true. */
  online?: () => boolean;
}

interface PullBody {
  notes: Note[];
  cursor: number;
}

interface PushBody {
  applied: number;
  deltas: Note[];
  cursor: number;
}

interface DrawPullBody {
  drawings: Drawing[];
  cursor: number;
}

interface DrawPushBody {
  applied: number;
  deltas: Drawing[];
  cursor: number;
}

function readCursor(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) return null;
  return value;
}

export class SyncEngine {
  private timer: ReturnType<typeof setTimeout> | undefined;
  private flight: Promise<void> | undefined;
  private onlineHandler: (() => void) | undefined;

  constructor(
    private readonly store: Store,
    private readonly deps: SyncDeps = {},
    private readonly drawings?: DrawingStore,
  ) {
    if (typeof window !== "undefined") {
      this.onlineHandler = () => {
        void this.flush();
      };
      window.addEventListener("online", this.onlineHandler);
    }
  }

  destroy(): void {
    if (this.timer !== undefined) clearTimeout(this.timer);
    if (typeof window !== "undefined" && this.onlineHandler) {
      window.removeEventListener("online", this.onlineHandler);
    }
  }

  private get fetchFn(): typeof fetch {
    const fn = this.deps.fetchFn ?? globalThis.fetch;
    if (!fn) throw new Error("SyncEngine: no fetch available");
    return fn;
  }

  private isOnline(): boolean {
    if (this.deps.online) return this.deps.online();
    if (typeof navigator !== "undefined" && typeof navigator.onLine === "boolean") {
      return navigator.onLine;
    }
    return true;
  }

  private setStatus(status: SyncStatus): void {
    this.deps.onStatus?.(status);
  }

  /** Debounced push-then-pull after local edits (~300ms default). */
  schedulePush(): void {
    if (!this.isOnline()) {
      this.setStatus("offline");
      return;
    }
    if (this.timer !== undefined) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.flush();
    }, this.deps.debounceMs ?? 300);
  }

  /** Immediate push-then-pull. Concurrent callers share one flight. */
  flush(): Promise<void> {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    if (!this.flight) {
      this.flight = this.round().finally(() => {
        this.flight = undefined;
      });
    }
    return this.flight;
  }

  /** Rows newer than the push mark (edits made mid-flight included). */
  private async needsPush(): Promise<boolean> {
    const mark = await this.store.getPushMark();
    const rows = await this.store.all();
    if (rows.some((n) => n.updatedAt > mark)) return true;
    return this.needsDrawingsPush();
  }

  private async needsDrawingsPush(): Promise<boolean> {
    if (!this.drawings) return false;
    const mark = await this.drawings.getDrawingsPushMark();
    const rows = await this.drawings.allDrawings();
    return rows.some((d) => d.updatedAt > mark);
  }

  private async round(): Promise<void> {
    if (!this.isOnline()) {
      this.setStatus("offline");
      return;
    }
    this.setStatus("syncing");
    try {
      // Loop until quiescent: an edit landing mid-flight schedules work
      // that the in-flight round would otherwise strand below the marks.
      for (let i = 0; i < 5; i += 1) {
        await this.push();
        await this.pull();
        await this.pushDrawings();
        await this.pullDrawings();
        if (!(await this.needsPush())) break;
      }
      this.setStatus("idle");
    } catch (error) {
      console.error("[keeps] sync round failed:", error);
      this.setStatus(this.isOnline() ? "error" : "offline");
    }
  }

  private async push(): Promise<void> {
    const mark = await this.store.getPushMark();
    const rows = await this.store.all();
    const dirty = rows.filter((n) => n.updatedAt > mark);
    if (dirty.length === 0) return;
    const since = await this.store.getCursor();
    const res = await this.fetchFn("/api/notes/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        upserts: dirty.filter((n) => !n.deleted),
        tombstones: dirty.filter((n) => n.deleted),
        since,
      }),
    });
    if (!res.ok) throw new Error(`push failed: ${res.status}`);
    const body = (await res.json()) as Partial<PushBody>;
    const cursor = readCursor(body.cursor);
    if (typeof body.applied !== "number" || !Array.isArray(body.deltas) || cursor === null) {
      throw new Error("push: bad response shape");
    }
    await this.applyDeltas(body.deltas);
    await this.store.setCursor(cursor);
    // Echoes of our rows carry our own updatedAt, so covering dirty is
    // enough. (A conflict loser's foreign timestamp can exceed the mark
    // and be re-pushed once with identical content — idempotent.)
    const maxDirty = dirty.reduce((m, n) => Math.max(m, n.updatedAt), mark);
    await this.store.setPushMark(maxDirty);
  }

  async pull(): Promise<void> {
    const cursor = await this.store.getCursor();
    const res = await this.fetchFn(`/api/notes?since=${cursor}`);
    if (!res.ok) throw new Error(`pull failed: ${res.status}`);
    const body = (await res.json()) as Partial<PullBody>;
    const next = readCursor(body.cursor);
    if (!Array.isArray(body.notes) || !body.notes.every(isNote) || next === null) {
      throw new Error("pull: bad response shape");
    }
    await this.applyDeltas(body.notes);
    await this.store.setCursor(next);
  }

  private async pushDrawings(): Promise<void> {
    if (!this.drawings) return;
    const mark = await this.drawings.getDrawingsPushMark();
    const dirty = (await this.drawings.allDrawings()).filter((d) => d.updatedAt > mark);
    if (dirty.length === 0) return;
    const since = await this.drawings.getDrawingsCursor();
    const res = await this.fetchFn("/api/drawings/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        upserts: dirty.filter((d) => !d.deleted),
        tombstones: dirty.filter((d) => d.deleted),
        since,
      }),
    });
    if (!res.ok) throw new Error(`drawings push failed: ${res.status}`);
    const body = (await res.json()) as Partial<DrawPushBody>;
    const cursor = readCursor(body.cursor);
    if (typeof body.applied !== "number" || !Array.isArray(body.deltas) || cursor === null) {
      throw new Error("drawings push: bad response shape");
    }
    await this.applyDrawingDeltas(body.deltas);
    await this.drawings.setDrawingsCursor(cursor);
    const maxDirty = dirty.reduce((m, d) => Math.max(m, d.updatedAt), mark);
    await this.drawings.setDrawingsPushMark(maxDirty);
  }

  private async pullDrawings(): Promise<void> {
    if (!this.drawings) return;
    const cursor = await this.drawings.getDrawingsCursor();
    const res = await this.fetchFn(`/api/drawings?since=${cursor}`);
    if (!res.ok) throw new Error(`drawings pull failed: ${res.status}`);
    const body = (await res.json()) as Partial<DrawPullBody>;
    const next = readCursor(body.cursor);
    if (!Array.isArray(body.drawings) || !body.drawings.every(isDrawing) || next === null) {
      throw new Error("drawings pull: bad response shape");
    }
    await this.applyDrawingDeltas(body.drawings);
    await this.drawings.setDrawingsCursor(next);
  }

  private async applyDrawingDeltas(deltas: Drawing[]): Promise<void> {
    if (!this.drawings || deltas.length === 0) return;
    const local = await this.drawings.allDrawings();
    const byId = new Map(local.map((d) => [d.id, d]));
    const merged = mergeDrawingLists(local, deltas);
    for (const drawing of merged) {
      const current = byId.get(drawing.id);
      if (!current || JSON.stringify(current) !== JSON.stringify(drawing)) {
        await this.drawings.putDrawing(drawing);
      }
    }
  }

  private async applyDeltas(deltas: Note[]): Promise<void> {
    if (deltas.length === 0) return;
    const local = await this.store.all();
    const byId = new Map(local.map((n) => [n.id, n]));
    const merged = mergeNoteLists(local, deltas);
    for (const note of merged) {
      const current = byId.get(note.id);
      if (!current || JSON.stringify(current) !== JSON.stringify(note)) {
        // put() notifies subscribers; unchanged rows stay silent.
        await this.store.put(note);
      }
    }
  }

  /**
   * Push the tombstone, then hard-drop the local row. Throws when offline
   * so the note stays safely in Trash instead of vanishing unsynced.
   */
  async deleteForever(id: string): Promise<void> {
    if (!this.isOnline()) throw new Error("deleteForever: offline");
    await this.store.tombstone(id);
    await this.flush();
    await this.store.dropLocal(id);
  }
}
