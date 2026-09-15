// Frozen contract (Phase 0). Lane agents: do NOT change this file's shapes
// without a plan update. All lanes import these types.

export interface Note {
  id: string;
  title: string;
  body: string;
  /** Color key, e.g. "default" | "red" | "green" | "blue" | "yellow". */
  color: string;
  pinned: boolean;
  archived: boolean;
  /** Unix epoch milliseconds, client-assigned. Last-write-wins key. */
  updatedAt: number;
  /** Soft delete. True = tombstone; tombstones sync like notes. */
  deleted: boolean;
  /** Label ids attached to this note (max 20, each max 64 chars). */
  labelIds: string[];
  /** Reminder fire time, unix epoch ms; null = no reminder. */
  reminderAt: number | null;
}

export const NOTE_LIMITS = {
  maxLabels: 20,
  maxLabelIdLength: 64,
} as const;

export const NOTE_COLORS = [
  "default",
  "red",
  "orange",
  "yellow",
  "green",
  "teal",
  "blue",
  "purple",
  "pink",
] as const;

export type NoteColor = (typeof NOTE_COLORS)[number];

export function isNote(value: unknown): value is Note {
  if (typeof value !== "object" || value === null) return false;
  const n = value as Record<string, unknown>;
  return (
    typeof n["id"] === "string" &&
    n["id"].length > 0 &&
    typeof n["title"] === "string" &&
    typeof n["body"] === "string" &&
    typeof n["color"] === "string" &&
    typeof n["pinned"] === "boolean" &&
    typeof n["archived"] === "boolean" &&
    typeof n["updatedAt"] === "number" &&
    Number.isFinite(n["updatedAt"]) &&
    typeof n["deleted"] === "boolean" &&
    Array.isArray(n["labelIds"]) &&
    (n["labelIds"] as unknown[]).length <= NOTE_LIMITS.maxLabels &&
    (n["labelIds"] as unknown[]).every(
      (id) => typeof id === "string" && id.length <= NOTE_LIMITS.maxLabelIdLength,
    ) &&
    (n["reminderAt"] === null ||
      (typeof n["reminderAt"] === "number" &&
        Number.isFinite(n["reminderAt"]) &&
        (n["reminderAt"] as number) >= 0))
  );
}

/** Client-side factory. Caller owns id generation (crypto.randomUUID). */
export function newNote(
  init: Pick<Note, "id"> & Partial<Omit<Note, "id" | "updatedAt">> & { updatedAt?: number },
): Note {
  return {
    title: "",
    body: "",
    color: "default",
    pinned: false,
    archived: false,
    deleted: false,
    labelIds: [],
    reminderAt: null,
    ...init,
    updatedAt: init.updatedAt ?? Date.now(),
  };
}
