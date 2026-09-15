// Label definitions (plan.md amendment). First-class synced rows with
// their own seq cursor and endpoints; notes reference them by id via
// Note.labelIds. Sync semantics mirror drawings exactly.

export interface Label {
  id: string;
  name: string;
  /** Color key, same palette as notes ("default" | ...). */
  color: string;
  /** Unix epoch milliseconds, client-assigned. Last-write-wins key. */
  updatedAt: number;
  /** Soft delete. True = tombstone; tombstones sync like labels. */
  deleted: boolean;
}

export const LABEL_LIMITS = {
  maxNameLength: 120,
  maxColorLength: 32,
} as const;

export function isLabel(value: unknown): value is Label {
  if (typeof value !== "object" || value === null) return false;
  const l = value as Record<string, unknown>;
  return (
    typeof l["id"] === "string" &&
    l["id"].length > 0 &&
    typeof l["name"] === "string" &&
    (l["name"] as string).length > 0 &&
    (l["name"] as string).length <= LABEL_LIMITS.maxNameLength &&
    typeof l["color"] === "string" &&
    (l["color"] as string).length <= LABEL_LIMITS.maxColorLength &&
    typeof l["updatedAt"] === "number" &&
    Number.isFinite(l["updatedAt"]) &&
    typeof l["deleted"] === "boolean"
  );
}

/** Winner of two versions of the same label id. Never mutates inputs. */
export function pickLabelWinner(local: Label, remote: Label): Label {
  if (local.id !== remote.id) throw new Error("pickLabelWinner: id mismatch");
  if (remote.updatedAt !== local.updatedAt) {
    return remote.updatedAt > local.updatedAt ? remote : local;
  }
  return JSON.stringify(remote) >= JSON.stringify(local) ? remote : local;
}

/** Merge two lists by id with last-write-wins. No duplicates, no mutation. */
export function mergeLabelLists(a: Label[], b: Label[]): Label[] {
  const byId = new Map<string, Label>();
  for (const label of [...a, ...b]) {
    const existing = byId.get(label.id);
    byId.set(label.id, existing ? pickLabelWinner(existing, label) : label);
  }
  return [...byId.values()];
}

/** Rows with updatedAt strictly greater than `since`. */
export function labelsChangedSince(labels: Label[], since: number): Label[] {
  return labels.filter((l) => l.updatedAt > since);
}
