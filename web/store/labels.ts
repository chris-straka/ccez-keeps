// Labels lane client. Local-first rows synced against the Worker's
// /api/labels endpoints (same cursor shape as notes/drawings). fetchFn
// is injectable like web/store/sync.ts; tests never touch the network.
import {
  isLabel,
  mergeLabelLists,
  type Label,
} from "../../shared/label.js";

export interface LabelsDeps {
  fetchFn?: typeof fetch;
}

function newId(): string {
  const c = globalThis.crypto as unknown as
    | { randomUUID?: () => string }
    | undefined;
  if (c?.randomUUID) return c.randomUUID();
  return `label-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

export class LabelsStore {
  private rows = new Map<string, Label>();
  private cursor = 0;
  private listeners = new Set<() => void>();

  constructor(private readonly deps: LabelsDeps = {}) {}

  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => {
      this.listeners.delete(fn);
    };
  }

  private notify(): void {
    for (const fn of this.listeners) fn();
  }

  private get fetchImpl(): typeof fetch {
    const fn = this.deps.fetchFn ?? globalThis.fetch;
    if (!fn) throw new Error("LabelsStore: no fetch available");
    return fn;
  }

  /** Live (non-deleted) labels, sorted by name. */
  all(): Label[] {
    return [...this.rows.values()]
      .filter((l) => !l.deleted)
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  get(id: string): Label | undefined {
    const label = this.rows.get(id);
    return label && !label.deleted ? label : undefined;
  }

  /** Local-only write (seeding/tests). Validates via isLabel. */
  put(label: Label): void {
    if (!isLabel(label)) throw new Error("LabelsStore.put: not a Label");
    this.rows.set(label.id, label);
    this.notify();
  }

  create(name: string): Label {
    const trimmed = name.trim();
    if (trimmed.length === 0) throw new Error("LabelsStore.create: empty name");
    const label: Label = {
      id: newId(),
      name: trimmed,
      color: "default",
      updatedAt: Date.now(),
      deleted: false,
    };
    this.rows.set(label.id, label);
    this.notify();
    void this.flush().catch(() => {});
    return label;
  }

  rename(id: string, name: string): void {
    const trimmed = name.trim();
    if (trimmed.length === 0) throw new Error("LabelsStore.rename: empty name");
    const current = this.rows.get(id);
    if (!current || current.deleted) return;
    this.rows.set(id, { ...current, name: trimmed, updatedAt: Date.now() });
    this.notify();
    void this.flush().catch(() => {});
  }

  remove(id: string): void {
    const current = this.rows.get(id);
    if (!current || current.deleted) return;
    this.rows.set(id, { ...current, deleted: true, updatedAt: Date.now() });
    this.notify();
    void this.flush().catch(() => {});
  }

  /** Push everything, then pull. Small row count; idempotent. */
  async flush(): Promise<void> {
    await this.push();
    await this.pull();
  }

  private async push(): Promise<void> {
    const rows = [...this.rows.values()];
    if (rows.length === 0) return;
    const res = await this.fetchImpl("/api/labels/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        upserts: rows.filter((l) => !l.deleted),
        tombstones: rows.filter((l) => l.deleted),
        since: this.cursor,
      }),
    });
    if (!res.ok) throw new Error(`labels push failed: ${res.status}`);
    const body = (await res.json()) as { deltas?: unknown; cursor?: unknown };
    if (!Array.isArray(body.deltas) || typeof body.cursor !== "number") {
      throw new Error("labels push: bad response shape");
    }
    this.applyDeltas(body.deltas);
    this.cursor = body.cursor;
  }

  async pull(): Promise<void> {
    const res = await this.fetchImpl(`/api/labels?since=${this.cursor}`);
    if (!res.ok) throw new Error(`labels pull failed: ${res.status}`);
    const body = (await res.json()) as { labels?: unknown; cursor?: unknown };
    if (!Array.isArray(body.labels) || typeof body.cursor !== "number") {
      throw new Error("labels pull: bad response shape");
    }
    this.applyDeltas(body.labels);
    this.cursor = body.cursor;
  }

  private applyDeltas(deltas: unknown): void {
    if (!Array.isArray(deltas) || deltas.length === 0) return;
    const valid = deltas.filter(isLabel);
    if (valid.length === 0) return;
    const merged = mergeLabelLists([...this.rows.values()], valid);
    this.rows = new Map(merged.map((l) => [l.id, l]));
    this.notify();
  }
}
